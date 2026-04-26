package dev.kuch.termx.core.domain.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic guards on the [PttLanguage] catalogue. The Settings
 * dropdowns and the FAB indicator both render via these helpers, so
 * regressions here would visibly bend the UI.
 */
class PttLanguageTest {

    @Test fun `displayLabel renders Language (Country) for known codes`() {
        assertEquals("English (US)", PttLanguage.displayLabel("en-US"))
        assertEquals("Portuguese (BR)", PttLanguage.displayLabel("pt-BR"))
        assertEquals("Spanish (CO)", PttLanguage.displayLabel("es-CO"))
        assertEquals("French (FR)", PttLanguage.displayLabel("fr-FR"))
        assertEquals("German (DE)", PttLanguage.displayLabel("de-DE"))
        assertEquals("Hindi (IN)", PttLanguage.displayLabel("hi-IN"))
    }

    @Test fun `displayLabel falls back to raw code on unknown input`() {
        assertEquals("xx-YY", PttLanguage.displayLabel("xx-YY"))
        assertEquals("", PttLanguage.displayLabel(""))
    }

    @Test fun `shortName drops everything before the last space`() {
        assertEquals("English", PttLanguage.shortName("en-US"))
        assertEquals("Portuguese", PttLanguage.shortName("pt-BR"))
        assertEquals("Spanish", PttLanguage.shortName("es-ES"))
        assertEquals("Hindi", PttLanguage.shortName("hi-IN"))
    }

    @Test fun `shortName returns the raw code when unknown`() {
        assertEquals("xx-YY", PttLanguage.shortName("xx-YY"))
    }

    @Test fun `normalise passes a known code through unchanged`() {
        for (code in PttLanguage.codes) {
            assertEquals(code, PttLanguage.normalise(code))
        }
    }

    @Test fun `normalise collapses unknown codes to DEFAULT_CODE`() {
        assertEquals(PttLanguage.DEFAULT_CODE, PttLanguage.normalise("xx-YY"))
        assertEquals(PttLanguage.DEFAULT_CODE, PttLanguage.normalise(""))
        assertEquals(PttLanguage.DEFAULT_CODE, PttLanguage.normalise("garbage"))
    }

    @Test fun `every code has a fullName entry`() {
        for (code in PttLanguage.codes) {
            assertTrue(
                "code $code missing from fullName map",
                PttLanguage.fullName.containsKey(code),
            )
        }
    }

    @Test fun `every fullName entry corresponds to a code`() {
        for (key in PttLanguage.fullName.keys) {
            assertTrue(
                "fullName has $key but it isn't in codes list",
                PttLanguage.codes.contains(key),
            )
        }
    }

    @Test fun `DEFAULT_CODE is in the codes list`() {
        assertTrue(PttLanguage.codes.contains(PttLanguage.DEFAULT_CODE))
    }
}
