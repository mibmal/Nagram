package tw.nekomimi.nekogram.transtale.source

import android.text.TextUtils
import io.ktor.http.ContentType
import org.json.JSONObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import tw.nekomimi.nekogram.cc.CCConverter
import tw.nekomimi.nekogram.cc.CCTarget
import tw.nekomimi.nekogram.transtale.Translator
import xyz.nextalone.nagram.NaConfig
import xyz.nextalone.nagram.network.NetworkRequestBuilder

/**
 * Translator implementation backed by a self-hosted LibreTranslate instance.
 *
 * The user supplies the instance URL (with or without the trailing `/translate`)
 * and, only if the instance requires one, an API key. Requests use the standard
 * LibreTranslate JSON API:
 *   POST /translate { "q": "...", "source": "auto", "target": "...", "format": "text", "api_key": "..." }
 * and the translation is returned in the "translatedText" field.
 */
object LibreTranslateTranslator : Translator {

    @JvmStatic
    fun convertLanguageCode(language: String, country: String): String {
        if (language != "zh") return language
        return when (country.uppercase()) {
            "TW", "HK", "MO" -> "zh-Hant"
            else -> "zh-Hans"
        }
    }

    @JvmStatic
    fun buildTranslateUrl(api: String): String {
        val base = api.trim().trimEnd('/')
        return if (base.endsWith("/translate")) base else "$base/translate"
    }

    private val HAN = Regex("\\p{IsHan}")

    /**
     * LibreTranslate ships Traditional and Simplified Chinese as separate
     * models, but its "auto" detection reports plain Chinese, so Traditional
     * text (Hong Kong / Taiwan) gets the Simplified model and noticeably worse
     * output. Decide locally instead: if converting to Simplified changes the
     * text, it was written in Traditional characters.
     */
    @JvmStatic
    fun resolveSource(from: String, query: String): String {
        if (from != "auto" || !HAN.containsMatchIn(query)) return from
        val han = HAN.findAll(query).count()
        val nonSpace = query.count { !it.isWhitespace() }
        if (han * 3 < nonSpace) return from // mostly not Chinese: let LibreTranslate detect
        val traditional = runCatching { CCConverter.get(CCTarget.SC).convert(query) != query }.getOrDefault(false)
        return if (traditional) "zh-Hant" else "zh-Hans"
    }

    override suspend fun doTranslate(from: String, to: String, query: String): String {
        val api = NaConfig.libreTranslateApi.String()
        if (TextUtils.isEmpty(api)) error("Missing LibreTranslate API")
        val apiKey = NaConfig.libreTranslateApiKey.String()

        val response = NetworkRequestBuilder.post(buildTranslateUrl(api!!)) {
            contentType(ContentType.Application.Json)
            setBody(JSONObject().apply {
                put("q", query)
                put("source", resolveSource(from, query))
                put("target", to)
                put("format", "text")
                if (!TextUtils.isEmpty(apiKey)) put("api_key", apiKey)
            }.toString())
        }.execute()

        val json = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.statusCode != 200 || json == null || !json.has("translatedText")) {
            val message = json?.optString("error")
            if (response.statusCode == 400 && message?.endsWith("is not supported") == true) {
                throw UnsupportedOperationException(LocaleController.getString(R.string.TranslateApiUnsupported))
            }
            error("HTTP ${response.statusCode} : ${if (message.isNullOrEmpty()) response.body else message}")
        }
        return json.getString("translatedText")
    }
}
