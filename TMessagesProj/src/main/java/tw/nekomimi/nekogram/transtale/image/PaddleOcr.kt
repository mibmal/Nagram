package tw.nekomimi.nekogram.transtale.image

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PaddleOCR (PP-OCRv5 mobile, Apache-2.0) on ONNX Runtime -- a port of Snap
 * Translate's paddle-core.ts. Two models bundled in assets/paddleocr:
 *   det.onnx  DB text detector -> per-pixel text probability map
 *   rec.onnx  recogniser over 18383 characters (Simplified, Traditional,
 *             English, Japanese) with CTC decoding
 * One recogniser covers Traditional, Simplified and English together, so no
 * per-script passes are needed. Runs entirely on the phone.
 */
object PaddleOcr {

    data class Line(val text: String, val score: Float, val box: Rect)

    private const val DET_LIMIT = 960
    private const val DET_THRESH = 0.3f
    private const val BOX_THRESH = 0.6f
    private const val UNCLIP = 1.6f
    private const val REC_H = 48
    private const val REC_MAX_W = 1600
    private const val BATCH = 8
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    private class Engine(val env: OrtEnvironment, val det: OrtSession, val rec: OrtSession, val dict: List<String>)

    @Volatile
    private var engine: Engine? = null

    @Synchronized
    private fun engine(ctx: Context): Engine {
        engine?.let { return it }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        fun asset(n: String) = ctx.assets.open("paddleocr/$n").use { it.readBytes() }
        val dict = String(asset("dict.txt"), Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.toMutableList()
        if (dict.lastOrNull() == "") dict.removeAt(dict.size - 1)
        dict.add(" ") // trailing space class
        return Engine(env, env.createSession(asset("det.onnx"), opts), env.createSession(asset("rec.onnx"), opts), dict).also { engine = it }
    }

    fun read(ctx: Context, bmp: Bitmap): List<Line> {
        val e = engine(ctx)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val boxes = detect(e, px, w, h)
        return recognise(e, px, w, h, boxes).filter { it.text.isNotBlank() && it.score > 0.5f }
    }

    /** Probability map -> connected components -> unclipped axis-aligned boxes (source pixels). */
    private fun detect(e: Engine, px: IntArray, W: Int, H: Int): List<Rect> {
        val k = min(1f, DET_LIMIT.toFloat() / max(W, H))
        val w = max(32, ((W * k) / 32f).roundToInt() * 32)
        val h = max(32, ((H * k) / 32f).roundToInt() * 32)
        val sx = W.toFloat() / w
        val sy = H.toFloat() / h
        val input = FloatArray(3 * w * h)
        val plane = w * h
        for (y in 0 until h) {
            val srcY = min(H - 1, ((y + 0.5f) * sy).toInt())
            for (x in 0 until w) {
                val c = px[srcY * W + min(W - 1, ((x + 0.5f) * sx).toInt())]
                val o = y * w + x
                // BGR order, ImageNet normalisation -- as PaddleOCR (cv2) feeds it.
                input[o] = ((c and 0xff) / 255f - MEAN[0]) / STD[0]
                input[plane + o] = (((c shr 8) and 0xff) / 255f - MEAN[1]) / STD[1]
                input[2 * plane + o] = (((c shr 16) and 0xff) / 255f - MEAN[2]) / STD[2]
            }
        }
        val prob: FloatArray = OnnxTensor.createTensor(e.env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            e.det.run(mapOf(e.det.inputNames.first() to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }

        val label = IntArray(plane)
        val stack = IntArray(plane)
        val boxes = ArrayList<Rect>()
        var next = 1
        for (p in 0 until plane) {
            if (label[p] != 0 || prob[p] < DET_THRESH) continue
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            var sum = 0f; var n = 0; var sp = 0
            label[p] = next
            stack[sp++] = p
            while (sp > 0) {
                val q = stack[--sp]
                val qx = q % w
                val qy = q / w
                sum += prob[q]; n++
                if (qx < x0) x0 = qx; if (qx > x1) x1 = qx
                if (qy < y0) y0 = qy; if (qy > y1) y1 = qy
                if (qx > 0) push(q - 1, label, prob, stack, next)?.let { stack[sp++] = it }
                if (qx < w - 1) push(q + 1, label, prob, stack, next)?.let { stack[sp++] = it }
                if (qy > 0) push(q - w, label, prob, stack, next)?.let { stack[sp++] = it }
                if (qy < h - 1) push(q + w, label, prob, stack, next)?.let { stack[sp++] = it }
            }
            next++
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (min(bw, bh) < 3 || sum / n < BOX_THRESH) continue
            // Unclip: offset = area * ratio / perimeter (DB paper).
            val d = bw * bh * UNCLIP / (2f * (bw + bh))
            boxes += Rect(
                max(0f, (x0 - d) * sx).toInt(), max(0f, (y0 - d) * sy).toInt(),
                min(W.toFloat(), (x1 + 1 + d) * sx).toInt(), min(H.toFloat(), (y1 + 1 + d) * sy).toInt(),
            )
        }
        return boxes
    }

    private fun push(r: Int, label: IntArray, prob: FloatArray, @Suppress("UNUSED_PARAMETER") stack: IntArray, id: Int): Int? {
        if (label[r] != 0 || prob[r] < DET_THRESH) return null
        label[r] = id
        return r
    }

    private class Crop(val box: Rect, val data: FloatArray, val width: Int)

    /** Crop one box to height 48, bilinear; tall boxes are rotated (vertical text). */
    private fun crop(px: IntArray, W: Int, H: Int, b: Rect): Crop {
        val vertical = b.height() > b.width() * 1.5f
        val srcW = (if (vertical) b.height() else b.width()).toFloat()
        val srcH = (if (vertical) b.width() else b.height()).toFloat().coerceAtLeast(1f)
        val width = (REC_H * srcW / srcH).roundToInt().coerceIn(8, REC_MAX_W)
        val data = FloatArray(3 * REC_H * width)
        val plane = REC_H * width
        for (y in 0 until REC_H) for (x in 0 until width) {
            var u = (x + 0.5f) / width * srcW
            var v = (y + 0.5f) / REC_H * srcH
            if (vertical) { val t = u; u = srcH - v; v = t }
            val fx = (b.left + u - 0.5f).coerceIn(0f, W - 1.001f)
            val fy = (b.top + v - 0.5f).coerceIn(0f, H - 1.001f)
            val ix = fx.toInt(); val iy = fy.toInt()
            val ax = fx - ix; val ay = fy - iy
            val c00 = px[iy * W + ix]; val c10 = px[iy * W + ix + 1]
            val c01 = px[(iy + 1) * W + ix]; val c11 = px[(iy + 1) * W + ix + 1]
            val o = y * width + x
            for (ch in 0 until 3) {
                val shift = ch * 8 // BGR: blue is the low byte of ARGB
                fun comp(c: Int) = ((c shr shift) and 0xff).toFloat()
                val value = comp(c00) * (1 - ax) * (1 - ay) + comp(c10) * ax * (1 - ay) + comp(c01) * (1 - ax) * ay + comp(c11) * ax * ay
                data[ch * plane + o] = (value / 255f - 0.5f) / 0.5f
            }
        }
        return Crop(b, data, width)
    }

    private fun recognise(e: Engine, px: IntArray, W: Int, H: Int, boxes: List<Rect>): List<Line> {
        val crops = boxes.map { crop(px, W, H, it) }
        val order = crops.indices.sortedBy { crops[it].width }
        val out = arrayOfNulls<Line>(crops.size)
        for (s in order.indices step BATCH) {
            val idx = order.subList(s, min(order.size, s + BATCH))
            val width = idx.maxOf { crops[it].width }
            // Zero padding = mid-grey after normalisation, as PaddleOCR pads.
            val batch = FloatArray(idx.size * 3 * REC_H * width)
            idx.forEachIndexed { j, i ->
                val c = crops[i]
                for (ch in 0 until 3) for (y in 0 until REC_H) {
                    System.arraycopy(c.data, ch * REC_H * c.width + y * c.width, batch, j * 3 * REC_H * width + ch * REC_H * width + y * width, c.width)
                }
            }
            OnnxTensor.createTensor(e.env, FloatBuffer.wrap(batch), longArrayOf(idx.size.toLong(), 3, REC_H.toLong(), width.toLong())).use { t ->
                e.rec.run(mapOf(e.rec.inputNames.first() to t)).use { r ->
                    val tensor = r[0] as OnnxTensor
                    val shape = tensor.info.shape
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val fb = tensor.floatBuffer
                    val probs = FloatArray(fb.remaining()).also { fb.get(it) }
                    idx.forEachIndexed { j, i ->
                        val (text, score) = ctc(probs, steps, classes, j * steps * classes, e.dict)
                        out[i] = Line(text.trim(), score, crops[i].box)
                    }
                }
            }
        }
        return out.filterNotNull()
    }

    /** CTC greedy decode: argmax per step, collapse repeats, drop blanks (index 0). */
    private fun ctc(p: FloatArray, steps: Int, classes: Int, offset: Int, dict: List<String>): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1
        var sum = 0f
        var n = 0
        for (t in 0 until steps) {
            var best = 0
            var bestP = Float.NEGATIVE_INFINITY
            val base = offset + t * classes
            for (c in 0 until classes) if (p[base + c] > bestP) { bestP = p[base + c]; best = c }
            if (best != 0 && best != prev) {
                sb.append(dict.getOrElse(best - 1) { "" })
                sum += bestP; n++
            }
            prev = best
        }
        return sb.toString() to (if (n > 0) sum / n else 0f)
    }
}
