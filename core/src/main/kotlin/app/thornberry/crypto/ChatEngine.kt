package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Pure-JVM orchestration of everything below it: contacts (pairwise Double Ratchet sessions), groups,
 * sync and relaying. It does no I/O: the app hands it received frames and sends the frames it
 * produces over Tor. All state can be saved and restored. NOT independently reviewed (D3).
 *
 * Frame on the wire = 16-byte contact tag + Double Ratchet message. Inside the ratchet:
 * type byte + body (1 text, 2 group update, 3 sync message, 4 hello).
 *
 * v1 constraints (documented, deliberate):
 * - Group updates travel only over direct links, so the admin must be paired with every member.
 * - Group records can be relayed through any member, but members can only reach each other
 *   through contacts they are paired with.
 * - Adding a member rotates the group key, so newcomers cannot read earlier traffic.
 * - A record that arrives before the update for its key epoch is stored for relaying but is not
 *   decrypted later (no retry yet).
 */
class ChatEngine private constructor(
    private val identity: Crypto.KeyPair,
    private val store: RecordStore,
) {
    constructor(identity: Crypto.KeyPair) : this(identity, InMemoryRecordStore())

    class Contact(
        val peerIdentity: ByteArray,
        var name: String,
        val onion: String,
        val session: DoubleRatchet,
        val ad: ByteArray,
        val sendTag: ByteArray,
        val receiveTag: ByteArray,
        val isInitiator: Boolean,
        var verified: Boolean,
    ) {
        val id: String get() = hex(peerIdentity)
    }

    class Group(val name: String, val session: GroupSession) {
        val id: String get() = session.groupIdHex
    }

    sealed class Event {
        class DirectText(val contactId: String, val text: String, val timestamp: Long) : Event()
        class GroupText(val groupId: String, val groupName: String, val sender: ByteArray, val text: String, val timestamp: Long) : Event()
        class GroupJoined(val groupId: String, val groupName: String) : Event()
        class GroupChanged(val groupId: String) : Event()
        class RemovedFromGroup(val groupId: String) : Event()
    }

    /** A frame to deliver to a contact's onion address. Keep until delivery succeeds. */
    class Outgoing(val toOnion: String, val contactId: String, val frame: ByteArray)

    class PairingResult(val contact: Contact, val safetyCode: String)

    private val contacts = LinkedHashMap<String, Contact>()
    private val byTag = HashMap<String, String>()
    private val groups = LinkedHashMap<String, Group>()
    private val syncPeers = HashMap<String, Pair<Set<String>, SyncPeer>>()
    private val sync = SyncEngine(store)
    private val pending = ArrayList<Outgoing>()
    private val events = ArrayList<Event>()
    var droppedFrames = 0L
        private set

    val identityPublic: ByteArray get() = identity.public.copyOf()
    fun contactList(): List<Contact> = contacts.values.toList()
    fun groupList(): List<Group> = groups.values.toList()
    fun contact(id: String): Contact? = contacts[id]
    fun group(id: String): Group? = groups[id]

    /** Everything that has happened since the last call. */
    fun drainEvents(): List<Event> = events.toList().also { events.clear() }

    /** Frames waiting to be sent. They stay queued until [markDelivered] is called. */
    fun pendingFrames(): List<Outgoing> = pending.toList()
    fun markDelivered(o: Outgoing) { pending.remove(o) }

    // ---------------- pairing ----------------

    fun beginPairing(onion: String): Pairing.Local = Pairing.createOffer(identity, onion)

    fun completePairing(local: Pairing.Local, scanned: ByteArray, name: String): PairingResult {
        val r = Pairing.complete(local, scanned)
        val id = hex(r.peerIdentityPublic)
        if (contacts.containsKey(id)) throw CryptoException("already paired")
        val c = Contact(r.peerIdentityPublic, name, r.peerOnion, r.session, r.associatedData, r.sendTag, r.receiveTag, r.isInitiator, false)
        contacts[id] = c
        byTag[hex(r.receiveTag)] = id
        if (c.isInitiator) queue(c, TYPE_HELLO, ByteArray(0)) // lets the other side start sending
        return PairingResult(c, r.safetyCode)
    }

    fun confirmVerified(contactId: String) { contacts[contactId]?.verified = true }

    fun removeContact(contactId: String) {
        val c = contacts.remove(contactId) ?: return
        byTag.remove(hex(c.receiveTag))
        syncPeers.remove(contactId)
        pending.removeAll { it.contactId == contactId }
    }

    // ---------------- direct chat ----------------

    fun sendDirect(contactId: String, text: String, now: Long) {
        val c = contacts[contactId] ?: throw CryptoException("unknown contact")
        val body = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeLong(now); write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        queue(c, TYPE_TEXT, body)
    }

    // ---------------- groups ----------------

    fun createGroup(name: String): String {
        val g = Group(name, GroupSession.create(identity))
        groups[g.id] = g
        return g.id
    }

    fun inviteToGroup(groupId: String, contactId: String, now: Long) {
        val g = groups[groupId] ?: throw CryptoException("unknown group")
        val c = contacts[contactId] ?: throw CryptoException("unknown contact")
        val update = g.session.addMember(identity, c.peerIdentity, now, rotateKey = true)
        distribute(g, update)
        events.add(Event.GroupChanged(g.id))
    }

    fun removeFromGroup(groupId: String, memberIdentity: ByteArray, now: Long) {
        val g = groups[groupId] ?: throw CryptoException("unknown group")
        val update = g.session.removeMember(identity, memberIdentity, now)
        distribute(g, update) // new member list no longer contains the removed person
        // Tell the removed person (never the new key), if we are paired with them.
        contacts.values.firstOrNull { Crypto.constantTimeEquals(it.peerIdentity, memberIdentity) }
            ?.let { queue(it, TYPE_GROUP_REMOVED, g.session.groupId.copyOf()) }
        events.add(Event.GroupChanged(g.id))
    }

    private fun distribute(g: Group, update: ByteArray) {
        val name = g.name.toByteArray(Charsets.UTF_8).copyOf(minOf(g.name.toByteArray().size, 60))
        val body = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeByte(name.size); write(name); write(update) } }.toByteArray()
        for (c in contacts.values) if (g.session.isMember(c.peerIdentity)) queue(c, TYPE_GROUP_UPDATE, body)
        syncPeers.clear() // shared-group sets changed
    }

    fun sendGroupText(groupId: String, text: String, now: Long, ttlMs: Long = 7L * 24 * 3600 * 1000) {
        val g = groups[groupId] ?: throw CryptoException("unknown group")
        val expires = now + ttlMs
        val wire = g.session.seal(identity, KIND_TEXT, text.toByteArray(Charsets.UTF_8), now, expires)
        sync.addLocal(wire, expires, now)
        for (c in contacts.values) if (g.session.isMember(c.peerIdentity)) offerTo(c, now)
    }

    /** Offer everything we hold to every contact (call periodically and when a connection comes up). */
    fun syncAll(now: Long) {
        store.purgeExpired(now)
        for (c in contacts.values) offerTo(c, now)
    }

    // ---------------- receiving ----------------

    /** Handle one frame from the network. Never throws on bad input; bad frames are counted and dropped. */
    fun receiveFrame(frame: ByteArray, now: Long) {
        try {
            if (frame.size < 16 + DoubleRatchet.HEADER_BYTES) { droppedFrames++; return }
            val cid = byTag[hex(frame.copyOf(16))]
            val c = if (cid != null) contacts[cid] else null
            if (c == null) { droppedFrames++; return }
            val plain = try {
                c.session.decrypt(frame.copyOfRange(16, frame.size), c.ad)
            } catch (e: CryptoException) {
                droppedFrames++; return
            }
            if (plain.isEmpty()) { droppedFrames++; return }
            val body = plain.copyOfRange(1, plain.size)
            when (plain[0].toInt()) {
                TYPE_HELLO -> {}
                TYPE_TEXT -> onText(c, body)
                TYPE_GROUP_UPDATE -> onGroupUpdate(c, body, now)
                TYPE_SYNC -> onSync(c, body, now)
                TYPE_GROUP_REMOVED -> onRemoved(c, body)
                else -> droppedFrames++
            }
        } catch (e: Exception) {
            droppedFrames++
        }
    }

    private fun onText(c: Contact, body: ByteArray) {
        if (body.size < 8) { droppedFrames++; return }
        val ts = java.nio.ByteBuffer.wrap(body, 0, 8).long
        events.add(Event.DirectText(c.id, String(body, 8, body.size - 8, Charsets.UTF_8), ts))
    }

    private fun onGroupUpdate(c: Contact, body: ByteArray, now: Long) {
        if (body.isEmpty()) { droppedFrames++; return }
        val nameLen = body[0].toInt() and 0xff
        if (body.size < 1 + nameLen + 17) { droppedFrames++; return }
        val name = String(body, 1, nameLen, Charsets.UTF_8)
        val update = body.copyOfRange(1 + nameLen, body.size)
        val gid = hex(update.copyOfRange(1, 17))
        val existing = groups[gid]
        try {
            if (existing != null) {
                if (!existing.session.isActive) { droppedFrames++; return }
                when (existing.session.applyUpdate(update, now, identity.public)) {
                    GroupSession.UpdateResult.APPLIED -> { syncPeers.clear(); events.add(Event.GroupChanged(gid)) }
                    GroupSession.UpdateResult.REMOVED -> { syncPeers.clear(); events.add(Event.RemovedFromGroup(gid)) }
                    GroupSession.UpdateResult.STALE -> {}
                }
            } else {
                // A new group: only accept it from someone we paired with in person, who must be its admin.
                val session = GroupSession.join(update, identity, c.peerIdentity)
                groups[gid] = Group(name, session)
                syncPeers.clear()
                events.add(Event.GroupJoined(gid, name))
            }
        } catch (e: CryptoException) {
            droppedFrames++
        }
    }

    private fun onRemoved(c: Contact, body: ByteArray) {
        val g = groups[hex(body)]
        // Only an admin of that group may tell us we were removed.
        if (body.size != 16 || g == null || !g.session.isActive || !g.session.isAdmin(c.peerIdentity)) { droppedFrames++; return }
        g.session.markRemoved()
        syncPeers.clear()
        events.add(Event.RemovedFromGroup(g.id))
    }

    private fun onSync(c: Contact, body: ByteArray, now: Long) {
        val msg = try { SyncMessage.decode(body) } catch (e: CryptoException) { droppedFrames++; return }
        val out = sync.handle(peerFor(c), msg, now)
        for (r in out.replies) queue(c, TYPE_SYNC, r.encode())
        var gotNew = false
        for (rec in out.received) {
            val g = groups[hex(rec.groupId)] ?: continue
            if (!g.session.isActive) continue
            gotNew = true
            try {
                val r = g.session.open(rec.wire, now)
                if (r.kind == KIND_TEXT) {
                    events.add(Event.GroupText(g.id, g.name, r.sender, String(r.body, Charsets.UTF_8), r.timestamp))
                }
            } catch (e: CryptoException) {
                droppedFrames++ // kept in the store for relaying, but not shown
            }
        }
        if (gotNew) for (other in contacts.values) if (other.id != c.id) offerTo(other, now) // relay onward
    }

    // ---------------- internals ----------------

    private fun sharedGroupIds(c: Contact): Set<String> =
        groups.values.filter { it.session.isActive && it.session.isMember(c.peerIdentity) }.map { it.id }.toSet()

    private fun peerFor(c: Contact): SyncPeer {
        val shared = sharedGroupIds(c)
        val cached = syncPeers[c.id]
        if (cached != null && cached.first == shared) return cached.second
        val peer = SyncPeer(shared.map { g -> g.chunked(2).map { it.toInt(16).toByte() }.toByteArray() })
        syncPeers[c.id] = shared to peer
        return peer
    }

    private fun offerTo(c: Contact, now: Long) {
        val offer = sync.offer(peerFor(c), now) ?: return
        queue(c, TYPE_SYNC, offer.encode())
    }

    private fun queue(c: Contact, type: Int, body: ByteArray) {
        val plain = byteArrayOf(type.toByte()) + body
        pending.add(Outgoing(c.onion, c.id, c.sendTag + c.session.encrypt(plain, c.ad)))
    }

    // ---------------- persistence ----------------

    /** Everything, including secret keys. The app must store this only encrypted (Android Keystore). */
    fun snapshot(): ByteArray {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        fun bytes(x: ByteArray) { o.writeInt(x.size); o.write(x) }
        o.writeByte(SNAPSHOT_VERSION)
        bytes(identity.private); bytes(identity.public)
        o.writeInt(contacts.size)
        for (c in contacts.values) {
            bytes(c.peerIdentity); bytes(c.name.toByteArray()); bytes(c.onion.toByteArray()); bytes(c.ad)
            bytes(c.sendTag); bytes(c.receiveTag); o.writeBoolean(c.isInitiator); o.writeBoolean(c.verified)
            bytes(c.session.serialize())
        }
        o.writeInt(groups.size)
        for (g in groups.values) { bytes(g.name.toByteArray()); bytes(g.session.serialize()) }
        val recs = store.all()
        o.writeInt(recs.size)
        for (r in recs) { bytes(r.id); bytes(r.groupId); bytes(r.wire); o.writeLong(r.expiresAt) }
        o.writeInt(pending.size)
        for (p in pending) { bytes(p.toOnion.toByteArray()); bytes(p.contactId.toByteArray()); bytes(p.frame) }
        return b.toByteArray()
    }

    companion object {
        const val TYPE_TEXT = 1
        const val TYPE_GROUP_UPDATE = 2
        const val TYPE_SYNC = 3
        const val TYPE_HELLO = 4
        const val TYPE_GROUP_REMOVED = 5
        const val KIND_TEXT = 1
        private const val SNAPSHOT_VERSION = 1

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun restore(data: ByteArray): ChatEngine = try {
            val i = DataInputStream(ByteArrayInputStream(data))
            if (i.readUnsignedByte() != SNAPSHOT_VERSION) throw CryptoException("bad snapshot version")
            fun bytes(): ByteArray = ByteArray(i.readInt().also { require(it in 0..2_000_000) }).also { i.readFully(it) }
            val priv = bytes(); val pub = bytes()
            val e = ChatEngine(Crypto.KeyPair(priv, pub), InMemoryRecordStore())
            repeat(i.readInt().also { require(it in 0..10_000) }) {
                val peer = bytes(); val name = String(bytes()); val onion = String(bytes()); val ad = bytes()
                val st = bytes(); val rt = bytes(); val init = i.readBoolean(); val ver = i.readBoolean()
                val c = Contact(peer, name, onion, DoubleRatchet.restore(bytes()), ad, st, rt, init, ver)
                e.contacts[c.id] = c; e.byTag[hex(rt)] = c.id
            }
            repeat(i.readInt().also { require(it in 0..10_000) }) {
                val name = String(bytes()); val g = Group(name, GroupSession.restore(bytes())); e.groups[g.id] = g
            }
            repeat(i.readInt().also { require(it in 0..1_000_000) }) {
                e.store.put(StoredRecord(bytes(), bytes(), bytes(), i.readLong()))
            }
            repeat(i.readInt().also { require(it in 0..1_000_000) }) {
                e.pending.add(Outgoing(String(bytes()), String(bytes()), bytes()))
            }
            require(i.available() == 0)
            e
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed snapshot")
        }
    }
}
