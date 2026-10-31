package app.thornberry.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * File transfer, torrent style.
 *
 * - The sender picks a random file key and id, cuts the file into [CHUNK]-byte pieces and encrypts each piece with
 *   XChaCha20-Poly1305 (nonce = piece number, associated data = file id + piece number), so pieces can be fetched in
 *   any order and from anyone, and can never be swapped or moved.
 * - The SHA-256 of every encrypted piece goes into a "manifest"; the SHA-256 of the manifest is in the offer. The offer
 *   (name, size, type, key, manifest hash) travels end to end encrypted like a message. Pieces themselves are already
 *   encrypted, so any member can store and pass them on without being able to read them, and every piece is checked
 *   against the manifest before it is kept.
 * - The manifest itself is fetched in [CHUNK]-byte parts (numbered -1, -2, ...) and checked as a whole.
 */
object FileXfer {
    const val CHUNK = 256 * 1024
    const val MAX_NAME_BYTES = 255
    const val MAX_MIME_BYTES = 127
    const val MAX_SIZE = 1L shl 40 // 1 TiB: a sanity bound, not a product limit
    const val MAX_REQ = 64
    private const val VERSION = 1

    /** Piece numbers: 0..chunks-1 are file data, -1..-manifestParts are manifest parts. */
    class Offer(
        val fileId: ByteArray,
        val name: String,
        val mime: String,
        val size: Long,
        val key: ByteArray,
        val manifestHash: ByteArray,
    ) {
        val chunks: Int get() = chunkCount(size)
        val manifestBytes: Long get() = chunks.toLong() * 32
        val manifestParts: Int get() = ((manifestBytes + CHUNK - 1) / CHUNK).toInt()
        val idHex: String get() = hex(fileId)
        fun plainLen(i: Int): Int = if (i < chunks - 1) CHUNK else (size - (chunks - 1).toLong() * CHUNK).toInt()
        fun cipherLen(i: Int): Int = plainLen(i) + Crypto.TAG_BYTES
    }

    fun chunkCount(size: Long): Int = ((size + CHUNK - 1) / CHUNK).toInt()

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun nonce(i: Int): ByteArray = ByteArray(Crypto.NONCE_BYTES).also { n ->
        for (k in 0 until 4) n[Crypto.NONCE_BYTES - 1 - k] = (i ushr (8 * k)).toByte()
    }

