package app.thornberry.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The ONLY file in the project that touches a crypto library. Everything else uses this small
 * interface, so the library stays swappable and reviewers have one file to audit.
 * Design: docs/crypto-decision.md. Not yet independently reviewed (D3).
 */
object Crypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16
    const val SIGNATURE_BYTES = 64

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    class KeyPair(val private: ByteArray, val public: ByteArray)

    // ---- X25519 key agreement (RFC 7748) ----
    fun x25519Generate(): KeyPair {
        val priv = X25519PrivateKeyParameters(rng)
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun x25519PublicFor(private: ByteArray): ByteArray =
        X25519PrivateKeyParameters(private, 0).generatePublicKey().encoded

    /** Throws if the result is the all-zero value (low-order peer key). */
    fun x25519Agree(private: ByteArray, peerPublic: ByteArray): ByteArray {
        require(private.size == KEY_BYTES && peerPublic.size == KEY_BYTES) { "bad key size" }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(private, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        if (MessageDigest.isEqual(out, ByteArray(out.size))) throw CryptoException("weak shared secret")
        return out
    }

    // ---- Ed25519 signatures (RFC 8032) ----
    fun ed25519Generate(): KeyPair {
        val priv = Ed25519PrivateKeyParameters(rng)
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun ed25519PublicFor(private: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(private, 0).generatePublicKey().encoded

    fun sign(private: ByteArray, message: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(private, 0))
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    fun verify(public: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (public.size != KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        return try {
            val s = Ed25519Signer()
            s.init(false, Ed25519PublicKeyParameters(public, 0))
            s.update(message, 0, message.size)
            s.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ---- HKDF-SHA256 (RFC 5869) ----
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { g.generateBytes(it, 0, length) }
    }

    fun sha256(data: ByteArray): ByteArray {
        val d = SHA256Digest()
        d.update(data, 0, data.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    // ---- HMAC-SHA256 (RFC 2104 / 4231) ----
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val m = HMac(SHA256Digest())
        m.init(KeyParameter(key))
        m.update(data, 0, data.size)
        return ByteArray(m.macSize).also { m.doFinal(it, 0) }
    }

    // ---- XChaCha20-Poly1305 AEAD ----
    /** Output is ciphertext followed by the 16-byte tag. Nonce must be unique per key. */
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES) { "bad key or nonce size" }
        val c = XChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        var n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        n += c.doFinal(out, n)
        return out.copyOf(n)
    }

    /** Throws [CryptoException] if authentication fails. Never returns partial plaintext. */
    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES) { "bad key or nonce size" }
        if (sealed.size < TAG_BYTES) throw CryptoException("too short")
        try {
            val c = XChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, aad))
            val out = ByteArray(c.getOutputSize(sealed.size))
            var n = c.processBytes(sealed, 0, sealed.size, out, 0)
            n += c.doFinal(out, n)
            return out.copyOf(n)
        } catch (e: Exception) {
            throw CryptoException("authentication failed")
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

class CryptoException(message: String) : Exception(message)
