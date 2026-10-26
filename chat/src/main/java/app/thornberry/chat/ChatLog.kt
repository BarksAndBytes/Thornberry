package app.thornberry.chat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain diagnostic log for the test build. Never contains message text, names, keys or full
 * addresses: only event kinds, counts, error class names and timing. Stored unencrypted in the
 * app's private folder and leaves the phone only when the owner saves it to a file.
 */
object ChatLog {
    private val lock = Any()
    private var file: File? = null
    private const val MAX_BYTES = 1_000_000L

    fun init(ctx: Context) {
        synchronized(lock) {
            if (file != null) return
            file = File(ctx.filesDir, "chat-log.txt")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("CRASH", "thread=${t.name} ${e.javaClass.name}: ${e.message}\n" + e.stackTrace.take(25).joinToString("\n") { "    at $it" })
            previous?.uncaughtException(t, e)
        }
    }

    fun log(kind: String, detail: String = "") {
        synchronized(lock) {
            try {
                val f = file ?: return
                if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "chat-log.old.txt"))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                f.appendText("$ts $kind $detail\n")
            } catch (_: Exception) {
            }
        }
    }

    fun err(where: String, e: Throwable) = log("ERROR", "where=$where ${e.javaClass.simpleName}: ${e.message?.take(120)}")

    fun fullText(): String = synchronized(lock) {
        val f = file ?: return "(no log)"
        val old = File(f.parentFile, "chat-log.old.txt")
        buildString {
            append("Thornberry chat diagnostic log (no message text, no keys)\n")
            append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n")
            if (old.exists()) append(old.readText())
            if (f.exists()) append(f.readText())
        }
    }

    fun clear() { synchronized(lock) { file?.delete(); file?.parentFile?.let { File(it, "chat-log.old.txt").delete() } } }
}
