package dev.alore.argos.data

import androidx.test.core.app.ApplicationProvider
import dev.alore.argos.testing.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecretStoreTest {
    private lateinit var store: SecretStore

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        store = SecretStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `round trip per server and kind`() {
        store.put("a", SecretKind.PASSWORD, "s3cr3t ñ")
        store.put("b", SecretKind.PASSWORD, "other")
        assertEquals("s3cr3t ñ", store.get("a", SecretKind.PASSWORD))
        assertEquals("other", store.get("b", SecretKind.PASSWORD))
        assertNull(store.get("a", SecretKind.PASSPHRASE))
    }

    @Test
    fun `nothing is stored in plain text`() {
        store.put("a", SecretKind.PRIVATE_KEY, "-----BEGIN OPENSSH PRIVATE KEY-----")
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("argos_secrets", 0).all.values.joinToString()
        assertFalse(prefs, "OPENSSH" in prefs)
    }

    @Test
    fun `empty deletes and deleteAll clears a server`() {
        store.put("a", SecretKind.PASSWORD, "x")
        store.put("a", SecretKind.PASSWORD, "")
        assertNull(store.get("a", SecretKind.PASSWORD))
        store.put("a", SecretKind.PASSPHRASE, "p")
        store.put("a", SecretKind.PRIVATE_KEY, "k")
        store.deleteAll("a")
        assertNull(store.get("a", SecretKind.PASSPHRASE))
        assertNull(store.get("a", SecretKind.PRIVATE_KEY))
    }
}
