package tw.nekomimi.nekogram.parts

import kotlinx.coroutines.*
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
import tw.nekomimi.nekogram.utils.AlertUtil
import tw.nekomimi.nekogram.utils.UIUtil
import tw.nekomimi.nekogram.utils.uDismiss
import tw.nekomimi.nekogram.utils.uUpdate
import xyz.nextalone.nagram.NaConfig
import xyz.nextalone.nagram.helper.MessageHelper
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Minutes to pause background auto-translation after it fails, indexed by NaConfig.autoTranslatePauseMinutes.
val AUTO_TRANSLATE_PAUSE_MINUTES = intArrayOf(0, 1, 5, 15, 60)

@Volatile
private var autoTranslatePausedUntil = 0L

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
        .take(5)
        .mapNotNull { MessageHelper.getMessagePlainText(it)?.takeIf { text -> text.isNotBlank() } }
        .toList()
        .asReversed()
}

fun MessageObject.translateFinished(locale: Locale): Int {

    val db = TranslateDb.forLocale(locale)

    translating = false

    if (isPoll) {
        val pool = (messageOwner.media as TLRPC.TL_messageMediaPoll).poll
        val question = db.query(pool.question.text) ?: return 0
        val translatedPoll = TranslateController.PollText.fromMessage(this)

        translatedPoll.question.text =
            "${if (!NaConfig.hideOriginAfterTranslation.Bool()) translatedPoll.question.text + " | " else ""}$question"
        translatedPoll.answers.forEach {
            val answer = db.query(it.text.text) ?: return 0
            it.text.text += " | $answer"
        }
        if (translatedPoll.solution != null) {
            val solution = db.query(translatedPoll.solution.text) ?: return 0
            translatedPoll.solution.text =
                "${if (!NaConfig.hideOriginAfterTranslation.Bool()) translatedPoll.solution.text + " | " else ""}$solution"
        }
        messageOwner.translatedPoll = translatedPoll
    } else {

        // A message whose only text is on the bot buttons ("View message" link buttons) is
        // not finished: it still has to go through translateMessage for its labels.
        val source = messageOwner.message.takeIf { !it.isNullOrBlank() }
            ?: return if (TranslateController.hasBotButtonsWithText(this)) 0 else 1

        val text = db.query(source)?.takeIf { it.isNotBlank() } ?: return 0

        messageOwner.translatedMessage =
            "${if (!NaConfig.hideOriginAfterTranslation.Bool()) messageOwner.message + "\n\n--------\n\n" else ""}$text"

    }

    // The text is cached, but the bot keyboard labels may not be translated yet.
    if (messageOwner.translatedButtons == null && TranslateController.hasBotButtonsWithText(this)) {
        return 0
    }

    return 2

}

@JvmName("translateMessages")
fun ChatActivity.translateMessages1() = translateMessages()

@JvmName("translateMessages")
fun ChatActivity.translateMessages2(target: Locale) = translateMessages(target)

@JvmName("translateMessages")
fun ChatActivity.translateMessages3(messages: List<MessageObject>) = translateMessages(messages = messages)

@JvmName("translateMessages")
fun ChatActivity.translateMessages4(messages: List<MessageObject>, autoTranslate: Boolean) = translateMessages(messages = messages, autoTranslate = autoTranslate)

fun ChatActivity.translateMessages(
    target: Locale = NekoConfig.translateToLang.String().code2Locale,
    messages: List<MessageObject> = messageForTranslate?.let { listOf(it) }
        ?: selectedObjectGroup?.messages
        ?: emptyList(),
    autoTranslate: Boolean = false
) {
    if (messages.any { it.translating }) return
    // While the translation server is unreachable, skip background auto-translation instead of failing every message.
    if (autoTranslate && System.currentTimeMillis() < autoTranslatePausedUntil) return

    if (messages.all { it.messageOwner.translated }) {
        messages.forEach {
            it.messageOwner.translated = false
            messageHelper.resetMessageContent(dialogId, it)
            it.translating = false
        }
        return
    } else {
        messages.forEach { it.translating = true }
    }

    var status: AlertDialog? = null
    val cancel = AtomicBoolean()
    if (!autoTranslate) {
        status = AlertUtil.showProgress(parentActivity).apply {
            setOnCancelListener { cancel.set(true) }
            show()
        }
    }

    val deferreds = LinkedList<Deferred<Unit>>()
    val taskCount = AtomicInteger(messages.size)
    val transPool = newFixedThreadPoolContext(5, "Message Trans Pool")

    fun next() {
        val index = taskCount.decrementAndGet()
        if (index == 0) {
            status?.uDismiss()
        } else if (messages.size > 1) {
            status?.uUpdate("${messages.size - index} / ${messages.size}")
        }
    }

    val error = AtomicReference<Throwable?>()
    val failed = ConcurrentLinkedQueue<MessageObject>()

    val timeline = this.messages.toList()
    GlobalScope.launch(Dispatchers.IO) {
        messages.forEach { selectedObject ->
            val context = translationContext(selectedObject, timeline)
            when (if (context.isEmpty()) selectedObject.translateFinished(target) else 0) {
                1 -> next()
                2 -> {
                    next()
                    withContext(Dispatchers.Main) {
                        selectedObject.messageOwner.translated = true
                        messageHelper.resetMessageContent(dialogId, selectedObject)
                    }
                }
                else -> deferreds.add(async(transPool) {
                    val success = translateMessage(selectedObject, target, context, cancel, error)
                    if (!cancel.get()) {
                        if (success) {
                            selectedObject.messageOwner.translated = true
                        } else {
                            failed.add(selectedObject)
                            selectedObject.messageOwner.translated = false
                            selectedObject.messageOwner.translatedMessage = selectedObject.messageOwner.message
                        }
                        next()
                        withContext(Dispatchers.Main) {
                            messageHelper.resetMessageContent(dialogId, selectedObject)
                        }
                    }
                })
            }
        }

        deferreds.awaitAll()
        transPool.cancel()
        UIUtil.runOnUIThread { if (!cancel.get()) status?.uDismiss() }
        error.get()?.let { handleError(target, it, failed.toList(), autoTranslate, cancel) }
    }
    messages.forEach { it.translating = false }
}

