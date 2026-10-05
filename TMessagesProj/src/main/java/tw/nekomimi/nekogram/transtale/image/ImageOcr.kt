package tw.nekomimi.nekogram.transtale.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device text recognition for "Translate image": PaddleOCR PP-OCRv5
 * (PaddleOcr.kt, ONNX Runtime). Nothing leaves the phone at this stage.
 * Turns the recogniser's text lines into blocks (paragraphs, list items,
 * chat lines) for translation and painting.
 */
object ImageOcr {

    data class Line(val text: String, val box: Rect)

    /** A text block; `groups` are its logical lines (wrapped lines re-joined). */
    data class Block(val box: Rect, val lines: List<Line>, val groups: List<Group>)

    data class Group(val text: String, val box: Rect, val lines: List<Line>)

    data class Result(val blocks: List<Block>, val confidence: Int, val lang: String)

    /** Long side cap: recognition gains nothing above this, memory does. */
    private const val MAX_SIDE = 2400

    fun read(ctx: Context, src: Bitmap, onStage: (String) -> Unit = {}): Result {
        val long = max(src.width, src.height)
        val scale = min(1f, MAX_SIDE.toFloat() / long)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        try {
            onStage("paddleocr")
            val lines = PaddleOcr.read(ctx, bmp)
            val conf = if (lines.isEmpty()) 0 else (lines.map { it.score }.average() * 100).toInt()
            fun Rect.unscale() = Rect((left / scale).toInt(), (top / scale).toInt(), (right / scale).toInt(), (bottom / scale).toInt())
            val rows = mergeRows(lines.map { Line(TextTidy.line(it.text), it.box.unscale()) })
            return Result(blocks(rows), conf, "ppocrv5")
        } finally {
            if (bmp !== src) bmp.recycle()
        }
    }

    /** Detector fragments on the same row (a dish and its price) become one line. */
    private fun mergeRows(lines: List<Line>): List<Line> {
        val rows = ArrayList<Line>()
        for (l in lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
            val i = rows.indexOfFirst { r ->
                val overlap = min(r.box.bottom, l.box.bottom) - max(r.box.top, l.box.top)
                val gap = l.box.left - r.box.right
                overlap > 0.6f * min(r.box.height(), l.box.height()) && gap > -0.3f * l.box.height() && gap < 1.2f * max(r.box.height(), l.box.height())
            }
            if (i >= 0) {
                val r = rows[i]
                rows[i] = Line("${r.text} ${l.text}", union(listOf(r.box, l.box)))
            } else rows += l
        }
        return rows.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** Consecutive, aligned, similar-height lines with small gaps form a block. */
    private fun blocks(rows: List<Line>): List<Block> {
        val groups = ArrayList<MutableList<Line>>()
        for (l in rows) {
            val g = groups.firstOrNull { q ->
                val last = q.last().box
                val box = union(q.map { it.box })
                val gap = l.box.top - last.bottom
                val similar = max(l.box.height(), last.height()).toFloat() / max(1, min(l.box.height(), last.height())) < 1.5f
                val aligned = abs(l.box.left - box.left) < 1.5f * l.box.height() || (l.box.left < box.right && l.box.right > box.left)
                gap > -0.3f * l.box.height() && gap < 0.9f * min(l.box.height(), last.height()) && similar && aligned
            }
            if (g != null) g += l else groups += mutableListOf(l)
        }
        return groups.map { makeBlock(union(it.map { l -> l.box }), it) }
    }

    /**
     * Geometry decides what wrapped: a line that runs (nearly) to the block's
     * right edge and doesn't end a sentence continues on the next line.
     * Short lines -- menu items, chat bubbles, labels -- stay separate.
     */
    private fun makeBlock(box: Rect, lines: List<Line>): Block {
        val right = lines.maxOf { it.box.right }
        val left = lines.minOf { it.box.left }
        val width = maxOf(1, right - left)
        val groups = ArrayList<Group>()
        var cur = ArrayList<Line>()
        for ((i, l) in lines.withIndex()) {
            cur.add(l)
            val wrapped = i < lines.size - 1 &&
                l.box.right - left >= width * 0.85 &&
                !TextTidy.endsSentence(l.text)
            if (!wrapped) {
                groups.add(Group(TextTidy.join(cur.map { it.text }), union(cur.map { it.box }), cur))
                cur = ArrayList()
            }
        }
        return Block(box, lines, groups)
    }

    fun union(rects: List<Rect>) = Rect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
}
