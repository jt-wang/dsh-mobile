package dev.dshmobile.app.data

import dev.dshmobile.app.ui.chat.ViewerGeometry
import dev.dshmobile.app.ui.chat.ImageSizing
import org.junit.Assert.*
import org.junit.Test

class ViewerGeometryTest {
    @Test fun `landscape image pan cannot expose horizontal edges or drift vertically`() {
        val offset = ViewerGeometry.clamp(999f, 999f, 2.5f, 400f, 900f, 1200, 600)
        assertEquals(300f, offset.x, .01f); assertEquals(0f, offset.y, .01f)
    }
    @Test fun `double tap zoom keeps tapped point stationary when within bounds`() {
        val offset = ViewerGeometry.zoom(0f, 0f, 1f, 2.5f, 300f, 450f, 400f, 900f, 400, 900)
        assertEquals(-150f, offset.x, .01f); assertEquals(0f, offset.y, .01f)
        assertEquals(0f, ViewerGeometry.zoom(offset.x, offset.y, 2.5f, 1f, 300f, 450f, 400f, 900f, 400, 900).x, .01f)
    }
    @Test fun `extreme valid dimensions sampled before decode within bitmap budget`() {
        assertEquals(64, ImageSizing.sample(100000, 100000, 4096))
        assertEquals(16, ImageSizing.sample(4000, 3000, 256))
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid bounds rejected without allocation`() { ImageSizing.sample(-1, 200, 1024) }
}
