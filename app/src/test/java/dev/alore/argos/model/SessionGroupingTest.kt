package dev.alore.argos.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {
    private fun session(name: String) = TmuxSession(name, 1, false, 0)

    @Test
    fun `split uses the first slash`() = assertEquals("magic-agents" to "backend", SessionGrouping.split("magic-agents/backend"))

    @Test
    fun `no slash goes to General with the full name`() = assertEquals("General" to "main", SessionGrouping.split("main"))

    @Test
    fun `empty prefix goes to General with the full name`() = assertEquals("General" to "/foo", SessionGrouping.split("/foo"))

    @Test
    fun `empty suffix keeps a usable label`() = assertEquals("foo" to "foo/", SessionGrouping.split("foo/"))

    @Test
    fun `General is always last`() {
        val groups = SessionGrouping.groups(listOf(session("zeta/a"), session("solo"), session("alpha/b")))
        assertEquals(listOf("alpha", "zeta", "General"), groups.map { it.name })
    }

    @Test
    fun `sessions sorted by short name case-insensitively`() {
        val groups = SessionGrouping.groups(listOf(session("g/Charlie"), session("g/alpha"), session("g/Bravo")))
        assertEquals(listOf("g/alpha", "g/Bravo", "g/Charlie"), groups.single { it.name == "g" }.sessions.map { it.name })
    }

    @Test
    fun `empty input gives no groups`() = assertTrue(SessionGrouping.groups(emptyList()).isEmpty())
}
