package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Store-and-forward record exchange between two peers (paired phones, group relays). Transport
 * independent: messages are plain bytes to be carried inside an authenticated channel (the
 * pairwise ratchet session). Design: docs/architecture.md (Sync, Relaying inside a group).
 * NOT independently reviewed (D3).
 *
 * Flow: A sends Offer (ids, group, expiry), B answers Want (ids it lacks), A sends Deliver (the
 * encrypted record blobs), B answers Ack. A peer only receives records for groups shared with
 * it, only blobs it asked for are accepted, and every limit below is enforced on both sides.
 * The engine never reads record contents; expiry stated by a peer is capped and the real expiry
 * is enforced by [GroupSession.open] when a member decrypts.
 */
object SyncLimits {
    const val ID_BYTES = 16
    const val MAX_ENTRIES = 256
    const val MAX_BLOB_BYTES = 8_300 // largest padded record plus header and tag
    const val MAX_DELIVER_BLOBS = 32
    const val MAX_MESSAGE_BYTES = 512 * 1024
    const val MAX_TTL_MS = 48L * 3600 * 1000
    const val MAX_OUTSTANDING_WANTS = 512
    const val MAX_STORE_BYTES = 32L * 1024 * 1024
    const val MAX_STORE_RECORDS_PER_GROUP = 5_000
    const val BUCKET_BYTES = 1_048_576L // burst allowance per peer
    const val REFILL_BYTES_PER_MS = 4.27 // about 256 KiB per minute
}

sealed class SyncMessage {
    class Entry(val id: ByteArray, val groupId: ByteArray, val expiresAt: Long)
    class Offer(val entries: List<Entry>) : SyncMessage()
    class Want(val ids: List<ByteArray>) : SyncMessage()
    class Deliver(val blobs: List<ByteArray>) : SyncMessage()
    class Ack(val ids: List<ByteArray>) : SyncMessage()

    fun encode(): ByteArray {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        when (this) {
            is Offer -> { o.writeByte(1); o.writeShort(entries.size); entries.forEach { o.write(it.id); o.write(it.groupId); o.writeLong(it.expiresAt) } }
            is Want -> { o.writeByte(2); o.writeShort(ids.size); ids.forEach { o.write(it) } }
            is Deliver -> { o.writeByte(3); o.writeShort(blobs.size); blobs.forEach { o.writeInt(it.size); o.write(it) } }
            is Ack -> { o.writeByte(4); o.writeShort(ids.size); ids.forEach { o.write(it) } }
        }
        val out = b.toByteArray()
        check(out.size <= SyncLimits.MAX_MESSAGE_BYTES) { "message too large" }
        return out
    }

    companion object {
        /** Strict parser. Throws [CryptoException] for anything malformed or over limit. */
        fun decode(data: ByteArray): SyncMessage {
            try {
                if (data.size > SyncLimits.MAX_MESSAGE_BYTES || data.size < 3) throw CryptoException("bad sync size")
                val i = DataInputStream(ByteArrayInputStream(data))
                val type = i.readUnsignedByte()
                val n = i.readUnsignedShort()
                fun id() = ByteArray(SyncLimits.ID_BYTES).also { i.readFully(it) }
                val msg: SyncMessage = when (type) {
                    1 -> {
                        if (n > SyncLimits.MAX_ENTRIES) throw CryptoException("too many entries")
                        Offer(List(n) { Entry(id(), id(), i.readLong()) })
                    }
                    2 -> { if (n > SyncLimits.MAX_ENTRIES) throw CryptoException("too many ids"); Want(List(n) { id() }) }
                    3 -> {
                        if (n > SyncLimits.MAX_DELIVER_BLOBS) throw CryptoException("too many blobs")
                        Deliver(List(n) {
                            val len = i.readInt()
                            if (len !in 1..SyncLimits.MAX_BLOB_BYTES || len > i.available()) throw CryptoException("bad blob length")
                            ByteArray(len).also { b -> i.readFully(b) }
                        })
                    }
                    4 -> { if (n > SyncLimits.MAX_ENTRIES) throw CryptoException("too many ids"); Ack(List(n) { id() }) }
                    else -> throw CryptoException("unknown sync message")
                }
                if (i.available() != 0) throw CryptoException("trailing bytes")
                return msg
            } catch (e: CryptoException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException("malformed sync message")
            }
        }
    }
}

