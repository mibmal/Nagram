package tw.nekomimi.nekogram.transtale.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * On-device text recognition for "Translate image": Tesseract (Apache-2.0,
 * via Tesseract4Android) with the tessdata_best_int models bundled in
 * assets/tessdata. Nothing leaves the phone at this stage.
 *
 * Chinese needs care: with chi_tra and chi_sim loaded together Tesseract mixes
 * the two scripts line by line, and a second (English) model hijacks CJK words
 * it's unsure of. So each model gets its own pass and the results are compared:
 * the Traditional model reads Simplified text confidently (as Traditional
 * glyphs), while the Simplified model falls apart on Traditional -- so
 * Simplified wins only with a clear lead.
 */
object ImageOcr {

    data class Line(val text: String, val box: Rect)

    /** A text block; `groups` are its logical lines (wrapped lines re-joined). */
    data class Block(val box: Rect, val lines: List<Line>, val groups: List<Group>)

    data class Group(val text: String, val box: Rect, val lines: List<Line>)

    data class Result(val blocks: List<Block>, val confidence: Int, val lang: String)

    private const val SIMP_LEAD = 2
    private const val RETRY_BELOW = 60
    private val MODELS = listOf("chi_tra", "chi_sim", "eng")
    private val HAN = Regex("\\p{IsHan}")

    /** Bump when the bundled models change, so the copy in filesDir is refreshed. */
    private const val MODEL_VERSION = "tessdata_best_int-4.0.0"

    // Tesseract needs real files; assets are compressed inside the APK, so the
    // models are copied out once (and again only when MODEL_VERSION changes).
    @Synchronized
    private fun dataPath(ctx: Context): String {
        val dir = File(ctx.filesDir, "tesseract/tessdata").apply { mkdirs() }
        val stamp = File(dir, ".version")
        val fresh = stamp.exists() && stamp.readText() == MODEL_VERSION && MODELS.all { File(dir, "$it.traineddata").length() > 0 }
        if (!fresh) {
            for (m in MODELS) {
                val tmp = File(dir, "$m.traineddata.tmp")
                ctx.assets.open("tessdata/$m.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(File(dir, "$m.traineddata"))
            }
            stamp.writeText(MODEL_VERSION)
        }
        return dir.parentFile!!.absolutePath
    }

    /**
     * Grayscale, dark mode inverted to dark-on-light, small crops upscaled,
     * huge photos capped. Returns the prepared bitmap and its scale.
     */
    private fun prepare(src: Bitmap): Pair<Bitmap, Float> {
        val long = maxOf(src.width, src.height)
        val scale = when {
            src.width < 600 -> 2f
            long > 2400 -> 2400f / long
            else -> 1f
        }
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        Canvas(out).drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(gray)
        })
        // Mean luminance on a coarse grid; mostly dark => invert.
        var sum = 0L
        var n = 0
        val step = maxOf(1, minOf(w, h) / 40)
        for (y in 0 until h step step) for (x in 0 until w step step) {
            sum += out.getPixel(x, y) and 0xff
            n++
        }
        if (n > 0 && sum / n < 110) {
            val inv = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
            val copy = out.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(out).drawBitmap(copy, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(inv) })
            copy.recycle()
        }
        return out to scale
    }

    private class Pass(val lang: String, val text: String, val confidence: Int, val paras: List<Rect>, val lines: List<Line>)

    private fun run(path: String, img: Bitmap, lang: String): Pass {
        val api = TessBaseAPI()
        try {
            if (!api.init(path, lang, TessBaseAPI.OEM_LSTM_ONLY)) error("Tesseract: can't load $lang")
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setVariable("preserve_interword_spaces", "1")
            api.setImage(img)
            val text = api.getUTF8Text() ?: ""
            val conf = api.meanConfidence()
            val paras = ArrayList<Rect>()
            val lines = ArrayList<Line>()
            val iter = api.getResultIterator()
            if (iter != null && text.isNotBlank()) {
                val para = TessBaseAPI.PageIteratorLevel.RIL_PARA
                val line = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                iter.begin()
                do {
                    iter.getBoundingRect(para)?.let { r -> paras.add(r) }
                } while (iter.next(para))
                iter.begin()
                do {
                    val t = iter.getUTF8Text(line)?.trim().orEmpty()
                    val r = iter.getBoundingRect(line)
                    if (t.isNotEmpty() && r != null && iter.confidence(line) > 30f) lines.add(Line(TextTidy.line(t), r))
                } while (iter.next(line))
            }
            return Pass(lang, text, conf, paras, lines)
        } finally {
            api.recycle()
        }
    }

    /** Read `src`; block coordinates are in `src`'s own pixels. */
    fun read(ctx: Context, src: Bitmap, onStage: (String) -> Unit = {}): Result {
        val path = dataPath(ctx)
        val (img, scale) = prepare(src)
        try {
            onStage("chi_tra")
            var best = run(path, img, "chi_tra")
            val hanShare = { p: Pass -> HAN.findAll(p.text).count().toFloat() / maxOf(1, p.text.count { !it.isWhitespace() }) }
            if (hanShare(best) < 0.2f || best.confidence < RETRY_BELOW) {
                onStage("eng")
                val eng = run(path, img, "eng")
                if (eng.confidence > best.confidence && hanShare(best) < 0.3f) best = eng
            }
            if (best.lang == "chi_tra" && HAN.containsMatchIn(best.text)) {
                onStage("chi_sim")
                val sim = run(path, img, "chi_sim")
                if (sim.confidence - best.confidence >= SIMP_LEAD) best = sim
            }
            return Result(group(best, scale), best.confidence, best.lang)
        } finally {
            img.recycle()
        }
    }

    /** Assign lines to their paragraph and re-join wrapped lines, mapped back to source pixels. */
    private fun group(p: Pass, scale: Float): List<Block> {
        fun Rect.unscale() = Rect((left / scale).toInt(), (top / scale).toInt(), (right / scale).toInt(), (bottom / scale).toInt())
        val buckets = p.paras.map { ArrayList<Line>() }
        val orphans = ArrayList<Line>()
        for (l in p.lines) {
            val i = p.paras.indexOfFirst { it.contains(l.box.centerX(), l.box.centerY()) }
            if (i >= 0) buckets[i].add(l) else orphans.add(l)
        }
        val blocks = ArrayList<Block>()
        p.paras.forEachIndexed { i, r -> if (buckets[i].isNotEmpty()) blocks.add(makeBlock(r, buckets[i])) }
        orphans.forEach { blocks.add(makeBlock(it.box, listOf(it))) }
        return blocks.map { b ->
            Block(
                b.box.unscale(),
                b.lines.map { Line(it.text, it.box.unscale()) },
                b.groups.map { g -> Group(g.text, g.box.unscale(), g.lines.map { Line(it.text, it.box.unscale()) }) },
            )
        }
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
