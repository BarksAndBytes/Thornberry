package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A place shared with a group: every member sees it and may change or delete it.
 * [editedAt] and [editor] (first 8 bytes of the editor's identity, hex) decide which version wins
 * (last writer wins). They come from whoever last re-sent the list, so they are not proof of who edited;
 * they are only used for ordering.
 */
class SharedPlace(
    val id: String,
    val name: String,
    val latMicro: Int,
    val lonMicro: Int,
    val radiusM: Int,
    val deleted: Boolean,
    val editedAt: Long,
    val editor: String,
) {
    val lat: Double get() = latMicro / 1e6
    val lon: Double get() = lonMicro / 1e6
}

/**
 * The shared places of one group. Every change is sent as the whole list (record kind 5), so a member who missed
 * an update, or joined later, catches up from the next list anyone sends. Deletions are kept as markers for
 * [TOMBSTONE_KEEP_MS] so an old copy cannot bring a deleted place back.
 */
class SharedPlaces {
    companion object {
        const val KIND_PLACES = 5
        const val MAX_LIVE = 40
        const val MAX_ENTRIES = 60
        const val MAX_NAME_BYTES = 120
        const val TOMBSTONE_KEEP_MS = 30L * 24 * 3600_000
        private const val VERSION = 1

        private fun hexOk(s: String, len: Int) = s.length == len && s.all { it in '0'..'9' || it in 'a'..'f' }

        fun validate(p: SharedPlace) {
            if (!hexOk(p.id, 16) || !hexOk(p.editor, 16)) throw CryptoException("bad place id")
            if (!p.deleted) {
                val nb = p.name.toByteArray(Charsets.UTF_8).size
                if (p.name.isBlank() || nb > MAX_NAME_BYTES) throw CryptoException("bad place name")
                if (p.latMicro !in -90_000_000..90_000_000 || p.lonMicro !in -180_000_000..180_000_000) throw CryptoException("bad place position")
                if (p.radiusM !in PlaceBook.MIN_RADIUS_M..PlaceBook.MAX_RADIUS_M) throw CryptoException("bad place radius")
            }
        }

        fun encode(list: List<SharedPlace>): ByteArray {
            require(list.size <= MAX_ENTRIES) { "too many places" }
            val b = ByteArrayOutputStream()
            val o = DataOutputStream(b)
            o.writeByte(VERSION); o.writeShort(list.size)
            for (p in list) {
                validate(p)
                o.write(unhex(p.id)); o.writeBoolean(p.deleted); o.writeLong(p.editedAt); o.write(unhex(p.editor))
                if (!p.deleted) {
                    val nb = p.name.toByteArray(Charsets.UTF_8)
                    o.writeByte(nb.size); o.write(nb)
                    o.writeInt(p.latMicro); o.writeInt(p.lonMicro); o.writeShort(p.radiusM)
                }
            }
            return b.toByteArray()
        }

        fun decode(body: ByteArray): List<SharedPlace> {
            try {
                val i = DataInputStream(ByteArrayInputStream(body))
                if (i.readUnsignedByte() != VERSION) throw CryptoException("bad places version")
                val n = i.readUnsignedShort()
                if (n > MAX_ENTRIES) throw CryptoException("too many places")
                val out = ArrayList<SharedPlace>(n)
                for (k in 0 until n) {
                    val id = hex(ByteArray(8).also { i.readFully(it) })
                    val deleted = i.readBoolean()
                    val at = i.readLong()
                    val editor = hex(ByteArray(8).also { i.readFully(it) })
                    val p = if (deleted) SharedPlace(id, "", 0, 0, 0, true, at, editor) else {
                        val len = i.readUnsignedByte()
                        val name = String(ByteArray(len).also { i.readFully(it) }, Charsets.UTF_8)
                        SharedPlace(id, name, i.readInt(), i.readInt(), i.readUnsignedShort(), false, at, editor)
                    }
                    validate(p)
                    out.add(p)
                }
                if (i.available() != 0) throw CryptoException("trailing bytes")
                if (out.map { it.id }.toSet().size != out.size) throw CryptoException("duplicate place")
                return out
            } catch (e: CryptoException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException("malformed places")
            }
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private val map = LinkedHashMap<String, SharedPlace>()

    fun live(): List<SharedPlace> = map.values.filter { !it.deleted }
    fun all(): List<SharedPlace> = map.values.toList()
    fun get(id: String): SharedPlace? = map[id]
    fun isEmpty() = map.isEmpty()

    private fun newer(a: SharedPlace, b: SharedPlace): Boolean =
        a.editedAt > b.editedAt || (a.editedAt == b.editedAt && a.editor > b.editor)

    /** Merge one version; returns true if it changed what we hold. New places beyond [MAX_LIVE] are refused. */
    fun put(p: SharedPlace): Boolean {
        validate(p)
        val cur = map[p.id]
        if (cur != null && !newer(p, cur)) return false
        if (cur == null && !p.deleted && live().size >= MAX_LIVE) return false
        if (cur == null && map.size >= MAX_ENTRIES) return false
        map[p.id] = p
        return true
    }

    /** Drop deletion markers older than [TOMBSTONE_KEEP_MS]. */
    fun prune(now: Long) { map.values.removeAll { it.deleted && it.editedAt < now - TOMBSTONE_KEEP_MS } }

    fun serialize(): ByteArray = encode(all())
    fun restoreFrom(data: ByteArray) { for (p in decode(data)) map[p.id] = p }
}
