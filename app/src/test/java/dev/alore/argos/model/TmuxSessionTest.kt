package dev.alore.argos.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionTest {
    @Test
    fun `well formed line parses every field`() {
        val session = TmuxSession.parse("main|3|1|1700000000")!!
        assertEquals("main", session.name)
        assertEquals(3, session.windowCount)
        assertTrue(session.isAttached)
        assertEquals(1_700_000_000L, session.createdAtEpochSeconds)
    }

    @Test
    fun `attached 0 means not attached`() {
        assertFalse(TmuxSession.parse("logs|1|0|1700000000")!!.isAttached)
    }

    @Test
    fun `invalid lines return null`() {
        listOf(
            "", "   ", "main|3|1", "main|3|1|1700|extra", "main|x|1|1700000000",
            "main|3|y|1700000000", "main|3|1|notanepoch", "|3|1|1700000000",
        ).forEach { assertNull("'$it'", TmuxSession.parse(it)) }
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val session = TmuxSession.parse("  main|2|0|1700000000  \n")!!
        assertEquals("main", session.name)
        assertEquals(2, session.windowCount)
    }

    @Test
    fun `parseList skips garbage lines`() {
        val sessions = TmuxSession.parseList("a|1|0|1\nnot a session\n\nb/c|2|1|2\n")
        assertEquals(listOf("a", "b/c"), sessions.map { it.name })
    }
}
