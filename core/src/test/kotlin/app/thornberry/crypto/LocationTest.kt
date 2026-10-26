package app.thornberry.crypto

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationTest {
    private val now = 1_000_000_000_000L
    private val min = 60_000L
    private fun fix(lat: Double = 40.7128, lon: Double = -74.0060, acc: Int = 12, t: Long = now, speed: Float? = null, bat: Int? = null) =
        LocationFix(lat, lon, acc, t, speed, bat)

    // ---------- payload ----------

    @Test fun exact_precision_round_trips_to_microdegrees() {
        val p = LocationPayload.decode(LocationPayload.encode(fix(speed = 1.5f, bat = 80), Precision.EXACT), now)
        assertTrue(abs(p.lat - 40.7128) < 1e-6 && abs(p.lon + 74.0060) < 1e-6)
        assertEquals(12, p.accuracyM); assertEquals(150, p.speedCmS); assertEquals(80, p.batteryPct); assertEquals(now, p.timestamp)
    }

    @Test fun coarser_precision_hides_the_exact_position_and_widens_accuracy() {
        val exact = fix(40.712_876, -74.006_049)
        for ((prec, grid) in listOf(Precision.BLOCK to 0.001, Precision.AREA to 0.01, Precision.CITY to 0.1)) {
            val p = LocationPayload.decode(LocationPayload.encode(exact, prec), now)
            assertTrue(abs(p.lat - exact.lat) <= grid / 2 + 1e-6, "$prec lat")
            assertTrue(abs(p.lon - exact.lon) <= grid / 2 + 1e-6, "$prec lon")
            assertTrue(p.accuracyM >= prec.minAccuracyM)
            assertEquals(0.0, (p.lat / grid) - Math.round(p.lat / grid).toDouble(), 1e-4) // on the grid
            assertNull(p.speedCmS); assertNull(p.batteryPct) // optional fields are off unless given
        }
        // two different exact positions in the same grid cell become the same shared position
        val a = LocationPayload.encode(fix(40.7121, -74.0061), Precision.AREA)
        val b = LocationPayload.encode(fix(40.7139, -74.0072), Precision.AREA)
        assertTrue(a.contentEquals(b))
    }

    @Test fun poles_and_date_line_stay_in_range() {
        for ((la, lo) in listOf(90.0 to 180.0, -90.0 to -180.0, 89.9999 to 179.9999)) {
            for (prec in Precision.values()) LocationPayload.decode(LocationPayload.encode(fix(la, lo), prec), now)
        }
        assertFailsWith<IllegalArgumentException> { LocationPayload.encode(fix(91.0, 0.0), Precision.EXACT) }
    }

    @Test fun decode_is_strict() {
        val ok = LocationPayload.encode(fix(speed = 2f, bat = 50), Precision.EXACT)
        for (n in 0 until ok.size) assertFailsWith<CryptoException>("trunc $n") { LocationPayload.decode(ok.copyOf(n), now) }
        assertFailsWith<CryptoException> { LocationPayload.decode(ok + byteArrayOf(0), now) }
        assertFailsWith<CryptoException> { LocationPayload.decode(ok.copyOf().also { it[0] = 2 }, now) } // version
        assertFailsWith<CryptoException> { LocationPayload.decode(ok.copyOf().also { it[11] = 0x80.toByte() }, now) } // unknown flag
        val bad = ok.copyOf().also { it[1] = 0x7f } // latitude far out of range
        assertFailsWith<CryptoException> { LocationPayload.decode(bad, now) }
        val r = java.util.Random(9)
        repeat(3000) {
            try { LocationPayload.decode(ByteArray(r.nextInt(20)).also { b -> r.nextBytes(b) }, now) } catch (e: CryptoException) {}
        }
    }

    // ---------- policy ----------

    @Test fun policy_first_fix_moves_heartbeat_and_poor_fixes() {
        val last = LocationPolicy.Sent(40.0, -74.0, now)
        assertTrue(LocationPolicy.shouldSend(null, fix(), now, 90))
        // stationary: nothing until the heartbeat
        assertFalse(LocationPolicy.shouldSend(last, fix(40.0, -74.0), now + 5 * min, 90))
        assertTrue(LocationPolicy.shouldSend(last, fix(40.0, -74.0), now + 10 * min, 90))
        // moved 300 m: only after the minimum moving interval
        val moved = fix(40.0027, -74.0)
        assertFalse(LocationPolicy.shouldSend(last, moved, now + 1 * min, 90))
        assertTrue(LocationPolicy.shouldSend(last, moved, now + 3 * min, 90))
        // low battery stretches the moving interval, but never past the 10 minute heartbeat
        assertFalse(LocationPolicy.shouldSend(last, moved, now + 3 * min, 10))
        assertTrue(LocationPolicy.shouldSend(last, moved, now + 5 * min, 10))
        assertTrue(LocationPolicy.shouldSend(last, fix(40.0, -74.0), now + 10 * min, 5))
        // a poor fix does not trigger an early update, but the heartbeat still goes out
        assertFalse(LocationPolicy.shouldSend(last, fix(40.0027, -74.0, acc = 2000), now + 4 * min, 90))
        assertTrue(LocationPolicy.shouldSend(last, fix(40.0027, -74.0, acc = 2000), now + 11 * min, 90))
        // small jitter (30 m) is not movement
        assertFalse(LocationPolicy.shouldSend(last, fix(40.00027, -74.0), now + 4 * min, 90))
        assertTrue(LocationPolicy.nextSampleDelayMs(false, 90) < LocationPolicy.HEARTBEAT_MS)
        assertTrue(LocationPolicy.nextSampleDelayMs(true, 5) < LocationPolicy.HEARTBEAT_MS)
    }

    @Test fun distance_is_about_right() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 300.0)
        assertEquals(0.0, Geo.distanceM(40.0, -74.0, 40.0, -74.0), 0.001)
    }

    // ---------- board ----------

    private fun pt(t: Long, lat: Int = 1) = LocationPoint(lat, 2, 10, t, null, null)

    @Test fun board_keeps_newest_trims_trail_and_expires() {
        val b = LocationBoard()
        assertTrue(b.update("g", "s", pt(now)))
        assertFalse(b.update("g", "s", pt(now - 1000))) // late arrival does not replace newest
        assertTrue(b.update("g", "s", pt(now + 1000)))
        assertEquals(now + 1000, b.forGroup("g").single().latest!!.timestamp)
        assertEquals(3, b.forGroup("g").single().trail.size)
        repeat(400) { b.update("g", "s", pt(now + 2000 + it)) }
        assertTrue(b.forGroup("g").single().trail.size <= LocationBoard.TRAIL_MAX_POINTS)
        b.update("g", "old", pt(now - 25 * 3600_000L))
        b.purge(now + 2 * 3600_000L)
        assertNotNull(b.forGroup("g").firstOrNull { it.sender == "s" })
        b.purge(now + 30 * 3600_000L)
        assertTrue(b.forGroup("g").isEmpty())
        // pause state ordering
        assertTrue(b.setPaused("g", "s", true, now)); assertFalse(b.setPaused("g", "s", false, now - 5))
        assertTrue(b.forGroup("g").single().paused)
    }

    @Test fun board_serializes_and_rejects_garbage() {
        val b = LocationBoard()
        b.update("g", "s", LocationPoint(5, 6, 7, now, 100, 40)); b.setPaused("g", "t", true, now)
        val c = LocationBoard().also { it.restoreFrom(b.serialize()) }
        assertEquals(40, c.forGroup("g").first { it.sender == "s" }.latest!!.batteryPct)
        assertTrue(c.forGroup("g").first { it.sender == "t" }.paused)
        assertFailsWith<CryptoException> { LocationBoard().restoreFrom(ByteArray(7)) }
    }

    // ---------- engine integration (three phones, relay) ----------

    private class Phone(val onion: String) {
        var engine = ChatEngine(Crypto.ed25519Generate())
        val events = ArrayList<ChatEngine.Event>()
    }

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

    private fun triangle(): Triple<Phone, Phone, Phone> {
        val a = Phone("a".repeat(56)); val b = Phone("b".repeat(56)); val c = Phone("c".repeat(56))
        val all = listOf(a, b, c)
        pair(a, b); pair(c, b); run(all, now)
        val gid = b.engine.createGroup("Family")
        for (x in listOf(a, c)) { b.engine.inviteToGroup(gid, b.engine.contactList().first { it.onion == x.onion }.id, now); run(all, now) }
        return Triple(a, b, c)
    }

    private fun gid(p: Phone) = p.engine.groupList().single().id
    private fun senderOf(p: Phone) = p.engine.identityPublic.joinToString("") { "%02x".format(it) }

    @Test fun location_reaches_everyone_in_the_group_at_the_chosen_precision() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        a.engine.shareLocation(gid(a), fix(40.712_876, -74.006_049), Precision.AREA, now + min)
        run(all, now + min)
        val atC = c.events.filterIsInstance<ChatEngine.Event.LocationUpdate>().single()
        assertEquals(senderOf(a), atC.sender)
        assertTrue(abs(atC.point.lat - 40.71) < 1e-6 && abs(atC.point.lon + 74.01) < 1e-6) // ~1 km grid: 40.71, -74.01
        assertTrue(atC.point.accuracyM >= 1500)
        val board = c.engine.locations(gid(c)).first { it.sender == senderOf(a) }
        assertEquals(now + min, board.latest!!.timestamp)
    }

    @Test fun pause_and_resume_are_visible_and_old_positions_do_not_replace_new_ones() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        a.engine.shareLocation(gid(a), fix(1.0, 1.0), Precision.EXACT, now + min); run(all, now + min)
        a.engine.shareLocation(gid(a), fix(2.0, 2.0), Precision.EXACT, now + 2 * min); run(all, now + 2 * min)
        assertEquals(2.0, c.engine.locations(gid(c)).first { it.sender == senderOf(a) }.latest!!.lat, 1e-6)
        a.engine.setSharingPaused(gid(a), true, now + 3 * min); run(all, now + 3 * min)
        assertTrue(c.engine.locations(gid(c)).first { it.sender == senderOf(a) }.paused)
        a.engine.setSharingPaused(gid(a), false, now + 4 * min); run(all, now + 4 * min)
        assertFalse(c.engine.locations(gid(c)).first { it.sender == senderOf(a) }.paused)
    }

    @Test fun removed_member_stops_receiving_locations_and_loses_the_board() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        a.engine.shareLocation(gid(a), fix(1.0, 1.0), Precision.EXACT, now + min); run(all, now + min)
        b.engine.removeFromGroup(gid(b), c.engine.identityPublic, now + 2 * min); run(all, now + 2 * min)
        assertTrue(c.engine.locations(gid(c)).isEmpty())
        val before = c.events.filterIsInstance<ChatEngine.Event.LocationUpdate>().size
        a.engine.shareLocation(gid(a), fix(3.0, 3.0), Precision.EXACT, now + 3 * min); run(all, now + 3 * min)
        assertEquals(before, c.events.filterIsInstance<ChatEngine.Event.LocationUpdate>().size)
        assertEquals(3.0, b.engine.locations(gid(b)).first { it.sender == senderOf(a) }.latest!!.lat, 1e-6)
    }

    @Test fun snapshot_keeps_the_board_and_old_snapshots_still_restore() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        a.engine.shareLocation(gid(a), fix(5.0, 6.0), Precision.EXACT, now + min); run(all, now + min)
        val restored = ChatEngine.restore(c.engine.snapshot())
        assertEquals(5.0, restored.locations(restored.groupList().single().id).first { it.sender == senderOf(a) }.latest!!.lat, 1e-6)
        val old = ChatEngine.restore(c.engine.snapshot(includeBoard = false)) // format used by app versions before location
        assertEquals(1, old.groupList().size)
        assertTrue(old.locations(old.groupList().single().id).isEmpty())
    }

    // ---------- route history (track records) ----------

    private fun route(from: Int, to: Int) = (from..to).map { i -> fix(40.0 + i * 0.001, -74.0 + i * 0.001, t = now + i * 30_000L) }

    @Test fun track_round_trips_and_decode_is_strict() {
        val fixes = route(0, 9)
        val body = LocationPayload.encodeTrack(fixes, Precision.EXACT)
        val pts = LocationPayload.decodeTrack(body)
        assertEquals(10, pts.size)
        assertEquals(now + 9 * 30_000L, pts.last().timestamp)
        assertEquals(40.009, pts.last().lat, 1e-6)
        for (n in 0 until body.size) assertFailsWith<CryptoException>("trunc $n") { LocationPayload.decodeTrack(body.copyOf(n)) }
        assertFailsWith<CryptoException> { LocationPayload.decodeTrack(body + byteArrayOf(0)) }
        assertFailsWith<CryptoException> { LocationPayload.decodeTrack(body.copyOf().also { it[0] = 2 }) }
        // a zero time step is refused (times must increase)
        assertFailsWith<CryptoException> { LocationPayload.decodeTrack(body.copyOf().also { for (k in 0 until 4) it[31 + k] = 0 }) }
        assertFailsWith<IllegalArgumentException> { LocationPayload.encodeTrack(emptyList(), Precision.EXACT) }
        assertFailsWith<IllegalArgumentException> { LocationPayload.encodeTrack(route(0, 200), Precision.EXACT) }
        assertFailsWith<IllegalArgumentException> { LocationPayload.encodeTrack(listOf(fix(t = now + 5), fix(t = now)), Precision.EXACT) }
        // coarse precision applies to every point
        val coarse = LocationPayload.decodeTrack(LocationPayload.encodeTrack(fixes, Precision.AREA))
        assertTrue(coarse.all { abs(it.lat / 0.01 - Math.round(it.lat / 0.01)) < 1e-4 && it.accuracyM >= 1500 })
        val r = java.util.Random(4)
        repeat(3000) { try { LocationPayload.decodeTrack(ByteArray(r.nextInt(60)).also { b -> r.nextBytes(b) }) } catch (e: CryptoException) {} }
        assertTrue(LocationPayload.encodeTrack(route(0, 199), Precision.EXACT).size < 3500) // fits a 4 KiB record
    }

    @Test fun a_track_gives_receivers_the_whole_route_and_missed_batches_are_backfilled() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        val t1 = now + 5 * min
        // first batch (points 0..9) reaches everyone
        a.engine.shareTrack(gid(a), route(0, 9), Precision.EXACT, t1); run(all, t1)
        val atC = { c.engine.locations(gid(c)).first { it.sender == senderOf(a) } }
        assertEquals(10, atC().trail.size)
        // C goes offline: it misses the batch with points 10..19 sent to B only
        val t2 = t1 + 5 * min
        a.engine.shareTrack(gid(a), route(0, 19), Precision.EXACT, t2); run(listOf(a, b), t2)
        assertEquals(10, atC().trail.size)
        // the next batch repeats recent points, so C ends up with the complete, ordered route and no duplicates
        val t3 = t2 + 5 * min
        a.engine.shareTrack(gid(a), route(0, 29), Precision.EXACT, t3); run(all, t3)
        val trail = atC().trail
        assertEquals(30, trail.size)
        assertEquals(trail.map { it.timestamp }.sorted(), trail.map { it.timestamp })
        assertEquals(now + 29 * 30_000L, atC().latest!!.timestamp)
        assertEquals(30, b.engine.locations(gid(b)).first { it.sender == senderOf(a) }.trail.size)
        assertEquals(30, a.engine.locations(gid(a)).first { it.sender == senderOf(a) }.trail.size)
    }

    @Test fun an_older_batch_arriving_late_fills_the_gap_without_moving_the_latest_position() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        val t = now + 5 * min
        a.engine.shareTrack(gid(a), route(10, 19), Precision.EXACT, t); run(all, t)
        a.engine.shareTrack(gid(a), route(0, 9), Precision.EXACT, t + 1000); run(all, t + 1000) // late
        val e = c.engine.locations(gid(c)).first { it.sender == senderOf(a) }
        assertEquals(20, e.trail.size)
        assertEquals(now + 19 * 30_000L, e.latest!!.timestamp)
    }

    @Test fun a_track_from_the_future_is_refused() {
        val (a, b, c) = triangle(); val all = listOf(a, b, c)
        val t = now + 5 * min
        val future = (0..3).map { fix(t = t + 3600_000L + it * 30_000L) }
        a.engine.shareTrack(gid(a), future, Precision.EXACT, t); run(all, t)
        assertTrue(c.engine.locations(gid(c)).none { it.sender == senderOf(a) })
    }
}
