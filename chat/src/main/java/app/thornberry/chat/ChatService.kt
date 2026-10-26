package app.thornberry.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.CancellationSignal
import app.thornberry.crypto.LocationFix
import app.thornberry.crypto.LocationPolicy
import app.thornberry.crypto.Geo
import kotlin.math.roundToInt
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
        const val ACTION_LOCATION_REFRESH = "app.thornberry.chat.LOCATION_REFRESH"
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
    private val locationLoop = AtomicBoolean(false)
    private val lastSent = HashMap<String, LocationPolicy.Sent>()
    private var lastSample: Pair<Double, Double>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        Node.init(this)
        startInForeground(if (started) "Tor ${Node.torState}" else "Starting")
        if (intent?.action == ACTION_LOCATION_REFRESH) { io.execute { locationSetup() }; return START_STICKY }
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
                    ChatLog.log("TOR_STATE", s.name)
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
            ChatLog.log("ONION_PUBLISHED", "prefix=${Node.onion.take(6)}")
            Node.changed()
            updateNotification("Connected")

            io.execute { acceptLoop(srv) }
            io.execute { locationSetup() }
            scheduler.scheduleWithFixedDelay({ requestSend() }, 20, 30, TimeUnit.SECONDS)
            scheduler.scheduleWithFixedDelay({
                try { Node.syncNow() } catch (_: Exception) {}
            }, 60, 180, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            ChatLog.err("runTor", e)
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
                    try { Node.use { e -> e.receiveFrame(frame, System.currentTimeMillis()) } } catch (e: Exception) { ChatLog.err("receive", e) }
                    ChatLog.log("FRAME_IN", "bytes=${frame.size} dropped=${Node.read { it.droppedFrames }}")
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
                val pending = Node.read { it.pendingFrames() }
                val byContact = pending.groupBy { it.contactId }
                for ((cid, frames) in byContact) {
                    synchronized(inFlight) { if (!inFlight.add(cid)) null else Unit } ?: continue
                    io.execute {
                        try { sendTo(frames) } finally { synchronized(inFlight) { inFlight.remove(cid) } }
                    }
                }
            } catch (e: Exception) {
                ChatLog.err("requestSend", e)
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
                    ChatLog.log("FRAME_OUT_OK", "bytes=${f.frame.size}")
                }
            }
        } catch (e: Exception) {
            // Stays queued; retried every 30 seconds.
            ChatLog.log("FRAME_OUT_FAIL", "${e.javaClass.simpleName} queued=${frames.size}")
        }
    }

    // ---------------- location sharing ----------------

    private fun hasLocationPermission() =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Start sampling if any group is set to share and permission is granted. Safe to call repeatedly. */
    private fun locationSetup() {
        if (!running) return
        if (Node.sharingGroups().isEmpty() || !hasLocationPermission()) return
        try {
            val n = buildNotification("Sharing location with your groups")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // Android refuses the location type if the service was started in the background. Open the app to retry.
            ChatLog.err("fgs-location", e)
            return
        }
        if (locationLoop.compareAndSet(false, true)) {
            ChatLog.log("LOC_START")
            scheduler.schedule({ sampleLocation() }, 3, TimeUnit.SECONDS)
        }
    }

    private fun pickProvider(lm: LocationManager): String? {
        val order = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
        }
        return order.firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
    }

    private fun sampleLocation() {
        try {
            if (!running) { locationLoop.set(false); return }
            if (Node.sharingGroups().isEmpty() || !hasLocationPermission()) { locationLoop.set(false); ChatLog.log("LOC_STOP"); return }
            val lm = getSystemService(LocationManager::class.java)
            val provider = pickProvider(lm)
            if (provider == null) { ChatLog.log("LOC_NO_PROVIDER"); scheduler.schedule({ sampleLocation() }, 120, TimeUnit.SECONDS); return }
            val cancel = CancellationSignal()
            mainHandler.postDelayed({ cancel.cancel() }, 30_000)
            lm.getCurrentLocation(provider, cancel, io) { loc -> onFix(loc) }
        } catch (e: Exception) {
            ChatLog.err("sampleLocation", e)
            scheduler.schedule({ sampleLocation() }, 120, TimeUnit.SECONDS)
        }
    }

    private fun onFix(loc: Location?) {
        var delay = 120_000L
        try {
            if (loc == null) { ChatLog.log("LOC_NO_FIX"); return }
            val now = System.currentTimeMillis()
            val battery = try { getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { null }
            // The battery level is used only for the sending schedule here; it is not included in what is shared.
            val fix = LocationFix(loc.latitude, loc.longitude, loc.accuracy.roundToInt(), now, if (loc.hasSpeed()) loc.speed else null, null)
            val prev = lastSample
            val moved = prev != null && Geo.distanceM(prev.first, prev.second, fix.lat, fix.lon) > 30
            lastSample = fix.lat to fix.lon
            for ((gid, precision) in Node.sharingGroups()) {
                if (LocationPolicy.shouldSend(lastSent[gid], fix, now, battery)) {
                    Node.use { it.shareLocation(gid, fix, precision, now) }
                    lastSent[gid] = LocationPolicy.Sent(fix.lat, fix.lon, now)
                    ChatLog.log("LOC_SENT", "group=${gid.take(4)} precision=${precision.name} acc=${fix.accuracyM}")
                }
            }
            delay = LocationPolicy.nextSampleDelayMs(moved, battery)
        } catch (e: Exception) {
            ChatLog.err("onFix", e)
        } finally {
            if (running) scheduler.schedule({ sampleLocation() }, delay, TimeUnit.MILLISECONDS)
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
