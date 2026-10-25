package dev.alore.argos.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerTest {
    @Test
    fun `json round trip`() {
        val server = Server(name = "dev", host = "10.0.0.1", port = 2222, username = "u", keyLabel = "id_ed25519")
        assertEquals(server, Json.decodeFromString<Server>(Json.encodeToString(server)))
    }

    @Test
    fun `endpoint is host colon port`() = assertEquals("h:22", Server(name = "x", host = "h", username = "u").endpoint)

    @Test
    fun `missing optional fields get defaults`() {
        val server = Json.decodeFromString<Server>("""{"id":"1","name":"a","host":"h","username":"u"}""")
        assertEquals(22, server.port)
        assertEquals(AuthMethod.KEY, server.authMethod)
    }

    @Test
    fun `auth method is serialized in lowercase`() {
        val json = Json.encodeToString(Server(name = "a", host = "h", username = "u", authMethod = AuthMethod.PASSWORD))
        assert("\"password\"" in json) { json }
    }
}
