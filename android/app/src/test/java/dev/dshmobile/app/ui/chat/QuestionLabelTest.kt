package dev.dshmobile.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class QuestionLabelTest {
    @Test fun `recommendation display strips only a trailing marked suffix`() {
        for (suffix in listOf(" (Recommended)", "（ recommended ）", " ( 推荐 ) ", "[ Recommended ]", "［推荐］", "(RECOMMENDED)")) {
            assertEquals("Choice", questionOptionDisplayLabel("Choice$suffix"))
        }
        for (label in listOf("Recommended in prose", "A (Recommended) then B", "Plain")) {
            assertEquals(label, questionOptionDisplayLabel(label))
        }
    }
    @Test fun `continued questions share the open question controls`() {
        assertTrue(questionAnswerable("open")); assertTrue(questionAnswerable("continued"))
        assertFalse(questionAnswerable("expired")); assertFalse(questionAnswerable("unknown"))
    }
}
