package dev.dshmobile.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogKeyTest {
    @Test fun `colon separated provider model pairs never collide`() {
        assertNotEquals(modelCatalogKey("a:b", "c"), modelCatalogKey("a", "b:c"))
        val keys = listOf("", ":", "a", "a:", "a:b", "模型").flatMap { p ->
            listOf("", ":", "b:c", "c", "模型").map { m -> modelCatalogKey(p, m) }
        }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(modelCatalogKey("p", "m"), modelCatalogKey("p", "m"))
    }
}
