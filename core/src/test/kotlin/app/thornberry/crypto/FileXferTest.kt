package app.thornberry.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileXferTest {
    private val now = 1_000_000_000_000L
    private val C = FileXfer.CHUNK

    /** Encrypt a whole file the way the sender does; returns offer, encrypted pieces, manifest. */
    private fun prepare(data: ByteArray, name: String = "photo.jpg"): Triple<FileXfer.Offer, List<ByteArray>, ByteArray> {
        val id = FileXfer.newId(); val key = FileXfer.newKey()
        val n = FileXfer.chunkCount(data.size.toLong())
        val mb = FileXfer.ManifestBuilder(n)
        val pieces = (0 until n).map { i ->
            val plain = data.copyOfRange(i * C, minOf(data.size, (i + 1) * C))
            FileXfer.encryptChunk(key, id, i, plain).also { mb.add(i, it) }
        }
        val manifest = mb.done()
        return Triple(FileXfer.Offer(id, name, "image/jpeg", data.size.toLong(), key, Crypto.sha256(manifest)), pieces, manifest)
    }

    @Test fun a_file_survives_the_round_trip_and_every_piece_is_checked() {
        val data = Crypto.randomBytes(3 * C + 1000)
        val (offer, pieces, manifest) = prepare(data)
        assertEquals(4, offer.chunks); assertEquals(1000 + 16, offer.cipherLen(3)); assertEquals(1, offer.manifestParts)
        assertTrue(FileXfer.checkManifest(offer, manifest))
        assertFalse(FileXfer.checkManifest(offer, manifest.copyOf().also { it[5] = (it[5] + 1).toByte() }))
        for (i in 0 until 4) assertTrue(FileXfer.checkChunk(manifest, offer, i, pieces[i]))
        assertFalse(FileXfer.checkChunk(manifest, offer, 1, pieces[0])) // a piece cannot be moved
        assertFalse(FileXfer.checkChunk(manifest, offer, 0, pieces[0].copyOf().also { it[10] = (it[10] + 1).toByte() }))
        assertFalse(FileXfer.checkChunk(manifest, offer, 9, pieces[0]))
        val out = (0 until 4).map { FileXfer.decryptChunk(offer.key, offer.fileId, it, pieces[it]) }.reduce { a, b -> a + b }
        assertContentEquals(data, out)
        assertFailsWith<CryptoException> { FileXfer.decryptChunk(offer.key, offer.fileId, 1, pieces[0]) }
        assertFailsWith<CryptoException> { FileXfer.decryptChunk(FileXfer.newKey(), offer.fileId, 0, pieces[0]) }
    }

    @Test fun empty_and_exact_size_files() {
        val (empty, p0, m0) = prepare(ByteArray(0))
        assertEquals(0, empty.chunks); assertEquals(0, empty.manifestParts); assertTrue(p0.isEmpty())
        assertTrue(FileXfer.checkManifest(empty, m0))
        val (exact, p1, _) = prepare(ByteArray(2 * C))
        assertEquals(2, exact.chunks); assertEquals(C + 16, p1[1].size)
        // a large file needs a manifest in several parts (8192 pieces per part)
        val big = FileXfer.Offer(ByteArray(16), "big.mkv", "", 8193L * C, ByteArray(32), ByteArray(32))
        assertEquals(2, big.manifestParts)
    }

    @Test fun offer_encoding_is_strict_and_refuses_paths() {
        val (offer, _, _) = prepare(ByteArray(10), name = "Résumé 2026.pdf")
        val enc = FileXfer.encodeOffer(offer)
        val back = FileXfer.decodeOffer(enc)
        assertEquals("Résumé 2026.pdf", back.name); assertEquals(10L, back.size); assertContentEquals(offer.key, back.key)
        for (n in 0 until enc.size) assertFailsWith<CryptoException>("trunc $n") { FileXfer.decodeOffer(enc.copyOf(n)) }
        assertFailsWith<CryptoException> { FileXfer.decodeOffer(enc + byteArrayOf(0)) }
        for (bad in listOf("../x", "a/b", "a\\b", "..", "tab\tname")) {
            val o = FileXfer.Offer(offer.fileId, bad, "", 1, offer.key, offer.manifestHash)
            assertFailsWith<CryptoException>(bad) { FileXfer.decodeOffer(FileXfer.encodeOffer(o)) }
        }
    }

    @Test fun control_messages_round_trip_and_are_strict() {
        val id = FileXfer.newId()
        val (offer, _, _) = prepare(ByteArray(5))
        val msgs = listOf(
            FileXfer.Ctrl.OfferMsg(offer, now),
            FileXfer.Ctrl.Req(id, 3, intArrayOf(-1, 0, 5, 7)),
            FileXfer.Ctrl.Have(id, true, 10, Bits(10).also { it.set(3); it.set(9) }.bytes),
        )
        for (m in msgs) {
            val enc = FileXfer.encodeCtrl(m)
            val back = FileXfer.decodeCtrl(enc)
            assertEquals(m::class, back::class)
            for (n in 0 until enc.size) assertFailsWith<CryptoException>("trunc $n of ${m::class.simpleName}") { FileXfer.decodeCtrl(enc.copyOf(n)) }
            assertFailsWith<CryptoException> { FileXfer.decodeCtrl(enc + byteArrayOf(1)) }
        }
        val have = FileXfer.decodeCtrl(FileXfer.encodeCtrl(msgs[2])) as FileXfer.Ctrl.Have
        val bits = Bits(have.count, have.bits)
        assertTrue(bits[3] && bits[9] && !bits[4]); assertTrue(have.fetching)
        assertContentEquals(intArrayOf(-1, 0, 5, 7), (FileXfer.decodeCtrl(FileXfer.encodeCtrl(msgs[1])) as FileXfer.Ctrl.Req).pieces)
        assertFailsWith<IllegalArgumentException> { FileXfer.encodeCtrl(FileXfer.Ctrl.Req(id, 0, IntArray(65))) }
        val r = java.util.Random(3)
        repeat(3000) { try { FileXfer.decodeCtrl(ByteArray(r.nextInt(60)).also { b -> r.nextBytes(b) }) } catch (e: CryptoException) {} }
    }

    @Test fun piece_frames_are_recognised_and_bounded() {
        val id = FileXfer.newId()
        val f = FileXfer.encodePiece(id, -2, byteArrayOf(1, 2, 3))
        assertTrue(FileXfer.isPieceFrame(f))
        val p = FileXfer.decodePiece(f)
        assertContentEquals(id, p.fileId); assertEquals(-2, p.index); assertContentEquals(byteArrayOf(1, 2, 3), p.data)
        assertFalse(FileXfer.isPieceFrame(ByteArray(40)))
        assertFailsWith<CryptoException> { FileXfer.decodePiece(FileXfer.encodePiece(id, 0, ByteArray(C + 17))) }
    }

    // ---------------- swarm scheduling ----------------

    @Test fun swarm_asks_for_the_manifest_first_then_spreads_pieces_over_peers() {
        val s = Swarm(chunks = 40, manifestParts = 1, perPeer = 5)
        s.wantAll()
        val first = s.plan(now, listOf("a", "b"))
        assertEquals(listOf(-1), first.values.flatten()) // only the manifest
        s.onPiece(-1); s.onManifestDone()
        val next = s.plan(now, listOf("a", "b"))
        assertEquals(5, next["a"]!!.size); assertEquals(5, next["b"]!!.size)
        assertEquals(10, next.values.flatten().toSet().size) // nothing asked twice
        assertTrue(s.plan(now, listOf("a", "b")).isEmpty()) // both peers are busy
    }

    @Test fun swarm_respects_what_peers_have_and_reassigns_slow_or_missing_pieces() {
        val s = Swarm(chunks = 6, manifestParts = 1, perPeer = 6, timeoutMs = 1000)
        s.wantAll(); s.onPiece(-1); s.onManifestDone()
        val bHas = Bits(6).also { it.set(4); it.set(5) }
        s.onHave("b", bHas, fetching = false)
        val plan = s.plan(now, listOf("b", "a"))
        assertEquals(listOf(4, 5), plan["b"]); assertEquals(listOf(0, 1, 2, 3), plan["a"])
        // a says it has nothing and is not fetching: its pieces are freed at once and go nowhere (b lacks them)
        s.onHave("a", Bits(6), fetching = false)
        assertTrue(s.plan(now, listOf("b", "a")).isEmpty())
        // b gets them later (no longer known to lack them once it says it is fetching)
        s.onHave("b", bHas, fetching = true)
        assertEquals(listOf(0, 1, 2, 3), s.plan(now, listOf("b", "a"))["b"])
        // nothing arrives: after the timeout everything is asked again
        assertEquals(6, s.plan(now + 2000, listOf("b")).values.flatten().size)
        for (i in 0 until 6) s.onPiece(i)
        assertTrue(s.complete())
    }

    @Test fun relaying_wants_only_the_requested_pieces() {
        val s = Swarm(chunks = 100, manifestParts = 1)
        s.want(7); s.want(8); s.onPiece(-1); s.onManifestDone()
        assertEquals(setOf(7, 8), s.plan(now, listOf("x")).values.flatten().toSet())
        s.onPiece(7); s.onPiece(8)
        assertTrue(s.complete())
    }

    // ---------------- through the engine ----------------

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

    @Test fun file_messages_reach_a_contact_and_group_offers_reach_relayed_members() {
        val a = Phone("a".repeat(56)); val b = Phone("b".repeat(56)); val c = Phone("c".repeat(56))
        val all = listOf(a, b, c)
        pair(a, b); pair(c, b); run(all, now)
        val bId = a.engine.contactList().single().id
        val (offer, _, _) = prepare(ByteArray(100))
        a.engine.sendFileControl(bId, FileXfer.encodeCtrl(FileXfer.Ctrl.OfferMsg(offer, now))); run(all, now)
        val ctl = b.events.filterIsInstance<ChatEngine.Event.FileControl>().single()
        assertEquals("photo.jpg", (FileXfer.decodeCtrl(ctl.body) as FileXfer.Ctrl.OfferMsg).offer.name)

        val gid = b.engine.createGroup("Family")
        for (x in listOf(a, c)) { b.engine.inviteToGroup(gid, b.engine.contactList().first { it.onion == x.onion }.id, now); run(all, now) }
        a.engine.shareGroupFile(gid, FileXfer.encodeOffer(offer), now + 1000); run(all, now + 1000)
        val atC = c.events.filterIsInstance<ChatEngine.Event.GroupFile>().single()
        assertEquals(gid, atC.groupId)
        assertEquals(a.engine.identityPublic.joinToString("") { "%02x".format(it) }, atC.sender)
        assertEquals(offer.idHex, FileXfer.decodeOffer(atC.body).idHex)
        // a removed member gets no further offers
        b.engine.removeFromGroup(gid, c.engine.identityPublic, now + 2000); run(all, now + 2000)
        a.engine.shareGroupFile(gid, FileXfer.encodeOffer(prepare(ByteArray(3)).first), now + 3000); run(all, now + 3000)
        assertEquals(1, c.events.filterIsInstance<ChatEngine.Event.GroupFile>().size)
    }
}
