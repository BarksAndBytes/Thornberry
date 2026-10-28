package app.thornberry.chat

import android.content.Context
import android.content.SharedPreferences
import app.thornberry.crypto.GeoEvent
import app.thornberry.crypto.LocationBoard
import app.thornberry.crypto.Place
import app.thornberry.crypto.PlaceBook
import app.thornberry.crypto.Precision
import android.os.Handler
import android.os.Looper
import app.thornberry.crypto.ChatEngine
import app.thornberry.crypto.Crypto
import app.thornberry.crypto.Pairing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** One stored chat line. [conv] is "c:<contactId>" or "g:<groupId>". */
class Msg(val conv: String, val outgoing: Boolean, val sender: String, val text: String, val ts: Long)

/**
 * The single place that owns the chat engine and the message history. All access is serialized
 * and every change is saved (encrypted) before returning.
 */
object Node {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private var engine: ChatEngine? = null
    private val history = ArrayList<Msg>()
    private var pairingLocal: Pairing.Local? = null
    private var placeBook = PlaceBook()
    private var placesBlocked = false
    /** A pairing that finished and still needs the people to compare the safety code. */
    @Volatile var pendingPairing: ChatEngine.PairingResult? = null

    @Volatile var onion: String = ""
    @Volatile var torState: String = "STOPPED"
    /** Local port of our own Tor client's SOCKS proxy (0 until Tor is started). Used for map tiles. */
    @Volatile var socksPort: Int = 0
    @Volatile var loadError: String? = null
    @Volatile var selfTest: String = "not run"
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var sendHook: (() -> Unit)? = null
    @Volatile var notifyHook: ((String) -> Unit)? = null
    @Volatile var placeHook: ((String) -> Unit)? = null

    private const val ENGINE_FILE = "engine.bin"
    private const val PLACES_FILE = "places.bin"
    private const val HISTORY_FILE = "history.bin"
    private const val HISTORY_MAX = 5000

    fun init(ctx: Context) {
        synchronized(lock) {
            if (engine != null || loadError != null) return
            dir = ctx.filesDir
            prefs = ctx.getSharedPreferences("share", Context.MODE_PRIVATE)
            ChatLog.init(ctx)
            selfTest = SelfTest.run()
            ChatLog.log("START", "selfTest=$selfTest")
            try {
                val snap = SecureStorage.read(dir, ENGINE_FILE)
                engine = if (snap == null) {
                    ChatEngine(Crypto.ed25519Generate()).also { SecureStorage.write(dir, ENGINE_FILE, it.snapshot()) }
                } else {
                    ChatEngine.restore(snap)
                }
                SecureStorage.read(dir, HISTORY_FILE)?.let { loadHistory(it) }
                try {
                    SecureStorage.read(dir, PLACES_FILE)?.let { placeBook = PlaceBook.restore(it) }
                } catch (e: Exception) {
                    // Keep the unreadable file as it is (never overwrite it) and run without places.
                    placesBlocked = true
                    ChatLog.err("places-load", e)
                }
                engine?.let { if (!placesBlocked) { reconcileShared(it); savePlaces() } }
            } catch (e: Exception) {
                // Never overwrite unreadable state with an empty one: the owner may be able to recover it.
                engine = null
                loadError = "Saved data could not be opened (${e.javaClass.simpleName}). Nothing was changed."
                ChatLog.err("load", e)
            }
        }
    }

    private val refreshPosted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Ask the UI to redraw. Coalesced: many calls before the UI runs cause one redraw. */
    fun changed() {
        if (refreshPosted.compareAndSet(false, true)) {
            main.post { refreshPosted.set(false); listeners.forEach { it() } }
        }
    }

    /**
     * Read-only access: no events drained, nothing saved, no redraw requested. Use this for drawing the UI.
     * (Using [use] while drawing would request a redraw from inside the redraw, forever.)
     */
    fun <T> read(block: (ChatEngine) -> T): T = synchronized(lock) {
        block(engine ?: throw IllegalStateException(loadError ?: "not ready"))
    }

