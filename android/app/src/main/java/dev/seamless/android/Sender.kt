package dev.seamless.android

import android.content.Context
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

/** Helpers used by the share sheet, tile and notification action. */
object Sender {
    private val URL_RE = Regex("""https?://\S+""")

    /**
     * @param openLinks when true (share sheet), a lone link opens in the Mac's browser;
     *                  otherwise everything goes to the Mac clipboard.
     */
    fun sendText(ctx: Context, text: String, openLinks: Boolean): Boolean {
        val trimmed = text.trim()
        val isLoneUrl = openLinks && URL_RE.matchEntire(trimmed) != null
        val msg = if (isLoneUrl) {
            JSONObject().put("type", "url").put("url", YouTube.withPlaybackPosition(ctx, trimmed))
        } else {
            JSONObject().put("type", "clipboard").put("text", text)
        }
        msg.put("id", UUID.randomUUID().toString())
        val ok = LinkService.send(msg)
        val name = Pairing.load(ctx)?.name ?: "Mac"
        Toast.makeText(
            ctx,
            when {
                !ok -> "Not connected to $name"
                isLoneUrl -> "Opening on $name"
                else -> "Copied to $name"
            },
            Toast.LENGTH_SHORT,
        ).show()
        if (!ok) LinkService.start(ctx)
        return ok
    }

    /** Pulls the first URL out of shared text such as "Check this out https://…". */
    fun extractSharedUrl(text: String): String? {
        val m = URL_RE.findAll(text).toList()
        return if (m.size == 1) m[0].value.trimEnd('.', ',', ')') else null
    }
}
