package dev.alore.argos.data

import android.util.Log
import dev.alore.argos.model.Server
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File

/** Observable list of servers persisted as JSON (`servers.json` in app-private storage). */
class ServerStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    fun server(id: String): Server? = _servers.value.firstOrNull { it.id == id }

    /** Adds or replaces (by id), keeping the display order. */
    fun upsert(server: Server) = mutate { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }

    fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private fun mutate(transform: (List<Server>) -> List<Server>) {
        _servers.update(transform)
        persist(_servers.value)
    }

    private fun persist(servers: List<Server>) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(servers))
            if (!tmp.renameTo(file)) error("rename failed")
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist ${file.path}", e)
        }
    }

    private fun load(): List<Server> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Server>>(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt ${file.path}; starting with an empty list", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "Argos/Store"
    }
}
