package jp.hi7.sshtermproto

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class SshTermApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android's built-in "BC" provider is a crippled stub (missing
        // X25519, modern curves, etc). Replace it with the real
        // BouncyCastle implementation so sshj's key exchange works.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
