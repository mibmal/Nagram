package tw.nekomimi.nekogram.transtale.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LibreTranslateTranslatorTest {
    @Test
    fun mapsChineseToScriptCodes() {
        assertEquals("zh-Hans", LibreTranslateTranslator.convertLanguageCode("zh", "CN"))
        assertEquals("zh-Hans", LibreTranslateTranslator.convertLanguageCode("zh", ""))
        assertEquals("zh-Hant", LibreTranslateTranslator.convertLanguageCode("zh", "TW"))
        assertEquals("zh-Hant", LibreTranslateTranslator.convertLanguageCode("zh", "hk"))
        assertEquals("en", LibreTranslateTranslator.convertLanguageCode("en", "US"))
    }

    @Test
    fun normalizesTranslateUrl() {
        assertEquals("https://lt.example/translate", LibreTranslateTranslator.buildTranslateUrl("https://lt.example"))
        assertEquals("https://lt.example/translate", LibreTranslateTranslator.buildTranslateUrl("https://lt.example/"))
        assertEquals("https://lt.example/translate", LibreTranslateTranslator.buildTranslateUrl(" https://lt.example/translate "))
        assertEquals("https://lt.example/lt/translate", LibreTranslateTranslator.buildTranslateUrl("https://lt.example/lt"))
    }
}
