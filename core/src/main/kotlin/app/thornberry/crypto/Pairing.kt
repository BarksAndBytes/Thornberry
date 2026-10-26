package app.thornberry.crypto

import java.io.ByteArrayOutputStream

/**
 * In-person contact pairing by exchanging two QR codes (each phone shows one, the other scans it).
 * Design: docs/architecture.md (Contact pairing). NOT independently reviewed (D3).
 *
 * Each offer carries: long-term Ed25519 identity public key, a fresh X25519 key made for this
 * pairing, the phone's onion address for this contact, and a random pairing secret. The offer is
 * signed by the identity key, so the fresh key and onion address cannot be swapped without
 * detection. Both phones then compute the same session secret and the same short code, which the
 * two people can read out to each other to confirm the right phones paired.
 *
 * The QR shows secrets: show it only while the other person scans it, and never share a screenshot.
 */
object Pairing {
    private const val VERSION = 1
    private const val ONION_LEN = 56 // v3 onion address without ".onion"
    private val ONION_RE = Regex("^[a-z2-7]{56}$")
    private val SIGN_PREFIX = "thornberry/pair/v1".toByteArray()
    const val ENCODED_BYTES = 1 + 32 + 32 + 32 + ONION_LEN + 64

    class Offer(
        val identityPublic: ByteArray,
        val ephemeralPublic: ByteArray,
        val secret: ByteArray,
        val onion: String,
        private val signature: ByteArray,
    ) {
        private fun body(): ByteArray {
            val b = ByteArrayOutputStream()
            b.write(VERSION); b.write(identityPublic); b.write(ephemeralPublic); b.write(secret)
            b.write(onion.toByteArray(Charsets.US_ASCII))
            return b.toByteArray()
        }

        /** The bytes to put into the QR code. */
        fun encode(): ByteArray = body() + signature

        internal fun signedBody(): ByteArray = SIGN_PREFIX + body()
        internal fun signatureBytes(): ByteArray = signature
    }

    /** Our own offer plus the private halves needed to finish pairing. Keep private; short-lived. */
    class Local(val offer: Offer, internal val identityPrivate: ByteArray, internal val ephemeral: Crypto.KeyPair)

    class Result(
        val session: DoubleRatchet,
        /** Pass as associated data to every encrypt and decrypt of this session. */
        val associatedData: ByteArray,
        /** Short code both people should read out and compare, e.g. "123 456 789". */
        val safetyCode: String,
        /** The initiator must send the first message of the session. */
        val isInitiator: Boolean,
        val peerIdentityPublic: ByteArray,
        val peerOnion: String,
        /** 16-byte tag to put in front of every frame we send to this contact (lets the receiver find the session). */
        val sendTag: ByteArray,
        /** The tag we expect on frames from this contact. */
        val receiveTag: ByteArray,
    )

    fun createOffer(identity: Crypto.KeyPair, onion: String): Local {
        require(ONION_RE.matches(onion)) { "bad onion address" }
        val eph = Crypto.x25519Generate()
        val secret = Crypto.randomBytes(32)
        val unsigned = Offer(identity.public, eph.public, secret, onion, ByteArray(0))
        val sig = Crypto.sign(identity.private, unsigned.signedBody())
        return Local(Offer(identity.public, eph.public, secret, onion, sig), identity.private, eph)
    }

    /** Parse and verify a scanned offer. Throws [CryptoException] if anything is wrong. */
    fun decode(data: ByteArray): Offer {
        if (data.size != ENCODED_BYTES) throw CryptoException("bad offer size")
        if (data[0].toInt() != VERSION) throw CryptoException("bad offer version")
        var o = 1
        fun take(n: Int) = data.copyOfRange(o, o + n).also { o += n }
        val id = take(32)
        val eph = take(32)
        val secret = take(32)
        val onionBytes = take(ONION_LEN)
        val sig = take(64)
        val onion = String(onionBytes, Charsets.US_ASCII)
        if (!ONION_RE.matches(onion)) throw CryptoException("bad onion address")
        val offer = Offer(id, eph, secret, onion, sig)
        if (!Crypto.verify(id, offer.signedBody(), sig)) throw CryptoException("bad offer signature")
        return offer
    }

    /** Finish pairing after scanning the peer's offer. */
    fun complete(own: Local, scannedPeer: ByteArray): Result {
        val peer = decode(scannedPeer)
        if (Crypto.constantTimeEquals(peer.identityPublic, own.offer.identityPublic)) {
            throw CryptoException("cannot pair with yourself")
        }
        if (Crypto.constantTimeEquals(peer.ephemeralPublic, own.offer.ephemeralPublic)) {
            throw CryptoException("duplicate pairing key")
        }
        val ownFirst = compareUnsigned(own.offer.identityPublic, peer.identityPublic) < 0
        val first = if (ownFirst) own.offer else peer
        val second = if (ownFirst) peer else own.offer

        val transcript = Crypto.sha256(first.encode() + second.encode())
        val dh = Crypto.x25519Agree(own.ephemeral.private, peer.ephemeralPublic)
        val ikm = dh + first.secret + second.secret
        val sk = Crypto.hkdf(ikm, transcript, "thornberry/pair/sk".toByteArray(), 32)
        val sas = Crypto.hkdf(ikm, transcript, "thornberry/pair/sas".toByteArray(), 4)
        var n = 0L
        for (b in sas) n = (n shl 8) or (b.toLong() and 0xff)
        val digits = "%09d".format(n % 1_000_000_000L)
        val code = "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6, 9)}"

        val tagFirst = Crypto.hkdf(ikm, transcript, "thornberry/pair/tag/first".toByteArray(), 16)
        val tagSecond = Crypto.hkdf(ikm, transcript, "thornberry/pair/tag/second".toByteArray(), 16)

        val session = if (ownFirst) {
            DoubleRatchet.initiator(sk, peer.ephemeralPublic)
        } else {
            DoubleRatchet.responder(sk, own.ephemeral)
        }
        return Result(
            session = session,
            associatedData = "thornberry/ad/v1".toByteArray() + first.identityPublic + second.identityPublic + transcript,
            safetyCode = code,
            isInitiator = ownFirst,
            peerIdentityPublic = peer.identityPublic,
            peerOnion = peer.onion,
            sendTag = if (ownFirst) tagFirst else tagSecond,
            receiveTag = if (ownFirst) tagSecond else tagFirst,
        )
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }
}
