package dev.seamless.android

import android.content.Context
import android.net.Uri

/** What we learn from the Mac's pairing QR code: seamless://pair?h=..&p=..&k=..&n=..&id=.. */
data class Pairing(val host: String, val port: Int, val key: String, val name: String, val id: String) {
    companion object {
        private const val PREFS = "seamless"

        fun parse(text: String): Pairing? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "seamless" || uri.host != "pair") return null
            val host = uri.getQueryParameter("h") ?: return null
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
            val key = uri.getQueryParameter("k") ?: return null
            if (runCatching { Crypto(key) }.isFailure) return null
            return Pairing(host, port, key, uri.getQueryParameter("n") ?: "Mac", uri.getQueryParameter("id") ?: "")
        }

        fun load(ctx: Context): Pairing? {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = p.getString("key", null) ?: return null
            return Pairing(
                p.getString("host", "")!!, p.getInt("port", 47823), key,
                p.getString("name", "Mac")!!, p.getString("id", "")!!,
            )
        }

        fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", host).putInt("port", port).putString("key", key)
            .putString("name", name).putString("id", id).apply()
    }

    /** The Mac's IP can change (DHCP); remember the latest one found via Bonjour/NSD. */
    fun withHost(ctx: Context, newHost: String, newPort: Int): Pairing =
        copy(host = newHost, port = newPort).also { it.save(ctx) }
}
