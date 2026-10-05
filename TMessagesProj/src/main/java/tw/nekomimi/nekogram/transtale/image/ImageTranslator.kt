package tw.nekomimi.nekogram.transtale.image

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.R
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.transtale.Translator
import tw.nekomimi.nekogram.transtale.code2Locale
import java.io.File

/**
 * "Translate image" in the photo viewer: read the text on the phone
 * (ImageOcr: PaddleOCR on ONNX Runtime), translate each text block with the user's chosen translation
 * provider, and show the picture with the translations painted in place
 * (ImagePainter), plus the text pairs below.
 *
 * Nothing is kept: no translation-history entries (a non-empty context makes
 * Translator skip TranslateDb), the bitmaps die with the dialog, and only an
 * explicit "Save" writes the translated picture to the gallery.
 */
object ImageTranslator {

    @JvmStatic
    fun show(activity: Activity, bitmap: Bitmap) {
        Screen(activity, bitmap).show()
    }

    // Context tag for providers that use context (LLM); its presence also keeps
    // the text out of the translation history.
    private val CONTEXT = listOf("Text recognised from an image (OCR); may contain recognition errors.")

    @SuppressLint("ClickableViewAccessibility")
    private class Screen(val activity: Activity, val original: Bitmap) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var translated: Bitmap? = null
        private var showingTranslated = true

        private val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(original)
            setBackgroundColor(Color.BLACK)
        }
        private val status = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xCC1C1C24.toInt()) }
        }
        private val toggle = chip(LocaleController.getString(R.string.TranslateImageOriginal))
        private val save = chip(LocaleController.getString(R.string.Save))
        private val pairs = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        init {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8) + AndroidUtilities.statusBarHeight, dp(8), dp(8))
                addView(chip("✕").apply { setOnClickListener { dismiss() } })
                addView(TextView(activity).apply {
                    text = LocaleController.getString(R.string.TranslateImage)
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(toggle)
                addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            }
            toggle.visibility = View.GONE
            save.visibility = View.GONE
            toggle.setOnClickListener {
                showingTranslated = !showingTranslated
                refresh()
            }
            save.setOnClickListener { saveToGallery() }
            // Press and hold the picture to compare with the original.
            image.setOnTouchListener { _, e ->
                val t = translated ?: return@setOnTouchListener false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> image.setImageBitmap(original)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> image.setImageBitmap(if (showingTranslated) t else original)
                }
                true
            }

            val imageFrame = FrameLayout(activity).apply {
                addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            val list = ScrollView(activity).apply {
                setBackgroundColor(0xFF0E0F16.toInt())
                addView(pairs)
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(bar)
                addView(imageFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.3f).toInt()))
            }
            setContentView(root)
            setOnDismissListener {
                scope.cancel()
                translated?.recycle()
            }
            start()
        }

        private fun start() = scope.launch {
            try {
                status.text = LocaleController.getString(R.string.TranslateImageReading)
                val result = withContext(Dispatchers.Default) { ImageOcr.read(activity, original) }
                if (result.blocks.isEmpty()) {
                    status.text = LocaleController.getString(R.string.TranslateImageNoText)
                    return@launch
                }
                status.text = LocaleController.getString(R.string.TranslateImageTranslating)
                val to = NekoConfig.translateToLang.String()?.code2Locale ?: LocaleController.getInstance().currentLocale
                val texts = result.blocks.map { b -> b.groups.joinToString("\n") { it.text } }
                // A few blocks at a time: providers rate-limit, and a self-hosted
                // LibreTranslate on a small box shouldn't get 40 requests at once.
                val gate = Semaphore(3)
                val outs = texts.map { t ->
                    async(Dispatchers.IO) {
                        gate.withPermit { runCatching { Translator.translate(to, t, CONTEXT) }.onFailure { FileLog.e(it) }.getOrNull() }
                    }
                }.awaitAll()
                if (outs.all { it == null }) {
                    status.text = LocaleController.getString(R.string.TranslateFailed)
                    return@launch
                }
                val painted = withContext(Dispatchers.Default) { ImagePainter.paint(original, result.blocks, outs) }
                translated = painted
                status.visibility = View.GONE
                toggle.visibility = View.VISIBLE
                save.visibility = View.VISIBLE
                refresh()
                texts.forEachIndexed { i, src -> addPair(src, outs[i]) }
            } catch (e: Throwable) {
                FileLog.e(e)
                status.text = e.message ?: e.javaClass.simpleName
            }
        }

        private fun refresh() {
            val t = translated ?: return
            image.setImageBitmap(if (showingTranslated) t else original)
            toggle.text = LocaleController.getString(if (showingTranslated) R.string.TranslateImageOriginal else R.string.TranslateImageTranslated)
        }

        private fun addPair(src: String, out: String?) {
            pairs.addView(TextView(activity).apply {
                text = src
                setTextColor(0x99FFFFFF.toInt())
                textSize = 13f
                setTextIsSelectable(true)
                setPadding(0, dp(10), 0, dp(2))
            })
            pairs.addView(TextView(activity).apply {
                text = out ?: "—"
                setTextColor(Color.WHITE)
                textSize = 15f
                setTextIsSelectable(true)
            })
        }

        private fun saveToGallery() {
            val t = translated ?: return
            scope.launch {
                val f = withContext(Dispatchers.IO) {
                    File(AndroidUtilities.getCacheDir(), "translated_${System.currentTimeMillis()}.png").also { file ->
                        file.outputStream().use { t.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                MediaController.saveFile(f.absolutePath, activity, 0, null, "image/png") { f.delete() }
            }
        }

        private fun chip(label: String) = TextView(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x33FFFFFF) }
        }

        private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())
    }
}
