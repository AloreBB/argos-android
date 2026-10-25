package dev.alore.argos.ui.sessions

import dev.alore.argos.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private val sessions = listOf("Producción/api", "dev", "logs").map { TmuxSession(it, 1, false, 0) }

    @Test
    fun `empty query returns all`() = assertEquals(sessions, matching(sessions, "  "))

    @Test
    fun `match is case and accent insensitive`() =
        assertEquals(listOf("Producción/api"), matching(sessions, "produccion").map { it.name })
}
