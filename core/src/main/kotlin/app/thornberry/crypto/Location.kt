package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** One position fix from the phone (before any privacy reduction). */
class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    val timeMs: Long,
    val speedMps: Float? = null,
    val batteryPct: Int? = null,
)

/** How precisely a person chooses to share. The reduction happens on the sender's phone, before encryption. */
enum class Precision(val gridDegrees: Double, val minAccuracyM: Int, val label: String) {
    EXACT(0.0, 0, "Exact"),
    BLOCK(0.001, 150, "About 100 m"),
    AREA(0.01, 1500, "About 1 km"),
    CITY(0.1, 15000, "City level"),
}

/** The decoded content of a location record. Coordinates are in micro-degrees. */
class LocationPoint(
    val latMicro: Int,
    val lonMicro: Int,
    val accuracyM: Int,
    /** Sender's clock, signed inside the record. */
    val timestamp: Long,
    val speedCmS: Int?,
    val batteryPct: Int?,
) {
    val lat: Double get() = latMicro / 1e6
    val lon: Double get() = lonMicro / 1e6
}

/** Wire format of a location record body (record kind 2). Strict on decode. */
object LocationPayload {
    const val KIND_LOCATION = 2
    const val KIND_SHARING_STATE = 3
    /** A batch of recent positions (the route so far), so history survives gaps in delivery. */
    const val KIND_TRACK = 4
    const val TRACK_MAX_POINTS = 200
    private const val TRACK_VERSION = 1
    private const val VERSION = 1

    fun quantize(value: Double, grid: Double): Double =
        if (grid <= 0.0) value else (value / grid).roundToLong() * grid

    fun encode(fix: LocationFix, precision: Precision): ByteArray {
        require(fix.lat in -90.0..90.0 && fix.lon in -180.0..180.0) { "bad coordinates" }
        val lat = quantize(fix.lat, precision.gridDegrees).coerceIn(-90.0, 90.0)
        val lon = quantize(fix.lon, precision.gridDegrees).coerceIn(-180.0, 180.0)
        val acc = max(fix.accuracyM, precision.minAccuracyM).coerceIn(0, 65535)
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        o.writeByte(VERSION)
        o.writeInt((lat * 1e6).roundToInt()); o.writeInt((lon * 1e6).roundToInt())
        o.writeShort(acc)
        val hasSpeed = fix.speedMps != null
        val hasBattery = fix.batteryPct != null
        o.writeByte((if (hasSpeed) 1 else 0) or (if (hasBattery) 2 else 0))
        if (hasSpeed) o.writeShort((fix.speedMps!! * 100).roundToInt().coerceIn(0, 65535))
        if (hasBattery) o.writeByte(fix.batteryPct!!.coerceIn(0, 100))
        return b.toByteArray()
    }

    /**
     * Encode up to [TRACK_MAX_POINTS] fixes (oldest first, strictly increasing times) at the chosen precision.
     * Layout: version, count (u16), first time (i64 ms), then per point: lat i32, lon i32, accuracy u16,
     * and for every point after the first the time since the previous one (u32 ms).
     */
    fun encodeTrack(fixes: List<LocationFix>, precision: Precision): ByteArray {
        require(fixes.size in 1..TRACK_MAX_POINTS) { "bad track size" }
        for (i in 1 until fixes.size) require(fixes[i].timeMs > fixes[i - 1].timeMs) { "times must increase" }
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        o.writeByte(TRACK_VERSION); o.writeShort(fixes.size); o.writeLong(fixes[0].timeMs)
        for ((i, f) in fixes.withIndex()) {
            require(f.lat in -90.0..90.0 && f.lon in -180.0..180.0) { "bad coordinates" }
            val lat = quantize(f.lat, precision.gridDegrees).coerceIn(-90.0, 90.0)
            val lon = quantize(f.lon, precision.gridDegrees).coerceIn(-180.0, 180.0)
            o.writeInt((lat * 1e6).roundToInt()); o.writeInt((lon * 1e6).roundToInt())
            o.writeShort(max(f.accuracyM, precision.minAccuracyM).coerceIn(0, 65535))
            if (i > 0) {
                val dt = f.timeMs - fixes[i - 1].timeMs
                require(dt <= 0xFFFF_FFFFL) { "gap too large" }
                o.writeInt(dt.toInt())
            }
        }
        return b.toByteArray()
    }

