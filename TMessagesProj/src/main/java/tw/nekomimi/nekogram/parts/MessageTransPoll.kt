package tw.nekomimi.nekogram.parts

import org.telegram.messenger.MessageObject
import org.telegram.messenger.TranslateController
import org.telegram.tgnet.TLRPC
import tw.nekomimi.nekogram.transtale.TranslateDb
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Poll translation, built on top of [MessageTransText].
 *
 * Every part of a poll (question, answers and solution) is translated independently through the
 * shared rich-text pipeline, and the outcome is stored in the official upstream field
 * `TLRPC.Message.translatedPoll`.
 */
internal object MessageTransPoll {

    private val separator = MessageTransText.separatorFor(true)

    /**
     * Translates every part of [pollText] using the cache only.
     *
     * Returns null as soon as one part is missing from the cache, so the caller can fall back to
     * a network round trip for the whole poll.
     */
    fun translateCached(
        pollText: TranslateController.PollText,
        db: TranslateDb
    ): TranslateController.PollText? {
        val question = pollText.question ?: return null
        pollText.question = MessageTransText.mergeCached(question, db, separator) ?: return null

        for (answer in pollText.answers) {
            answer.text = MessageTransText.mergeCached(answer.text, db, separator) ?: return null
        }

        val solution = pollText.solution
        if (solution != null) {
            pollText.solution = MessageTransText.mergeCached(solution, db, separator) ?: return null
        }
        return pollText
    }

    /**
     * Translates every part of [pollText], asking the provider only for what the cache misses.
     */
    suspend fun translate(
        pollText: TranslateController.PollText,
        target: Locale,
        context: List<String>,
        db: TranslateDb,
        cancel: AtomicBoolean
    ): TranslateController.PollText {
        pollText.question = pollText.question?.let { translatePart(it, target, context, db, cancel) }
        pollText.answers.forEach { answer ->
            answer.text = translatePart(answer.text, target, context, db, cancel)
        }
        pollText.solution = pollText.solution?.let { translatePart(it, target, context, db, cancel) }
        return pollText
    }

    /** Builds the detached poll skeleton that receives the translated texts. */
    fun pollTextOf(message: MessageObject): TranslateController.PollText? =
        if (message.isPoll) TranslateController.PollText.fromMessage(message) else null

    private suspend fun translatePart(
        source: TLRPC.TL_textWithEntities,
        target: Locale,
        context: List<String>,
        db: TranslateDb,
        cancel: AtomicBoolean
    ): TLRPC.TL_textWithEntities {
        MessageTransText.mergeCached(source, db, separator)?.let { return it }
        val translated = translateWithRetry(target, MessageTransText.queryTextOf(source), context, cancel)
        return MessageTransText.merge(source, translated, separator)
    }
}