    /** Run [block] on the engine, then collect events, save, and notify. */
    fun <T> use(block: (ChatEngine) -> T): T {
        val result: T
        var trigger = false
        synchronized(lock) {
            val e = engine ?: throw IllegalStateException(loadError ?: "not ready")
            result = block(e)
            val geoSenders = HashSet<String>()
            var placesChanged = false
            val meHex = e.identityPublic.joinToString("") { "%02x".format(it) }
            for (ev in e.drainEvents()) {
                when (ev) {
                    is ChatEngine.Event.DirectText -> {
                        addMsg(Msg("c:" + ev.contactId, false, ev.contactId, ev.text, ev.timestamp)); notifyHook?.invoke("New message")
                    }
                    is ChatEngine.Event.GroupText -> {
                        addMsg(Msg("g:" + ev.groupId, false, ev.sender.joinToString("") { "%02x".format(it) }, ev.text, ev.timestamp))
                        notifyHook?.invoke("New group message")
                    }
                    is ChatEngine.Event.GroupJoined -> if (!isDmName(ev.groupName)) notifyHook?.invoke("You were added to a group")
                    is ChatEngine.Event.RemovedFromGroup -> notifyHook?.invoke("You were removed from a group")
                    is ChatEngine.Event.GroupChanged -> {}
                    is ChatEngine.Event.LocationUpdate -> if (ev.sender != meHex) geoSenders.add(ev.sender)
                    is ChatEngine.Event.SharingState -> {}
                    is ChatEngine.Event.PlacesChanged -> placesChanged = true
                    is ChatEngine.Event.Paired -> {
                        ChatLog.log("PAIRED_BY_RESPONSE")
                        e.contact(ev.contactId)?.let { pendingPairing = ChatEngine.PairingResult(it, ev.safetyCode) }
                        pairingLocal = null
                        notifyHook?.invoke("Someone paired with you: compare the safety code")
                    }
                }
            }
            if (placesChanged && !placesBlocked) {
                try { reconcileShared(e); savePlaces() } catch (ex: Exception) { ChatLog.err("places-sync", ex) }
            }
            if (geoSenders.isNotEmpty() && placeBook.hasPlaces()) runGeofence(e, geoSenders)
            trigger = e.pendingFrames().isNotEmpty()
            SecureStorage.write(dir, ENGINE_FILE, e.snapshot())
            SecureStorage.write(dir, HISTORY_FILE, saveHistory())
        }
        if (trigger) sendHook?.invoke()
        changed()
        return result
    }

    /** Check the people whose position just changed against our places. Runs inside [use], on this phone only. */
    private fun runGeofence(e: ChatEngine, senders: Set<String>) {
        val now = System.currentTimeMillis()
        try {
            for (s in senders) {
                val pts = e.groupList().flatMap { g -> e.locations(g.id).filter { it.sender == s }.flatMap { it.trail } }
                    .distinctBy { it.timestamp }
                val who = e.contact(s)?.name ?: "A member"
                for (ev in placeBook.feed(s, pts, now)) {
                    ChatLog.log("PLACE_EVENT", "arrived=${ev.arrived} notify=${ev.notify}")
                    if (ev.notify) placeHook?.invoke("$who ${if (ev.arrived) "arrived at" else "left"} ${ev.placeName}")
                }
            }
            savePlaces()
        } catch (ex: Exception) {
            ChatLog.err("geofence", ex)
        }
    }

    private fun savePlaces() { if (!placesBlocked) SecureStorage.write(dir, PLACES_FILE, placeBook.serialize()) }

    // ---------------- places: shared with a chat, or only on this phone ----------------
    // A shared place lives in the chat's group (the engine syncs it); the geofence book holds a copy under the id
    // "s:<groupId>:<placeId>" plus our own choice of who to be alerted about. Older places made before sharing
    // existed stay "only on this phone" until someone shares them.

    private const val SHARED = "s:"

