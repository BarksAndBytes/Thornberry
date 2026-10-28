package app.thornberry.chat

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Checks the rolling test release on GitHub when the app opens, and installs a newer build when the owner taps it.
 *
 * - Everything goes through our own Tor client: GitHub sees a Tor exit, not this phone's address.
 * - Only a small version file is fetched on launch. The APK is downloaded only after the owner taps "install".
 * - The download must match the size and SHA-256 in the version file, be this app's package, be NEWER than the
 *   installed build, and be signed by the same key. Android then asks the owner to confirm the install.
 * - Trust: this trusts the GitHub repository and its build workflow. Test builds use a PUBLIC signing key, so the
 *   signature check does not prove who built it. Real releases need a private key and signed release files.
 */
object Updater {
    private const val BASE = "https://github.com/BarksAndBytes/Thornberry/releases/download/chat-latest/"
    private const val APK_NAME = "thornberry-chat-debug.apk"
    private const val MIN_GAP_MS = 10 * 60_000L

    class Info(val versionCode: Int, val versionName: String, val commit: String, val size: Long, val sha256: String)

    @Volatile var available: Info? = null
    @Volatile var status: String = "Not checked yet"
    @Volatile var busy = false
    @Volatile var launchCheckPending = true
    @Volatile private var lastCheck = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("check_on_launch", true)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("check_on_launch", v).apply() }

    /** Called when the app opens and whenever the screen refreshes; runs once per launch, as soon as Tor is up. */
    fun maybeCheckOnLaunch(ctx: Context) {
        if (!launchCheckPending || !enabled(ctx) || busy) return
        if (Node.torState != "CONNECTED" || Node.socksPort == 0) return
        launchCheckPending = false
        if (System.currentTimeMillis() - lastCheck < MIN_GAP_MS) return
        checkNow(ctx.applicationContext)
    }

    fun checkNow(ctx: Context) {
        if (busy) return
        busy = true; status = "Checking for updates…"; Node.changed()
        Thread {
            try {
                val buf = ByteArrayOutputStream()
                TorHttp.get(Node.socksPort, BASE + "version.json", 4096, buf)
                val info = parse(buf.toByteArray())
                lastCheck = System.currentTimeMillis()
                available = if (info.versionCode > BuildConfig.VERSION_CODE) info else null
                status = if (available != null) "Update available: ${info.versionName}" else "Up to date (${BuildConfig.VERSION_NAME})"
                ChatLog.log("UPDATE_CHECK", "remote=${info.versionCode} local=${BuildConfig.VERSION_CODE}")
            } catch (e: Exception) {
                status = "Could not check for updates (${e.message ?: e.javaClass.simpleName})"
                ChatLog.log("UPDATE_CHECK_FAIL", e.javaClass.simpleName)
            } finally {
                busy = false; Node.changed()
            }
        }.start()
    }

    /** Strict: unexpected fields or values mean "no update". The APK address is fixed in the app, never taken from the file. */
    fun parse(b: ByteArray): Info {
        val j = JSONObject(String(b, Charsets.UTF_8))
        val vc = j.getInt("versionCode")
        val vn = j.getString("versionName")
        val commit = j.getString("commit")
        val size = j.getLong("size")
        val sha = j.getString("sha256").lowercase()
        require(j.getString("apk") == APK_NAME) { "unexpected file name" }
        require(vc in 1..1_000_000) { "bad version" }
        require(vn.length in 1..40 && vn.all { it.isLetterOrDigit() || it in ".-_" }) { "bad version name" }
        require(Regex("[0-9a-f]{40}").matches(commit)) { "bad commit" }
        require(size in 1..150_000_000L) { "bad size" }
        require(Regex("[0-9a-f]{64}").matches(sha)) { "bad checksum" }
        return Info(vc, vn, commit, size, sha)
    }

    fun downloadAndInstall(ctx: Context) {
        val info = available ?: return
        if (busy) return
        val app = ctx.applicationContext
        busy = true; status = "Downloading update…"; Node.changed()
        Thread {
            val f = File(app.cacheDir, "update.apk")
            try {
                f.delete()
                val md = MessageDigest.getInstance("SHA-256")
                var lastPct = -1
                FileOutputStream(f).use { fo ->
                    DigestOutputStream(fo, md).use { out ->
                        TorHttp.get(Node.socksPort, BASE + APK_NAME, info.size, out) { got ->
                            val pct = (got * 100 / info.size).toInt()
                            if (pct != lastPct) { lastPct = pct; status = "Downloading update: $pct%"; Node.changed() }
                        }
                    }
                }
                if (f.length() != info.size) throw IllegalStateException("size does not match")
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != info.sha256) throw IllegalStateException("checksum does not match")
                verifyApk(app, f, info)
                status = "Waiting for you to confirm the install…"; Node.changed()
                install(app, f)
                ChatLog.log("UPDATE_INSTALL_STARTED", "code=${info.versionCode}")
            } catch (e: Exception) {
                f.delete()
                status = "Update failed (${e.message ?: e.javaClass.simpleName})"
                ChatLog.log("UPDATE_FAIL", e.javaClass.simpleName)
            } finally {
                busy = false; Node.changed()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(ctx: Context, f: File, info: Info) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val arch = pm.getPackageArchiveInfo(f.path, flags) ?: throw IllegalStateException("not an app")
        if (arch.packageName != ctx.packageName) throw IllegalStateException("different app")
        if (arch.longVersionCode <= BuildConfig.VERSION_CODE) throw IllegalStateException("not newer")
        if (arch.longVersionCode != info.versionCode.toLong()) throw IllegalStateException("version does not match")
        val mine = pm.getPackageInfo(ctx.packageName, flags).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val theirs = arch.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        // Android refuses an update signed by another key anyway; this check just fails earlier and more clearly.
        if (mine != null && theirs != null && mine != theirs) throw IllegalStateException("signed by a different key")
    }

    private fun install(ctx: Context, f: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            s.commit(pi.intentSender)
        }
    }
}

/** Receives the install result; shows Android's confirmation screen when the installer asks for it. Not exported. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (e: Exception) { Updater.status = "Could not show the install screen"; Node.changed() }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { Updater.status = "Updated"; Node.changed() }
            else -> {
                Updater.status = "Install did not finish (${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"})"
                ChatLog.log("UPDATE_INSTALL_RESULT", "status=${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)}")
                Node.changed()
            }
        }
    }
}
