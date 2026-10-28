package app.thornberry.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedPlacesTest {
    private val now = 1_000_000_000_000L
    private val min = 60_000L

    private fun sp(id: String = "01".repeat(8), name: String = "Home", at: Long = now, editor: String = "aa".repeat(8), deleted: Boolean = false, r: Int = 200) =
        SharedPlace(id, name, 40_000_000, -74_000_000, r, deleted, at, editor)

    @Test fun last_writer_wins_and_deletions_stick() {
        val b = SharedPlaces()
        assertTrue(b.put(sp()))
        assertFalse(b.put(sp(name = "Older", at = now - 1)))
        assertTrue(b.put(sp(name = "Newer", at = now + 1)))
        assertEquals("Newer", b.live().single().name)
        // same time: the higher editor id wins, so every phone ends up with the same version
        assertTrue(b.put(sp(name = "Tie", at = now + 1, editor = "bb".repeat(8))))
        assertFalse(b.put(sp(name = "TieLow", at = now + 1, editor = "00".repeat(8))))
        assertTrue(b.put(sp(at = now + 2, deleted = true)))
        assertTrue(b.live().isEmpty())
        assertFalse(b.put(sp(name = "Old copy", at = now + 1))) // an old copy cannot bring it back
        b.prune(now + SharedPlaces.TOMBSTONE_KEEP_MS + 10)
        assertTrue(b.all().isEmpty())
    }

    @Test fun limits_are_enforced() {
        val b = SharedPlaces()
        repeat(SharedPlaces.MAX_LIVE) { b.put(sp(id = "%016x".format(it))) }
        assertFalse(b.put(sp(id = "ff".repeat(8))))
        assertFailsWith<CryptoException> { b.put(sp(name = "")) }
        assertFailsWith<CryptoException> { b.put(sp(r = 10)) }
        assertFailsWith<CryptoException> { b.put(sp(id = "xyz")) }
        assertFailsWith<CryptoException> { b.put(sp(name = "é".repeat(61))) } // 122 bytes
    }

    @Test fun encoding_round_trips_and_decode_is_strict() {
        val list = listOf(sp(), sp(id = "02".repeat(8), name = "School 🏫"), sp(id = "03".repeat(8), deleted = true))
        val body = SharedPlaces.encode(list)
        val back = SharedPlaces.decode(body)
        assertEquals(listOf("Home", "School 🏫", ""), back.map { it.name })
        assertTrue(back[2].deleted)
        for (n in 0 until body.size) assertFailsWith<CryptoException>("trunc $n") { SharedPlaces.decode(body.copyOf(n)) }
        assertFailsWith<CryptoException> { SharedPlaces.decode(body + byteArrayOf(0)) }
        assertFailsWith<CryptoException> { SharedPlaces.decode(body.copyOf().also { it[0] = 7 }) }
        assertFailsWith<CryptoException> { SharedPlaces.decode(SharedPlaces.encode(listOf(sp(), sp()))) } // duplicate id
        val r = java.util.Random(5)
        repeat(3000) { try { SharedPlaces.decode(ByteArray(r.nextInt(80)).also { x -> r.nextBytes(x) }) } catch (e: CryptoException) {} }
        // the largest allowed list still fits in one record
        val big = (0 until SharedPlaces.MAX_LIVE).map { sp(id = "%016x".format(it), name = "x".repeat(SharedPlaces.MAX_NAME_BYTES)) } +
            (0 until SharedPlaces.MAX_ENTRIES - SharedPlaces.MAX_LIVE).map { sp(id = "%016x".format(1000 + it), deleted = true) }
        assertTrue(SharedPlaces.encode(big).size < 7800)
    }

    // ---------- in a group (three phones, relaying) ----------

    private class Phone(val onion: String) { val engine = ChatEngine(Crypto.ed25519Generate()); val events = ArrayList<ChatEngine.Event>() }

    private fun run(phones: List<Phone>, t: Long) {
        var progress = true; var guard = 0
        while (progress && guard++ < 200) {
            progress = false
            for (p in phones) for (o in p.engine.pendingFrames()) {
                val to = phones.firstOrNull { it.onion == o.toOnion } ?: continue
                p.engine.markDelivered(o); to.engine.receiveFrame(o.frame, t); progress = true
            }
            phones.forEach { it.events += it.engine.drainEvents() }
        }
    }

    private fun pair(a: Phone, b: Phone) {
        val la = a.engine.beginPairing(a.onion); val lb = b.engine.beginPairing(b.onion)
        a.engine.completePairing(la, lb.offer.encode(), "x"); b.engine.completePairing(lb, la.offer.encode(), "y")
    }

    /** b is admin; a and c are each paired only with b (b relays). */
    private fun triangle(): Triple<Phone, Phone, Phone> {
        val a = Phone("a".repeat(56)); val b = Phone("b".repeat(56)); val c = Phone("c".repeat(56))
        val all = listOf(a, b, c)
        pair(a, b); pair(c, b); run(all, now)
        val gid = b.engine.createGroup("Family")
        for (x in listOf(a, c)) { b.engine.inviteToGroup(gid, b.engine.contactList().first { it.onion == x.onion }.id, now); run(all, now) }
        return Triple(a, b, c)
    }

    private fun gid(p: Phone) = p.engine.groupList().single().id

    @Test fun any_member_adds_edits_and_deletes_and_everyone_sees_it() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        val t1 = now + min
        val id = a.engine.saveSharedPlace(gid(a), null, "School", 40.0, -74.0, 300, t1); run(all, t1)
        assertEquals("School", c.engine.sharedPlaces(gid(c)).single().name) // relayed through b
        assertTrue(c.events.any { it is ChatEngine.Event.PlacesChanged })
        val t2 = t1 + min
        c.engine.saveSharedPlace(gid(c), id, "Big school", 40.001, -74.0, 500, t2); run(all, t2) // a different member edits it
        val atA = a.engine.sharedPlaces(gid(a)).single()
        assertEquals("Big school", atA.name); assertEquals(500, atA.radiusM); assertEquals(40.001, atA.lat, 1e-6)
        val t3 = t2 + min
        b.engine.deleteSharedPlace(gid(b), id, t3); run(all, t3)
        assertTrue(a.engine.sharedPlaces(gid(a)).isEmpty()); assertTrue(c.engine.sharedPlaces(gid(c)).isEmpty())
    }

    @Test fun a_newcomer_receives_the_existing_places() {
        val a = Phone("a".repeat(56)); val b = Phone("b".repeat(56)); val d = Phone("d".repeat(56))
        val all = listOf(a, b, d)
        pair(a, b); pair(d, b); run(all, now)
        val g = b.engine.createGroup("Family")
        b.engine.inviteToGroup(g, b.engine.contactList().first { it.onion == a.onion }.id, now); run(all, now)
        a.engine.saveSharedPlace(g, null, "Home", 40.0, -74.0, 200, now + min); run(all, now + min)
        b.engine.inviteToGroup(g, b.engine.contactList().first { it.onion == d.onion }.id, now + 2 * min); run(all, now + 2 * min)
        assertEquals("Home", d.engine.sharedPlaces(g).single().name)
    }

    @Test fun a_member_who_missed_everything_catches_up_from_the_daily_resend() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        a.engine.saveSharedPlace(gid(a), null, "Home", 40.0, -74.0, 200, now + min)
        run(listOf(a, b), now + min) // c is away
        // two days later the original record has expired, but b re-sends the list
        val later = now + 2 * 24 * 3600_000L
        b.engine.syncAll(later); run(all, later)
        assertEquals("Home", c.engine.sharedPlaces(gid(c)).single().name)
    }

    @Test fun edits_dated_in_the_future_are_refused() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        a.engine.saveSharedPlace(gid(a), null, "Future", 40.0, -74.0, 200, now + 3600_000L) // claims an hour ahead
        run(all, now + min) // receivers' clocks say now + 1 min
        assertTrue(c.engine.sharedPlaces(gid(c)).isEmpty())
    }

    @Test fun removed_member_loses_the_places_and_snapshots_keep_them() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        a.engine.saveSharedPlace(gid(a), null, "Home", 40.0, -74.0, 200, now + min); run(all, now + min)
        val restored = ChatEngine.restore(c.engine.snapshot())
        assertEquals("Home", restored.sharedPlaces(restored.groupList().single().id).single().name)
        b.engine.removeFromGroup(gid(b), c.engine.identityPublic, now + 2 * min); run(all, now + 2 * min)
        assertTrue(c.engine.sharedPlaces(gid(c)).isEmpty())
        a.engine.saveSharedPlace(gid(a), null, "School", 41.0, -74.0, 200, now + 3 * min); run(all, now + 3 * min)
        assertTrue(c.engine.sharedPlaces(gid(c)).isEmpty())
        assertEquals(2, b.engine.sharedPlaces(gid(b)).size)
    }
}