    private fun reconcileShared(e: ChatEngine) {
        val keep = HashSet<String>()
        for (g in e.groupList()) {
            if (!g.session.isActive) continue
            for (p in e.sharedPlaces(g.id)) {
                val id = "$SHARED${g.id}:${p.id}"
                try { placeBook.upsertExternal(id, p.name, p.lat, p.lon, p.radiusM); keep.add(id) } catch (ex: Exception) { ChatLog.err("place-copy", ex) }
            }
        }
        for (pl in placeBook.list()) if (pl.id.startsWith(SHARED) && pl.id !in keep) placeBook.remove(pl.id)
    }

    fun isShared(placeId: String) = placeId.startsWith(SHARED)
    private fun splitShared(placeId: String): kotlin.Pair<String, String> {
        val rest = placeId.removePrefix(SHARED)
        return rest.substringBefore(':') to rest.substringAfter(':')
    }

    /** "Shared with Family", "Shared with you and Alex", or "Only on this phone". */
    fun placeScope(placeId: String): String {
        if (!isShared(placeId)) return "Only on this phone"
        val gid = splitShared(placeId).first
        return read { e ->
            val g = e.group(gid) ?: return@read "Shared"
            if (isDmName(g.name)) {
                val me = hexOf(e.identityPublic)
                val other = g.session.memberKeys().map { hexOf(it) }.firstOrNull { it != me }
                "Shared with you and " + (other?.let { e.contact(it)?.name } ?: "your contact")
            } else "Shared with ${g.name}"
        }
    }

    fun placesUnreadable(): Boolean = synchronized(lock) { placesBlocked }
    fun places(): List<Place> = synchronized(lock) { placeBook.list() }
    fun placeEvents(): List<GeoEvent> = synchronized(lock) { placeBook.events() }

    /** Places to draw on a chat's map: those shared in that chat, plus the ones only on this phone. */
    fun placesForConv(conv: String): List<Place> {
        val gids = shareGroupIds(conv, false).toSet()
        return places().filter { !isShared(it.id) || splitShared(it.id).first in gids }
    }

    /** New places are shared with the chat they were made in (a direct chat uses its hidden two-person group). */
    fun addPlace(conv: String, name: String, lat: Double, lon: Double, radiusM: Int) {
        check(!placesUnreadable()) { "saved places could not be read" }
        val gid = shareGroupIds(conv, true).first()
        use { it.saveSharedPlace(gid, null, name, lat, lon, radiusM, System.currentTimeMillis()) }
        ChatLog.log("PLACE_ADDED", "radius=$radiusM shared=true")
    }

    /** A place kept only on this phone (nobody else sees it). */
    fun addPersonalPlace(name: String, lat: Double, lon: Double, radiusM: Int) {
        synchronized(lock) {
            check(!placesBlocked) { "saved places could not be read" }
            placeBook.add(name, lat, lon, radiusM); savePlaces()
        }
        ChatLog.log("PLACE_ADDED", "radius=$radiusM shared=false")
        changed()
    }

    fun updatePlace(id: String, name: String, radiusM: Int, watch: Set<String>?) {
        check(!placesUnreadable()) { "saved places could not be read" }
        if (isShared(id)) {
            val (gid, pid) = splitShared(id)
            val cur = synchronized(lock) { placeBook.get(id) } ?: throw IllegalStateException("unknown place")
            if (cur.name != name || cur.radiusM != radiusM) use { it.saveSharedPlace(gid, pid, name, cur.lat, cur.lon, radiusM, System.currentTimeMillis()) }
            synchronized(lock) { placeBook.setWatch(id, watch); savePlaces() }
        } else synchronized(lock) { placeBook.update(id, name, radiusM, watch); savePlaces() }
        changed()
    }