    private fun aad(fileId: ByteArray, i: Int): ByteArray = "TB-file".toByteArray() + fileId + byteArrayOf(
        (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte(),
    )

    fun encryptChunk(key: ByteArray, fileId: ByteArray, i: Int, plain: ByteArray): ByteArray =
        Crypto.aeadEncrypt(key, nonce(i), aad(fileId, i), plain)

    fun decryptChunk(key: ByteArray, fileId: ByteArray, i: Int, sealed: ByteArray): ByteArray =
        Crypto.aeadDecrypt(key, nonce(i), aad(fileId, i), sealed)

    fun leaf(sealed: ByteArray): ByteArray = Crypto.sha256(sealed)

    /** Part [k] (0-based) of a manifest. */
    fun manifestPart(manifest: ByteArray, k: Int): ByteArray {
        val from = k * CHUNK
        return manifest.copyOfRange(from, minOf(manifest.size, from + CHUNK))
    }

    /** Is a received data piece the one the manifest describes? */
    fun checkChunk(manifest: ByteArray, offer: Offer, i: Int, sealed: ByteArray): Boolean {
        if (i !in 0 until offer.chunks || sealed.size != offer.cipherLen(i)) return false
        return Crypto.constantTimeEquals(leaf(sealed), manifest.copyOfRange(i * 32, i * 32 + 32))
    }

    fun checkManifest(offer: Offer, manifest: ByteArray): Boolean =
        manifest.size.toLong() == offer.manifestBytes && Crypto.constantTimeEquals(Crypto.sha256(manifest), offer.manifestHash)

    /** Offer for a new file: makes the id and key. The manifest is built while encrypting (see [ManifestBuilder]). */
    fun newKey(): ByteArray = Crypto.randomBytes(32)
    fun newId(): ByteArray = Crypto.randomBytes(16)

    class ManifestBuilder(private val chunks: Int) {
        private val out = ByteArrayOutputStream(chunks * 32)
        private var next = 0
        fun add(i: Int, sealed: ByteArray) {
            require(i == next) { "pieces must be added in order" }
            out.write(leaf(sealed)); next++
        }
        fun done(): ByteArray { require(next == chunks) { "missing pieces" }; return out.toByteArray() }
    }

    // ---------------- offer encoding (inside an encrypted message) ----------------

    fun encodeOffer(o: Offer): ByteArray {
        val b = ByteArrayOutputStream()
        val d = DataOutputStream(b)
        val nb = o.name.toByteArray(Charsets.UTF_8); val mb = o.mime.toByteArray(Charsets.UTF_8)
        require(o.fileId.size == 16 && o.key.size == 32 && o.manifestHash.size == 32)
        require(nb.size in 1..MAX_NAME_BYTES && mb.size <= MAX_MIME_BYTES && o.size in 0..MAX_SIZE)
        d.writeByte(VERSION); d.write(o.fileId); d.writeLong(o.size); d.write(o.key); d.write(o.manifestHash)
        d.writeByte(nb.size); d.write(nb); d.writeByte(mb.size); d.write(mb)
        return b.toByteArray()
    }

    fun decodeOffer(body: ByteArray): Offer {
        try {
            val i = DataInputStream(ByteArrayInputStream(body))
            if (i.readUnsignedByte() != VERSION) throw CryptoException("bad offer version")
            val id = ByteArray(16).also { i.readFully(it) }
            val size = i.readLong()
            val key = ByteArray(32).also { i.readFully(it) }
            val mh = ByteArray(32).also { i.readFully(it) }
            val nb = ByteArray(i.readUnsignedByte()).also { i.readFully(it) }
            val mb = ByteArray(i.readUnsignedByte()).also { i.readFully(it) }
            if (i.available() != 0) throw CryptoException("trailing bytes")
            if (size !in 0..MAX_SIZE || nb.isEmpty() || mb.size > MAX_MIME_BYTES) throw CryptoException("bad offer")
            val name = String(nb, Charsets.UTF_8)
            // A file name must not carry a path or control characters.
            if (name.any { it == '/' || it == '\\' || it.code < 0x20 } || name == "." || name == "..") throw CryptoException("bad file name")
            return Offer(id, name, String(mb, Charsets.UTF_8), size, key, mh)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed offer")
        }
    }

    // ---------------- control messages (between paired contacts, inside the encrypted channel) ----------------

    sealed class Ctrl {
        /** A file shared in a direct chat. */
        class OfferMsg(val offer: Offer, val timestamp: Long) : Ctrl()
        /** "Please send me these pieces." [hops] limits how far a request may be passed on. */
        class Req(val fileId: ByteArray, val hops: Int, val pieces: IntArray) : Ctrl()
        /** "These are the pieces I have." [fetching]: I am getting the ones you asked for and will send them. */
        class Have(val fileId: ByteArray, val fetching: Boolean, val count: Int, val bits: ByteArray) : Ctrl()
    }

    private const val C_OFFER = 1
    private const val C_REQ = 2
    private const val C_HAVE = 3

    fun encodeCtrl(c: Ctrl): ByteArray {
        val b = ByteArrayOutputStream()
        val d = DataOutputStream(b)
        when (c) {
            is Ctrl.OfferMsg -> { d.writeByte(C_OFFER); d.writeLong(c.timestamp); d.write(encodeOffer(c.offer)) }
            is Ctrl.Req -> {
                require(c.fileId.size == 16 && c.pieces.size in 1..MAX_REQ && c.hops in 0..7)
                d.writeByte(C_REQ); d.write(c.fileId); d.writeByte(c.hops); d.writeByte(c.pieces.size)
                c.pieces.forEach { d.writeInt(it) }
            }
            is Ctrl.Have -> {
                require(c.fileId.size == 16 && c.count >= 0 && c.bits.size == (c.count + 7) / 8)
                d.writeByte(C_HAVE); d.write(c.fileId); d.writeBoolean(c.fetching); d.writeInt(c.count); d.write(c.bits)
            }
        }
        return b.toByteArray()
    }

    fun decodeCtrl(body: ByteArray): Ctrl {
        try {
            val i = DataInputStream(ByteArrayInputStream(body))
            val out = when (i.readUnsignedByte()) {
                C_OFFER -> {
                    val ts = i.readLong()
                    val rest = ByteArray(i.available()).also { i.readFully(it) }
                    Ctrl.OfferMsg(decodeOffer(rest), ts)
                }
                C_REQ -> {
                    val id = ByteArray(16).also { i.readFully(it) }
                    val hops = i.readUnsignedByte(); val n = i.readUnsignedByte()
                    if (hops > 7 || n !in 1..MAX_REQ) throw CryptoException("bad request")
                    Ctrl.Req(id, hops, IntArray(n) { i.readInt() })
                }
                C_HAVE -> {
                    val id = ByteArray(16).also { i.readFully(it) }
                    val fetching = i.readBoolean(); val count = i.readInt()
                    if (count < 0 || count > chunkCount(MAX_SIZE)) throw CryptoException("bad have")
                    Ctrl.Have(id, fetching, count, ByteArray((count + 7) / 8).also { i.readFully(it) })
                }
                else -> throw CryptoException("unknown file message")
            }
            if (i.available() != 0) throw CryptoException("trailing bytes")
            return out
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("malformed file message")
        }
    }

    // ---------------- piece frames (sent outside the message channel; checked against the manifest) ----------------

    val FRAME_MARK = ByteArray(16) { 0xFF.toByte() }
    const val FRAME_HEADER = 16 + 16 + 4

    fun isPieceFrame(frame: ByteArray): Boolean =
        frame.size >= FRAME_HEADER && (0 until 16).all { frame[it] == 0xFF.toByte() }

    fun encodePiece(fileId: ByteArray, index: Int, data: ByteArray): ByteArray {
        val b = ByteArrayOutputStream(FRAME_HEADER + data.size)
        b.write(FRAME_MARK); b.write(fileId)
        DataOutputStream(b).writeInt(index)
        b.write(data)
        return b.toByteArray()
    }

    class Piece(val fileId: ByteArray, val index: Int, val data: ByteArray)

    fun decodePiece(frame: ByteArray): Piece {
        if (!isPieceFrame(frame) || frame.size > FRAME_HEADER + CHUNK + Crypto.TAG_BYTES) throw CryptoException("bad piece frame")
        val id = frame.copyOfRange(16, 32)
        val idx = java.nio.ByteBuffer.wrap(frame, 32, 4).int
        return Piece(id, idx, frame.copyOfRange(FRAME_HEADER, frame.size))
    }
}

/** A simple fixed-size bit set with a stable byte layout (bit i is bit i%8 of byte i/8). */
class Bits(val count: Int, bytes: ByteArray? = null) {
    val bytes: ByteArray = bytes?.copyOf() ?: ByteArray((count + 7) / 8)
    init { require(this.bytes.size == (count + 7) / 8) { "bad bit set" } }
    operator fun get(i: Int): Boolean = i in 0 until count && (bytes[i ushr 3].toInt() shr (i and 7)) and 1 == 1
    fun set(i: Int) { if (i in 0 until count) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (1 shl (i and 7))).toByte() }
    fun cardinality(): Int { var n = 0; for (i in 0 until count) if (get(i)) n++; return n }
    fun isFull(): Boolean = cardinality() == count
}

