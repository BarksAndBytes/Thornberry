package app.thornberry.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManagerFactory
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.TorWrapper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps Tor running with one onion service, accepts frames from contacts, and sends queued frames.
 * Wire protocol over the onion connection: repeated [4-byte length][frame]; the receiver answers
 * one byte (1) after it has processed each frame, and a frame is considered delivered only then.
 * The frames are already end-to-end encrypted and authenticated by the engine; Tor is the carrier.
 */
class ChatService : Service() {

    companion object {
        const val ACTION_STOP = "app.thornberry.chat.STOP"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_MSG = "messages"
        private const val NOTIFICATION_ID = 1
        private const val ONION_PORT = 80
        private const val CONNECT_TIMEOUT_MS = 90_000
        private const val MAX_FRAME = 600_000
        private const val MAX_FRAMES_PER_CONNECTION = 64
        private const val HS_KEY_FILE = "hs.key"
        @Volatile var running = false
    }

    private val io: ExecutorService = Executors.newCachedThreadPool()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<String>()
    private val sending = AtomicBoolean(false)
    @Volatile private var wrapper: AndroidTorWrapper? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socksPort = 0
    private var started = false
    private var msgCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        Node.init(this)
        startInForeground("Starting")
        if (!started) {
            started = true
            running = true
            Node.sendHook = { requestSend() }
            Node.notifyHook = { text -> notifyMessage(text) }
            io.execute { runTor() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun runTor() {
        try {
            if (!Node.ready()) { updateNotification("Locked: data unreadable"); return }
            val app = application
            val wakeLocks = AndroidWakeLockManagerFactory.createAndroidWakeLockManager(app)
            socksPort = freePort()
            val controlPort = freePort()
            val w = AndroidTorWrapper(
                app, wakeLocks, io, Executor { r -> mainHandler.post(r) },
                Build.SUPPORTED_ABIS[0], File(filesDir, "tor"), socksPort, controlPort,
            )
            wrapper = w
            w.setObserver(object : TorWrapper.Observer {
                override fun onState(s: TorWrapper.TorState) {
                    Node.torState = s.name; Node.changed(); updateNotification("Tor ${s.name}")
                    if (s.name == "CONNECTED") requestSend()
                }
                override fun onBootstrapPercentage(percentage: Int) {}
                override fun onHsDescriptorUpload(onion: String) {}
                override fun onClockSkewDetected(skewSeconds: Long) {}
            })
            w.start()
            w.enableNetwork(true)

            val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = srv
            val savedKey = SecureStorage.read(filesDir, HS_KEY_FILE)?.toString(Charsets.UTF_8)
            val props = w.publishHiddenService(srv.localPort, ONION_PORT, savedKey)
            if (savedKey == null) SecureStorage.write(filesDir, HS_KEY_FILE, props.privKey.toByteArray(Charsets.UTF_8))
            Node.onion = if (props.onion.endsWith(".onion")) props.onion else props.onion + ".onion"
            Node.changed()
            updateNotification("Connected")

            io.execute { acceptLoop(srv) }
            scheduler.scheduleWithFixedDelay({ requestSend() }, 20, 30, TimeUnit.SECONDS)
            scheduler.scheduleWithFixedDelay({
                try { Node.syncNow() } catch (_: Exception) {}
            }, 60, 180, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            updateNotification("Error: ${e.javaClass.simpleName}")
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // ---------------- receiving ----------------

    private fun acceptLoop(srv: ServerSocket) {
        while (running && !srv.isClosed) {
            try {
                val c = srv.accept()
                io.execute { handleIncoming(c) }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleIncoming(c: Socket) {
        c.use {
            try {
                it.soTimeout = 60_000
                val inp = DataInputStream(BufferedInputStream(it.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(it.getOutputStream()))
                var n = 0
                while (n++ < MAX_FRAMES_PER_CONNECTION) {
                    val len = try { inp.readInt() } catch (_: java.io.EOFException) { break }
                    if (len <= 0 || len > MAX_FRAME) break
                    val frame = ByteArray(len)
                    inp.readFully(frame)
                    try { Node.use { e -> e.receiveFrame(frame, System.currentTimeMillis()) } } catch (_: Exception) {}
                    out.writeByte(1); out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- sending ----------------

    private fun requestSend() {
        if (!running || Node.torState != "CONNECTED") return
        if (!sending.compareAndSet(false, true)) { return }
        io.execute {
            try {
                val pending = Node.use { it.pendingFrames() }
                val byContact = pending.groupBy { it.contactId }
                for ((cid, frames) in byContact) {
                    synchronized(inFlight) { if (!inFlight.add(cid)) null else Unit } ?: continue
                    io.execute {
                        try { sendTo(frames) } finally { synchronized(inFlight) { inFlight.remove(cid) } }
                    }
                }
            } catch (_: Exception) {
            } finally {
                sending.set(false)
            }
        }
    }

    private fun sendTo(frames: List<app.thornberry.crypto.ChatEngine.Outgoing>) {
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            Socket(proxy).use { s ->
                s.soTimeout = 60_000
                s.connect(InetSocketAddress.createUnresolved(frames[0].toOnion + ".onion", ONION_PORT), CONNECT_TIMEOUT_MS)
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                for (f in frames.take(MAX_FRAMES_PER_CONNECTION)) {
                    out.writeInt(f.frame.size); out.write(f.frame); out.flush()
                    if (inp.readByte().toInt() != 1) return
                    Node.use { it.markDelivered(f) }
                }
            }
        } catch (_: Exception) {
            // Stays queued; retried every 30 seconds.
        }
    }

    // ---------------- lifecycle and notifications ----------------

    private fun shutdown() {
        running = false
        Node.sendHook = null; Node.notifyHook = null
        Node.torState = "STOPPED"; Node.onion = ""; Node.changed()
        try { server?.close() } catch (_: Exception) {}
        scheduler.shutdownNow()
        val w = wrapper
        if (w != null) Thread { try { w.stop() } catch (_: Exception) {} }.start()
    }

    private fun notifyMessage(text: String) {
        try {
            msgCount++
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Deliberately no sender and no text on the lock screen.
            val n = Notification.Builder(this, CHANNEL_MSG)
                .setContentTitle("Thornberry")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            getSystemService(NotificationManager::class.java).notify(100, n)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setContentTitle("Thornberry (test)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MSG, "Messages", NotificationManager.IMPORTANCE_DEFAULT))
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) } catch (_: Exception) {}
    }
}