    fun movePlace(id: String, lat: Double, lon: Double) {
        check(!placesUnreadable()) { "saved places could not be read" }
        if (isShared(id)) {
            val (gid, pid) = splitShared(id)
            val cur = synchronized(lock) { placeBook.get(id) } ?: throw IllegalStateException("unknown place")
            use { it.saveSharedPlace(gid, pid, cur.name, lat, lon, cur.radiusM, System.currentTimeMillis()) }
        } else synchronized(lock) { placeBook.move(id, lat, lon); savePlaces() }
        ChatLog.log("PLACE_MOVED", "shared=${isShared(id)}")
        changed()
    }

    fun removePlace(id: String) {
        check(!placesUnreadable()) { "saved places could not be read" }
        if (isShared(id)) {
            val (gid, pid) = splitShared(id)
            use { it.deleteSharedPlace(gid, pid, System.currentTimeMillis()) }
        } else synchronized(lock) { placeBook.remove(id); savePlaces() }
        changed()
    }

    /** Turn a place that is only on this phone into one shared with a chat (keeps our alert choice). */
    fun sharePersonalPlace(id: String, conv: String) {
        check(!placesUnreadable()) { "saved places could not be read" }
        val cur = synchronized(lock) { placeBook.get(id) } ?: throw IllegalStateException("unknown place")
        val gid = shareGroupIds(conv, true).first()
        val pid = use { it.saveSharedPlace(gid, null, cur.name, cur.lat, cur.lon, cur.radiusM, System.currentTimeMillis()) }
        synchronized(lock) {
            val newId = "$SHARED$gid:$pid"
            if (placeBook.get(newId) != null) placeBook.setWatch(newId, cur.watch)
            placeBook.remove(id); savePlaces()
        }
        changed()
    }

    fun ready(): Boolean = synchronized(lock) { engine != null }

    // ---------------- history ----------------

    private fun addMsg(m: Msg) {
        history.add(m)
        while (history.size > HISTORY_MAX) history.removeAt(0)
    }

    fun addOutgoing(conv: String, text: String, ts: Long) {
        synchronized(lock) { addMsg(Msg(conv, true, "me", text, ts)) }
    }

    fun messages(conv: String): List<Msg> = synchronized(lock) { history.filter { it.conv == conv } }

    private fun saveHistory(): ByteArray {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        o.writeByte(1); o.writeInt(history.size)
        for (m in history) { o.writeUTF(m.conv); o.writeBoolean(m.outgoing); o.writeUTF(m.sender); o.writeUTF(m.text); o.writeLong(m.ts) }
        return b.toByteArray()
    }

    private fun loadHistory(data: ByteArray) {
        val i = DataInputStream(ByteArrayInputStream(data))
        require(i.readByte().toInt() == 1)
        repeat(i.readInt()) { history.add(Msg(i.readUTF(), i.readBoolean(), i.readUTF(), i.readUTF(), i.readLong())) }
    }

    // ---------------- actions used by the UI ----------------

    fun sendDirect(contactId: String, text: String) {
        val now = System.currentTimeMillis()
        use { it.sendDirect(contactId, text, now) }
        addOutgoing("c:$contactId", text, now)
        use { }
    }

    fun sendGroup(groupId: String, text: String) {
        val now = System.currentTimeMillis()
        use { it.sendGroupText(groupId, text, now) }
        addOutgoing("g:$groupId", text, now)
        use { }
    }

    /** A pairing offer for the QR code, or null while our onion address is not published yet. */
    fun pairingOffer(): ByteArray? {
        val o = onion.removeSuffix(".onion")
        if (o.length != 56) return null
        synchronized(lock) {
            val e = engine ?: return null
            val local = pairingLocal ?: e.beginPairing(o).also { pairingLocal = it }
            return local.offer.encode()
        }
    }

    fun finishPairing(scanned: ByteArray, name: String): ChatEngine.PairingResult {
        val res = synchronized(lock) {
            val local = pairingLocal ?: throw IllegalStateException("no pairing in progress")
            use { it.completePairing(local, scanned, name) }.also { pairingLocal = null }
        }
        pendingPairing = res
        return res
    }

    fun cancelPairing() {
        synchronized(lock) {
            pairingLocal = null
            engine?.cancelPairing()
        }
    }

