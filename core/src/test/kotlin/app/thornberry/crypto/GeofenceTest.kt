package app.thornberry.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeofenceTest {
    private val now = 1_000_000_000_000L
    private val sec = 1000L
    private val home = 40.0 to -74.0
    private val bob = "bb".repeat(32)
    private val cat = "cc".repeat(32)

    // one degree of latitude is about 111,195 m: 0.001 is about 111 m
    private fun at(offsetLat: Double, t: Long, acc: Int = 10) =
        LocationPoint(Math.round((home.first + offsetLat) * 1e6).toInt(), Math.round(home.second * 1e6).toInt(), acc, t, null, null)
    private fun inside(t: Long, acc: Int = 10) = at(0.0, t, acc)
    private fun outside(t: Long, acc: Int = 10) = at(0.01, t, acc) // ~1.1 km away

    private fun book(radius: Int = 200, watch: Set<String>? = null) =
        PlaceBook().also { it.add("Home", home.first, home.second, radius, watch) }

    @Test fun first_sighting_sets_state_without_an_alert_then_leaving_and_arriving_are_found() {
        val b = book()
        assertTrue(b.feed(bob, listOf(inside(now)), now).isEmpty()) // already home: no "arrived"
        assertTrue(b.feed(bob, listOf(outside(now + 15 * sec)), now + 15 * sec).isEmpty()) // one outside point is not enough
        val left = b.feed(bob, listOf(outside(now + 30 * sec)), now + 30 * sec)
        assertEquals(1, left.size); assertFalse(left[0].arrived); assertEquals("Home", left[0].placeName)
        assertEquals(now + 15 * sec, left[0].atMs) // time of the first point on the new side
        assertTrue(left[0].notify)
        val back = b.feed(bob, listOf(inside(now + 600 * sec), inside(now + 615 * sec)), now + 615 * sec)
        assertEquals(1, back.size); assertTrue(back[0].arrived)
        assertEquals(2, b.events().size); assertTrue(b.events()[0].arrived) // newest first
    }

    @Test fun a_single_bad_point_does_not_cause_an_alert() {
        val b = book()
        b.feed(bob, listOf(inside(now)), now)
        val ev = b.feed(bob, listOf(outside(now + 15 * sec), inside(now + 30 * sec), inside(now + 45 * sec), outside(now + 60 * sec), inside(now + 75 * sec)), now + 75 * sec)
        assertTrue(ev.isEmpty())
    }

    @Test fun unsure_and_coarse_positions_never_alert() {
        val b = book(radius = 200)
        b.feed(bob, listOf(inside(now)), now)
        // someone sharing at city level: stated accuracy 15 km, so we cannot tell inside from outside
        val coarse = (1..10).map { outside(now + it * 15 * sec, acc = 15_000) }
        assertTrue(b.feed(bob, coarse, now + 200 * sec).isEmpty())
        // just outside the edge but within the accuracy margin: unsure
        val edge = (1..10).map { at(0.0021, now + 300 * sec + it * 15 * sec, acc = 100) } // ~233 m away, margin 50 m: could be either side
        assertTrue(b.feed(bob, edge, now + 500 * sec).isEmpty())
    }

    @Test fun each_position_is_processed_once_and_late_old_batches_do_not_replay() {
        val b = book()
        b.feed(bob, listOf(inside(now)), now)
        val trip = listOf(outside(now + 15 * sec), outside(now + 30 * sec))
        assertEquals(1, b.feed(bob, trip, now + 30 * sec).size)
        assertTrue(b.feed(bob, trip, now + 40 * sec).isEmpty()) // same positions again
        assertTrue(b.feed(bob, listOf(inside(now + 5 * sec), inside(now + 6 * sec)), now + 50 * sec).isEmpty()) // older batch arriving late
    }

    @Test fun overlapping_route_batches_do_not_double_count() {
        val b = book()
        val all = (0..9).map { if (it < 3) inside(now + it * 15 * sec) else outside(now + it * 15 * sec) }
        b.feed(bob, listOf(all[0]), now)
        assertEquals(1, b.feed(bob, all.take(5), now + 60 * sec).size) // in, in, out, out: left
        assertTrue(b.feed(bob, all.drop(2), now + 150 * sec).isEmpty()) // overlaps the first batch
        assertEquals(1, b.events().size)
    }

    @Test fun old_events_are_logged_but_do_not_notify() {
        val b = book()
        b.feed(bob, listOf(inside(now)), now)
        val ev = b.feed(bob, listOf(outside(now + 15 * sec), outside(now + 30 * sec)), now + 3600_000L) // seen an hour later
        assertEquals(1, ev.size); assertFalse(ev[0].notify)
        assertEquals(1, b.events().size)
    }

    @Test fun only_watched_people_are_checked() {
        val b = book(watch = setOf(cat))
        b.feed(bob, listOf(inside(now)), now)
        assertTrue(b.feed(bob, listOf(outside(now + 15 * sec), outside(now + 30 * sec)), now + 30 * sec).isEmpty())
        b.feed(cat, listOf(inside(now)), now)
        assertEquals(1, b.feed(cat, listOf(outside(now + 15 * sec), outside(now + 30 * sec)), now + 30 * sec).size)
    }

    @Test fun adding_a_place_later_does_not_replay_history_and_changing_its_size_resets_state() {
        val b = PlaceBook()
        b.feed(bob, listOf(outside(now)), now) // before any place exists
        val p = b.add("School", home.first, home.second, 100)
        b.feed(bob, listOf(inside(now + 15 * sec)), now + 15 * sec) // first confident position: silent
        assertTrue(b.feed(bob, listOf(inside(now + 30 * sec)), now + 30 * sec).isEmpty())
        b.update(p.id, "School", 300, null)
        assertTrue(b.feed(bob, listOf(outside(now + 45 * sec), outside(now + 60 * sec)), now + 60 * sec).isEmpty()) // state was reset: silent restart
    }

    @Test fun validation_limits_and_removal() {
        val b = PlaceBook()
        assertFailsWith<CryptoException> { b.add("", 1.0, 1.0, 100) }
        assertFailsWith<CryptoException> { b.add("x".repeat(61), 1.0, 1.0, 100) }
        assertFailsWith<CryptoException> { b.add("A", 91.0, 1.0, 100) }
        assertFailsWith<CryptoException> { b.add("A", 1.0, 1.0, 10) }
        assertFailsWith<CryptoException> { b.add("A", 1.0, 1.0, 100_000) }
        repeat(PlaceBook.MAX_PLACES) { b.add("P$it", 1.0, 1.0, 100) }
        assertFailsWith<CryptoException> { b.add("one too many", 1.0, 1.0, 100) }
        val id = b.list().first().id
        b.remove(id)
        assertEquals(PlaceBook.MAX_PLACES - 1, b.list().size)
    }

    @Test fun serialization_round_trips_and_rejects_garbage() {
        val b = book(watch = setOf(bob, cat))
        b.feed(bob, listOf(inside(now)), now)
        b.feed(bob, listOf(outside(now + 15 * sec), outside(now + 30 * sec)), now + 30 * sec)
        val c = PlaceBook.restore(b.serialize())
        assertEquals(1, c.list().size); assertEquals(setOf(bob, cat), c.list()[0].watch); assertEquals(1, c.events().size)
        // restored state continues correctly: coming back needs two points
        assertTrue(c.feed(bob, listOf(inside(now + 100 * sec)), now + 100 * sec).isEmpty())
        assertEquals(1, c.feed(bob, listOf(inside(now + 115 * sec)), now + 115 * sec).size)
        val data = b.serialize()
        for (n in 0 until data.size) assertFailsWith<CryptoException>("trunc $n") { PlaceBook.restore(data.copyOf(n)) }
        assertFailsWith<CryptoException> { PlaceBook.restore(data + byteArrayOf(0)) }
        assertFailsWith<CryptoException> { PlaceBook.restore(data.copyOf().also { it[0] = 9 }) }
        val r = java.util.Random(11)
        repeat(2000) { try { PlaceBook.restore(ByteArray(r.nextInt(80)).also { x -> r.nextBytes(x) }) } catch (e: CryptoException) {} }
    }
}
