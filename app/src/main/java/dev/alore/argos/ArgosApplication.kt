package dev.alore.argos

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class ArgosApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
        container = AppContainer(this)
    }

    companion object {
        /**
         * Android ships a stripped-down "BC" provider that lacks what sshj needs (Ed25519, X25519,
         * modern ciphers). Replace it with the full BouncyCastle before any SSH work.
         */
        fun installBouncyCastle() {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
