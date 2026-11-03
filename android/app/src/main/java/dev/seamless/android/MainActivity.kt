package dev.seamless.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Pairing + setup checklist. Built in code to keep the project tiny. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var checklist: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 20f; setPadding(0, 0, 0, pad) }
        root.addView(status)
        root.addView(button("Pair with Mac (scan QR)") { scan() })
        root.addView(button("Pair by pasting code") { pasteDialog() })
        root.addView(button("Send phone clipboard to Mac") {
            startActivity(Intent(this, ClipboardSendActivity::class.java))
        })
        root.addView(TextView(this).apply {
            text = "\nSetup checklist"
            textSize = 16f
        })
        checklist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(checklist)
        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = """
                How to use
                • Copy on the Mac → it's on your phone clipboard automatically.
                • Select text on the phone → tap “Send to Mac” in the menu (⋮) → it's on the Mac clipboard.
                • Copied something already? Pull down Quick Settings → tap “Clipboard → Mac”, or use the action in the Seamless notification.
                • Any link / YouTube video → Share → “Send to Mac” → opens on the Mac (YouTube at the same second).
                • On the Mac, click the Seamless icon in Chrome (or ⌘⇧Y) → the tab opens here (YouTube at the same second).
            """.trimIndent()
        })
        setContentView(ScrollView(this).apply { addView(root) })

        intent?.data?.toString()?.let { applyPairing(it) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        LinkService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { applyPairing(it) }
    }

    override fun onResume() {
        super.onResume()
        LinkService.onStateChanged = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        LinkService.onStateChanged = null
        super.onPause()
    }

    private fun refresh() {
        val p = Pairing.load(this)
        status.text = when {
            p == null -> "Not paired yet"
            LinkService.state == LinkService.State.CONNECTED -> "✅ Connected to ${p.name}"
            LinkService.state == LinkService.State.CONNECTING -> "⏳ Connecting to ${p.name} (${p.host})…"
            else -> "⚠️ ${p.name} not reachable — is the agent running and are you on the same Wi-Fi?"
        }
        checklist.removeAllViews()
        val pm = getSystemService(PowerManager::class.java)
        checkItem(
            "Open links from the Mac instantly (Display over other apps)",
            Settings.canDrawOverlays(this),
        ) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        checkItem(
            "YouTube handoff at the exact second (Notification access)",
            YouTube.hasMediaAccess(this),
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        checkItem(
            "Stay connected in the background (Battery: unrestricted)",
            pm.isIgnoringBatteryOptimizations(packageName),
        ) { requestBatteryExemption() }
    }

    private fun checkItem(label: String, ok: Boolean, fix: () -> Unit) {
        checklist.addView(if (ok) TextView(this).apply { text = "✓ $label"; setPadding(0, 12, 0, 12) } else button("Enable: $label", fix))
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    private fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { it.rawValue?.let(::applyPairing) }
            .addOnFailureListener { Toast.makeText(this, "Scanner unavailable — paste the code instead", Toast.LENGTH_LONG).show() }
    }

    private fun pasteDialog() {
        val input = EditText(this).apply {
            hint = "seamless://pair?..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Paste pairing code from the Mac")
            .setView(input)
            .setPositiveButton("Pair") { _, _ -> applyPairing(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPairing(text: String) {
        val p = Pairing.parse(text)
        if (p == null) {
            Toast.makeText(this, "That's not a Seamless pairing code", Toast.LENGTH_LONG).show()
            return
        }
        p.save(this)
        LinkService.stop(this)
        LinkService.start(this)
        Toast.makeText(this, "Paired with ${p.name}", Toast.LENGTH_SHORT).show()
        refresh()
    }
}
