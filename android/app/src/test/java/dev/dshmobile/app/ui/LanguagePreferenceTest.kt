package dev.dshmobile.app.ui

import dev.dshmobile.app.locale.LanguagePreference
import org.junit.Assert.*
import org.junit.Test

class LanguagePreferenceTest {
    @Test fun `missing or empty choice follows system but foreign pin selects no picker option`() {
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromTag(null))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromTag(""))
        assertNull(LanguagePreference.fromTag("de"))
        assertNull(LanguagePreference.fromTag("uk"))
        assertEquals("", LanguagePreference.SYSTEM.tag)
    }

    @Test fun `supported platform regional tags map to picker and persisted choices round trip`() {
        assertEquals(LanguagePreference.RUSSIAN, LanguagePreference.fromTag("ru-RU"))
        assertEquals(LanguagePreference.ENGLISH, LanguagePreference.fromTag("en-US"))
        assertEquals("ru", LanguagePreference.RUSSIAN.tag)
        assertEquals("en", LanguagePreference.ENGLISH.tag)
        LanguagePreference.entries.forEach { assertEquals(it, LanguagePreference.fromTag(it.tag)) }
    }
}