    /** Strict decode of a track body. Point times come from the payload (the whole record is signed by the sender). */
    fun decodeTrack(body: ByteArray): List<LocationPoint> {
        try {
            val i = DataInputStream(ByteArrayInputStream(body))
            if (i.readUnsignedByte() != TRACK_VERSION) throw CryptoException("bad track version")
            val n = i.readUnsignedShort()
            if (n !in 1..TRACK_MAX_POINTS) throw CryptoException("bad track size")
            var t = i.readLong()
            val out = ArrayList<LocationPoint>(n)
            for (k in 0 until n) {
                val lat = i.readInt(); val lon = i.readInt(); val acc = i.readUnsignedShort()
                if (k > 0) {
                    val dt = i.readInt().toLong() and 0xFFFF_FFFFL
                    if (dt == 0L) throw CryptoException("times must increase")
                    t += dt
                }
                if (lat !in -90_000_000..90_000_000 || lon !in -180_000_000..180_000_000) throw CryptoException("out of range")
                out.add(LocationPoint(lat, lon, acc, t, null, null))
            }
            if (i.available() != 0) throw CryptoException("trailing bytes")
            return out
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed track")
        }
    }

    /** [timestamp] comes from the verified record, not from the payload. */
    fun decode(body: ByteArray, timestamp: Long): LocationPoint {
        try {
            val i = DataInputStream(ByteArrayInputStream(body))
            if (i.readUnsignedByte() != VERSION) throw CryptoException("bad location version")
            val lat = i.readInt(); val lon = i.readInt()
            val acc = i.readUnsignedShort()
            val flags = i.readUnsignedByte()
            if (flags and 3.inv() != 0) throw CryptoException("unknown flags")
            val speed = if (flags and 1 != 0) i.readUnsignedShort() else null
            val battery = if (flags and 2 != 0) i.readUnsignedByte() else null
            if (i.available() != 0) throw CryptoException("trailing bytes")
            if (lat !in -90_000_000..90_000_000 || lon !in -180_000_000..180_000_000) throw CryptoException("out of range")
            if (battery != null && battery > 100) throw CryptoException("bad battery")
            return LocationPoint(lat, lon, acc, timestamp, speed, battery)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed location")
        }
    }
}

object Geo {
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = (lat2 - lat1) * PI / 180; val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/**
 * When to send an update (design: docs/architecture.md, update policy). Pure function of what was
 * last sent and the new fix, so it can be tested without a phone. The 10-minute maximum age
 * applies whenever the phone has any fix at all.
 */
object LocationPolicy {
    const val MOVE_THRESHOLD_M = 75.0
    const val MOVING_INTERVAL_MS = 3 * 60_000L
    const val MOVING_INTERVAL_LOW_BATTERY_MS = 5 * 60_000L
    const val HEARTBEAT_MS = 10 * 60_000L
    const val POOR_FIX_M = 500
    const val LOW_BATTERY_PCT = 15

    class Sent(val lat: Double, val lon: Double, val atMs: Long)

    fun shouldSend(last: Sent?, fix: LocationFix, now: Long, batteryPct: Int?): Boolean {
        if (last == null) return true
        val age = now - last.atMs
        if (age >= HEARTBEAT_MS) return true
        if (fix.accuracyM > POOR_FIX_M) return false // a poor fix is not worth an early update
        val moved = Geo.distanceM(last.lat, last.lon, fix.lat, fix.lon) > MOVE_THRESHOLD_M
        val low = batteryPct != null && batteryPct <= LOW_BATTERY_PCT
        return moved && age >= (if (low) MOVING_INTERVAL_LOW_BATTERY_MS else MOVING_INTERVAL_MS)
    }

    /** How long to wait before taking the next fix (always comfortably under the heartbeat). */
    fun nextSampleDelayMs(recentlyMoved: Boolean, batteryPct: Int?): Long {
        val low = batteryPct != null && batteryPct <= LOW_BATTERY_PCT
        // While moving, sample often so the route has enough points; the batch is sent every few minutes.
        return if (recentlyMoved) (if (low) 60_000L else 30_000L) else 180_000L
    }
}

/** Latest position and a short trail per member per group, with expiry. */
class LocationBoard {
    class Entry(val sender: String, val latest: LocationPoint?, val paused: Boolean, val pausedAt: Long, val trail: List<LocationPoint>)