/**
 * Decides which pieces to ask which peer for. Pure logic, so it can be tested without phones.
 *
 * - Manifest parts first (nothing can be checked without the manifest).
 * - At most [perPeer] pieces outstanding per peer; a request not answered within [timeoutMs] is asked elsewhere.
 * - Peers whose piece list is known are asked only for pieces they have; peers not yet known are asked, and answer
 *   with their list (and may fetch the pieces for us if they are relaying).
 */
class Swarm(val chunks: Int, val manifestParts: Int, val perPeer: Int = 12, val timeoutMs: Long = 180_000) {
    val have = Bits(chunks)
    val manifestHave = BooleanArray(manifestParts)
    var manifestDone = manifestParts == 0
    /** Data pieces we want (all of them for a download; a subset for relaying). */
    val wanted = Bits(chunks)
    private val inflight = HashMap<Int, kotlin.Pair<String, Long>>()
    private val peerHas = HashMap<String, Bits>()
    private val peerFetching = HashSet<String>()

    fun wantAll() { for (i in 0 until chunks) wanted.set(i) }
    fun want(i: Int) { wanted.set(i) }

    fun complete(): Boolean = manifestDone && (0 until chunks).all { !wanted[it] || have[it] }

    fun onPiece(index: Int) {
        if (index >= 0) have.set(index) else if (-index - 1 < manifestParts) manifestHave[-index - 1] = true
        inflight.remove(index)
    }

