package tw.nekomimi.nekogram.transtale.source

import android.text.TextUtils
import io.ktor.http.ContentType
import org.json.JSONObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
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

    override suspend fun doTranslate(from: String, to: String, query: String): String {
        val api = NaConfig.libreTranslateApi.String()
        if (TextUtils.isEmpty(api)) error("Missing LibreTranslate API")
        val apiKey = NaConfig.libreTranslateApiKey.String()

        val response = NetworkRequestBuilder.post(buildTranslateUrl(api!!)) {
            contentType(ContentType.Application.Json)
            setBody(JSONObject().apply {
                put("q", query)
                put("source", from)
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
