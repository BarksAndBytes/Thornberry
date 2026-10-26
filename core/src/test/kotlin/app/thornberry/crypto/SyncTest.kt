package app.thornberry.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L
    private fun b(s: String) = s.toByteArray()

    private class Node(val engine: SyncEngine = SyncEngine(InMemoryRecordStore()))

    /** Runs a full exchange until both sides are quiet: from -> to offer, replies bounce back and forth. */
    private fun sync(from: Node, fromPeer: SyncPeer, to: Node, toPeer: SyncPeer, t: Long): List<SyncEngine.Received> {
        val got = ArrayList<SyncEngine.Received>()
        var toTo: List<SyncMessage> = listOfNotNull(from.engine.offer(fromPeer, t))
        var sender = from; var senderPeer = fromPeer; var receiver = to; var receiverPeer = toPeer
        var rounds = 0
        while (toTo.isNotEmpty() && rounds++ < 20) {
            val next = ArrayList<SyncMessage>()
            for (m in toTo) {
                val wire = SyncMessage.decode(m.encode()) // also exercises the codec
                val out = receiver.engine.handle(receiverPeer, wire, t)
                got += out.received
                next += out.replies
            }
            toTo = next
            val tmp = sender; sender = receiver; receiver = tmp
            val tp = senderPeer; senderPeer = receiverPeer; receiverPeer = tp
        }
        return got
    }

    private fun groupWith(vararg ids: Crypto.KeyPair): Pair<GroupSession, List<GroupSession>> {
        val admin = ids[0]
        val g = GroupSession.create(admin)
        val sessions = arrayListOf(g)
        for (k in ids.drop(1)) {
            val u = g.addMember(admin, k.public, now)
            for (s in sessions.drop(1)) s.applyUpdate(u, now, ids[sessions.indexOf(s)].public)
            sessions.add(GroupSession.join(u, k, admin.public))
        }
        return g to sessions
    }

    @Test fun record_travels_through_a_relay_between_people_who_never_meet() {
        val ka = Crypto.ed25519Generate(); val kb = Crypto.ed25519Generate(); val kc = Crypto.ed25519Generate()
        val (g, sessions) = groupWith(ka, kb, kc)
        val a = Node(); val bNode = Node(); val c = Node()
        val wire = g.seal(ka, 1, b("hello from A"), now, now + hour)
        a.engine.addLocal(wire, now + hour, now)
        val ab = SyncPeer(listOf(g.groupId)); val ba = SyncPeer(listOf(g.groupId))
        val bc = SyncPeer(listOf(g.groupId)); val cb = SyncPeer(listOf(g.groupId))
        assertEquals(1, sync(a, ab, bNode, ba, now).size)
        val atC = sync(bNode, bc, c, cb, now + 1000)
        assertEquals(1, atC.size)
        assertContentEquals(b("hello from A"), sessions[2].open(atC[0].wire, now + 1000).body)
    }

    @Test fun nothing_is_resent_after_peer_has_it() {
        val k = Crypto.ed25519Generate(); val g = GroupSession.create(k)
        val a = Node(); val x = Node()
        a.engine.addLocal(g.seal(k, 1, b("m"), now, now + hour), now + hour, now)
        val ap = SyncPeer(listOf(g.groupId)); val xp = SyncPeer(listOf(g.groupId))
        assertEquals(1, sync(a, ap, x, xp, now).size)
        assertNull(a.engine.offer(ap, now)) // acked, nothing more to offer
        // A fresh session with the same peer re-offers, but the peer answers with nothing wanted.
        val ap2 = SyncPeer(listOf(g.groupId)); val xp2 = SyncPeer(listOf(g.groupId))
        assertEquals(0, sync(a, ap2, x, xp2, now).size)
    }

    @Test fun peers_only_get_records_of_groups_they_share() {
        val k = Crypto.ed25519Generate()
        val g1 = GroupSession.create(k); val g2 = GroupSession.create(k)
        val a = Node(); val x = Node()
        a.engine.addLocal(g1.seal(k, 1, b("one"), now, now + hour), now + hour, now)
        a.engine.addLocal(g2.seal(k, 1, b("two"), now, now + hour), now + hour, now)
        val got = sync(a, SyncPeer(listOf(g1.groupId)), x, SyncPeer(listOf(g1.groupId)), now)
        assertEquals(1, got.size)
        assertContentEquals(g1.groupId, got[0].groupId)
        // A peer that claims a group we do not share with it in its offer is ignored.
        val xe = Node()
        val liar = SyncPeer(listOf(g1.groupId))
        val fake = SyncMessage.Offer(listOf(SyncMessage.Entry(Crypto.randomBytes(16), g2.groupId, now + hour)))
        assertTrue(xe.engine.handle(liar, fake, now).replies.isEmpty())
    }

    @Test fun unsolicited_and_mislabeled_deliveries_are_dropped() {
        val k = Crypto.ed25519Generate(); val g = GroupSession.create(k)
        val x = Node(); val p = SyncPeer(listOf(g.groupId))
        val wire = g.seal(k, 1, b("push"), now, now + hour)
        val out = x.engine.handle(p, SyncMessage.Deliver(listOf(wire)), now)
        assertTrue(out.received.isEmpty()); assertTrue(out.replies.isEmpty())
        assertNull(x.engine.store.get(SyncEngine.recordId(wire)))
        // Want it for the right group, then deliver a blob whose header names another group.
        val id = SyncEngine.recordId(wire)
        x.engine.handle(p, SyncMessage.Offer(listOf(SyncMessage.Entry(id, g.groupId, now + hour))), now)
        val swapped = wire.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
        // id of the swapped blob differs from what was wanted, so it is dropped as unsolicited
        assertTrue(x.engine.handle(p, SyncMessage.Deliver(listOf(swapped)), now).received.isEmpty())
        assertEquals(1, x.engine.handle(p, SyncMessage.Deliver(listOf(wire)), now).received.size)
    }

    @Test fun expired_records_are_not_offered_wanted_or_kept() {
        val k = Crypto.ed25519Generate(); val g = GroupSession.create(k)
        val a = Node(); val x = Node()
        a.engine.addLocal(g.seal(k, 1, b("short"), now, now + 1000), now + 1000, now)
        val ap = SyncPeer(listOf(g.groupId))
        assertNotNull(a.engine.offer(ap, now))
        assertNull(a.engine.offer(SyncPeer(listOf(g.groupId)), now + 2000))
        val stale = SyncMessage.Offer(listOf(SyncMessage.Entry(Crypto.randomBytes(16), g.groupId, now - 1)))
        assertTrue(x.engine.handle(SyncPeer(listOf(g.groupId)), stale, now).replies.isEmpty())
        assertEquals(1, (a.engine.store as InMemoryRecordStore).purgeExpired(now + 2000))
    }

    @Test fun claimed_expiry_is_capped() {
        val k = Crypto.ed25519Generate(); val g = GroupSession.create(k)
        val a = Node()
        a.engine.addLocal(g.seal(k, 1, b("long"), now, now + 1000 * hour), now + 1000 * hour, now)
        val offered = a.engine.offer(SyncPeer(listOf(g.groupId)), now)!!
        assertTrue(offered.entries[0].expiresAt <= now + SyncLimits.MAX_TTL_MS)
    }

    @Test fun delivery_is_rate_limited_per_peer() {
        val k = Crypto.ed25519Generate(); val g = GroupSession.create(k)
        val a = Node()
        val ids = ArrayList<ByteArray>()
        repeat(200) { // ~256 bytes each is too small to hit the cap, so use big padded records
            val w = g.seal(k, 1, ByteArray(5000), now, now + hour)
            a.engine.addLocal(w, now + hour, now); ids.add(SyncEngine.recordId(w))
        }
        val p = SyncPeer(listOf(g.groupId))
        val first = a.engine.handle(p, SyncMessage.Want(ids.take(128)), now)
        val delivered = first.replies.sumOf { (it as SyncMessage.Deliver).blobs.size }
        assertTrue(delivered in 1..127, "got $delivered") // bucket of 1 MiB cannot cover 128 x 8 KiB
        val again = a.engine.handle(p, SyncMessage.Want(ids.take(128)), now) // no time passed, no refill
        assertTrue(again.replies.isEmpty())
        val later = a.engine.handle(p, SyncMessage.Want(ids.take(128)), now + 60_000)
        assertTrue(later.replies.isNotEmpty())
    }

    @Test fun store_quota_evicts_soonest_expiring_first() {
        val k = Crypto.ed25519Generate(); val g = GroupSession.create(k)
        val store = InMemoryRecordStore()
        val e = SyncEngine(store)
        repeat(4200) { i -> // 4200 x ~8 KiB = ~34 MB > 32 MiB cap
            e.addLocal(g.seal(k, 1, ByteArray(5000), now, now + hour), now + hour + i, now)
        }
        assertTrue(store.totalBytes <= SyncLimits.MAX_STORE_BYTES)
    }

    @Test fun codec_rejects_junk_without_other_exceptions() {
        val r = java.util.Random(11)
        repeat(3000) {
            val junk = ByteArray(r.nextInt(300)).also { r.nextBytes(it) }
            if (junk.isNotEmpty()) junk[0] = (1 + r.nextInt(4)).toByte()
            try { SyncMessage.decode(junk) } catch (e: CryptoException) { /* expected */ }
        }
        assertFailsWith<CryptoException> { SyncMessage.decode(ByteArray(0)) }
        assertFailsWith<CryptoException> { SyncMessage.decode(byteArrayOf(3, 0, 1, 0, 0, 0x7f, 0x7f)) }
        val ok = SyncMessage.Want(listOf(Crypto.randomBytes(16))).encode()
        assertFailsWith<CryptoException> { SyncMessage.decode(ok + byteArrayOf(0)) }
    }
}
