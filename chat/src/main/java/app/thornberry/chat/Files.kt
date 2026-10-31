package app.thornberry.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.thornberry.crypto.Bits
import app.thornberry.crypto.ChatEngine
import app.thornberry.crypto.FileXfer
import app.thornberry.crypto.Swarm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * File transfer on the phone (protocol in core [FileXfer]).
 *
 * Storage, per file, in the app's private folder `xfer/<file id>/`:
 * - `meta`: the offer (including the file key), the chat, the state. Encrypted with the Keystore key.
 * - `manifest`: SHA-256 of every encrypted piece (not secret).
 * - `data`: the encrypted pieces at fixed offsets (only readable with the file key).
 * - `have`: which pieces are present.
 * The sender keeps no copy: pieces are read from the original file and encrypted again when asked for, and checked
 * against the manifest (if the original changed, nothing is sent).
 *
 * Who may ask for pieces: in a direct chat only that contact; in a group only paired contacts who are members.
 * Members relay: if asked for pieces they do not have, they fetch them (at most [HOPS] steps) and pass them on.
 *
 * Locking: [lock] is never held while calling into [Node] (Node may call in here while holding its own lock).
 */
object Files {
    const val MARK = "\u0001file:"
    const val ST_OFFERED = 0
    const val ST_PREPARING = 1
    const val ST_SHARED = 2
    const val ST_DOWNLOADING = 3
    const val ST_DONE = 4
    const val ST_FAILED = 5
    const val ST_RELAY = 6
    private const val HOPS = 2
    private const val RELAY_KEEP_MS = 48L * 3600_000
    private const val PIECE_SLOT = FileXfer.CHUNK + 16L

    class T(
        val offer: FileXfer.Offer, val conv: String, val from: String, val mine: Boolean,
        var state: Int, var sourceUri: String?, var lastActive: Long,
    ) {
        var note = ""
        var manifest: ByteArray? = null
        var have = Bits(offer.chunks)
        var haveCount = 0
        var swarm: Swarm? = null
        val manifestParts = HashMap<Int, ByteArray>()
        /** Relaying: piece -> contacts waiting for it. */
        val waiting = HashMap<Int, MutableSet<String>>()
        var relayHops = 0
        var progressPct = 0 // preparing or saving
        var dirty = false
        val id: String get() = offer.idHex
    }

    class Info(val name: String, val size: Long, val mime: String, val state: Int, val pct: Int, val note: String, val mine: Boolean)
    class Result(val replies: List<Pair<String, ByteArray>> = emptyList(), val history: Msg? = null)

    private val lock = Any()
    private lateinit var app: Context
    private lateinit var root: File
    private val all = HashMap<String, T>()
    private val outbound = LinkedHashMap<String, ArrayDeque<Pair<String, Int>>>()
    @Volatile var sendHook: (() -> Unit)? = null
    @Volatile var doneHook: ((String) -> Unit)? = null
    @Volatile private var ready = false

    fun init(ctx: Context) {
        synchronized(lock) {
            if (ready) return
            app = ctx.applicationContext
            root = File(ctx.filesDir, "xfer").also { it.mkdirs() }
            for (d in root.listFiles() ?: emptyArray()) {
                try {
                    val t = loadMeta(d) ?: continue
                    File(d, "manifest").takeIf { it.exists() }?.let { t.manifest = it.readBytes() }
                    File(d, "have").takeIf { it.exists() }?.readBytes()?.let { b -> if (b.size == (t.offer.chunks + 7) / 8) t.have = Bits(t.offer.chunks, b) }
                    t.haveCount = t.have.cardinality()
                    if (t.state == ST_PREPARING) { t.state = ST_FAILED; t.note = "Interrupted while preparing. Send it again." }
                    if (t.state == ST_DOWNLOADING) t.swarm = newSwarm(t).also { it.wantAll() }
                    all[t.id] = t
                } catch (e: Exception) {
                    ChatLog.err("file-load", e) // leave the folder as it is
                }
            }
            ready = true
        }
    }

    private fun dirOf(id: String) = File(root, id)

    // ---------------- persistence ----------------

