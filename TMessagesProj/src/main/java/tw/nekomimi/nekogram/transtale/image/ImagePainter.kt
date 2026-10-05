package tw.nekomimi.nekogram.transtale.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Paints translations back onto the picture (Snap Translate's "translated
 * image", ported): each text block is covered with the colour sampled around
 * it and the translation written in the sampled text colour. When the
 * translation keeps the block's line structure (menus, lists, chat lines) each
 * line goes back where it was with its own colours; otherwise the text is
 * reflowed into the block, which may grow rightwards into free space because
 * English needs more room than the CJK it replaces.
 */
object ImagePainter {

    fun paint(src: Bitmap, blocks: List<ImageOcr.Block>, translations: List<String?>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        blocks.forEachIndexed { i, b ->
            val t = translations.getOrNull(i)?.trim()
            if (t.isNullOrEmpty()) return@forEachIndexed
            val parts = t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size == b.groups.size) {
                b.groups.forEachIndexed { j, g -> drawUnit(canvas, src, g.lines.map { it.box }, g.box, parts[j], blocks) }
            } else {
                drawUnit(canvas, src, b.lines.map { it.box }, b.box, parts.joinToString("\n"), blocks)
            }
        }
        return out
    }

    private fun drawUnit(canvas: Canvas, src: Bitmap, lineBoxes: List<Rect>, box: Rect, text: String, blocks: List<ImageOcr.Block>) {
        val bg = background(src, box)
        val fg = foreground(src, box, bg)
        val fill = Paint().apply { color = bg; style = Paint.Style.FILL }
        for (r in lineBoxes) {
            val pad = max(2f, r.height() * 0.12f)
            canvas.drawRect(RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad), fill)
        }

        val lineH = median(lineBoxes.map { it.height() }).toFloat()
        val width = room(src, box, blocks)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        // Start at the original line height and shrink until it fits the box
        // (a little overflow allowed); below ~7px give up and extend the cover.
        var size = max(8f, lineH * 0.82f)
        tp.textSize = size
        var layout = buildLayout(text, tp, width)
        while (layout.height > box.height() + lineH * 0.5f && size > 7f) {
            size -= max(0.5f, size * 0.06f)
            tp.textSize = size
            layout = buildLayout(text, tp, width)
        }
        val used = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        if (layout.height > box.height() || used > box.width()) {
            canvas.drawRect(RectF(box.left - 2f, box.top - 2f, box.left + max(box.width().toFloat(), used) + 2f, box.top + max(box.height(), layout.height) + 2f), fill)
        }
        canvas.save()
        canvas.translate(box.left.toFloat(), box.top + max(0f, (box.height() - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
    }

    @Suppress("DEPRECATION")
    private fun buildLayout(text: String, tp: TextPaint, width: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, tp, width).setLineSpacing(0f, 1.1f).setIncludePad(false).build()
        } else {
            StaticLayout(text, tp, width, Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
        }

    /** Width available: up to the next block on the same rows, or the image edge. */
    private fun room(src: Bitmap, b: Rect, blocks: List<ImageOcr.Block>): Int {
        var right = src.width - min(b.left, 24)
        for (q in blocks) {
            val qb = q.box
            val overlaps = qb.top < b.bottom && qb.bottom > b.top
            if (overlaps && qb.left >= b.right - 1 && qb != b) right = min(right, qb.left - 8)
        }
        return max(b.width(), right - b.left).coerceAtLeast(1)
    }

    private fun px(src: Bitmap, x: Float, y: Float): Int =
        src.getPixel(x.toInt().coerceIn(0, src.width - 1), y.toInt().coerceIn(0, src.height - 1))

    /** Background = median colour of a ring just outside the box. */
    private fun background(src: Bitmap, b: Rect): Int {
        val pad = max(2f, b.height() * 0.15f)
        val pts = ArrayList<Int>()
        val n = 24
        for (i in 0..n) {
            val x = b.left - pad + (b.width() + 2 * pad) * i / n
            val y = b.top - pad + (b.height() + 2 * pad) * i / n
            pts += px(src, x, b.top - pad); pts += px(src, x, b.bottom + pad)
            pts += px(src, b.left - pad, y); pts += px(src, b.right + pad, y)
        }
        return Color.rgb(median(pts.map { Color.red(it) }), median(pts.map { Color.green(it) }), median(pts.map { Color.blue(it) }))
    }

    /** Text colour = average of the in-box pixels furthest from the background. */
    private fun foreground(src: Bitmap, b: Rect, bg: Int): Int {
        val step = max(1f, min(b.width(), b.height()) / 12f)
        val pts = ArrayList<Pair<Int, Int>>()
        var y = b.top.toFloat()
        while (y < b.bottom) {
            var x = b.left.toFloat()
            while (x < b.right) {
                val c = px(src, x, y)
                pts += c to (abs(Color.red(c) - Color.red(bg)) + abs(Color.green(c) - Color.green(bg)) + abs(Color.blue(c) - Color.blue(bg)))
                x += step
            }
            y += step
        }
        if (pts.isEmpty()) return if (lum(bg) > 128) Color.rgb(17, 17, 17) else Color.rgb(245, 245, 245)
        val top = pts.sortedByDescending { it.second }.take(max(1, pts.size / 10)).map { it.first }
        val avg = Color.rgb(top.map { Color.red(it) }.average().toInt(), top.map { Color.green(it) }.average().toInt(), top.map { Color.blue(it) }.average().toInt())
        // Faint or anti-aliased text: plain black/white reads better.
        return if (abs(lum(avg) - lum(bg)) < 90) (if (lum(bg) > 128) Color.rgb(17, 17, 17) else Color.rgb(245, 245, 245)) else avg
    }

    private fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
    private fun median(v: List<Int>) = if (v.isEmpty()) 0 else v.sorted()[v.size / 2]
}
