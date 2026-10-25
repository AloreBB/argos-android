package dev.alore.argos.ssh

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

@Serializable
data class KnownHost(val keyType: String, val fingerprint: String)

/**
 * Trust-On-First-Use host key store (Argos' known_hosts.json):
 * `{"host:port": {"keyType": "ssh-ed25519", "fingerprint": "SHA256:..."}}`.
 *
 * The key type is stored too so the next handshake negotiates the SAME host key algorithm;
 * otherwise a server with several host keys could present a different (legit) one and look like
 * a MitM.
 */
class KnownHostsStore(private val file: File) {
    private val json = Json { prettyPrint = true }

    @Synchronized
    fun get(endpoint: String): KnownHost? = load()[endpoint]

    /** Returns the stored entry when [presented] differs from it; trusts and stores it on first use. */
    @Synchronized
    fun verifyOrTrust(endpoint: String, presented: KnownHost): KnownHost? {
        val known = load()
        val stored = known[endpoint]
        if (stored == null) {
            save(known + (endpoint to presented))
            Log.i(TAG, "TOFU: trusting $endpoint ${presented.keyType} ${presented.fingerprint}")
            return null
        }
        return stored.takeIf { it != presented }
    }

    /** Forgets the trusted key so the next connection re-trusts (after a legit reinstall). */
    @Synchronized
    fun forget(endpoint: String) {
        val known = load()
        if (endpoint !in known) return
        save(known - endpoint)
        Log.i(TAG, "Forgot host key for $endpoint")
    }

    private fun load(): Map<String, KnownHost> {
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, KnownHost>>(file.readText())
        } catch (e: Exception) {
            // Security-relevant: an unreadable store means every host gets re-trusted. Log it loudly.
            Log.e(TAG, "known_hosts corrupt at ${file.path}; ignoring it and re-trusting hosts", e)
            emptyMap()
        }
    }

    private fun save(known: Map<String, KnownHost>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(known))
            if (!tmp.renameTo(file)) error("rename failed")
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist known_hosts at ${file.path}", e)
        }
    }

    companion object {
        private const val TAG = "Argos/HostKey"

        /** SHA256 fingerprint in OpenSSH's visual format (`SHA256:<base64 without padding>`). */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

/**
 * sshj verifier backed by [KnownHostsStore]. When it rejects a key, sshj only throws a generic
 * TransportException, so the mismatch details are kept in [mismatch] for a precise error.
 */
class TofuHostKeyVerifier(private val store: KnownHostsStore) : HostKeyVerifier {
    @Volatile
    var mismatch: SshError.HostKeyMismatch? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val endpoint = "$hostname:$port"
        val presented = KnownHost(KeyType.fromKey(key).toString(), KnownHostsStore.fingerprint(key))
        val stored = store.verifyOrTrust(endpoint, presented) ?: return true
        mismatch = SshError.HostKeyMismatch(endpoint, stored.fingerprint, presented.fingerprint)
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        listOfNotNull(store.get("$hostname:$port")?.keyType)
}
