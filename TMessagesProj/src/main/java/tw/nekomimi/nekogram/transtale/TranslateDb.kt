package tw.nekomimi.nekogram.transtale

import io.objectbox.Box
import org.telegram.messenger.LocaleController
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.database.ChatCCTarget
import tw.nekomimi.nekogram.database.ChatLanguage
import tw.nekomimi.nekogram.database.TransItem
import tw.nekomimi.nekogram.database.mkDatabase
import tw.nekomimi.nekogram.database.queryTransItemModel
import tw.nekomimi.nekogram.utils.UIUtil
import java.util.Locale

class TranslateDb(val code: String) {

    var conn: Box<TransItem> = db.boxFor(TransItem::class.java)

    companion object {

        val db = mkDatabase("translate_caches")

        val repo = HashMap<Locale, TranslateDb>()
        val chat: Box<ChatLanguage> = db.boxFor(ChatLanguage::class.java)
        val ccTarget: Box<ChatCCTarget> = db.boxFor(ChatCCTarget::class.java)

        @JvmStatic
        fun getChatLanguage(chatId: Long, default: Locale): Locale {
            return if (chatId == 0L) default
            else chat.get(chatId)?.language?.code2Locale ?: default
        }

        @JvmStatic
        fun saveChatLanguage(chatId: Long, locale: Locale) = UIUtil.runOnIoDispatcher {
            chat.put(ChatLanguage(chatId, locale.locale2code))
        }

        @JvmStatic
        fun getChatCCTarget(chatId: Long, default: String?): String? {
            return if (chatId == 0L) default
            else ccTarget.get(chatId)?.ccTarget ?: default
        }

        @JvmStatic
        fun saveChatCCTarget(chatId: Long, target: String) = UIUtil.runOnIoDispatcher {
            ccTarget.put(ChatCCTarget(chatId, target))
        }

        @JvmStatic
        fun currentTarget() = NekoConfig.translateToLang.String()?.transDbByCode
            ?: LocaleController.getInstance().currentLocale.transDb

        @JvmStatic
        fun forLocale(locale: Locale) = locale.transDb

        @JvmStatic
        fun currentInputTarget() = NekoConfig.translateInputLang.String().transDbByCode

        @JvmStatic
        fun clearAll() {
            db.removeAllObjects()
            repo.clear()
        }
    }

    fun clear() = synchronized(this) {
        conn.removeAll()
    }

    fun contains(text: String) = query(text) != null

    private val targetIsCjk = code.startsWith("zh", true) || code.startsWith("ja", true) || code.startsWith("yue", true)

    /** A "translation" into a non-CJK language that still contains Chinese/Japanese is an echo of the
     *  source (older builds cached those); never store it and never serve it. */
    private fun isEcho(trans: String) =
        !targetIsCjk && tw.nekomimi.nekogram.transtale.source.LLMTranslator.containsCjk(trans)

    fun save(text: String, trans: String) = synchronized<Unit>(this) {
        if (isEcho(trans)) return@synchronized
        var model = queryTransItemModel(conn, code, text)
        if (model != null) {
            model.trans = trans;
        } else {
            model = TransItem(code, text, trans)
        }
        conn.put(model)
    }

    fun query(text: String): String? = synchronized(this) {
        val model = queryTransItemModel(conn, code, text) ?: return@synchronized null
        val trans = model.trans
        if (trans == null || isEcho(trans)) {
            conn.remove(model)
            return@synchronized null
        }
        trans
    }
}
