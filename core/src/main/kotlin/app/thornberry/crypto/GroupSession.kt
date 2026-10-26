package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.TreeSet

/**
 * Group layer: records (chat, location, ...) encrypted with a shared epoch key and signed by the
 * sender, plus signed membership updates that rotate the key when someone is removed.
 * Design: docs/architecture.md (Group keys and removal). NOT independently reviewed (D3).
 *
 * - Records: the sender's identity is INSIDE the encrypted part, so relays see only group id,
 *   epoch, nonce and padded size. Holding the group key is not enough to forge a record: every
 *   record carries an Ed25519 signature from a current member.
 * - Updates (add, remove, rotate) are signed by an admin and meant to travel over the pairwise
 *   Double Ratchet channel to each member. Removal and rotation switch to a fresh random key.
 * - Only the current epoch is accepted, plus the previous epoch for [GRACE_MS] so in-flight
 *   messages still decrypt; the sender must still be a current member either way.
 * - Open race (decision D5): two admins issuing different updates at once with the same version
 *   are rejected as a conflict, which can split the group until an admin reissues.
 */
class GroupSession private constructor(
    val groupId: ByteArray,
    private var epoch: Int,
    private var version: Int,
    private var key: ByteArray,
    private var members: List<ByteArray>,
    private var admins: List<ByteArray>,
    private var prevEpoch: Int,
    private var prevKey: ByteArray?,
    private var prevUntil: Long,
    private var ownSeq: Long,
    private var active: Boolean,
    private val seen: MutableMap<String, SeenWindow>,
) {
    class Record(
        val kind: Int,
        val sender: ByteArray,
        val seq: Long,
        val timestamp: Long,
        val expiresAt: Long,
        val body: ByteArray,
    )

    enum class UpdateResult { APPLIED, REMOVED, STALE }

    private class SeenWindow(var highest: Long, val seqs: TreeSet<Long>) {
        fun accept(seq: Long): Boolean {
            if (seq <= highest - WINDOW || seqs.contains(seq)) return false
            return true
        }
        fun commit(seq: Long) {
            if (seq > highest) highest = seq
            seqs.add(seq)
            while (seqs.isNotEmpty() && seqs.first() <= highest - WINDOW) seqs.pollFirst()
        }
    }

    companion object {
        const val VERSION = 1
        const val MAX_MEMBERS = 64
        const val GRACE_MS = 24L * 3600 * 1000
        const val MAX_SKEW_MS = 10L * 60 * 1000
        const val WINDOW = 4096L
        private val SIZE_CLASSES = intArrayOf(256, 512, 1024, 2048, 4096, 8192)
        private val REC_PREFIX = "thornberry/grp/rec/v1".toByteArray()
        private val UPD_PREFIX = "thornberry/grp/upd/v1".toByteArray()
        private const val HEADER = 1 + 16 + 4 + 24

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun contains(list: List<ByteArray>, x: ByteArray) = list.any { Crypto.constantTimeEquals(it, x) }
        private fun sorted(list: List<ByteArray>) = list.sortedBy { hex(it) }

        /** Create a new group; the creator is the first member and admin. */
        fun create(admin: Crypto.KeyPair): GroupSession = GroupSession(
            Crypto.randomBytes(16), 1, 1, Crypto.randomBytes(32), listOf(admin.public), listOf(admin.public),
            0, null, 0, 0, true, HashMap(),
        )

        /**
         * Join from an update received from [trustedInviter] (someone you paired with in person).
         * Throws if the signature is bad, the signer is not the inviter or not an admin, or we are
         * not in the member list.
         */
        fun join(update: ByteArray, own: Crypto.KeyPair, trustedInviter: ByteArray): GroupSession {
            val u = parseUpdate(update)
            if (!Crypto.constantTimeEquals(u.signer, trustedInviter)) throw CryptoException("unexpected signer")
            if (!contains(u.admins, u.signer)) throw CryptoException("signer is not an admin")
            if (!contains(u.members, own.public)) throw CryptoException("not a member")
            return GroupSession(u.groupId, u.epoch, u.version, u.key, u.members, u.admins, 0, null, 0, 0, true, HashMap())
        }

        private class Update(
            val groupId: ByteArray, val epoch: Int, val version: Int, val key: ByteArray,
            val members: List<ByteArray>, val admins: List<ByteArray>, val signer: ByteArray,
        )

        private fun encodeUpdateBody(
            groupId: ByteArray, epoch: Int, version: Int, key: ByteArray,
            members: List<ByteArray>, admins: List<ByteArray>, issuedAt: Long, signer: ByteArray,
        ): ByteArray {
            val b = ByteArrayOutputStream()
            val o = DataOutputStream(b)
            o.writeByte(VERSION); o.write(groupId); o.writeInt(epoch); o.writeInt(version); o.write(key)
            o.writeShort(members.size); members.forEach { o.write(it) }
            o.writeShort(admins.size); admins.forEach { o.write(it) }
            o.writeLong(issuedAt); o.write(signer)
            return b.toByteArray()
        }

        private fun parseUpdate(data: ByteArray): Update {
            try {
                if (data.size < 1 + 16 + 4 + 4 + 32 + 2 + 2 + 8 + 32 + 64) throw CryptoException("update too short")
                val body = data.copyOf(data.size - 64)
                val sig = data.copyOfRange(data.size - 64, data.size)
                val i = DataInputStream(ByteArrayInputStream(body))
                if (i.readByte().toInt() != VERSION) throw CryptoException("bad update version")
                val groupId = ByteArray(16).also { i.readFully(it) }
                val epoch = i.readInt()
                val version = i.readInt()
                val key = ByteArray(32).also { i.readFully(it) }
                fun list(): List<ByteArray> {
                    val n = i.readUnsignedShort()
                    if (n !in 1..MAX_MEMBERS) throw CryptoException("bad member count")
                    return List(n) { ByteArray(32).also { b -> i.readFully(b) } }
                }
                val members = list()
                val admins = list()
                i.readLong()
                val signer = ByteArray(32).also { i.readFully(it) }
                if (i.available() != 0) throw CryptoException("trailing bytes")
                if (epoch < 1 || version < 1) throw CryptoException("bad counters")
                if (members.map { hex(it) }.toSet().size != members.size) throw CryptoException("duplicate members")
                if (!admins.all { contains(members, it) }) throw CryptoException("admin not a member")
                if (!Crypto.verify(signer, UPD_PREFIX + body, sig)) throw CryptoException("bad update signature")
                return Update(groupId, epoch, version, key, members, admins, signer)
            } catch (e: CryptoException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException("malformed update")
            }
        }

        fun restore(data: ByteArray): GroupSession = try {
            val i = DataInputStream(ByteArrayInputStream(data))
            if (i.readByte().toInt() != VERSION) throw CryptoException("bad state version")
            fun fixed(n: Int) = ByteArray(n).also { i.readFully(it) }
            val gid = fixed(16)
            val epoch = i.readInt(); val version = i.readInt(); val key = fixed(32)
            fun list(): List<ByteArray> = List(i.readUnsignedShort().also { require(it in 0..MAX_MEMBERS) }) { fixed(32) }
            val members = list(); val admins = list()
            val prevEpoch = i.readInt()
            val prevKey = if (i.readBoolean()) fixed(32) else null
            val prevUntil = i.readLong(); val ownSeq = i.readLong(); val active = i.readBoolean()
            val seen = HashMap<String, SeenWindow>()
            repeat(i.readInt().also { require(it in 0..MAX_MEMBERS) }) {
                val id = hex(fixed(32))
                val hi = i.readLong()
                val set = TreeSet<Long>()
                repeat(i.readInt().also { n -> require(n in 0..WINDOW.toInt()) }) { set.add(i.readLong()) }
                seen[id] = SeenWindow(hi, set)
            }
            require(i.available() == 0)
            GroupSession(gid, epoch, version, key, members, admins, prevEpoch, prevKey, prevUntil, ownSeq, active, seen)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed state")
        }
    }

    val currentEpoch: Int get() = epoch
    val groupIdHex: String get() = hex(groupId)
    val currentVersion: Int get() = version
    val isActive: Boolean get() = active
    fun memberKeys(): List<ByteArray> = members.map { it.copyOf() }
    fun isMember(identityPublic: ByteArray) = contains(members, identityPublic)
    fun isAdmin(identityPublic: ByteArray) = contains(admins, identityPublic)

    fun serialize(): ByteArray {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        o.writeByte(VERSION); o.write(groupId); o.writeInt(epoch); o.writeInt(version); o.write(key)
        o.writeShort(members.size); members.forEach { o.write(it) }
        o.writeShort(admins.size); admins.forEach { o.write(it) }
        o.writeInt(prevEpoch); o.writeBoolean(prevKey != null); prevKey?.let { o.write(it) }
        o.writeLong(prevUntil); o.writeLong(ownSeq); o.writeBoolean(active)
        o.writeInt(seen.size)
        for ((id, w) in seen) {
            o.write(id.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            o.writeLong(w.highest); o.writeInt(w.seqs.size); w.seqs.forEach { o.writeLong(it) }
        }
        return b.toByteArray()
    }

    // ---------------- records ----------------

    /** Build, sign, pad and encrypt one record. [expiresAt] is required (receivers must delete after it). */
    fun seal(identity: Crypto.KeyPair, kind: Int, body: ByteArray, timestamp: Long, expiresAt: Long): ByteArray {
        check(active) { "group is not active" }
        if (!isMember(identity.public)) throw CryptoException("sender is not a member")
        require(kind in 0..255 && expiresAt > timestamp)
        ownSeq += 1
        val signed = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeByte(kind); write(identity.public); writeLong(ownSeq); writeLong(timestamp); writeLong(expiresAt)
                writeInt(body.size); write(body)
            }
        }.toByteArray()
        val epochBytes = java.nio.ByteBuffer.allocate(4).putInt(epoch).array()
        val sig = Crypto.sign(identity.private, REC_PREFIX + groupId + epochBytes + signed)
        val content = signed + sig
        val total = SIZE_CLASSES.firstOrNull { it >= 4 + content.size } ?: throw CryptoException("record too large")
        val padded = ByteArray(total)
        java.nio.ByteBuffer.wrap(padded).putInt(content.size)
        System.arraycopy(content, 0, padded, 4, content.size)

        val nonce = Crypto.randomBytes(Crypto.NONCE_BYTES)
        val header = ByteArray(1 + 16 + 4).also {
            it[0] = VERSION.toByte(); System.arraycopy(groupId, 0, it, 1, 16); System.arraycopy(epochBytes, 0, it, 17, 4)
        }
        return header + nonce + Crypto.aeadEncrypt(key, nonce, header, padded)
    }

    /**
     * Decrypt and fully verify a record. Throws [CryptoException] on any problem (wrong group or
     * epoch, bad tag, unknown sender, bad signature, expired, future-dated, replayed).
     */
    fun open(wire: ByteArray, now: Long): Record {
        try {
            if (!active) throw CryptoException("group is not active")
            if (wire.size < HEADER + Crypto.TAG_BYTES) throw CryptoException("too short")
            if (wire[0].toInt() != VERSION) throw CryptoException("bad version")
            if (!Crypto.constantTimeEquals(wire.copyOfRange(1, 17), groupId)) throw CryptoException("wrong group")
            val msgEpoch = java.nio.ByteBuffer.wrap(wire, 17, 4).int
            val useKey = when {
                msgEpoch == epoch -> key
                msgEpoch == prevEpoch && prevKey != null && now <= prevUntil -> prevKey!!
                else -> throw CryptoException("epoch not accepted")
            }
            val header = wire.copyOf(17 + 4)
            val nonce = wire.copyOfRange(21, HEADER)
            val padded = Crypto.aeadDecrypt(useKey, nonce, header, wire.copyOfRange(HEADER, wire.size))

            val len = java.nio.ByteBuffer.wrap(padded).int
            if (len < 0 || len > padded.size - 4) throw CryptoException("bad length")
            val content = padded.copyOfRange(4, 4 + len)
            // padding must be all zero (no hidden channel)
            for (k in 4 + len until padded.size) if (padded[k].toInt() != 0) throw CryptoException("bad padding")
            if (content.size < 64) throw CryptoException("too short")
            val signed = content.copyOf(content.size - 64)
            val sig = content.copyOfRange(content.size - 64, content.size)
            val i = DataInputStream(ByteArrayInputStream(signed))
            val kind = i.readUnsignedByte()
            val sender = ByteArray(32).also { i.readFully(it) }
            val seq = i.readLong(); val ts = i.readLong(); val exp = i.readLong()
            val bodyLen = i.readInt()
            if (bodyLen < 0 || bodyLen != i.available()) throw CryptoException("bad body length")
            val body = ByteArray(bodyLen).also { i.readFully(it) }

            if (!isMember(sender)) throw CryptoException("sender is not a member")
            val epochBytes = java.nio.ByteBuffer.allocate(4).putInt(msgEpoch).array()
            if (!Crypto.verify(sender, REC_PREFIX + groupId + epochBytes + signed, sig)) throw CryptoException("bad signature")
            if (exp <= now) throw CryptoException("expired")
            if (ts > now + MAX_SKEW_MS) throw CryptoException("from the future")
            if (seq < 1) throw CryptoException("bad sequence")
            val w = seen.getOrPut(hex(sender)) { SeenWindow(0, TreeSet()) }
            if (!w.accept(seq)) throw CryptoException("replayed or too old")
            w.commit(seq)
            return Record(kind, sender, seq, ts, exp, body)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed record")
        }
    }

    // ---------------- membership updates ----------------

    private fun requireAdmin(admin: Crypto.KeyPair) {
        check(active) { "group is not active" }
        if (!isAdmin(admin.public)) throw CryptoException("not an admin")
    }

    private fun issue(
        admin: Crypto.KeyPair, newEpoch: Int, newKey: ByteArray, newMembers: List<ByteArray>,
        newAdmins: List<ByteArray>, now: Long,
    ): ByteArray {
        val newVersion = version + 1
        val body = encodeUpdateBody(groupId, newEpoch, newVersion, newKey, newMembers, newAdmins, now, admin.public)
        val update = body + Crypto.sign(admin.private, UPD_PREFIX + body)
        applyUpdate(update, now, admin.public) // the issuer adopts it the same way every member will
        return update
    }

    /** Add a member. Same key and epoch (no history for the newcomer unless you send it). Send the result to everyone, including the newcomer. */
    fun addMember(admin: Crypto.KeyPair, newMember: ByteArray, now: Long, rotateKey: Boolean = false): ByteArray {
        requireAdmin(admin)
        if (isMember(newMember)) throw CryptoException("already a member")
        if (members.size >= MAX_MEMBERS) throw CryptoException("group is full")
        // With rotateKey the newcomer gets a fresh key, so they cannot read anything sent before they joined.
        return if (rotateKey) {
            issue(admin, epoch + 1, Crypto.randomBytes(32), members + newMember, admins, now)
        } else {
            issue(admin, epoch, key, members + newMember, admins, now)
        }
    }

    /** Remove a member and rotate to a fresh random key. Send the result to every REMAINING member over their pairwise channel, never to the removed one. */
    fun removeMember(admin: Crypto.KeyPair, member: ByteArray, now: Long): ByteArray {
        requireAdmin(admin)
        if (!isMember(member)) throw CryptoException("not a member")
        val newAdmins = admins.filter { !Crypto.constantTimeEquals(it, member) }
        if (newAdmins.isEmpty()) throw CryptoException("group needs an admin")
        return issue(admin, epoch + 1, Crypto.randomBytes(32), members.filter { !Crypto.constantTimeEquals(it, member) }, newAdmins, now)
    }

    /** Rotate the key without changing members (scheduled, or after a phone is lost). */
    fun rotate(admin: Crypto.KeyPair, now: Long): ByteArray {
        requireAdmin(admin)
        return issue(admin, epoch + 1, Crypto.randomBytes(32), members, admins, now)
    }

    /** Apply a signed update received from an admin. Throws [CryptoException] if it is invalid or conflicts. */
    fun applyUpdate(update: ByteArray, now: Long, ownIdentityPublic: ByteArray): UpdateResult {
        val u = parseUpdate(update)
        if (!Crypto.constantTimeEquals(u.groupId, groupId)) throw CryptoException("wrong group")
        if (!active) throw CryptoException("group is not active")
        if (!isAdmin(u.signer)) throw CryptoException("signer is not an admin")
        if (u.version < version) return UpdateResult.STALE
        if (u.version == version) {
            val same = u.epoch == epoch && Crypto.constantTimeEquals(u.key, key) &&
                sorted(u.members).map(::hex) == sorted(members).map(::hex)
            if (same) return UpdateResult.STALE
            throw CryptoException("conflicting update at the same version")
        }
        if (u.epoch < epoch) throw CryptoException("epoch rollback")
        if (u.epoch == epoch && !Crypto.constantTimeEquals(u.key, key)) throw CryptoException("key changed without new epoch")

        if (!contains(u.members, ownIdentityPublic)) {
            // We were removed: forget everything secret about this group.
            key = ByteArray(32); prevKey = null; active = false
            members = emptyList(); admins = emptyList()
            return UpdateResult.REMOVED
        }
        if (u.epoch != epoch) {
            prevEpoch = epoch; prevKey = key; prevUntil = now + GRACE_MS
        }
        epoch = u.epoch; version = u.version; key = u.key; members = u.members; admins = u.admins
        return UpdateResult.APPLIED
    }
}