    private fun saveMeta(t: T) {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply {
            writeByte(1)
            val o = FileXfer.encodeOffer(t.offer); writeInt(o.size); write(o)
            writeUTF(t.conv); writeUTF(t.from); writeBoolean(t.mine); writeByte(t.state)
            writeUTF(t.sourceUri ?: ""); writeLong(t.lastActive); writeUTF(t.note.take(200))
        }
        val d = dirOf(t.id).also { it.mkdirs() }
        SecureStorage.write(d, "meta", b.toByteArray())
    }

    private fun loadMeta(d: File): T? {
        val data = SecureStorage.read(d, "meta") ?: return null
        val i = DataInputStream(ByteArrayInputStream(data))
        require(i.readUnsignedByte() == 1)
        val offer = FileXfer.decodeOffer(ByteArray(i.readInt().also { require(it in 1..2000) }).also { i.readFully(it) })
        val t = T(offer, i.readUTF(), i.readUTF(), i.readBoolean(), i.readUnsignedByte(), i.readUTF().ifEmpty { null }, i.readLong())
        t.note = i.readUTF()
        return t
    }

    private fun saveHave(t: T) { File(dirOf(t.id), "have").writeBytes(t.have.bytes); t.dirty = false }

    private fun newSwarm(t: T): Swarm {
        val s = Swarm(t.offer.chunks, t.offer.manifestParts)
        if (t.manifest != null) { for (k in 0 until t.offer.manifestParts) s.onPiece(-k - 1); s.onManifestDone() }
        for (i in 0 until t.offer.chunks) if (t.have[i]) s.onPiece(i)
        return s
    }

    // ---------------- queries for the screen ----------------

    fun info(id: String): Info? = synchronized(lock) {
        val t = all[id] ?: return null
        val pct = when (t.state) {
            ST_DOWNLOADING -> if (t.offer.chunks == 0) 100 else (t.haveCount * 100L / t.offer.chunks).toInt()
            else -> t.progressPct
        }
        val st = if (t.state == ST_RELAY) ST_OFFERED else t.state
        Info(t.offer.name, t.offer.size, t.offer.mime, st, pct, t.note, t.mine)
    }

    fun previewText(text: String): String =
        if (text.startsWith(MARK)) "📎 " + (info(text.removePrefix(MARK))?.name ?: "File") else text

    // ---------------- sending ----------------

