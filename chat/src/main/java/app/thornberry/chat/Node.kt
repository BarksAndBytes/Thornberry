package app.thornberry.chat

import android.content.Context
import android.content.SharedPreferences
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

    private const val ENGINE_FILE = "engine.bin"
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
            for (ev in e.drainEvents()) {
                when (ev) {
                    is ChatEngine.Event.DirectText -> {
                        addMsg(Msg("c:" + ev.contactId, false, ev.contactId, ev.text, ev.timestamp)); notifyHook?.invoke("New message")
                    }
                    is ChatEngine.Event.GroupText -> {
                        addMsg(Msg("g:" + ev.groupId, false, ev.sender.joinToString("") { "%02x".format(it) }, ev.text, ev.timestamp))
                        notifyHook?.invoke("New group message")
                    }
                    is ChatEngine.Event.GroupJoined -> notifyHook?.invoke("You were added to a group")
                    is ChatEngine.Event.RemovedFromGroup -> notifyHook?.invoke("You were removed from a group")
                    is ChatEngine.Event.GroupChanged -> {}
                    is ChatEngine.Event.LocationUpdate -> {}
                    is ChatEngine.Event.SharingState -> {}
                    is ChatEngine.Event.Paired -> {
                        ChatLog.log("PAIRED_BY_RESPONSE")
                        e.contact(ev.contactId)?.let { pendingPairing = ChatEngine.PairingResult(it, ev.safetyCode) }
                        pairingLocal = null
                        notifyHook?.invoke("Someone paired with you: compare the safety code")
                    }
                }
            }
            trigger = e.pendingFrames().isNotEmpty()
            SecureStorage.write(dir, ENGINE_FILE, e.snapshot())
            SecureStorage.write(dir, HISTORY_FILE, saveHistory())
        }
        if (trigger) sendHook?.invoke()
        changed()
        return result
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

    /** Whether our own marker is drawn on the map (display only: does not change what we share). */
    fun mapShowMe(): Boolean = prefs.getBoolean("map_show_me", true)
    fun setMapShowMe(v: Boolean) { prefs.edit().putBoolean("map_show_me", v).apply() }

    fun syncNow() { use { it.syncAll(System.currentTimeMillis()) } }
}
