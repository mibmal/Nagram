package tw.nekomimi.nekogram.parts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.TranslateController
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ChatActivity
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.transtale.TranslateDb
import tw.nekomimi.nekogram.transtale.Translator
import tw.nekomimi.nekogram.transtale.code2Locale
import tw.nekomimi.nekogram.transtale.source.LLMTranslator
import tw.nekomimi.nekogram.transtale.source.UnusableTranslationException
import tw.nekomimi.nekogram.utils.AlertUtil
import tw.nekomimi.nekogram.utils.UIUtil
import tw.nekomimi.nekogram.utils.uDismiss
import tw.nekomimi.nekogram.utils.uUpdate
import xyz.nextalone.nagram.NaConfig
import xyz.nextalone.nagram.helper.MessageHelper
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val MAX_CONTEXT_MESSAGES = 5
private const val TRANSLATION_PARALLELISM = 5

// Minutes to pause background auto-translation after it fails, indexed by NaConfig.autoTranslatePauseMinutes.
val AUTO_TRANSLATE_PAUSE_MINUTES = intArrayOf(0, 1, 5, 15, 60)

@Volatile
private var autoTranslatePausedUntil = 0L

/**
 * Collects the few messages following [message] so that an LLM translator can resolve the
 * pronouns and the context of the sentence being translated.
 */
private fun isPlainAscii(text: String?): Boolean = text != null && text.all { it.code < 0x80 }

private fun translationContext(message: MessageObject, timeline: List<MessageObject>): List<String> {
    if (NekoConfig.translationProvider.Int() != Translator.providerLLM || !NaConfig.llmUseContext.Bool()) {
        return emptyList()
    }
    val index = timeline.indexOf(message)
    if (index < 0) return emptyList()

    return timeline.asSequence()
        .drop(index + 1)
        .filter {
            it.messageOwner.id != 0 && !it.isDateObject && !it.isSponsored &&
                MessageHelper.isMessageObjectAutoTranslatable(it)
        }
        .take(MAX_CONTEXT_MESSAGES)
        .mapNotNull { MessageHelper.getMessagePlainText(it)?.takeIf { text -> text.isNotBlank() } }
        .toList()
        .asReversed()
}

@JvmName("translateMessages")
fun ChatActivity.translateMessages1() = translateMessages()

@JvmName("translateMessages")
fun ChatActivity.translateMessages2(target: Locale) = translateMessages(target)

@JvmName("translateMessages")
fun ChatActivity.translateMessages3(messages: List<MessageObject>) = translateMessages(messages = messages)

@JvmName("translateMessages")
fun ChatActivity.translateMessages4(messages: List<MessageObject>, autoTranslate: Boolean) =
    translateMessages(messages = messages, autoTranslate = autoTranslate)

fun ChatActivity.translateMessages(
    target: Locale = NekoConfig.translateToLang.String().code2Locale,
    messages: List<MessageObject> = messageForTranslate?.let { listOf(it) }
        ?: selectedObjectGroup?.messages
        ?: emptyList(),
    autoTranslate: Boolean = false
) {
    if (messages.isEmpty() || messages.any { it.translating }) return
    // While the translation server is unreachable, skip background auto-translation instead of failing every message.
    if (autoTranslate && System.currentTimeMillis() < autoTranslatePausedUntil) return
    // Auto translation sends every message to the provider (no MLKit gate). Text made only of
    // ASCII cannot need an English translation, and skipping it keeps a local model from
    // translating every English message, link and number on screen. Cantonese with a Latin
    // name or an emoji opener still contains CJK characters, so it is not skipped.
    if (autoTranslate && target.language == "en" && messages.all { isPlainAscii(MessageHelper.getMessagePlainText(it)) }) return

    if (messages.all { it.translated }) {
        messages.forEach { clearTranslation(it) }
        return
    }

    messages.forEach { it.translating = true }

    // Auto translation starts one job per incoming message, so the cancel flag, the progress
    // label and the bounded dispatcher are per chat and shared by every job of this dialog.
    val cancel = cancellationFlag()
    val status = progressDialog(autoTranslate)
    val error = AtomicReference<Throwable?>()
    val failed = ConcurrentLinkedQueue<MessageObject>()
    val timeline = this.messages.toList()
    val scope = translationScope()

    scope.launch {
        val progress = ProgressCounter(messages.size, status)
        try {
            messages.map { message ->
                async {
                    if (!translateOne(message, target, timeline, cancel, error, progress) && !cancel.get()) {
                        failed.add(message)
                    }
                }
            }.awaitAll()
        } finally {
            messages.forEach { it.translating = false }
            UIUtil.runOnUIThread { if (!cancel.get()) status?.uDismiss() }
        }
        // One error dialog per batch, retrying only the messages that failed.
        error.get()?.let { handleError(target, it, failed.toList(), autoTranslate, cancel) }
    }
}