    /** Prepare and share a file in a chat ("c:<contact>" or "g:<group>"). Runs in the background. */
    fun sendFile(conv: String, uri: Uri) {
        Thread {
            var t: T? = null
            try {
                try { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                var name = "file"; var size = -1L
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                if (size < 0) throw IllegalStateException("the file's size is unknown")
                name = safeName(name)
                val mime = (app.contentResolver.getType(uri) ?: "application/octet-stream").take(100)
                val id = FileXfer.newId(); val key = FileXfer.newKey()
                // Placeholder offer while the manifest is being made (the real one replaces it).
                val placeholder = FileXfer.Offer(id, name, mime, size, key, ByteArray(32))
                val now = System.currentTimeMillis()
                val tt = T(placeholder, conv, "me", true, ST_PREPARING, uri.toString(), now)
                t = tt
                synchronized(lock) { all[tt.id] = tt }
                Node.addOutgoing(conv, MARK + tt.id, now); Node.changed()

                val n = placeholder.chunks
                val mb = FileXfer.ManifestBuilder(n)
                app.contentResolver.openInputStream(uri)?.use { input ->
                    for (i in 0 until n) {
                        val plain = readExactly(input, placeholder.plainLen(i))
                        mb.add(i, FileXfer.encryptChunk(key, id, i, plain))
                        val pct = ((i + 1) * 100L / n).toInt()
                        if (pct != tt.progressPct) { tt.progressPct = pct; Node.changed() }
                    }
                } ?: throw IllegalStateException("could not open the file")
                val manifest = mb.done()
                val offer = FileXfer.Offer(id, name, mime, size, key, app.thornberry.crypto.Crypto.sha256(manifest))
                val done = T(offer, conv, "me", true, ST_SHARED, uri.toString(), System.currentTimeMillis())
                done.manifest = manifest
                for (i in 0 until offer.chunks) done.have.set(i)
                done.haveCount = offer.chunks
                synchronized(lock) {
                    dirOf(done.id).mkdirs()
                    File(dirOf(done.id), "manifest").writeBytes(manifest)
                    saveMeta(done)
                    all[done.id] = done
                }
                val stamp = System.currentTimeMillis()
                if (conv.startsWith("g:")) Node.use { it.shareGroupFile(conv.substring(2), FileXfer.encodeOffer(offer), stamp) }
                else Node.use { it.sendFileControl(conv.substring(2), FileXfer.encodeCtrl(FileXfer.Ctrl.OfferMsg(offer, stamp))) }
                ChatLog.log("FILE_SHARED", "pieces=${offer.chunks} group=${conv.startsWith("g:")}")
            } catch (e: Exception) {
                ChatLog.err("file-send", e)
                t?.let { it.state = ST_FAILED; it.note = "Could not send: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                Node.changed()
            }
        }.start()
    }

    private fun safeName(n: String): String {
        val cleaned = n.map { if (it == '/' || it == '\\' || it.code < 0x20) '_' else it }.joinToString("").trim()
        var s = if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
        while (s.toByteArray(Charsets.UTF_8).size > FileXfer.MAX_NAME_BYTES) s = s.dropLast(1)
        return s
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val b = ByteArray(len); var got = 0
        while (got < len) { val r = input.read(b, got, len - got); if (r < 0) throw IllegalStateException("the file is shorter than expected"); got += r }
        return b
    }

    // ---------------- receiving offers (called by Node while it holds its lock) ----------------

    fun onGroupOffer(groupId: String, sender: String, body: ByteArray, ts: Long): Msg? {
        val offer = try { FileXfer.decodeOffer(body) } catch (e: Exception) { return null }
        synchronized(lock) {
            if (all.containsKey(offer.idHex)) return null
            val t = T(offer, "g:$groupId", sender, false, ST_OFFERED, null, System.currentTimeMillis())
            all[t.id] = t; saveMeta(t)
        }
        return Msg("g:$groupId", false, sender, MARK + offer.idHex, ts)
    }

    /** A file message from a contact. Returns control replies to send and a chat line to add, if any. */
    fun onControl(e: ChatEngine, contactId: String, body: ByteArray, now: Long): Result {
        val msg = try { FileXfer.decodeCtrl(body) } catch (ex: Exception) { return Result() }
        return when (msg) {
            is FileXfer.Ctrl.OfferMsg -> synchronized(lock) {
                if (all.containsKey(msg.offer.idHex)) return Result()
                val t = T(msg.offer, "c:$contactId", contactId, false, ST_OFFERED, null, now)
                all[t.id] = t; saveMeta(t)
                Result(history = Msg("c:$contactId", false, contactId, MARK + t.id, msg.timestamp))
            }
            is FileXfer.Ctrl.Have -> synchronized(lock) {
                all[FileXfer.hex(msg.fileId)]?.swarm?.onHave(contactId, Bits(msg.count, msg.bits), msg.fetching)
                Result()
            }
            is FileXfer.Ctrl.Req -> onRequest(e, contactId, msg, now)
        }
    }

    private fun allowed(e: ChatEngine, t: T, contactId: String): Boolean = when {
        t.conv == "c:$contactId" -> true
        t.conv.startsWith("g:") -> {
            val g = e.group(t.conv.substring(2))
            val c = e.contact(contactId)
            g != null && c != null && g.session.isActive && g.session.isMember(c.peerIdentity)
        }
        else -> false
    }

    private fun onRequest(e: ChatEngine, contactId: String, req: FileXfer.Ctrl.Req, now: Long): Result {
        val id = FileXfer.hex(req.fileId)
        val isAllowed = synchronized(lock) { all[id] }?.let { allowed(e, it, contactId) } ?: false
        if (!isAllowed) return Result()
        synchronized(lock) {
            val t = all[id] ?: return Result()
            val missing = ArrayList<Int>()
            for (p in req.pieces) {
                if (p < 0) {
                    if (-p - 1 >= t.offer.manifestParts) continue
                    if (t.manifest != null) enqueue(contactId, id, p) else missing.add(p)
                } else {
                    if (p >= t.offer.chunks) continue
                    if (t.mine || t.have[p]) enqueue(contactId, id, p) else missing.add(p)
                }
            }
            if (missing.isEmpty()) return Result()
            // Pass the request on (group files only, limited steps), unless this file is not wanted at all here.
            val canRelay = t.conv.startsWith("g:") && req.hops > 0 && t.state in setOf(ST_OFFERED, ST_RELAY, ST_DOWNLOADING) &&
                root.usableSpace > 500L * 1024 * 1024
            if (canRelay) {
                if (t.state == ST_OFFERED) { t.state = ST_RELAY; saveMeta(t) }
                val s = t.swarm ?: newSwarm(t).also { t.swarm = it }
                for (p in missing) {
                    if (p >= 0) s.want(p)
                    t.waiting.getOrPut(p) { HashSet() }.add(contactId)
                }
                t.relayHops = maxOf(t.relayHops, req.hops - 1)
                t.lastActive = now
            }
            val have = FileXfer.Ctrl.Have(req.fileId, canRelay, t.offer.chunks, if (t.mine) Bits(t.offer.chunks).also { b -> for (i in 0 until t.offer.chunks) b.set(i) }.bytes else t.have.bytes)
            return Result(replies = listOf(contactId to FileXfer.encodeCtrl(have)))
        }
    }

    // ---------------- downloading ----------------

    fun startDownload(id: String) {
        synchronized(lock) {
            val t = all[id] ?: return
            if (t.mine || t.state == ST_DONE || t.state == ST_DOWNLOADING) return
            t.state = ST_DOWNLOADING; t.note = ""; t.lastActive = System.currentTimeMillis()
            val s = t.swarm ?: newSwarm(t).also { t.swarm = it }
            s.wantAll()
            if (t.offer.chunks == 0 && FileXfer.checkManifest(t.offer, ByteArray(0))) { t.manifest = ByteArray(0); t.state = ST_DONE }
            saveMeta(t)
        }
        ChatLog.log("FILE_DOWNLOAD_START")
        Node.changed(); sendHook?.invoke()
    }

    /** Stop and delete what was downloaded (the message stays; it can be downloaded again). */
    fun deleteLocal(id: String) {
        synchronized(lock) {
            val t = all[id] ?: return
            if (t.mine) return
            t.state = ST_OFFERED; t.swarm = null; t.manifest = null; t.manifestParts.clear(); t.waiting.clear()
            t.have = Bits(t.offer.chunks); t.haveCount = 0; t.note = ""
            File(dirOf(id), "data").delete(); File(dirOf(id), "have").delete(); File(dirOf(id), "manifest").delete()
            saveMeta(t)
        }
        Node.changed()
    }

    /** Stop sharing a file you sent: nobody can fetch more pieces from this phone. */
    fun stopSharing(id: String) {
        synchronized(lock) {
            val t = all[id] ?: return
            if (!t.mine) return
            t.state = ST_FAILED; t.note = "You stopped sharing this file."
            saveMeta(t)
        }
        Node.changed()
    }

    /** Peers we may ask, original sender first. Computed without holding [lock]. */
    private fun peersFor(conv: String, from: String, exclude: Set<String>): List<String> = try {
        Node.read { e ->
            val list = if (conv.startsWith("c:")) listOfNotNull(e.contact(conv.substring(2))?.id)
            else {
                val g = e.group(conv.substring(2))
                if (g == null || !g.session.isActive) emptyList()
                else e.contactList().filter { g.session.isMember(it.peerIdentity) }.map { it.id }
            }
            list.filter { it !in exclude }.sortedBy { if (it == from) 0 else 1 }
        }
    } catch (_: Exception) { emptyList() }

    /** Called every couple of seconds while Tor is up. Returns piece requests to send (contact, message). */
    fun tick(now: Long): List<Pair<String, ByteArray>> {
        if (!ready) return emptyList()
        val active = synchronized(lock) {
            all.values.filter { (it.state == ST_DOWNLOADING || it.state == ST_RELAY) && it.swarm != null }
                .map { Triple(it.id, it.conv, it.from) to it.waiting.values.flatten().toSet() }
        }
        val out = ArrayList<Pair<String, ByteArray>>()
        for ((key, exclude) in active) {
            val (id, conv, from) = key
            val peers = peersFor(conv, from, exclude)
            synchronized(lock) {
                val t = all[id] ?: return@synchronized
                val s = t.swarm ?: return@synchronized
                val hops = if (t.state == ST_RELAY) t.relayHops else HOPS
                for ((peer, pieces) in s.plan(now, peers)) {
                    for (chunk in pieces.chunked(FileXfer.MAX_REQ)) out.add(peer to FileXfer.encodeCtrl(FileXfer.Ctrl.Req(t.offer.fileId, hops, chunk.toIntArray())))
                }
                if (t.dirty) saveHave(t)
            }
        }
        // Forget relayed pieces nobody has asked about for two days.
        synchronized(lock) {
            val old = all.values.filter { it.state == ST_RELAY && now - it.lastActive > RELAY_KEEP_MS }
            for (t in old) {
                File(dirOf(t.id), "data").delete(); File(dirOf(t.id), "have").delete(); File(dirOf(t.id), "manifest").delete()
                t.state = ST_OFFERED; t.swarm = null; t.waiting.clear(); t.manifest = null; t.have = Bits(t.offer.chunks); t.haveCount = 0
                saveMeta(t)
            }
        }
        return out
    }

    /** A piece arrived over the network (not authenticated: it is checked against the manifest). */
    fun onPieceFrame(frame: ByteArray) {
        if (!ready) return
        val p = try { FileXfer.decodePiece(frame) } catch (_: Exception) { return }
        val id = FileXfer.hex(p.fileId)
        var finished: String? = null
        synchronized(lock) {
            val t = all[id] ?: return
            val s = t.swarm ?: return
            t.lastActive = System.currentTimeMillis()
            if (p.index < 0) {
                val k = -p.index - 1
                if (k >= t.offer.manifestParts || t.manifest != null) return
                val expected = minOf(FileXfer.CHUNK.toLong(), t.offer.manifestBytes - k.toLong() * FileXfer.CHUNK).toInt()
                if (p.data.size != expected) return
                t.manifestParts[k] = p.data
                s.onPiece(p.index)
                if (t.manifestParts.size == t.offer.manifestParts) {
                    val m = ByteArrayOutputStream()
                    for (j in 0 until t.offer.manifestParts) m.write(t.manifestParts[j]!!)
                    val manifest = m.toByteArray()
                    t.manifestParts.clear()
                    if (FileXfer.checkManifest(t.offer, manifest)) {
                        t.manifest = manifest; s.onManifestDone()
                        File(dirOf(id), "manifest").writeBytes(manifest)
                        // pass manifest parts on to anyone waiting
                        for (j in 0 until t.offer.manifestParts) t.waiting.remove(-j - 1)?.forEach { enqueue(it, id, -j - 1) }
                    } else {
                        s.onManifestRejected(); ChatLog.log("FILE_MANIFEST_BAD")
                    }
                }
            } else {
                val m = t.manifest ?: return
                if (t.have[p.index]) { s.onPiece(p.index); return }
                if (!FileXfer.checkChunk(m, t.offer, p.index, p.data)) { ChatLog.log("FILE_PIECE_BAD"); return }
                RandomAccessFile(File(dirOf(id), "data"), "rw").use { f -> f.seek(p.index * PIECE_SLOT); f.write(p.data) }
                t.have.set(p.index); t.haveCount++; t.dirty = true
                s.onPiece(p.index)
                t.waiting.remove(p.index)?.forEach { enqueue(it, id, p.index) }
                if (t.state == ST_DOWNLOADING && s.complete() && t.haveCount == t.offer.chunks) {
                    t.state = ST_DONE; saveHave(t); saveMeta(t); finished = t.offer.name
                }
            }
        }
        finished?.let { ChatLog.log("FILE_DONE"); doneHook?.invoke(it) }
        Node.changed()
    }

    // ---------------- serving pieces ----------------

    private fun enqueue(contactId: String, id: String, index: Int) {
        val q = outbound.getOrPut(contactId) { ArrayDeque() }
        if (q.size >= 512 || q.contains(id to index)) return
        q.addLast(id to index)
        sendHook?.invoke()
    }

    fun contactsWithOutbound(): List<String> = synchronized(lock) { outbound.filterValues { it.isNotEmpty() }.keys.toList() }

    fun nextBatch(contactId: String, max: Int): List<Pair<String, Int>> = synchronized(lock) {
        val q = outbound[contactId] ?: return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        while (out.size < max && q.isNotEmpty()) out.add(q.removeFirst())
        out
    }

    fun requeue(contactId: String, items: List<Pair<String, Int>>) {
        synchronized(lock) { val q = outbound.getOrPut(contactId) { ArrayDeque() }; items.asReversed().forEach { if (!q.contains(it)) q.addFirst(it) } }
    }

    /** Build the frame for one piece, or null if it cannot be served (unknown, stopped, or the original changed). */
    fun buildPiece(id: String, index: Int): ByteArray? {
        val t = synchronized(lock) { all[id] } ?: return null
        if (t.mine && t.state != ST_SHARED) return null
        try {
            if (index < 0) {
                val m = t.manifest ?: return null
                return FileXfer.encodePiece(t.offer.fileId, index, FileXfer.manifestPart(m, -index - 1))
            }
            if (t.mine) {
                val m = t.manifest ?: return null
                val uri = Uri.parse(t.sourceUri ?: return null)
                val plain = app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fin ->
                        fin.channel.position(index.toLong() * FileXfer.CHUNK)
                        readExactly(fin, t.offer.plainLen(index))
                    }
                } ?: return null
                val sealed = FileXfer.encryptChunk(t.offer.key, t.offer.fileId, index, plain)
                if (!FileXfer.checkChunk(m, t.offer, index, sealed)) {
                    // The original was changed or replaced: never send it (and never reuse the key for other content).
                    synchronized(lock) { t.state = ST_FAILED; t.note = "The original file changed, so sharing stopped."; saveMeta(t) }
                    Node.changed()
                    return null
                }
                return FileXfer.encodePiece(t.offer.fileId, index, sealed)
            }
            if (!t.have[index]) return null
            val buf = ByteArray(t.offer.cipherLen(index))
            RandomAccessFile(File(dirOf(id), "data"), "r").use { f -> f.seek(index * PIECE_SLOT); f.readFully(buf) }
            return FileXfer.encodePiece(t.offer.fileId, index, buf)
        } catch (e: Exception) {
            ChatLog.log("FILE_SERVE_FAIL", e.javaClass.simpleName)
            return null
        }
    }

    // ---------------- saving a received file ----------------

    fun saveTo(id: String, uri: Uri, onDone: (String) -> Unit) {
        Thread {
            val msg = try {
                val t = synchronized(lock) { all[id] } ?: throw IllegalStateException("unknown file")
                if (t.state != ST_DONE) throw IllegalStateException("not downloaded yet")
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    RandomAccessFile(File(dirOf(id), "data"), "r").use { f ->
                        for (i in 0 until t.offer.chunks) {
                            val buf = ByteArray(t.offer.cipherLen(i))
                            f.seek(i * PIECE_SLOT); f.readFully(buf)
                            out.write(FileXfer.decryptChunk(t.offer.key, t.offer.fileId, i, buf))
                            val pct = ((i + 1) * 100L / t.offer.chunks).toInt()
                            if (pct != t.progressPct) { t.progressPct = pct; Node.changed() }
                        }
                    }
                } ?: throw IllegalStateException("could not write there")
                "Saved ${t.offer.name}"
            } catch (e: Exception) {
                ChatLog.err("file-save", e)
                "Could not save: ${e.message ?: e.javaClass.simpleName}"
            }
            onDone(msg)
        }.start()
    }
}