    // ---------------- location sharing settings (per group; off by default) ----------------

    fun sharePrecision(groupId: String): Precision? =
        prefs.getInt("share_$groupId", -1).let { if (it < 0) null else Precision.values()[it] }

    /** Turn sharing for a group on at a precision, or off (null). Tells the group so "paused" is not mistaken for "lost signal". */
    fun setSharePrecision(groupId: String, p: Precision?) {
        val before = sharePrecision(groupId)
        prefs.edit().putInt("share_$groupId", p?.ordinal ?: -1).apply()
        if ((before == null) != (p == null)) {
            try { use { it.setSharingPaused(groupId, p == null, System.currentTimeMillis()) } } catch (e: Exception) { ChatLog.err("pauseFlag", e) }
        }
        ChatLog.log("SHARE_SETTING", "group=${groupId.take(4)} precision=${p?.name ?: "OFF"}")
        changed()
    }

    fun sharingGroups(): List<Pair<String, Precision>> = read { e ->
        e.groupList().filter { it.session.isActive }.mapNotNull { g -> sharePrecision(g.id)?.let { g.id to it } }
    }

    // ---------------- location in direct chats ----------------
    // Location sharing is per group. A direct chat shares through a hidden two-person group (its name starts with
    // DM_PREFIX), created the first time you share with that person. Hidden groups never appear in the chat list.

    const val DM_PREFIX = "\u200Bdm\u200B"
    fun isDmName(n: String) = n.startsWith(DM_PREFIX)
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Active hidden groups shared with this contact (normally one; two if both of you created one at once). */
    fun dmGroupIds(contactId: String): List<String> = read { e ->
        e.groupList().filter { isDmName(it.name) && it.session.isActive && it.session.memberKeys().any { k -> hexOf(k) == contactId } }
            .map { it.id }.sorted()
    }

    private fun ensureDmGroups(contactId: String): List<String> {
        val have = dmGroupIds(contactId)
        if (have.isNotEmpty()) return have
        val now = System.currentTimeMillis()
        val gid = use { it.createGroup(DM_PREFIX) }
        use { it.inviteToGroup(gid, contactId, now) }
        return listOf(gid)
    }

    /** The groups that carry location for a conversation ("g:<id>" is itself; "c:<id>" is the hidden group). */
    fun shareGroupIds(conv: String, create: Boolean): List<String> {
        val id = conv.substring(2)
        return if (conv.startsWith("g:")) listOf(id) else if (create) ensureDmGroups(id) else dmGroupIds(id)
    }

    fun sharePrecisionFor(conv: String): Precision? = shareGroupIds(conv, false).firstNotNullOfOrNull { sharePrecision(it) }

    fun setSharePrecisionFor(conv: String, p: Precision?) {
        for (gid in shareGroupIds(conv, p != null)) setSharePrecision(gid, p)
    }

    /** One board entry per sender for a conversation (the newest if a direct chat has two hidden groups). */
    fun locationEntries(conv: String): List<LocationBoard.Entry> {
        val ids = shareGroupIds(conv, false)
        return read { e -> ids.flatMap { e.locations(it) } }
            .groupBy { it.sender }
            .map { (_, l) -> l.maxByOrNull { maxOf(it.latest?.timestamp ?: 0L, it.pausedAt) }!! }
    }

    /** Whether our own marker is drawn on the map (display only: does not change what we share). */
    fun mapShowMe(): Boolean = prefs.getBoolean("map_show_me", true)
    fun setMapShowMe(v: Boolean) { prefs.edit().putBoolean("map_show_me", v).apply() }

    /** Whether viewed map tiles are saved (encrypted) on this phone. Default on. */
    fun mapCacheEnabled(): Boolean = prefs.getBoolean("map_cache", true)
    fun setMapCacheEnabled(v: Boolean) { prefs.edit().putBoolean("map_cache", v).apply() }

    fun syncNow() { use { it.syncAll(System.currentTimeMillis()) } }
}