    private class Member(var latest: LocationPoint? = null, var paused: Boolean = false, var pausedAt: Long = 0, val trail: ArrayList<LocationPoint> = ArrayList())

    private val members = LinkedHashMap<String, Member>() // key = groupId + ":" + sender
    companion object {
        const val TRAIL_MAX_MS = 24L * 3600 * 1000
        const val TRAIL_MAX_POINTS = 1500
    }

    private fun m(group: String, sender: String) = members.getOrPut("$group:$sender") { Member() }

    /** Last-writer-wins by the sender's timestamp. Returns true if this became the newest position. */
    fun update(group: String, sender: String, p: LocationPoint): Boolean {
        val mem = m(group, sender)
        val cur = mem.latest
        if (cur != null && p.timestamp <= cur.timestamp) {
            if (mem.trail.none { it.timestamp == p.timestamp }) { mem.trail.add(p); mem.trail.sortBy { it.timestamp } }
            trim(mem, p.timestamp)
            return false
        }
        mem.latest = p
        mem.trail.add(p)
        trim(mem, p.timestamp)
        return true
    }

    fun setPaused(group: String, sender: String, paused: Boolean, at: Long): Boolean {
        val mem = m(group, sender)
        if (at <= mem.pausedAt) return false
        mem.paused = paused; mem.pausedAt = at
        return true
    }

    private fun trim(mem: Member, newest: Long) {
        mem.trail.removeAll { it.timestamp < newest - TRAIL_MAX_MS }
        while (mem.trail.size > TRAIL_MAX_POINTS) mem.trail.removeAt(0)
    }

    fun purge(now: Long) {
        for (mem in members.values) {
            mem.trail.removeAll { it.timestamp < now - TRAIL_MAX_MS }
            val l = mem.latest
            if (l != null && l.timestamp < now - TRAIL_MAX_MS) mem.latest = null
        }
        members.entries.removeAll { it.value.latest == null && it.value.trail.isEmpty() && !it.value.paused }
    }

    fun forGroup(group: String): List<Entry> =
        members.filterKeys { it.startsWith("$group:") }.map { (k, v) ->
            Entry(k.substringAfter(':'), v.latest, v.paused, v.pausedAt, v.trail.toList())
        }

    fun dropGroup(group: String) { members.keys.removeAll { it.startsWith("$group:") } }

    fun serialize(): ByteArray {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        o.writeByte(1); o.writeInt(members.size)
        for ((k, v) in members) {
            o.writeUTF(k); o.writeBoolean(v.paused); o.writeLong(v.pausedAt)
            o.writeInt(v.trail.size)
            for (p in v.trail) writePoint(o, p)
            o.writeBoolean(v.latest != null); v.latest?.let { writePoint(o, it) }
        }
        return b.toByteArray()
    }

    private fun writePoint(o: DataOutputStream, p: LocationPoint) {
        o.writeInt(p.latMicro); o.writeInt(p.lonMicro); o.writeInt(p.accuracyM); o.writeLong(p.timestamp)
        o.writeInt(p.speedCmS ?: -1); o.writeInt(p.batteryPct ?: -1)
    }

    private fun readPoint(i: DataInputStream): LocationPoint {
        val lat = i.readInt(); val lon = i.readInt(); val acc = i.readInt(); val ts = i.readLong()
        val sp = i.readInt(); val bat = i.readInt()
        return LocationPoint(lat, lon, acc, ts, if (sp < 0) null else sp, if (bat < 0) null else bat)
    }

    fun restoreFrom(data: ByteArray) {
        try {
            val i = DataInputStream(ByteArrayInputStream(data))
            require(i.readUnsignedByte() == 1)
            repeat(i.readInt().also { require(it in 0..100_000) }) {
                val key = i.readUTF(); val mem = Member()
                mem.paused = i.readBoolean(); mem.pausedAt = i.readLong()
                repeat(i.readInt().also { require(it in 0..TRAIL_MAX_POINTS) }) { mem.trail.add(readPoint(i)) }
                if (i.readBoolean()) mem.latest = readPoint(i)
                members[key] = mem
            }
            require(i.available() == 0)
        } catch (e: Exception) {
            throw CryptoException("malformed location data")
        }
    }
}