class StoredRecord(val id: ByteArray, val groupId: ByteArray, val wire: ByteArray, val expiresAt: Long)

interface RecordStore {
    fun put(r: StoredRecord): Boolean
    fun has(id: ByteArray): Boolean
    fun get(id: ByteArray): StoredRecord?
    /** Not-expired records of the given groups, latest-expiring first. */
    fun list(groupIds: Collection<ByteArray>, now: Long, max: Int): List<StoredRecord>
    fun purgeExpired(now: Long): Int
    fun all(): List<StoredRecord>
}

/** In-memory store with quotas; a database-backed store replaces it later (same interface). */
class InMemoryRecordStore : RecordStore {
    private val byId = LinkedHashMap<String, StoredRecord>()
    private var bytes = 0L
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    override fun put(r: StoredRecord): Boolean {
        val k = hex(r.id)
        if (byId.containsKey(k)) return false
        byId[k] = r
        bytes += r.wire.size
        enforceQuotas(hex(r.groupId))
        return byId.containsKey(k)
    }

    private fun evict(r: StoredRecord) { byId.remove(hex(r.id))?.let { bytes -= it.wire.size } }

    private fun enforceQuotas(groupHex: String) {
        val inGroup = byId.values.filter { hex(it.groupId) == groupHex }.sortedBy { it.expiresAt }
        var over = inGroup.size - SyncLimits.MAX_STORE_RECORDS_PER_GROUP
        for (r in inGroup) { if (over <= 0) break; evict(r); over-- }
        if (bytes > SyncLimits.MAX_STORE_BYTES) {
            for (r in byId.values.sortedBy { it.expiresAt }) { if (bytes <= SyncLimits.MAX_STORE_BYTES) break; evict(r) }
        }
    }

    override fun has(id: ByteArray) = byId.containsKey(hex(id))
    override fun get(id: ByteArray) = byId[hex(id)]
    override fun list(groupIds: Collection<ByteArray>, now: Long, max: Int): List<StoredRecord> {
        val gs = groupIds.map { hex(it) }.toSet()
        return byId.values.filter { it.expiresAt > now && hex(it.groupId) in gs }.sortedByDescending { it.expiresAt }.take(max)
    }
    override fun purgeExpired(now: Long): Int {
        val dead = byId.values.filter { it.expiresAt <= now }
        dead.forEach { evict(it) }
        return dead.size
    }
    override fun all(): List<StoredRecord> = byId.values.toList()
    val totalBytes: Long get() = bytes
}

/** Per-peer state for one sync relationship. Create one per connected peer. */
class SyncPeer(sharedGroups: Collection<ByteArray>) {
    internal val groups: Set<String> = sharedGroups.map { g -> g.joinToString("") { "%02x".format(it) } }.toSet()
    internal val peerHas = HashSet<String>()
    internal class Expect(val groupHex: String, val expiresAt: Long)
    internal val wanted = HashMap<String, Expect>()
    internal var tokens = SyncLimits.BUCKET_BYTES.toDouble()
    internal var lastRefill = -1L
}

