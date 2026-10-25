package app.thornberry.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** Known-answer tests from the published RFCs and drafts. Expected values must never be edited to match output. */
class CryptoTest {

    @Test fun x25519_rfc7748_section6_1() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertContentEquals(alicePub, Crypto.x25519PublicFor(alicePriv))
        assertContentEquals(bobPub, Crypto.x25519PublicFor(bobPriv))
        assertContentEquals(shared, Crypto.x25519Agree(alicePriv, bobPub))
        assertContentEquals(shared, Crypto.x25519Agree(bobPriv, alicePub))
    }

    @Test fun x25519_rejects_low_order_point() {
        val priv = Crypto.x25519Generate().private
        assertFailsWith<Exception> { Crypto.x25519Agree(priv, ByteArray(32)) }
    }

    @Test fun ed25519_rfc8032_test1_empty_message() {
        val priv = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        assertContentEquals(pub, Crypto.ed25519PublicFor(priv))
        assertContentEquals(sig, Crypto.sign(priv, ByteArray(0)))
        assertTrue(Crypto.verify(pub, ByteArray(0), sig))
    }

    @Test fun ed25519_rejects_tampering() {
        val kp = Crypto.ed25519Generate()
        val msg = "location record".toByteArray()
        val sig = Crypto.sign(kp.private, msg)
        assertTrue(Crypto.verify(kp.public, msg, sig))
        assertFalse(Crypto.verify(kp.public, "location recorD".toByteArray(), sig))
        val bad = sig.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Crypto.verify(kp.public, msg, bad))
        assertFalse(Crypto.verify(kp.public, msg, sig.copyOf(10)))
        assertFalse(Crypto.verify(Crypto.ed25519Generate().public, msg, sig))
    }

    @Test fun hkdf_rfc5869_case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = hex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        assertContentEquals(okm, Crypto.hkdf(ikm, salt, info, 42))
    }

    @Test fun xchacha20poly1305_draft_irtf_cfrg_xchacha_A31() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = ByteArray(24) { (0x40 + it).toByte() }
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray()
        val sealed = Crypto.aeadEncrypt(key, nonce, aad, pt)
        assertEquals(pt.size + 16, sealed.size)
        assertEquals("c0875924c1c7987947deafd8780acf49", sealed.copyOfRange(pt.size, sealed.size).hex())
        assertEquals("bd6d179d3e83d43b9576579493c0e939", sealed.copyOf(16).hex())
        assertContentEquals(pt, Crypto.aeadDecrypt(key, nonce, aad, sealed))
    }

    @Test fun aead_rejects_tampering_and_wrong_context() {
        val key = Crypto.randomBytes(32)
        val nonce = Crypto.randomBytes(24)
        val aad = "group-1/epoch-3".toByteArray()
        val sealed = Crypto.aeadEncrypt(key, nonce, aad, "hello".toByteArray())
        assertContentEquals("hello".toByteArray(), Crypto.aeadDecrypt(key, nonce, aad, sealed))
        for (i in sealed.indices) {
            val t = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException> { Crypto.aeadDecrypt(key, nonce, aad, t) }
        }
        assertFailsWith<CryptoException> { Crypto.aeadDecrypt(key, nonce, "group-1/epoch-4".toByteArray(), sealed) }
        assertFailsWith<CryptoException> { Crypto.aeadDecrypt(Crypto.randomBytes(32), nonce, aad, sealed) }
        assertFailsWith<CryptoException> { Crypto.aeadDecrypt(key, nonce, aad, ByteArray(5)) }
    }

    @Test fun random_is_not_constant() {
        assertFalse(Crypto.randomBytes(32).contentEquals(Crypto.randomBytes(32)))
    }
}
