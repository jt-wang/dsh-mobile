package dev.dshmobile.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class AttachmentNameTest {
    @Test fun `picker identifiers use neutral localized names and preserve extension`() {
        assertEquals("Photo 3.jpg", attachmentDisplayName("64.jpg", "image/jpeg", "Photo 3"))
        assertEquals("Фото 4.HEIC", attachmentDisplayName("123.HEIC", "image/heic", "Фото 4"))
        assertEquals("Photo 5.png", attachmentDisplayName("---.png", "image/png", "Photo 5"))
        assertEquals("Photo 6.jpeg", attachmentDisplayName("", "image/jpeg", "Photo 6"))
        assertEquals("Photo 7.jpg", attachmentDisplayName("attachment.jpg", "image/jpeg", "Photo 7"))
    }
    @Test fun `meaningful names and non images remain unchanged after sanitizing`() {
        assertEquals("holiday.jpg", attachmentDisplayName("/picked/holiday.jpg", "image/jpeg", "Photo 1"))
        assertEquals("2026-invoice.pdf", attachmentDisplayName("2026-invoice.pdf", "application/pdf", "Photo 1"))
        assertEquals("64.txt", attachmentDisplayName("64.txt", "text/plain", "Photo 1"))
        assertEquals("report.jpg", attachmentDisplayName("report\u0000.jpg", "image/jpeg", "Photo 1"))
        val longName = attachmentDisplayName("я".repeat(200) + ".jpg", "image/jpeg", "Photo 1")
        assertTrue(longName.toByteArray().size <= 256)
        assertTrue(longName.endsWith(".jpg"))
    }
}