// Retries transient failures (e.g. "HTTP 0" network drops) with the same provider.
private suspend fun translateWithRetry(
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
        } catch (e: Exception) {
            if (++attempt >= 5 || cancel.get()) throw e
            // 1s, 2s, 4s, 8s: gives the network time to come back after the app is resumed
            delay(1000L shl (attempt - 1))
        }
    }
}

private suspend fun ChatActivity.translateMessage(
    message: MessageObject,
    target: Locale,
    context: List<String>,
    cancel: AtomicBoolean,
    error: AtomicReference<Throwable?>
): Boolean {
    val db = TranslateDb.forLocale(target)
    val textTranslated = if (message.isPoll) {
        translatePoll(message, target, context, db, cancel, error)
    } else {
        translateText(message, target, context, db, cancel, error)
    }
    // The bot keyboard buttons translate together with the text; a message whose only
    // text is on the buttons ("View message" channel link buttons) succeeds through them.
    return translateButtons(message, target, db, cancel, error) || textTranslated
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
    val pool = (message.messageOwner.media as TLRPC.TL_messageMediaPoll).poll
    var question = if (context.isEmpty()) db.query(pool.question.text)?.takeIf { it.isNotBlank() } else null
    if (question == null) {
        if (cancel.get()) return false
        question = runCatching {
            translateWithRetry(target, pool.question.text, context, cancel)
        }.getOrElse {
            error.compareAndSet(null, it)
            return false
        }
    }

    val translatedPoll = TranslateController.PollText.fromMessage(message)
    translatedPoll.question.text = buildString {
        if (!NaConfig.hideOriginAfterTranslation.Bool()) append(pool.question.text + " | ")
        append(question)
    }

    for (it in translatedPoll.answers) {
        var answer = if (context.isEmpty()) db.query(it.text.text)?.takeIf { it.isNotBlank() } else null
        if (answer == null) {
            if (cancel.get()) return false
            answer = runCatching {
                translateWithRetry(target, it.text.text, context, cancel)
            }.getOrElse { e ->
                error.compareAndSet(null, e)
                return false
            }
        }
        it.text.text = buildString {
            if (!NaConfig.hideOriginAfterTranslation.Bool()) append(it.text.text + " | ")
            append(answer)
        }
    }

    translatedPoll.solution?.let { solution ->
        var translatedSolution = if (context.isEmpty()) db.query(solution.text)?.takeIf { it.isNotBlank() } else null
        if (translatedSolution == null) {
            if (cancel.get()) return false
            translatedSolution = runCatching {
                translateWithRetry(target, solution.text, context, cancel)
            }.getOrElse {
                error.compareAndSet(null, it)
                return false
            }
        }
        solution.text = buildString {
            if (!NaConfig.hideOriginAfterTranslation.Bool()) append(solution.text + " | ")
            append(translatedSolution)
        }
    }

    message.messageOwner.translatedPoll = translatedPoll
    return true
}

private suspend fun ChatActivity.translateText(
    message: MessageObject,    target: Locale,
    context: List<String>,
    db: TranslateDb,
    cancel: AtomicBoolean,
    error: AtomicReference<Throwable?>
): Boolean {
    val originalMessage = message.messageOwner?.message
    if (originalMessage.isNullOrBlank()) {
        return false
    }
    var text = if (context.isEmpty()) db.query(originalMessage)?.takeIf { it.isNotBlank() } else null
    if (text == null) {
        text = runCatching {
            translateWithRetry(target, originalMessage, context, cancel)
        }.getOrElse {
            error.compareAndSet(null, it)
            message.messageOwner.translatedMessage = originalMessage
            return false
        }
    }

    if (text.isBlank()) {
        message.messageOwner.translatedMessage = originalMessage
        return false
    }

    message.messageOwner.translatedMessage = buildString {
        if (!NaConfig.hideOriginAfterTranslation.Bool()) append(originalMessage + "\n\n--------\n\n")
        append(text)
    }
    return true
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
        if (minutes > 0 && error !is UnsupportedOperationException) {
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
