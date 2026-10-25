package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Pairwise session using the Double Ratchet algorithm as published by Signal
 * (https://signal.org/docs/specifications/doubleratchet/), built only on [Crypto].
 * Design: docs/crypto-decision.md. NOT independently reviewed (D3). Do not ship before review.
 *
 * Choices and limits (deliberate, see docs):
 * - The shared secret [sk] comes from the QR pairing step (not implemented here yet).
 * - The initiator (who knows the peer's ratchet public key) must send first. The responder can
 *   send only after receiving a first message.
 * - Message headers (ratchet public key, counters) are sent in the clear but authenticated.
 *   Relays and the mailbox can therefore link messages of one conversation. Header encryption is
 *   a possible later hardening.
 * - Decryption is atomic: a failed message leaves the session exactly as it was.
 */
class DoubleRatchet private constructor(
    private var dhs: Crypto.KeyPair,
    private var dhr: ByteArray?,
    private var rk: ByteArray,
    private var cks: ByteArray?,
    private var ckr: ByteArray?,
    private var ns: Int,
    private var nr: Int,
    private var pn: Int,
    private val skipped: LinkedHashMap<String, ByteArray>,
) {
    companion object {
        const val VERSION: Byte = 1
        const val HEADER_BYTES = 1 + 32 + 4 + 4
        const val MAX_SKIP = 1000
        const val MAX_STORED_SKIPPED = 2000

        /** For the party that knows the peer's ratchet public key and sends first. */
        fun initiator(sk: ByteArray, peerRatchetPublic: ByteArray): DoubleRatchet {
            val dhs = Crypto.x25519Generate()
            val (rk, cks) = kdfRk(sk, Crypto.x25519Agree(dhs.private, peerRatchetPublic))
            return DoubleRatchet(dhs, peerRatchetPublic.copyOf(), rk, cks, null, 0, 0, 0, LinkedHashMap())
        }

        /** For the party whose ratchet key pair the peer already holds the public half of. */
        fun responder(sk: ByteArray, ownRatchetKeyPair: Crypto.KeyPair): DoubleRatchet =
            DoubleRatchet(ownRatchetKeyPair, null, sk.copyOf(), null, null, 0, 0, 0, LinkedHashMap())

        private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Crypto.hkdf(dhOut, rk, "thornberry/dr/rk".toByteArray(), 64)
            return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
        }

        private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> {
            val mk = Crypto.hmacSha256(ck, byteArrayOf(0x01))
            val next = Crypto.hmacSha256(ck, byteArrayOf(0x02))
            return next to mk
        }

        /** Each message key is used once, so deriving the nonce from it is safe. */
        private fun keyAndNonce(mk: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Crypto.hkdf(mk, null, "thornberry/dr/msg".toByteArray(), 56)
            return out.copyOfRange(0, 32) to out.copyOfRange(32, 56)
        }

        private fun header(dhPub: ByteArray, pn: Int, n: Int): ByteArray {
            val b = ByteArrayOutputStream()
            DataOutputStream(b).apply { writeByte(VERSION.toInt()); write(dhPub); writeInt(pn); writeInt(n) }
            return b.toByteArray()
        }

        private fun skipKey(dhPub: ByteArray, n: Int) = dhPub.joinToString("") { "%02x".format(it) } + ":" + n

        /** Restore a session saved with [serialize]. Throws [CryptoException] on malformed data. */
        fun restore(data: ByteArray): DoubleRatchet = try {
            val i = DataInputStream(ByteArrayInputStream(data))
            if (i.readByte() != VERSION) throw CryptoException("bad state version")
            fun bytes(): ByteArray = ByteArray(i.readInt().also { require(it in 0..64) }).also { i.readFully(it) }
            fun optBytes(): ByteArray? = if (i.readBoolean()) bytes() else null
            val priv = bytes()
            val pub = bytes()
            val dhr = optBytes()
            val rk = bytes()
            val cks = optBytes()
            val ckr = optBytes()
            val ns = i.readInt()
            val nr = i.readInt()
            val pn = i.readInt()
            val count = i.readInt()
            require(count in 0..MAX_STORED_SKIPPED && ns >= 0 && nr >= 0 && pn >= 0)
            val map = LinkedHashMap<String, ByteArray>()
            repeat(count) { val k = String(ByteArray(i.readInt().also { l -> require(l in 0..80) }).also { b -> i.readFully(b) }, Charsets.US_ASCII); map[k] = bytes() }
            require(i.available() == 0)
            DoubleRatchet(Crypto.KeyPair(priv, pub), dhr, rk, cks, ckr, ns, nr, pn, map)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed state")
        }
    }

    /** Save the session. Contains secret keys: store only encrypted (Android Keystore). */
    fun serialize(): ByteArray {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        fun bytes(x: ByteArray) { o.writeInt(x.size); o.write(x) }
        fun optBytes(x: ByteArray?) { o.writeBoolean(x != null); if (x != null) bytes(x) }
        o.writeByte(VERSION.toInt())
        bytes(dhs.private); bytes(dhs.public); optBytes(dhr); bytes(rk); optBytes(cks); optBytes(ckr)
        o.writeInt(ns); o.writeInt(nr); o.writeInt(pn); o.writeInt(skipped.size)
        for ((k, v) in skipped) { val kb = k.toByteArray(Charsets.US_ASCII); o.writeInt(kb.size); o.write(kb); bytes(v) }
        return b.toByteArray()
    }

    private fun copy(): DoubleRatchet = restore(serialize())

    private fun adopt(o: DoubleRatchet) {
        dhs = o.dhs; dhr = o.dhr; rk = o.rk; cks = o.cks; ckr = o.ckr
        ns = o.ns; nr = o.nr; pn = o.pn
        skipped.clear(); skipped.putAll(o.skipped)
    }

    /** [ad] is associated data both sides must agree on (e.g. both identity public keys). */
    fun encrypt(plaintext: ByteArray, ad: ByteArray): ByteArray {
        val ck = cks ?: throw CryptoException("cannot send before first message is received")
        val (next, mk) = kdfCk(ck)
        cks = next
        val h = header(dhs.public, pn, ns)
        ns += 1
        val (key, nonce) = keyAndNonce(mk)
        return h + Crypto.aeadEncrypt(key, nonce, ad + h, plaintext)
    }

    /** Throws [CryptoException] on any failure and then leaves the session unchanged. */
    fun decrypt(message: ByteArray, ad: ByteArray): ByteArray {
        try {
            if (message.size < HEADER_BYTES + Crypto.TAG_BYTES) throw CryptoException("too short")
            val hdr = message.copyOf(HEADER_BYTES)
            val inp = DataInputStream(ByteArrayInputStream(hdr))
            if (inp.readByte() != VERSION) throw CryptoException("bad version")
            val dh = ByteArray(32).also { inp.readFully(it) }
            val msgPn = inp.readInt()
            val n = inp.readInt()
            if (msgPn < 0 || n < 0) throw CryptoException("bad counters")
            val sealed = message.copyOfRange(HEADER_BYTES, message.size)

            val work = copy()
            val plaintext = work.decryptInner(dh, msgPn, n, hdr, sealed, ad)
            adopt(work)
            return plaintext
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("decryption failed")
        }
    }

    private fun decryptInner(dh: ByteArray, msgPn: Int, n: Int, hdr: ByteArray, sealed: ByteArray, ad: ByteArray): ByteArray {
        val stored = skipped[skipKey(dh, n)]
        if (stored != null) {
            val (key, nonce) = keyAndNonce(stored)
            val pt = Crypto.aeadDecrypt(key, nonce, ad + hdr, sealed)
            skipped.remove(skipKey(dh, n))
            return pt
        }
        val current = dhr
        if (current == null || !Crypto.constantTimeEquals(current, dh)) {
            skipMessageKeys(msgPn)
            dhRatchet(dh)
        }
        skipMessageKeys(n)
        val (next, mk) = kdfCk(ckr ?: throw CryptoException("no receiving chain"))
        val (key, nonce) = keyAndNonce(mk)
        val pt = Crypto.aeadDecrypt(key, nonce, ad + hdr, sealed)
        ckr = next
        nr += 1
        return pt
    }

    private fun skipMessageKeys(until: Int) {
        val ck0 = ckr ?: return
        if (until - nr > MAX_SKIP) throw CryptoException("too many skipped messages")
        var ck = ck0
        val dhPub = dhr ?: return
        while (nr < until) {
            val (next, mk) = kdfCk(ck)
            ck = next
            skipped[skipKey(dhPub, nr)] = mk
            nr += 1
            while (skipped.size > MAX_STORED_SKIPPED) skipped.remove(skipped.keys.first())
        }
        ckr = ck
    }

    private fun dhRatchet(newDhr: ByteArray) {
        pn = ns
        ns = 0
        nr = 0
        dhr = newDhr.copyOf()
        val (rk1, ckr1) = kdfRk(rk, Crypto.x25519Agree(dhs.private, newDhr))
        rk = rk1
        ckr = ckr1
        dhs = Crypto.x25519Generate()
        val (rk2, cks2) = kdfRk(rk, Crypto.x25519Agree(dhs.private, newDhr))
        rk = rk2
        cks = cks2
    }
}
