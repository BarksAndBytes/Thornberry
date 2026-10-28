package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A named circle on the map (home, school). Places exist only on the phone that made them: they are never sent to
 * anyone. [watch] is the set of people (identity hex) to alert about; null means everyone.
 */
class Place(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Int, val watch: Set<String>?)

/** Someone arrived at or left a place. [notify] is false for old events (a backfilled route) that are only logged. */
class GeoEvent(val placeId: String, val placeName: String, val person: String, val arrived: Boolean, val atMs: Long, val notify: Boolean)

/**
 * Works out "arrived" and "left" from the positions people share with us. Everything runs on the receiving phone;
 * no server and no one else learns which places we watch.
 *
 * Rules (so that GPS noise does not cause false alerts):
 * - A position is INSIDE only if it is inside the circle even after allowing half its stated accuracy, and OUTSIDE only
 *   if it is outside by the same margin. Anything in between (and anything from someone sharing at a coarse level, whose
 *   stated accuracy is wide) is ignored: no alert rather than a wrong one.
 * - The first confident position only sets the starting state (no "arrived" for someone already there).
 * - A change needs [CONFIRM_POINTS] confident positions in a row on the other side.
 * - Each position is processed once, in time order; a late-arriving older batch never replays history.
 * - Events older than [NOTIFY_WINDOW_MS] are logged but do not notify.
 */
class PlaceBook {
    companion object {
        const val MAX_PLACES = 50
        const val MAX_EVENTS = 100
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 5000
        const val MAX_NAME = 60
        const val CONFIRM_POINTS = 2
        const val NOTIFY_WINDOW_MS = 30L * 60_000
        private const val VERSION = 1

        fun restore(data: ByteArray): PlaceBook {
            val b = PlaceBook()
            try {
                val i = DataInputStream(ByteArrayInputStream(data))
                if (i.readUnsignedByte() != VERSION) throw CryptoException("bad places version")
                repeat(i.readInt().also { if (it !in 0..MAX_PLACES) throw CryptoException("bad places") }) {
                    val id = i.readUTF(); val name = i.readUTF()
                    val lat = i.readDouble(); val lon = i.readDouble(); val r = i.readInt()
                    val watch = if (i.readBoolean()) {
                        val n = i.readInt(); if (n !in 0..1000) throw CryptoException("bad watch list")
                        (0 until n).map { i.readUTF() }.toSet()
                    } else null
                    b.validate(name, lat, lon, r)
                    b.places[id] = Place(id, name, lat, lon, r, watch)
                }
                repeat(i.readInt().also { if (it !in 0..100_000) throw CryptoException("bad states") }) {
                    val key = i.readUTF(); val inside = i.readBoolean(); val pt = i.readUnsignedByte()
                    val pc = i.readInt(); val pf = i.readLong()
                    if (pt > 2) throw CryptoException("bad state")
                    b.states[key] = St(inside, pt, pc, pf)
                }
                repeat(i.readInt().also { if (it !in 0..100_000) throw CryptoException("bad times") }) { b.lastTs[i.readUTF()] = i.readLong() }
                repeat(i.readInt().also { if (it !in 0..MAX_EVENTS) throw CryptoException("bad events") }) {
                    b.log.addLast(GeoEvent(i.readUTF(), i.readUTF(), i.readUTF(), i.readBoolean(), i.readLong(), i.readBoolean()))
                }
                if (i.available() != 0) throw CryptoException("trailing bytes")
            } catch (e: CryptoException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException("malformed places")
            }
            return b
        }
    }

    private class St(var inside: Boolean, var pendingTo: Int, var pendingCount: Int, var pendingFirstTs: Long)

    private val places = LinkedHashMap<String, Place>()
    private val states = HashMap<String, St>() // placeId|person
    private val lastTs = HashMap<String, Long>() // person -> newest position already processed
    private val log = ArrayDeque<GeoEvent>()

    fun list(): List<Place> = places.values.toList()
    fun get(id: String): Place? = places[id]
    fun hasPlaces() = places.isNotEmpty()
    /** Newest first. */
    fun events(): List<GeoEvent> = log.reversed()

