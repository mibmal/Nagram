package tw.nekomimi.nekogram.parts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC
import org.telegram.ui.RestrictedLanguagesSelectActivity
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.transtale.TranslateDb
import tw.nekomimi.nekogram.transtale.Translator
import tw.nekomimi.nekogram.transtale.code2Locale
import tw.nekomimi.nekogram.transtale.popupwrapper.LanguageDetector
import tw.nekomimi.nekogram.utils.UIUtil
import xyz.nextalone.nagram.NaConfig
import java.util.concurrent.ConcurrentHashMap

private const val TRANSLATION_PARALLELISM = 3

/**
 * Translates group and channel titles with the user's translation provider while the
 * TranslateChatNames setting is on.
 *
 * Titles are resolved synchronously from an in memory cache on the render path, so a
 * view first draws with the original name and repaints through the chatNameTranslated
 * notification once the translation arrives. Titles that must stay in their original
 * language (restricted languages, provider failures) are cached as "keep the original",
 * so they are not re-requested on every bind.
 */
object ChatNameTrans {

    /** A resolved title; [translated] is null when the title stays in its original language. */
    private class Entry(val title: String, val target: String, val translated: String?)

    private val cache = ConcurrentHashMap<Long, Entry>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(TRANSLATION_PARALLELISM, "ChatNameTrans")
    )

    /**
     * The title of [chat] translated to the user's translation language, or its original
     * title when the setting is off, the user reads the title's language or the provider
     * failed. A not yet cached title is requested in the background.
     */
    @JvmStatic
    fun name(accountNum: Int, chat: TLRPC.Chat): String {
        val title = chat.title ?: return ""
        if (!NaConfig.translateChatNames.Bool() || title.isBlank()) {
            return title
        }
        val target = targetCode()
        val entry = cache[chat.id]
        if (entry != null && entry.title == title && entry.target == target) {
            return entry.translated ?: title
        }
        if (pending.add(chat.id)) {
            detect(accountNum, chat.id, title, target)
        }
        return title
    }

    /** Detects the language first, so titles the user already reads stay untouched. */
    private fun detect(accountNum: Int, chatId: Long, title: String, target: String) {
        if (!LanguageDetector.hasSupport()) {
            FileLog.e("[ChatNameTrans] MLKit language detection unavailable; keeping '$title' untranslated")
            store(chatId, title, target, null)
            return
        }
        LanguageDetector.detectLanguage(
            title,
            { lang ->
                if (isLanguageRestricted(lang, target)) {
                    FileLog.e("[ChatNameTrans] '$title' detected as '$lang', restricted for target '$target'; keeping original")
                    store(chatId, title, target, null)
                } else {
                    scope.launch { translate(accountNum, chatId, title, target) }
                }
            },
            {
                FileLog.e("[ChatNameTrans] language detection failed; keeping '$title' untranslated", it)
                store(chatId, title, target, null)
            }
        )
    }

    private suspend fun translate(accountNum: Int, chatId: Long, title: String, target: String) {
        val locale = target.code2Locale
        val db = TranslateDb.forLocale(locale)
        val translated = db.query(title)?.takeIf { it.isNotBlank() }
            ?: runCatching { Translator.translate(locale, title) }
                .onFailure { if (it is CancellationException) throw it else FileLog.e("[ChatNameTrans] provider failed for '$title'", it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        if (translated != null && translated != title) {
            FileLog.e("[ChatNameTrans] '$title' -> '$translated' (target $target)")
            store(chatId, title, target, translated)
            UIUtil.runOnUIThread {
                NotificationCenter.getInstance(accountNum)
                    .postNotificationName(NotificationCenter.chatNameTranslated, -chatId)
            }
        } else {
            FileLog.e("[ChatNameTrans] keeping '$title' untranslated (provider returned ${translated ?: "nothing"})")
            store(chatId, title, target, null)
        }
    }

    private fun store(chatId: Long, title: String, target: String, translated: String?) {
        cache[chatId] = Entry(title, target, translated)
        pending.remove(chatId)
    }

    /** The same restricted language rule as the chat auto translation. */
    private fun isLanguageRestricted(lang: String?, target: String): Boolean {
        if (lang == null || lang == "und") {
            return false
        }
        var toLang = target
        if (toLang.contains("-")) {
            toLang = toLang.substring(0, toLang.indexOf("-"))
        }
        var language = lang
        if (language.contains("-")) {
            language = language.substring(0, language.indexOf("-"))
        }
        if (language == toLang) {
            return true
        }
        for (restricted in RestrictedLanguagesSelectActivity.getRestrictedLanguages()) {
            if (restricted.contains("_")) {
                if (restricted.substring(0, restricted.indexOf("_")) == language) {
                    return true
                }
            } else if (restricted == language) {
                return true
            }
        }
        return false
    }

    private fun targetCode(): String {
        val target = NekoConfig.translateToLang.String()
        return if (target.isNullOrEmpty()) {
            LocaleController.getInstance().currentLocale.language
        } else {
            target
        }
    }
}
