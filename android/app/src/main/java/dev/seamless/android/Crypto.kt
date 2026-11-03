package dev.seamless.android

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Frame = base64url(nonce[12] || AES-256-GCM(json) || tag). Mirrors mac-agent/seamless_agent.py. */
class Crypto(keyB64: String) {
    private val key = SecretKeySpec(decode(keyB64).also { require(it.size == 32) }, "AES")
    private val random = SecureRandom()

    fun seal(msg: JSONObject): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return encode(nonce + c.doFinal(msg.toString().toByteArray()))
    }

    fun open(frame: String): JSONObject {
        val raw = decode(frame)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        return JSONObject(String(c.doFinal(raw, 12, raw.size - 12)))
    }

    private companion object {
        const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        fun encode(b: ByteArray): String = Base64.encodeToString(b, FLAGS)
        fun decode(s: String): ByteArray = Base64.decode(s, FLAGS)
    }
}
