package app.thornberry.spike

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** In-process state shared between the service and the screen. */
object SpikeState {
    @Volatile var torState: String = "NOT_STARTED"
    @Volatile var bootstrap: Int = 0
    @Volatile var onion: String = ""
    @Volatile var serviceRunning: Boolean = false
    @Volatile var mode: String = "tor"
    @Volatile var lastPing: String = "none yet"
}

/**
 * Append-only event log (CSV-ish: epochMs,TYPE,key=value ...) plus a summary generator.
 * Test data only: no location, no messages, no personal data.
 */
object SpikeLog {
    private val lock = Any()
    private var file: File? = null
    private val recent = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        synchronized(lock) {
            if (file == null) file = File(ctx.filesDir, "spike-events.csv")
        }
    }

    fun event(type: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val clean = detail.replace(',', ';').replace('\n', ' ')
        val line = "$now,$type,$clean"
        synchronized(lock) {
            try {
                val f = file
                if (f != null) {
                    if (f.length() > 5_000_000) f.renameTo(File(f.parentFile, "spike-events.old.csv"))
                    f.appendText(line + "\n")
                }
            } catch (_: Exception) {
            }
            recent.addLast("${timeFmt.format(Date(now))} $type $clean")
            while (recent.size > 200) recent.removeFirst()
        }
    }

    fun recentLines(n: Int): List<String> = synchronized(lock) { recent.toList().takeLast(n) }

    /**
     * Summary plus the COMPLETE event log (including the rotated older file, if any), with readable
     * timestamps, as one text. Used by the "Save summary and full log" button.
     */
    fun fullReport(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val files = synchronized(lock) {
            val f = file
            if (f == null) emptyList() else listOf(File(f.parentFile, "spike-events.old.csv"), f)
        }
        val sb = StringBuilder()
        sb.append(summary()).append("\n\n--- full event log (local time, type, details) ---\n")
        var lines = 0
        for (f in files) {
            if (!f.exists()) continue
            f.forEachLine { line ->
                val parts = line.split(',', limit = 3)
                val t = parts.getOrNull(0)?.toLongOrNull()
                if (t != null && parts.size >= 2) {
                    sb.append(fmt.format(Date(t))).append(' ').append(parts[1])
                    if (parts.size > 2 && parts[2].isNotEmpty()) sb.append(' ').append(parts[2])
                    sb.append('\n')
                    lines++
                }
            }
        }
        sb.append("--- end of log (").append(lines).append(" events) ---\n")
        return sb.toString()
    }

    fun clear() {
        synchronized(lock) {
            try {
                file?.delete()
            } catch (_: Exception) {
            }
            recent.clear()
        }
    }

    private class Ev(val t: Long, val type: String, val kv: Map<String, String>)

    private fun readEvents(): List<Ev> {
        val f = synchronized(lock) { file } ?: return emptyList()
        if (!f.exists()) return emptyList()
        val kvRegex = Regex("(\\w+)=(\\S*)")
        val out = ArrayList<Ev>()
        for (line in f.readLines()) {
            val parts = line.split(',', limit = 3)
            if (parts.size < 2) continue
            val t = parts[0].toLongOrNull() ?: continue
            val detail = if (parts.size > 2) parts[2] else ""
            out.add(Ev(t, parts[1], kvRegex.findAll(detail).associate { it.groupValues[1] to it.groupValues[2] }))
        }
        return out
    }

    private fun pct(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = ceil(p * sorted.size).toInt() - 1
        return sorted[idx.coerceIn(0, sorted.size - 1)]
    }

    private fun stats(values: List<Long>): String {
        if (values.isEmpty()) return "no data"
        val s = values.sorted()
        return "median ${pct(s, 0.5)} / p95 ${pct(s, 0.95)} / max ${s.last()}"
    }

    /** Longest gap between consecutive timestamps, and how many gaps exceed 10 minutes. */
    private fun gaps(times: List<Long>): String {
        if (times.size < 2) return "not enough data"
        val s = times.sorted()
        var max = 0L
        var over = 0
        for (i in 1 until s.size) {
            val g = s[i] - s[i - 1]
            if (g > max) max = g
            if (g > 10 * 60_000L) over++
        }
        val longestMin = String.format(Locale.US, "%.1f", max / 60_000.0)
        return "longest $longestMin min, gaps over 10 min: $over"
    }

    fun summary(): String {
        val ev = readEvents()
        if (ev.isEmpty()) return "No events recorded yet."
        val first = ev.first().t
        val last = ev.last().t
        val mins = (last - first) / 60_000
        val sb = StringBuilder()
        sb.append("Thornberry spike summary\n")
        sb.append("Window: ${fullFmt.format(Date(first))} to ${fullFmt.format(Date(last))} ($mins min)\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")

        val starts = ev.filter { it.type == "SERVICE_START" }
        val restarts = starts.count { it.kv["restart"] == "true" }
        sb.append("Service starts: ${starts.size} (restarts after the system killed it: $restarts)\n")

        val torStates = ev.filter { it.type == "TOR_STATE" }.mapNotNull { it.kv["state"] }
        val connectedIdx = torStates.indexOf("CONNECTED")
        val reconnects = if (connectedIdx >= 0) torStates.drop(connectedIdx + 1).count { it == "CONNECTING" } else 0
        sb.append("Tor: connected ${torStates.count { it == "CONNECTED" }} times, reconnect cycles after first connect: $reconnects, ")
        sb.append("onion descriptor uploads: ${ev.count { it.type == "HS_UPLOAD" }}\n")

        val outs = ev.filter { it.type == "PING_OUT" }
        val outOk = outs.filter { it.kv["ok"] == "1" }
        sb.append("Outgoing pings: ${outOk.size} ok, ${outs.size - outOk.size} failed")
        if (outs.isNotEmpty()) sb.append(" (${100 * outOk.size / outs.size}% ok)")
        sb.append("\n  round trip ms: ${stats(outOk.mapNotNull { it.kv["rttMs"]?.toLongOrNull() })}\n")
        sb.append("  gaps between successes: ${gaps(outOk.map { it.t })}\n")
        val fails = outs.filter { it.kv["ok"] != "1" }.groupingBy { it.kv["err"] ?: "?" }.eachCount()
        if (fails.isNotEmpty()) sb.append("  failure reasons: $fails\n")

        val ins = ev.filter { it.type == "PING_IN" }
        sb.append("Incoming pings: ${ins.size}\n")
        sb.append("  one-way ms (valid only when both copies run on this one phone): ${stats(ins.mapNotNull { it.kv["oneWayMs"]?.toLongOrNull() })}\n")
        sb.append("  gaps between arrivals: ${gaps(ins.map { it.t })}\n")

        val bat = ev.filter { it.type == "BATTERY" }
        if (bat.isNotEmpty()) {
            val b0 = bat.first()
            val b1 = bat.last()
            sb.append("Battery: ${b0.kv["level"]}% at ${timeFmt.format(Date(b0.t))} -> ${b1.kv["level"]}% at ${timeFmt.format(Date(b1.t))}; ")
            sb.append("samples ${bat.size}, samples while plugged in: ${bat.count { (it.kv["plugged"] ?: "0") != "0" }}, ")
            sb.append("samples in Doze: ${bat.count { it.kv["doze"] == "true" }}, ")
            sb.append("battery-optimization exempt: ${b1.kv["ignoreOpt"]}\n")

            val nets = bat.groupingBy { it.kv["net"] ?: "?" }.eachCount()
            sb.append("Network at samples: $nets\n")
            sb.append(drainBreakdown(ev, bat))
        }
        return sb.toString()
    }

    /**
     * Drain split by mode (tor or baseline) and screen state, using only intervals between two
     * consecutive battery samples where the phone was unplugged, the mode did not change and the
     * screen did not change state. The charge counter (mAh) is finer than the percent level.
     */
    private fun drainBreakdown(ev: List<Ev>, bat: List<Ev>): String {
        val screenEv = ev.filter { it.type == "SCREEN" }
        fun screenOnAt(t: Long, fallback: Boolean): Boolean {
            val e = screenEv.lastOrNull { it.t <= t }
            return if (e != null) e.kv["state"] == "on" else fallback
        }
        val minutes = HashMap<String, Double>()
        val points = HashMap<String, Int>()
        val microAh = HashMap<String, Long>()
        val microAhMinutes = HashMap<String, Double>()
        for (i in 1 until bat.size) {
            val a = bat[i - 1]
            val b = bat[i]
            if ((a.kv["plugged"] ?: "0") != "0" || (b.kv["plugged"] ?: "0") != "0") continue
            val mode = a.kv["mode"] ?: "tor"
            if (mode != (b.kv["mode"] ?: "tor")) continue
            if (screenEv.any { it.t > a.t && it.t <= b.t }) continue
            val on = screenOnAt(a.t, a.kv["screen"] == "true")
            val key = mode + "/" + (if (on) "screen-on" else "screen-off")
            val la = a.kv["level"]?.toIntOrNull() ?: continue
            val lb = b.kv["level"]?.toIntOrNull() ?: continue
            if (lb > la) continue
            val mins = (b.t - a.t) / 60_000.0
            minutes[key] = (minutes[key] ?: 0.0) + mins
            points[key] = (points[key] ?: 0) + (la - lb)
            val ua = a.kv["uah"]?.toLongOrNull()
            val ub = b.kv["uah"]?.toLongOrNull()
            if (ua != null && ub != null && ua > 0 && ub > 0 && ua >= ub) {
                microAh[key] = (microAh[key] ?: 0L) + (ua - ub)
                microAhMinutes[key] = (microAhMinutes[key] ?: 0.0) + mins
            }
        }
        if (minutes.isEmpty()) return "Drain breakdown: not enough unplugged intervals without a screen change yet.\n"
        val sb = StringBuilder("Drain breakdown (unplugged, no screen change inside the interval):\n")
        for (key in minutes.keys.sorted()) {
            val m = minutes[key] ?: 0.0
            if (m <= 0.0) continue
            val pts = points[key] ?: 0
            val pph = String.format(Locale.US, "%.2f", pts / m * 60.0)
            sb.append("  $key: ${m.toInt()} min, $pts battery points ($pph points/h)")
            val uah = microAh[key]
            val uahMin = microAhMinutes[key]
            if (uah != null && uahMin != null && uahMin > 0.0) {
                val mah = uah / 1000.0
                val mahPerHour = String.format(Locale.US, "%.1f", mah / uahMin * 60.0)
                sb.append("; ${String.format(Locale.US, "%.0f", mah)} mAh ($mahPerHour mAh/h)")
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
