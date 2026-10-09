package me.quantiom.advancedvanish.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionsTest {
    @Test
    fun `applyPlaceholders replaces every pair`() {
        val result = "%a% and %b%".applyPlaceholders("%a%" to "1", "%b%" to "2")

        assertEquals("1 and 2", result)
    }

    @Test
    fun `convertLegacyCodes translates colors, formats and hex`() {
        assertEquals("<red>hi", "&chi".convertLegacyCodes())
        assertEquals("<bold>hi", "§lhi".convertLegacyCodes())
        assertEquals("<#aabbcc>hi", "&x&a&a&b&b&c&chi".convertLegacyCodes())
        assertEquals("plain", "plain".convertLegacyCodes())
    }

    @Test
    fun `escapeUserInput neutralises MiniMessage tags and placeholders`() {
        val escaped = "<click:run_command:/op me>%server_ip%".escapeUserInput()

        assertFalse('%' in escaped)
        assertTrue(escaped.startsWith("\\<click"), escaped)
    }

    @Test
    fun `escapeUserInput caps the length`() {
        assertTrue("a".repeat(10_000).escapeUserInput().length <= 64)
    }
}