/** Per chat translation state, shared by every job because auto translation runs one per message. */
private class TransState {
    val cancel = AtomicBoolean()
    val dispatcher = Dispatchers.IO.limitedParallelism(TRANSLATION_PARALLELISM, "MessageTrans")
    var scope: CoroutineScope? = null
    var status: AlertDialog? = null
}

private val transStates = WeakHashMap<ChatActivity, TransState>()

/** Returns (and lazily creates) the translation state of this chat. */
private fun ChatActivity.translationState(): TransState {
    synchronized(transStates) {
        transStates[this]?.let { return it }
        val state = TransState()
        transStates[this] = state
        return state
    }
}

/** Cancellation flag of this chat; setting it aborts every running translation of it. */
private fun ChatActivity.cancellationFlag(): AtomicBoolean = translationState().cancel

/** Progress dialog of a manual translation. Auto translation shows none, so [autoTranslate] skips it. */
private fun ChatActivity.progressDialog(autoTranslate: Boolean): AlertDialog? {
    if (autoTranslate) return null
    val state = translationState()
    synchronized(state) {
        if (state.status?.isShowing != true) {
            state.status = AlertUtil.showProgress(parentActivity).apply {
                setOnCancelListener { cancelTranslations() }
                show()
            }
        }
        return state.status
    }
}

/**
 * Scope shared by every translation job of this chat.
 *
 * It is created once and reused on purpose: auto translation calls [translateMessages] once per
 * incoming message, so a fresh scope per call would cancel the jobs of the previous messages.
 */
private fun ChatActivity.translationScope(): CoroutineScope {
    val state = translationState()
    synchronized(transStates) {
        state.scope?.takeIf { it.isActive }?.let { return it }
        val scope = CoroutineScope(SupervisorJob() + state.dispatcher)
        state.scope = scope
        return scope
    }
}

/** Cancels the in-flight translation jobs of this chat, if any. */
fun ChatActivity.cancelTranslations() {
    synchronized(transStates) {
        transStates.remove(this)?.let { state ->
            state.cancel.set(true)
            state.scope?.cancel()
        }
    }
}

/** Keeps the "done / total" label of the progress dialog up to date. */
private class ProgressCounter(private val total: Int, private val status: AlertDialog?) {
    private val remaining = AtomicInteger(total)

    fun tick() {
        val left = remaining.decrementAndGet()
        if (status != null && total > 1) {
            status.uUpdate("${total - left} / $total")
        }
    }
}

