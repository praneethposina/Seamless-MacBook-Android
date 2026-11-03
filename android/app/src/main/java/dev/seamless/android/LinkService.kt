package dev.seamless.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps an encrypted WebSocket open to the Mac agent while paired.
 * Receives clipboard text + links from the Mac and sends ours.
 */
class LinkService : Service() {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        private const val TAG = "Seamless"
        private const val CH_STATUS = "status"
        private const val CH_LINKS = "links"
        private const val NOTIF_ID = 1
        private const val SERVICE_TYPE = "_seamless._tcp."

        @Volatile var instance: LinkService? = null
            private set
        @Volatile var state: State = State.DISCONNECTED
            private set
        var onStateChanged: (() -> Unit)? = null

        fun start(ctx: Context) {
            if (Pairing.load(ctx) == null) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java))
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, LinkService::class.java))

        /** Send a message to the Mac. Returns false if not connected. */
        fun send(msg: JSONObject): Boolean = instance?.sendNow(msg) ?: false
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var pairing: Pairing? = null
    private var crypto: Crypto? = null
    private var backoffMs = 1000L
    private var failures = 0
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discovering = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, statusNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        val p = Pairing.load(this) ?: run { stopSelf(); return START_NOT_STICKY }
        if (p != pairing || socket == null) {
            pairing = p
            crypto = Crypto(p.key)
            connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        socket = null
        stopDiscovery()
        setState(State.DISCONNECTED)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connection

    private fun connect() {
        val p = pairing ?: return
        socket?.cancel()
        setState(State.CONNECTING)
        val req = Request.Builder().url("ws://${p.host}:${p.port}/ws").build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("device", Build.MODEL)
                    .put("ts", System.currentTimeMillis() / 1000.0)
                ws.send(crypto!!.seal(hello))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val msg = runCatching { crypto!!.open(text) }.getOrNull() ?: return
                main.post { handle(msg) }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (code == 4001) Log.w(TAG, "Mac rejected our key — re-pair needed")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) = main.post { lost(ws) }.let { }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "connection failed: ${t.message}")
                main.post { lost(ws) }
            }
        })
    }

    private fun lost(ws: WebSocket) {
        if (ws !== socket) return
        socket = null
        setState(State.DISCONNECTED)
        failures++
        // After a couple of failures the Mac's IP may have changed: look it up via Bonjour.
        if (failures >= 2) discover()
        main.postDelayed({ if (socket == null && instance != null) connect() }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
    }

    private fun sendNow(msg: JSONObject): Boolean {
        val ws = socket ?: return false
        if (state != State.CONNECTED) return false
        return ws.send(crypto!!.seal(msg))
    }

    private fun setState(s: State) {
        state = s
        main.post {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, statusNotification())
            onStateChanged?.invoke()
        }
    }

    // ------------------------------------------------------------------ incoming

    private fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "welcome" -> {
                backoffMs = 1000; failures = 0
                stopDiscovery()
                setState(State.CONNECTED)
            }
            "clipboard" -> {
                val text = msg.optString("text")
                if (text.isNotEmpty()) setClipboard(text)
            }
            "url" -> openUrl(msg.optString("url"), msg.optString("title"))
        }
    }

    private fun setClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("From Mac", text)
        if (Build.VERSION.SDK_INT >= 33 && looksSensitive(text)) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied from Mac", Toast.LENGTH_SHORT).show()
    }

    /** Heuristic: hide password-like strings from the Android clipboard preview. */
    private fun looksSensitive(text: String) =
        text.length in 8..64 && !text.contains(' ') && text.any(Char::isDigit) && text.any(Char::isLetter) &&
            text.any { !it.isLetterOrDigit() } && !text.contains("://")

    private fun openUrl(url: String, title: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // With "Display over other apps" granted, Android allows opening directly from the background.
        if (Settings.canDrawOverlays(this)) {
            if (runCatching { startActivity(view) }.isSuccess) return
        }
        val pi = PendingIntent.getActivity(this, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_LINKS)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(title.ifBlank { "Link from ${pairing?.name ?: "Mac"}" })
            .setContentText("Tap to open · $url")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java).notify(url.hashCode(), n)
    }

    // ------------------------------------------------------------------ discovery

    private val nsd by lazy { getSystemService(NsdManager::class.java) }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            val id = pairing?.id ?: return
            if (!info.serviceName.contains(id)) return
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress ?: return
                    main.post {
                        val p = pairing ?: return@post
                        if (host != p.host || info.port != p.port) {
                            Log.i(TAG, "Mac moved to $host:${info.port}")
                            pairing = p.withHost(this@LinkService, host, info.port)
                        }
                        backoffMs = 1000
                        if (state != State.CONNECTED) connect()
                    }
                }
            })
        }
    }

    private fun discover() {
        if (discovering) return
        discovering = true
        multicastLock = getSystemService(WifiManager::class.java)
            .createMulticastLock("seamless").apply { setReferenceCounted(false); acquire() }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { discovering = false }
    }

    private fun stopDiscovery() {
        if (discovering) runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        discovering = false
        multicastLock?.release()
        multicastLock = null
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_LINKS, "Links from Mac", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun statusNotification(): Notification {
        val name = pairing?.name ?: Pairing.load(this)?.name ?: "Mac"
        val text = when (state) {
            State.CONNECTED -> "Connected to $name"
            State.CONNECTING -> "Connecting to $name…"
            State.DISCONNECTED -> "Waiting for $name"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sendClip = PendingIntent.getActivity(
            this, 1,
            Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("Seamless")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Send clipboard to Mac", sendClip)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