    private fun validate(name: String, lat: Double, lon: Double, radius: Int) {
        if (name.isBlank() || name.length > MAX_NAME) throw CryptoException("bad place name")
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || lat.isNaN() || lon.isNaN()) throw CryptoException("bad place position")
        if (radius !in MIN_RADIUS_M..MAX_RADIUS_M) throw CryptoException("bad place radius")
    }

    fun add(name: String, lat: Double, lon: Double, radiusM: Int, watch: Set<String>? = null): Place {
        if (places.size >= MAX_PLACES) throw CryptoException("too many places")
        val n = name.trim()
        validate(n, lat, lon, radiusM)
        val p = Place(Crypto.randomBytes(8).joinToString("") { "%02x".format(it) }, n, lat, lon, radiusM, watch)
        places[p.id] = p
        return p
    }

    /** Change a place's name, size or watch list. Changing size resets what we believe about who is inside. */
    fun update(id: String, name: String, radiusM: Int, watch: Set<String>?) {
        val old = places[id] ?: throw CryptoException("unknown place")
        val n = name.trim()
        validate(n, old.lat, old.lon, radiusM)
        places[id] = Place(id, n, old.lat, old.lon, radiusM, watch)
        if (radiusM != old.radiusM) states.keys.removeAll { it.startsWith("$id|") }
    }

    fun remove(id: String) {
        places.remove(id)
        states.keys.removeAll { it.startsWith("$id|") }
    }

    /** Forget a person (for example a removed contact). */
    fun forgetPerson(person: String) {
        lastTs.remove(person)
        states.keys.removeAll { it.endsWith("|$person") }
    }

    private fun classify(pl: Place, p: LocationPoint): Int {
        val d = Geo.distanceM(pl.lat, pl.lon, p.lat, p.lon)
        val margin = p.accuracyM / 2.0
        return when {
            d + margin <= pl.radiusM -> 1
            d - margin > pl.radiusM -> 2
            else -> 0
        }
    }

    /** Process a person's positions (any order, may repeat). Returns the arrivals and departures found. */
    fun feed(person: String, points: List<LocationPoint>, now: Long): List<GeoEvent> {
        val sorted = points.sortedBy { it.timestamp }
        val last = lastTs[person]
        val todo = if (last == null) sorted.takeLast(1) else sorted.filter { it.timestamp > last }
        if (todo.isEmpty()) return emptyList()
        lastTs[person] = todo.last().timestamp
        val out = ArrayList<GeoEvent>()
        for (pl in places.values) {
            if (pl.watch != null && person !in pl.watch) continue
            val key = "${pl.id}|$person"
            for (p in todo) {
                val c = classify(pl, p)
                if (c == 0) continue
                val st = states[key]
                if (st == null) { states[key] = St(c == 1, 0, 0, 0); continue }
                if ((c == 1) == st.inside) { st.pendingTo = 0; st.pendingCount = 0; continue }
                if (st.pendingTo != c) { st.pendingTo = c; st.pendingCount = 1; st.pendingFirstTs = p.timestamp } else st.pendingCount++
                if (st.pendingCount >= CONFIRM_POINTS) {
                    val at = st.pendingFirstTs
                    st.inside = (c == 1); st.pendingTo = 0; st.pendingCount = 0
                    val ev = GeoEvent(pl.id, pl.name, person, st.inside, at, now - at <= NOTIFY_WINDOW_MS)
                    out.add(ev)
                    log.addLast(ev)
                    while (log.size > MAX_EVENTS) log.removeFirst()
                }
            }
        }
        return out
    }

    fun serialize(): ByteArray {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        o.writeByte(VERSION)
        o.writeInt(places.size)
        for (p in places.values) {
            o.writeUTF(p.id); o.writeUTF(p.name); o.writeDouble(p.lat); o.writeDouble(p.lon); o.writeInt(p.radiusM)
            o.writeBoolean(p.watch != null)
            p.watch?.let { w -> o.writeInt(w.size); w.sorted().forEach { o.writeUTF(it) } }
        }
        o.writeInt(states.size)
        for ((k, s) in states) { o.writeUTF(k); o.writeBoolean(s.inside); o.writeByte(s.pendingTo); o.writeInt(s.pendingCount); o.writeLong(s.pendingFirstTs) }
        o.writeInt(lastTs.size)
        for ((k, t) in lastTs) { o.writeUTF(k); o.writeLong(t) }
        o.writeInt(log.size)
        for (e in log) { o.writeUTF(e.placeId); o.writeUTF(e.placeName); o.writeUTF(e.person); o.writeBoolean(e.arrived); o.writeLong(e.atMs); o.writeBoolean(e.notify) }
        return b.toByteArray()
    }
}
