package app.thornberry.crypto

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingTest {
    private val onionA = "a".repeat(56)
    private val onionB = "b".repeat(56)
    private fun b(s: String) = s.toByteArray()

    private fun pairUp(): Triple<Pairing.Result, Pairing.Result, Pair<Pairing.Local, Pairing.Local>> {
        val a = Pairing.createOffer(Crypto.ed25519Generate(), onionA)
        val bb = Pairing.createOffer(Crypto.ed25519Generate(), onionB)
        val ra = Pairing.complete(a, bb.offer.encode())
        val rb = Pairing.complete(bb, a.offer.encode())
        return Triple(ra, rb, a to bb)
    }

    @Test fun both_sides_agree_and_can_chat() {
        val (ra, rb, _) = pairUp()
        assertEquals(ra.safetyCode, rb.safetyCode)
        assertTrue(Regex("^\\d{3} \\d{3} \\d{3}$").matches(ra.safetyCode))
        assertContentEquals(ra.associatedData, rb.associatedData)
        assertNotEquals(ra.isInitiator, rb.isInitiator)
        assertEquals(onionB, ra.peerOnion)
        assertEquals(onionA, rb.peerOnion)
        val (init, resp) = if (ra.isInitiator) ra to rb else rb to ra
        val m = init.session.encrypt(b("hello"), init.associatedData)
        assertContentEquals(b("hello"), resp.session.decrypt(m, resp.associatedData))
        val r = resp.session.encrypt(b("hi back"), resp.associatedData)
        assertContentEquals(b("hi back"), init.session.decrypt(r, init.associatedData))
    }

    @Test fun different_pairings_have_different_codes() {
        val codes = (0 until 20).map { pairUp().first.safetyCode }.toSet()
        assertTrue(codes.size > 15)
    }

    @Test fun every_flipped_byte_in_an_offer_is_rejected() {
        val offer = Pairing.createOffer(Crypto.ed25519Generate(), onionA).offer.encode()
        for (i in offer.indices) {
            val t = offer.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException>("byte $i") { Pairing.decode(t) }
        }
    }

    @Test fun swapped_pairing_key_by_a_man_in_the_middle_is_rejected() {
        val a = Pairing.createOffer(Crypto.ed25519Generate(), onionA)
        val mallory = Pairing.createOffer(Crypto.ed25519Generate(), onionA)
        val forged = a.offer.encode().also { System.arraycopy(mallory.offer.ephemeralPublic, 0, it, 33, 32) }
        val bob = Pairing.createOffer(Crypto.ed25519Generate(), onionB)
        assertFailsWith<CryptoException> { Pairing.complete(bob, forged) }
    }

    @Test fun wrong_sizes_and_junk_are_rejected_without_other_exceptions() {
        assertFailsWith<CryptoException> { Pairing.decode(ByteArray(0)) }
        val ok = Pairing.createOffer(Crypto.ed25519Generate(), onionA).offer.encode()
        assertFailsWith<CryptoException> { Pairing.decode(ok.copyOf(ok.size - 1)) }
        assertFailsWith<CryptoException> { Pairing.decode(ok + byteArrayOf(0)) }
        val r = Random(7)
        repeat(1000) {
            val junk = ByteArray(Pairing.ENCODED_BYTES).also { r.nextBytes(it) }
            junk[0] = 1
            assertFailsWith<CryptoException> { Pairing.decode(junk) }
        }
    }

    @Test fun cannot_pair_with_yourself_or_a_replayed_own_offer() {
        val a = Pairing.createOffer(Crypto.ed25519Generate(), onionA)
        assertFailsWith<CryptoException> { Pairing.complete(a, a.offer.encode()) }
    }

    @Test fun bad_onion_address_rejected_when_creating() {
        assertFailsWith<IllegalArgumentException> { Pairing.createOffer(Crypto.ed25519Generate(), "short") }
        assertFailsWith<IllegalArgumentException> { Pairing.createOffer(Crypto.ed25519Generate(), "A".repeat(56)) }
    }

    @Test fun an_eavesdropper_with_both_qr_codes_cannot_join() {
        val (ra, _, offers) = pairUp()
        val eve = Pairing.createOffer(Crypto.ed25519Generate(), onionA)
        // Eve knows both public offers but has her own ephemeral key: she gets a different session.
        val eveResult = Pairing.complete(eve, offers.second.offer.encode())
        assertNotEquals(ra.safetyCode, eveResult.safetyCode)
    }
}