// Retries transient failures (e.g. "HTTP 0" network drops) with the same provider.
internal suspend fun translateWithRetry(
    target: Locale,
    text: String,
    context: List<String>,
    cancel: AtomicBoolean
): String {
    var attempt = 0
    while (true) {
        try {
            return Translator.translate(target, text, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: UnusableTranslationException) {
            // The provider cannot produce this translation (e.g. the CPU
            // failover backend leaving Chinese in an English result); the
            // original message shows instead, and nothing is cached.
            throw e
        } catch (e: LLMTranslator.NonRetryableException) {
            throw e
        } catch (e: Exception) {
            // The LLM provider already retries inside LLMTranslator; retrying here as well
            // multiplied every failure into up to 20 requests against the server.
            val maxAttempts = if (NekoConfig.translationProvider.Int() == Translator.providerLLM) 1 else 5
            if (++attempt >= maxAttempts || cancel.get()) throw e
            // 1s, 2s, 4s, 8s: gives the network time to come back after the app is resumed
            delay(1000L shl (attempt - 1))
        }
    }
}

private suspend fun ChatActivity.translateOne(
    message: MessageObject,
    target: Locale,
    timeline: List<MessageObject>,
    cancel: AtomicBoolean,
    error: AtomicReference<Throwable?>,
    progress: ProgressCounter
): Boolean {
    try {
        val context = translationContext(message, timeline)
        val db = TranslateDb.forLocale(target)
        val textTranslated = if (message.isPoll) {
            translatePoll(message, target, context, db, cancel, error)
        } else {
            translateFromCache(message, db) ||
                translateText(message, target, context, cancel, error) ||
                // A message can be translatable for its extras alone (bot keyboard
                // with no caption, link preview with no text); translateText
                // already covered the extras of messages that do have text.
                (message.textSource() == null && translateExtras(message, target, context, cancel))
        }
        // The bot keyboard buttons translate together with the text; a message whose only
        // text is on the buttons ("View message" channel link buttons) succeeds through them.
        return translateButtons(message, target, db, cancel, error) || textTranslated
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error.compareAndSet(null, e)
        return false
    } finally {
        progress.tick()
        // The chat may be gone by now: a canceled job must not touch the message list.
        if (currentCoroutineContext().isActive && !cancel.get()) {
            withContext(Dispatchers.Main) { messageHelper.resetMessageContent(dialogId, message) }
        }
    }
}

/**
 * Applies a cached translation of a plain message without any network access.
 *
 * @return true when the message was fully translated from the cache.
 */
private fun translateFromCache(message: MessageObject, db: TranslateDb): Boolean {
    val source = message.textSource() ?: return false
    val separator = MessageTransText.separatorFor(false)
    val cached = MessageTransText.mergeCached(source, db, separator) ?: return false
    // Extras are runtime-only; without a cache they need the network path, which owns them.
    if (message.hasTranslateExtras() && !message.applyCachedTranslateExtras()) return false
    markTranslatedLocally(message) { it.translatedText = cached }
    return true
}

private suspend fun ChatActivity.translateText(
    message: MessageObject,
    target: Locale,
    context: List<String>,
    cancel: AtomicBoolean,
    error: AtomicReference<Throwable?>
): Boolean {
    val source = message.textSource() ?: return false
    if (cancel.get()) return false

    val separator = MessageTransText.separatorFor(false)
    val translated = runCatching {
        translateWithRetry(target, MessageTransText.queryTextOf(source), context, cancel)
    }.getOrElse {
        // Scope cancellation is not a provider failure: let it propagate so no dialog is shown.
        if (it is CancellationException) throw it
        error.compareAndSet(null, it)
        return false
    }
    if (translated.isBlank() || cancel.get()) return false

    markTranslatedLocally(message) { it.translatedText = MessageTransText.merge(source, translated, separator) }
    // Best effort: buttons and the link preview never fail the text translation.
    translateExtras(message, target, context, cancel)
    return true
}

/**
 * Translates the labels of the inline bot keyboard buttons (channel link buttons like
 * "View message", bot keyboards) with the user's translation provider. Every label is a
 * separate request with an empty context: they are short and independent, and without a
 * context the provider saves each label to the per-locale [TranslateDb] cache.
 *
 * A label that stays untranslated keeps its original text, so a provider hiccup degrades
 * to the current behaviour instead of dropping the keyboard.
 *
 * @return true when at least one label is translated (or was translated earlier).
 */
private suspend fun ChatActivity.translateButtons(
    message: MessageObject,
    target: Locale,
    db: TranslateDb,
    cancel: AtomicBoolean,
    error: AtomicReference<Throwable?>
): Boolean {
    val markup = message.messageOwner.reply_markup as? TLRPC.TL_replyInlineMarkup ?: return false
    if (message.messageOwner.translatedButtons != null) return true
    val labels = markup.rows.flatMap { row -> row.buttons.map { it.text.orEmpty().trim() } }
    if (labels.none { it.isNotEmpty() }) return false

    val texts = ArrayList<TLRPC.TL_textWithEntities>(labels.size)
    var any = false
    for (label in labels) {
        if (cancel.get()) return any
        if (label.isEmpty()) {
            texts.add(TLRPC.TL_textWithEntities())
            continue
        }
        var translated = db.query(label)?.takeIf { it.isNotBlank() }
        if (translated == null) {
            translated = runCatching {
                translateWithRetry(target, label, emptyList(), cancel)
            }.getOrElse {
                error.compareAndSet(null, it)
                FileLog.e(it)
                null
            }
        }
        if (translated.isNullOrBlank()) {
            texts.add(TLRPC.TL_textWithEntities())
        } else {
            texts.add(TLRPC.TL_textWithEntities().also { it.text = translated })
            any = true
        }
    }
    if (any) {
        message.messageOwner.translatedButtons = TranslateController.ButtonsText().also {
            it.lang = target.toLanguageTag()
            it.texts = texts
        }
        // Flag it through the same local pipeline the text path uses, so the undo branch
        // and MessageObject.updateTranslation treat it as translated.
        markTranslatedLocally(message) { }
    }
    return any
}

private suspend fun ChatActivity.translatePoll(
    message: MessageObject,
    target: Locale,
    context: List<String>,
    db: TranslateDb,
    cancel: AtomicBoolean,
    error: AtomicReference<Throwable?>
): Boolean {
    MessageTransPoll.pollTextOf(message)?.let { skeleton ->
        MessageTransPoll.translateCached(skeleton, db)?.let { cached ->
            markTranslatedLocally(message) { it.translatedPoll = cached }
            return true
        }
    }
    if (cancel.get()) return false

    // A fresh skeleton is required here: translateCached mutates the poll it is given, so the one
    // above may already be partially merged.
    val pollText = MessageTransPoll.pollTextOf(message) ?: return false
    val translated = runCatching {
        MessageTransPoll.translate(pollText, target, context, db, cancel)
    }.getOrElse {
        // Scope cancellation is not a provider failure: let it propagate so no dialog is shown.
        if (it is CancellationException) throw it
        error.compareAndSet(null, it)
        return false
    }

    markTranslatedLocally(message) { it.translatedPoll = translated }
    return true
}

/**
 * Stores the translation into the official upstream fields and flags the message as locally
 * translated, so that `MessageObject.updateTranslation` keeps showing it regardless of the
 * dialog wide setting.
 */
private fun markTranslatedLocally(message: MessageObject, block: (TLRPC.Message) -> Unit) {
    block(message.messageOwner)
    message.translatedLocally = true
}

private fun ChatActivity.clearTranslation(message: MessageObject) {
    val owner = message.messageOwner
    message.restoreTranslateExtras()
    owner.translatedText = null
    owner.translatedPoll = null
    owner.translatedButtons = null
    message.translatedLocally = false
    message.updateTranslation(true)
    messageHelper.resetMessageContent(dialogId, message)
}

// --- Translation extras: the link preview (inline keyboard buttons go through translateButtons) ---

private fun MessageObject.linkWebPage(): TLRPC.WebPage? {
    return (messageOwner?.media as? TLRPC.TL_messageMediaWebPage)?.webpage as? TLRPC.WebPage
}

private fun MessageObject.hasTranslateExtras(): Boolean {
    val page = linkWebPage()
    return !page?.title.isNullOrBlank() || !page?.description.isNullOrBlank()
}

/** Re-applies the translated link preview from this message's cache.
 *  Returns false when there is something to translate but no cache yet, so
 *  the caller takes the network path. */
private fun MessageObject.applyCachedTranslateExtras(): Boolean {
    val owner = messageOwner ?: return true
    val page = linkWebPage()
    if (page != null) {
        if (owner.translatedWebPageTitle == null && owner.translatedWebPageDescription == null) {
            if (!page.title.isNullOrBlank() || !page.description.isNullOrBlank()) return false
        }
        owner.translatedWebPageTitle?.let { page.title = it }
        owner.translatedWebPageDescription?.let { page.description = it }
    }
    return true
}

/** Restores the original link preview (undo / show original). */
private fun MessageObject.restoreTranslateExtras() {
    val owner = messageOwner ?: return
    val page = linkWebPage()
    if (page != null) {
        owner.originalWebPageTitle?.let { page.title = it }
        owner.originalWebPageDescription?.let { page.description = it }
    }
}

/**
 * Translates the extras of [message] -- the link preview title/description --
 * caching the result on the message owner and applying it in place. Best
 * effort: a failed extra keeps its original text and never fails the message.
 * The inline keyboard buttons are owned by [translateButtons], which caches
 * each label in the per-locale provider cache.
 *
 * @return true when [message] has no text and its extras were applied, so it
 *         counts as fully translated for the extras-only fallback in
 *         [translateOne].
 */
private suspend fun ChatActivity.translateExtras(
    message: MessageObject,
    target: Locale,
    context: List<String>,
    cancel: AtomicBoolean
): Boolean {
    val owner = message.messageOwner ?: return false
    val extrasOnly = message.textSource() == null

    val page = message.linkWebPage()
    if (page != null && owner.translatedWebPageTitle == null && owner.translatedWebPageDescription == null) {
        val title = page.title
        val description = page.description
        val translatedTitle = title?.takeIf { it.isNotBlank() && it.any { c -> c.isLetter() } }?.let { text ->
            runCatching { Translator.translate(target, text, context) }
                .getOrElse { FileLog.e("webpage title translate failed", it); text }
        }
        val translatedDescription = description?.takeIf { it.isNotBlank() && it.any { c -> c.isLetter() } }?.let { text ->
            runCatching { Translator.translate(target, text, context) }
                .getOrElse { FileLog.e("webpage description translate failed", it); text }
        }
        if (cancel.get()) return false
        owner.originalWebPageTitle = title
        owner.originalWebPageDescription = description
        owner.translatedWebPageTitle = translatedTitle ?: title
        owner.translatedWebPageDescription = translatedDescription ?: description
    }

    if (!message.applyCachedTranslateExtras()) return false
    if (extrasOnly) {
        // Flag it through the same local pipeline the text path uses, so the
        // undo branch and MessageObject.updateTranslation treat it as translated.
        markTranslatedLocally(message) { }
    }
    return extrasOnly
}

// Shows at most one error dialog per batch, and Retry only re-translates the messages that failed.
private fun ChatActivity.handleError(
    target: Locale,
    error: Throwable,
    failed: List<MessageObject>,
    autoTranslate: Boolean,
    cancel: AtomicBoolean
) {
    // Background auto-translation: don't interrupt reading with an error popup, just keep the original text.
    if (autoTranslate) {
        FileLog.e("auto translate failed: ${error.message ?: error.javaClass.simpleName}")
        val minutes = AUTO_TRANSLATE_PAUSE_MINUTES.getOrElse(NaConfig.autoTranslatePauseMinutes.Int()) { 0 }
        // Pause only when the server itself is failing. One message the model could not translate
        // (it echoed the source back) used to pause auto-translation for everything after it.
        if (minutes > 0 && error !is UnsupportedOperationException && error !is UnusableTranslationException) {
            autoTranslatePausedUntil = System.currentTimeMillis() + minutes * 60_000L
        }
        return
    }
    if (parentActivity != null && !cancel.get() && failed.isNotEmpty()) {
        AlertUtil.showTransFailedDialog(
            parentActivity,
            error is UnsupportedOperationException,
            error.message ?: error.javaClass.simpleName
        ) {
            translateMessages(target, failed)
        }
    }
}

/** The rich text of a plain (non poll) message, or null when there is nothing to translate. */
private fun MessageObject.textSource(): TLRPC.TL_textWithEntities? {
    val text = messageOwner.message?.takeIf { it.isNotBlank() } ?: return null
    return TLRPC.TL_textWithEntities().also {
        it.text = text
        it.entities = messageOwner.entities
    }
}