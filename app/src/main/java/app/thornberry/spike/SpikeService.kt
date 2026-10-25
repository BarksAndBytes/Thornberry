package app.thornberry.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManagerFactory
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.TorWrapper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feasibility spike: keeps Tor running, publishes one onion service, and exchanges small
 * timestamped test pings with a peer onion address. No real data, no encryption beyond Tor's
 * own (the ping carries nothing sensitive). The local listener is on loopback and not
 * authenticated: acceptable for this test only.
 */
class SpikeService : Service() {

    companion object {
        const val ACTION_STOP = "app.thornberry.spike.STOP"
        const val EXTRA_BASELINE = "baseline"
        private const val CHANNEL_ID = "spike"
        private const val NOTIFICATION_ID = 1
        private const val ONION_PORT = 80
        private const val CONNECT_TIMEOUT_MS = 90_000
    }

    private val io: ExecutorService = Executors.newCachedThreadPool()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pingCounter = AtomicInteger()

    @Volatile private var wrapper: AndroidTorWrapper? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socksPort = 0
    @Volatile private var running = false
    @Volatile private var baselineMode = false
    private var screenReceiver: BroadcastReceiver? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SpikeLog.event("SERVICE_STOP", "by=user")
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("spike", MODE_PRIVATE)
        // A restart by the system delivers a null intent: keep the previous mode.
        val baseline = if (intent == null) {
            prefs.getBoolean("baseline", false)
        } else {
            intent.getBooleanExtra(EXTRA_BASELINE, false)
        }
        prefs.edit().putBoolean("baseline", baseline).apply()
        startInForeground(if (baseline) "Baseline (Tor off)" else "Starting")
        if (!started) {
            started = true
            running = true
            baselineMode = baseline
            SpikeState.serviceRunning = true
            SpikeState.mode = if (baseline) "baseline" else "tor"
            SpikeLog.event("SERVICE_START", "restart=${intent == null} mode=${SpikeState.mode}")
            registerScreenReceiver()
            io.execute { runSpike() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Tor and pings

    private fun runSpike() {
        try {
            if (baselineMode) {
                // Control run: no Tor, no network traffic; only battery and screen logging.
                SpikeState.torState = "OFF (baseline)"
                scheduler.scheduleWithFixedDelay({ sampleBattery() }, 0, 5, TimeUnit.MINUTES)
                return
            }
            val app = application
            val wakeLocks = AndroidWakeLockManagerFactory.createAndroidWakeLockManager(app)
            // Both copies of the app on one phone share loopback, so each needs its own ports.
            socksPort = freePort()
            val controlPort = freePort()
            val w = AndroidTorWrapper(
                app,
                wakeLocks,
                io,
                Executor { r -> mainHandler.post(r) },
                Build.SUPPORTED_ABIS[0],
                File(filesDir, "tor"),
                socksPort,
                controlPort,
            )
            wrapper = w
            w.setObserver(object : TorWrapper.Observer {
                override fun onState(s: TorWrapper.TorState) {
                    SpikeState.torState = s.name
                    SpikeLog.event("TOR_STATE", "state=${s.name}")
                    updateNotification("Tor ${s.name}")
                }

                override fun onBootstrapPercentage(percentage: Int) {
                    SpikeState.bootstrap = percentage
                    SpikeLog.event("TOR_BOOTSTRAP", "percent=$percentage")
                }

                override fun onHsDescriptorUpload(onion: String) {
                    SpikeLog.event("HS_UPLOAD", "onion=${onion.take(8)}")
                }

                override fun onClockSkewDetected(skewSeconds: Long) {
                    SpikeLog.event("CLOCK_SKEW", "seconds=$skewSeconds")
                }
            })

            SpikeLog.event("TOR_START", "socks=$socksPort")
            w.start()
            w.enableNetwork(true)

            val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = srv
            val prefs = getSharedPreferences("spike", MODE_PRIVATE)
            // Test-only key stored in app-private prefs so the onion address stays the same.
            val props = w.publishHiddenService(srv.localPort, ONION_PORT, prefs.getString("hsKey", null))
            prefs.edit().putString("hsKey", props.privKey).apply()
            val onion = if (props.onion.endsWith(".onion")) props.onion else props.onion + ".onion"
            SpikeState.onion = onion
            SpikeLog.event("HS_PUBLISHED", "onion=${onion.take(8)}")
            updateNotification("Onion published")

            io.execute { acceptLoop(srv) }
            scheduleNextPing(initialDelaySeconds = 60)
            scheduler.scheduleWithFixedDelay({ sampleBattery() }, 0, 5, TimeUnit.MINUTES)
        } catch (e: Throwable) {
            SpikeLog.event("ERROR", "where=runSpike err=${e.javaClass.simpleName}:${e.message}")
            updateNotification("Error: ${e.javaClass.simpleName}")
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun acceptLoop(srv: ServerSocket) {
        while (running && !srv.isClosed) {
            try {
                val c = srv.accept()
                io.execute { handleIncoming(c) }
            } catch (e: Exception) {
                if (running) SpikeLog.event("ERROR", "where=accept err=${e.javaClass.simpleName}")
            }
        }
    }

    private fun handleIncoming(c: Socket) {
        c.use {
            try {
                it.soTimeout = 30_000
                val line = BufferedReader(InputStreamReader(it.getInputStream())).readLine() ?: return
                val recv = System.currentTimeMillis()
                val p = line.split(' ')
                if (p.size >= 3 && p[0] == "PING") {
                    val sent = p[2].toLongOrNull() ?: recv
                    it.getOutputStream().write("PONG ${p[1]} $sent $recv\n".toByteArray())
                    it.getOutputStream().flush()
                    SpikeLog.event("PING_IN", "id=${p[1]} oneWayMs=${recv - sent}")
                    SpikeState.lastPing = "incoming #${p[1]}"
                }
            } catch (e: Exception) {
                SpikeLog.event("ERROR", "where=incoming err=${e.javaClass.simpleName}")
            }
        }
    }

    private fun scheduleNextPing(initialDelaySeconds: Long? = null) {
        if (!running) return
        val delay = initialDelaySeconds ?: (intervalMinutes() * 60L)
        scheduler.schedule({
            try {
                pingOnce()
            } finally {
                scheduleNextPing()
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun intervalMinutes(): Int =
        getSharedPreferences("spike", MODE_PRIVATE).getInt("intervalMin", 2).coerceIn(1, 60)

    private fun pingOnce() {
        val prefs = getSharedPreferences("spike", MODE_PRIVATE)
        var peer = prefs.getString("peer", "")?.trim().orEmpty()
        if (peer.isEmpty()) {
            SpikeLog.event("PING_SKIP", "reason=no_peer")
            return
        }
        if (!peer.endsWith(".onion")) peer += ".onion"
        if (SpikeState.torState != "CONNECTED") {
            SpikeLog.event("PING_SKIP", "reason=tor_${SpikeState.torState}")
            return
        }
        val id = pingCounter.incrementAndGet()
        val t0 = System.currentTimeMillis()
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            Socket(proxy).use { s ->
                s.soTimeout = CONNECT_TIMEOUT_MS
                s.connect(InetSocketAddress.createUnresolved(peer, ONION_PORT), CONNECT_TIMEOUT_MS)
                s.getOutputStream().write("PING $id $t0\n".toByteArray())
                s.getOutputStream().flush()
                val reply = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                val rtt = System.currentTimeMillis() - t0
                if (reply != null && reply.startsWith("PONG")) {
                    SpikeLog.event("PING_OUT", "ok=1 id=$id rttMs=$rtt")
                    SpikeState.lastPing = "out #$id ok ${rtt}ms"
                } else {
                    SpikeLog.event("PING_OUT", "ok=0 id=$id err=bad_reply elapsedMs=$rtt")
                    SpikeState.lastPing = "out #$id bad reply"
                }
            }
        } catch (e: Exception) {
            val el = System.currentTimeMillis() - t0
            SpikeLog.event("PING_OUT", "ok=0 id=$id err=${e.javaClass.simpleName} elapsedMs=$el")
            SpikeState.lastPing = "out #$id failed (${e.javaClass.simpleName})"
        }
    }

    private fun sampleBattery() {
        try {
            val bm = getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // Remaining charge in microamp-hours: finer than whole percent points.
            val uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val pm = getSystemService(PowerManager::class.java)
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                else -> "other"
            }
            SpikeLog.event(
                "BATTERY",
                "level=$level uah=$uah plugged=$plugged doze=${pm.isDeviceIdleMode} " +
                    "ignoreOpt=${pm.isIgnoringBatteryOptimizations(packageName)} " +
                    "screen=${pm.isInteractive} mode=${SpikeState.mode} net=$net",
            )
        } catch (e: Exception) {
            SpikeLog.event("ERROR", "where=battery err=${e.javaClass.simpleName}")
        }
    }

    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val on = intent?.action == Intent.ACTION_SCREEN_ON
                SpikeLog.event("SCREEN", "state=${if (on) "on" else "off"}")
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(r, f)
        }
        screenReceiver = r
        val pm = getSystemService(PowerManager::class.java)
        SpikeLog.event("SCREEN", "state=${if (pm.isInteractive) "on" else "off"}")
    }

    private fun shutdown() {
        running = false
        SpikeState.serviceRunning = false
        SpikeState.torState = "STOPPED"
        SpikeState.onion = ""
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        screenReceiver = null
        try {
            server?.close()
        } catch (_: Exception) {
        }
        scheduler.shutdownNow()
        val w = wrapper
        if (w != null) {
            Thread {
                try {
                    w.stop()
                } catch (e: Exception) {
                    SpikeLog.event("ERROR", "where=tor_stop err=${e.javaClass.simpleName}")
                }
            }.start()
        }
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Thornberry spike (test)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Spike status", NotificationManager.IMPORTANCE_LOW),
        )
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }
}
