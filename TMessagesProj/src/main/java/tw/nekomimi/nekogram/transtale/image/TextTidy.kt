package tw.nekomimi.nekogram.transtale.image

/** Clean-up of Tesseract output before translation (mirrors Snap Translate's tidyCjk). */
object TextTidy {

    private const val CJK = "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\u3000-\\u303f\\uff00-\\uffef"
    private val SPACE_BETWEEN = Regex("(?<=[$CJK])[ \\t]+(?=[$CJK])")
    private val ASCII_PUNCT = Regex("(?<=[$CJK])[ \\t]*([,.?!:;])(?=[ \\t]*(?:[$CJK]|$))[ \\t]*")
    private val SPACE_PUNCT = Regex("(?<=[$CJK])[ \\t]+(?=[，。？！：；）])|(?<=[，。？！：；（])[ \\t]+(?=[$CJK])")
    private val FULLWIDTH = mapOf(',' to "，", '.' to "。", '?' to "？", '!' to "！", ':' to "：", ';' to "；")
    private val CJK_CHAR = Regex("[$CJK]")
    private val SENTENCE_END = Regex("[。！？.!?:：]$")

    /** Tesseract spaces out CJK glyphs and emits ASCII punctuation next to them. */
    fun line(t: String): String = t
        .replace(SPACE_BETWEEN, "")
        .replace(ASCII_PUNCT) { FULLWIDTH[it.groupValues[1][0]] ?: it.groupValues[1] }
        .replace(SPACE_PUNCT, "")
        .trim()

    fun endsSentence(t: String) = SENTENCE_END.containsMatchIn(t.trimEnd())

    /** Re-join wrapped lines: no space between CJK, a space between words. */
    fun join(lines: List<String>): String = lines.reduce { acc, l ->
        val glue = if (CJK_CHAR.containsMatchIn(acc.takeLast(1)) || CJK_CHAR.containsMatchIn(l.take(1))) "" else " "
        acc + glue + l
    }
}