    fun onManifestDone() { manifestDone = true }
    fun onManifestRejected() { for (k in manifestHave.indices) manifestHave[k] = false; inflight.keys.removeAll { it < 0 } }

    fun onHave(peer: String, bits: Bits, fetching: Boolean) {
        if (bits.count != chunks) return
        peerHas[peer] = bits
        if (fetching) peerFetching.add(peer) else peerFetching.remove(peer)
        // Pieces this peer said it does not have and will not fetch: ask someone else at once.
        if (!fetching) inflight.entries.removeAll { (i, v) -> v.first == peer && i >= 0 && !bits[i] }
    }

    fun inflightCount(peer: String) = inflight.values.count { it.first == peer }

    private fun mayHave(peer: String, i: Int): Boolean {
        if (i < 0) return true
        val b = peerHas[peer] ?: return true
        return b[i] || peerFetching.contains(peer)
    }

    /** Returns peer -> pieces to request now. [peers] are the contacts we can ask, in order of preference. */
    fun plan(now: Long, peers: List<String>): Map<String, List<Int>> {
        inflight.entries.removeAll { now - it.value.second > timeoutMs }
        if (peers.isEmpty()) return emptyMap()
        val load = HashMap<String, Int>().apply { peers.forEach { put(it, inflightCount(it)) } }
        val out = LinkedHashMap<String, MutableList<Int>>()
        val needed = ArrayList<Int>()
        if (!manifestDone) {
            for (k in 0 until manifestParts) if (!manifestHave[k] && !inflight.containsKey(-k - 1)) needed.add(-k - 1)
        } else {
            for (i in 0 until chunks) if (wanted[i] && !have[i] && !inflight.containsKey(i)) needed.add(i)
        }
        // Start at a different peer each second, so a peer that cannot help is not asked first forever.
        var rr = ((now / 1000) % peers.size).toInt()
        for (i in needed) {
            var chosen: String? = null
            // Prefer peers known to have the piece; then peers that might (unknown, or fetching it).
            for (pass in 0..1) {
                for (k in peers.indices) {
                    val p = peers[(rr + k) % peers.size]
                    if ((load[p] ?: 0) >= perPeer) continue
                    val ok = if (pass == 0) i >= 0 && peerHas[p]?.get(i) == true else mayHave(p, i)
                    if (ok) { chosen = p; rr = (rr + k + 1) % peers.size; break }
                }
                if (chosen != null) break
            }
            val p = chosen ?: continue
            load[p] = (load[p] ?: 0) + 1
            inflight[i] = p to now
            out.getOrPut(p) { ArrayList() }.add(i)
            if (out.values.sumOf { it.size } >= peers.size * perPeer) break
        }
        return out
    }
}
