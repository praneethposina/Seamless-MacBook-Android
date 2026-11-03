package dev.seamless.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.widget.Toast

/** Share sheet ("Send to Mac") and the text-selection toolbar entry. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) {
            // Apps like YouTube/Chrome share "Title https://link" – send just the link so it opens on the Mac.
            val payload = if (intent.action == Intent.ACTION_SEND) Sender.extractSharedUrl(text) ?: text else text
            Sender.sendText(this, payload, openLinks = intent.action == Intent.ACTION_SEND)
        }
        finish()
    }
}

/**
 * Android only lets the focused app read the clipboard, so this invisible activity
 * grabs focus for a moment, reads it, sends it to the Mac and closes.
 */
class ClipboardSendActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        } else {
            Sender.sendText(this, text, openLinks = false)
        }
        finish()
    }
}

/** Exists only so we may read media playback positions (YouTube handoff). */
class MediaAccessService : NotificationListenerService()

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LinkService.start(ctx)
        }
    }
}
