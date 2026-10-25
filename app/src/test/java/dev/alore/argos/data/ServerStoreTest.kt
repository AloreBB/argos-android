package dev.alore.argos.data

import dev.alore.argos.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `first run starts empty (no hardcoded server)`() =
        assertTrue(ServerStore(tmp.root.resolve("servers.json")).servers.value.isEmpty())

    @Test
    fun `upsert and remove persist across reloads`() {
        val file = tmp.root.resolve("servers.json")
        val store = ServerStore(file)
        val prod = Server(name = "prod", host = "p", username = "u")
        store.upsert(prod)
        store.upsert(Server(name = "dev", host = "d", username = "u"))
        store.upsert(prod.copy(name = "producción"))

        val reloaded = ServerStore(file)
        assertEquals(listOf("producción", "dev"), reloaded.servers.value.map { it.name })

        reloaded.remove(prod.id)
        assertNull(ServerStore(file).server(prod.id))
        assertEquals(1, ServerStore(file).servers.value.size)
    }

    @Test
    fun `corrupt file starts empty instead of crashing`() {
        val file = tmp.root.resolve("servers.json").apply { writeText("{not json") }
        assertTrue(ServerStore(file).servers.value.isEmpty())
    }
}
