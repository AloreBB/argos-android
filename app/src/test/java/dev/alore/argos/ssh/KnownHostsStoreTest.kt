package dev.alore.argos.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator

class KnownHostsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ed = KnownHost("ssh-ed25519", "SHA256:aaa")

    @Test
    fun `first use trusts and persists`() {
        val file = tmp.root.resolve("known_hosts.json")
        assertNull(KnownHostsStore(file).verifyOrTrust("h:22", ed))
        assertEquals(ed, KnownHostsStore(file).get("h:22"))
    }

    @Test
    fun `same key verifies, different key is reported`() {
        val store = KnownHostsStore(tmp.root.resolve("k.json"))
        store.verifyOrTrust("h:22", ed)
        assertNull(store.verifyOrTrust("h:22", ed))
        assertEquals(ed, store.verifyOrTrust("h:22", ed.copy(fingerprint = "SHA256:evil")))
        // A mismatch must NOT overwrite the trusted key.
        assertEquals(ed, store.get("h:22"))
    }

    @Test
    fun `port is part of the identity`() {
        val store = KnownHostsStore(tmp.root.resolve("k.json"))
        store.verifyOrTrust("h:22", ed)
        assertNull(store.verifyOrTrust("h:2222", ed.copy(fingerprint = "SHA256:other")))
    }

    @Test
    fun `forget allows re-trusting`() {
        val store = KnownHostsStore(tmp.root.resolve("k.json"))
        store.verifyOrTrust("h:22", ed)
        store.forget("h:22")
        assertNull(store.verifyOrTrust("h:22", ed.copy(fingerprint = "SHA256:new")))
    }

    @Test
    fun `verifier exposes mismatch and preferred algorithm`() {
        val store = KnownHostsStore(tmp.root.resolve("k.json"))
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val verifier = TofuHostKeyVerifier(store)
        assertTrue(verifier.verify("h", 22, key))
        assertEquals(listOf("ssh-rsa"), verifier.findExistingAlgorithms("h", 22))

        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val second = TofuHostKeyVerifier(store)
        assertFalse(second.verify("h", 22, other))
        assertEquals("h:22", second.mismatch?.endpoint)
    }

    @Test
    fun `fingerprint matches OpenSSH format`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val fp = KnownHostsStore.fingerprint(key)
        assertTrue(fp, fp.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
    }
}