class SyncEngine(val store: RecordStore) {
    class Received(val id: ByteArray, val groupId: ByteArray, val wire: ByteArray)
    class Output(val replies: List<SyncMessage>, val received: List<Received>)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        fun recordId(wire: ByteArray): ByteArray = Crypto.sha256(wire).copyOf(SyncLimits.ID_BYTES)
    }

    /** Add a record we created or decrypted ourselves (e.g. after sealing) so it can be relayed. */
    fun addLocal(wire: ByteArray, expiresAt: Long, now: Long): Boolean {
        require(wire.size in 1 + 16 + 4 + 24 + 16..SyncLimits.MAX_BLOB_BYTES)
        val ttl = minOf(expiresAt, now + SyncLimits.MAX_TTL_MS)
        return store.put(StoredRecord(recordId(wire), wire.copyOfRange(1, 17), wire, ttl))
    }

    /** What we can offer this peer right now: shared groups only, nothing it already has. */
    fun offer(peer: SyncPeer, now: Long): SyncMessage.Offer? {
        val groups = peer.groups.map { g -> g.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        val entries = store.list(groups, now, SyncLimits.MAX_ENTRIES)
            .filter { hex(it.id) !in peer.peerHas }
            .map { SyncMessage.Entry(it.id, it.groupId, it.expiresAt) }
        return if (entries.isEmpty()) null else SyncMessage.Offer(entries)
    }

    private fun refill(peer: SyncPeer, now: Long) {
        if (peer.lastRefill >= 0 && now > peer.lastRefill) {
            peer.tokens = minOf(SyncLimits.BUCKET_BYTES.toDouble(), peer.tokens + (now - peer.lastRefill) * SyncLimits.REFILL_BYTES_PER_MS)
        }
        peer.lastRefill = now
    }

    fun handle(peer: SyncPeer, message: SyncMessage, now: Long): Output {
        val replies = ArrayList<SyncMessage>()
        val received = ArrayList<Received>()
        when (message) {
            is SyncMessage.Offer -> {
                val want = ArrayList<ByteArray>()
                for (e in message.entries) {
                    val g = hex(e.groupId)
                    if (g !in peer.groups) continue
                    peer.peerHas.add(hex(e.id))
                    if (store.has(e.id) || e.expiresAt <= now || peer.wanted.containsKey(hex(e.id))) continue
                    if (peer.wanted.size >= SyncLimits.MAX_OUTSTANDING_WANTS) break
                    peer.wanted[hex(e.id)] = SyncPeer.Expect(g, minOf(e.expiresAt, now + SyncLimits.MAX_TTL_MS))
                    want.add(e.id)
                }
                if (want.isNotEmpty()) replies.add(SyncMessage.Want(want))
            }
            is SyncMessage.Want -> {
                refill(peer, now)
                var batch = ArrayList<ByteArray>()
                fun flush() { if (batch.isNotEmpty()) { replies.add(SyncMessage.Deliver(batch)); batch = ArrayList() } }
                for (id in message.ids) {
                    val r = store.get(id) ?: continue
                    if (hex(r.groupId) !in peer.groups || r.expiresAt <= now) continue
                    if (peer.tokens < r.wire.size) break // over this peer's rate; it can ask again later
                    peer.tokens -= r.wire.size
                    batch.add(r.wire)
                    peer.peerHas.add(hex(r.id))
                    if (batch.size >= SyncLimits.MAX_DELIVER_BLOBS) flush()
                }
                flush()
            }
            is SyncMessage.Deliver -> {
                val ok = ArrayList<ByteArray>()
                for (blob in message.blobs) {
                    if (blob.size > SyncLimits.MAX_BLOB_BYTES || blob.size < 1 + 16 + 4 + 24 + 16) continue
                    val id = recordId(blob)
                    val expect = peer.wanted.remove(hex(id)) ?: continue // never asked for it: drop
                    val groupId = blob.copyOfRange(1, 17)
                    if (blob[0].toInt() != GroupSession.VERSION || hex(groupId) != expect.groupHex) continue
                    peer.peerHas.add(hex(id))
                    if (store.put(StoredRecord(id, groupId, blob, expect.expiresAt))) received.add(Received(id, groupId, blob))
                    ok.add(id)
                }
                if (ok.isNotEmpty()) replies.add(SyncMessage.Ack(ok))
            }
            is SyncMessage.Ack -> message.ids.forEach { peer.peerHas.add(hex(it)) }
        }
        return Output(replies, received)
    }
}
