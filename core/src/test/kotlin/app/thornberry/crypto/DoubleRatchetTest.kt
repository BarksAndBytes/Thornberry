package app.thornberry.crypto

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class DoubleRatchetTest {
    private val ad = "alice-pub|bob-pub".toByteArray()
    private fun b(s: String) = s.toByteArray()

    private fun pair(): Pair<DoubleRatchet, DoubleRatchet> {
        val sk = Crypto.randomBytes(32)
        val bobKp = Crypto.x25519Generate()
        return DoubleRatchet.initiator(sk, bobKp.public) to DoubleRatchet.responder(sk, bobKp)
    }

    @Test fun ping_pong_both_directions() {
        val (a, bob) = pair()
        repeat(5) { i ->
            assertContentEquals(b("a$i"), bob.decrypt(a.encrypt(b("a$i"), ad), ad))
            assertContentEquals(b("b$i"), a.decrypt(bob.encrypt(b("b$i"), ad), ad))
        }
    }

    @Test fun many_in_a_row_one_direction() {
        val (a, bob) = pair()
        val msgs = (0 until 20).map { a.encrypt(b("m$it"), ad) }
        msgs.forEachIndexed { i, m -> assertContentEquals(b("m$i"), bob.decrypt(m, ad)) }
    }

    @Test fun out_of_order_within_a_chain() {
        val (a, bob) = pair()
        val m = (0 until 5).map { a.encrypt(b("m$it"), ad) }
        for (i in listOf(3, 0, 4, 1, 2)) assertContentEquals(b("m$i"), bob.decrypt(m[i], ad))
    }

    @Test fun dropped_messages_do_not_block_later_ones() {
        val (a, bob) = pair()
        val m = (0 until 6).map { a.encrypt(b("m$it"), ad) }
        assertContentEquals(b("m5"), bob.decrypt(m[5], ad))
        assertContentEquals(b("m0"), bob.decrypt(m[0], ad))
    }

    @Test fun out_of_order_across_a_ratchet_step() {
        val (a, bob) = pair()
        val old1 = a.encrypt(b("old1"), ad)
        val old2 = a.encrypt(b("old2"), ad)
        assertContentEquals(b("old1"), bob.decrypt(old1, ad))
        val reply = bob.encrypt(b("reply"), ad)
        assertContentEquals(b("reply"), a.decrypt(reply, ad))
        val fresh = a.encrypt(b("fresh"), ad)
        assertContentEquals(b("fresh"), bob.decrypt(fresh, ad))
        assertContentEquals(b("old2"), bob.decrypt(old2, ad)) // late message from the previous chain
    }

    @Test fun replay_and_duplicates_are_rejected() {
        val (a, bob) = pair()
        val m0 = a.encrypt(b("x"), ad)
        val m1 = a.encrypt(b("y"), ad)
        bob.decrypt(m0, ad)
        assertFailsWith<CryptoException> { bob.decrypt(m0, ad) }
        bob.decrypt(m1, ad)
        assertFailsWith<CryptoException> { bob.decrypt(m1, ad) }
        val late = a.encrypt(b("late"), ad)
        val later = a.encrypt(b("later"), ad)
        bob.decrypt(later, ad)
        bob.decrypt(late, ad)
        assertFailsWith<CryptoException> { bob.decrypt(late, ad) }
    }

    @Test fun tampering_wrong_context_and_failure_leave_state_unchanged() {
        val (a, bob) = pair()
        val m0 = a.encrypt(b("first"), ad)
        val m1 = a.encrypt(b("second"), ad)
        for (i in m0.indices) {
            val t = m0.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException> { bob.decrypt(t, ad) }
        }
        assertFailsWith<CryptoException> { bob.decrypt(m0, b("other-context")) }
        // After all those failures the session still works, in order and out of order.
        assertContentEquals(b("second"), bob.decrypt(m1, ad))
        assertContentEquals(b("first"), bob.decrypt(m0, ad))
    }

    @Test fun too_many_skipped_messages_rejected() {
        val (a, bob) = pair()
        val first = a.encrypt(b("first"), ad)
        repeat(DoubleRatchet.MAX_SKIP + 5) { a.encrypt(b("x"), ad) }
        val far = a.encrypt(b("far"), ad)
        assertFailsWith<CryptoException> { bob.decrypt(far, ad) }
        assertContentEquals(b("first"), bob.decrypt(first, ad))
    }

    @Test fun responder_cannot_send_before_receiving() {
        val (_, bob) = pair()
        assertFailsWith<CryptoException> { bob.encrypt(b("hi"), ad) }
    }

    @Test fun save_and_restore_mid_conversation() {
        val (a, bob) = pair()
        val m0 = a.encrypt(b("one"), ad)
        val m2skip = a.encrypt(b("two"), ad)
        val m3 = a.encrypt(b("three"), ad)
        bob.decrypt(m0, ad)
        bob.decrypt(m3, ad) // leaves one skipped key stored
        val bob2 = DoubleRatchet.restore(bob.serialize())
        assertContentEquals(b("two"), bob2.decrypt(m2skip, ad))
        val reply = bob2.encrypt(b("back"), ad)
        val a2 = DoubleRatchet.restore(a.serialize())
        assertContentEquals(b("back"), a2.decrypt(reply, ad))
        assertContentEquals(b("next"), bob2.decrypt(a2.encrypt(b("next"), ad), ad))
    }

    @Test fun restore_rejects_garbage() {
        assertFailsWith<CryptoException> { DoubleRatchet.restore(ByteArray(0)) }
        assertFailsWith<CryptoException> { DoubleRatchet.restore(Crypto.randomBytes(300)) }
        val (a, _) = pair()
        val s = a.serialize()
        assertFailsWith<CryptoException> { DoubleRatchet.restore(s.copyOf(s.size - 1)) }
        assertFailsWith<CryptoException> { DoubleRatchet.restore(s + byteArrayOf(0)) }
    }

    @Test fun random_bytes_never_crash_decrypt() {
        val (_, bob) = pair()
        val r = Random(1)
        repeat(2000) {
            val len = r.nextInt(200)
            val junk = ByteArray(len).also { r.nextBytes(it) }
            if (len > 0 && r.nextBoolean()) junk[0] = DoubleRatchet.VERSION
            assertFailsWith<CryptoException> { bob.decrypt(junk, ad) }
        }
    }

    @Test fun randomized_conversation_with_drops_reorders_and_duplicates() {
        val r = Random(42)
        repeat(30) { round ->
            val (a, bob) = pair()
            val side = arrayOf(a, bob)
            val inbox = arrayOf(ArrayList<Pair<String, ByteArray>>(), ArrayList())
            val delivered = HashSet<String>()
            var bobCanSend = false
            repeat(300) { step ->
                if (r.nextInt(3) > 0) {
                    val who = if (bobCanSend) r.nextInt(2) else 0
                    val text = "r$round-s$step-from$who"
                    inbox[1 - who].add(text to side[who].encrypt(b(text), ad))
                } else {
                    val rx = r.nextInt(2) // receiver
                    if (inbox[rx].isNotEmpty()) {
                        val (text, m) = inbox[rx].removeAt(r.nextInt(inbox[rx].size))
                        if (r.nextInt(6) == 0) inbox[rx].add(text to m) // a duplicate stays in the network
                        if (r.nextInt(5) != 0) { // otherwise the copy is dropped
                            if (delivered.add(text)) {
                                assertContentEquals(b(text), side[rx].decrypt(m, ad))
                                if (rx == 1) bobCanSend = true
                            } else {
                                assertFailsWith<CryptoException> { side[rx].decrypt(m, ad) }
                            }
                        }
                    }
                }
            }
            assertEquals(true, bobCanSend)
        }
    }

}
