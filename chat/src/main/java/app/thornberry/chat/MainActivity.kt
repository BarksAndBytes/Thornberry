package app.thornberry.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.thornberry.crypto.ChatEngine
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.integration.android.IntentIntegrator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Programmatic UI: Home, Pairing, Chat. Test build; plain on purpose. */
class MainActivity : Activity() {

    private sealed class Screen {
        object Home : Screen()
        object Pair : Screen()
        class Chat(val conv: String) : Screen()
    }

    private var screen: Screen = Screen.Home
    private lateinit var root: ScrollView
    private var chatLog: TextView? = null
    private var chatScroll: ScrollView? = null
    private var pairName = "Contact"
    private var confirmName: String? = null
    private val refresh: () -> Unit = { render(keepInput = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hides the screen from screenshots, screen recording and the recent-apps preview
        // (pairing QR codes contain secrets).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Node.init(this)
        root = ScrollView(this).apply {
            val pad = dp(16)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Node.ready()) startForegroundService(Intent(this, ChatService::class.java))
        render()
    }

    override fun onResume() { super.onResume(); Node.listeners.add(refresh); render(keepInput = true) }
    override fun onPause() { Node.listeners.remove(refresh); super.onPause() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen is Screen.Home) super.onBackPressed() else {
            if (screen is Screen.Pair) Node.cancelPairing()
            Node.pendingPairing = null; confirmName = null
            go(Screen.Home)
        }
    }

    private fun go(s: Screen) { screen = s; chatLog = null; render() }

    // ------------------------------------------------------------ rendering

    private var chatInputText = ""

    private fun render(keepInput: Boolean = false) {
        if (screen is Screen.Chat && keepInput && chatLog != null) { updateChatLog(); return }
        // A pairing finished (here, or by someone scanning our code): always show its safety code.
        if (Node.pendingPairing != null && screen !is Screen.Pair) screen = Screen.Pair
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.removeAllViews(); root.addView(col)
        when (val s = screen) {
            is Screen.Home -> home(col)
            is Screen.Pair -> pair(col)
            is Screen.Chat -> chat(col, s.conv)
        }
    }

    private fun label(text: String, size: Float = 14f, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun home(col: LinearLayout) {
        col.addView(label("Thornberry (test build 0.2.1)", 22f, true))
        col.addView(label("Test data only. No server. Not independently reviewed.", 12f))
        Node.loadError?.let { col.addView(label(it, 14f, true)); return }
        col.addView(label(
            "Tor: ${Node.torState}\nMy address: ${if (Node.onion.isEmpty()) "(not published yet)" else Node.onion.take(12) + "…"}\n" +
                "Crypto self-test on this phone: ${Node.selfTest}", 12f, mono = true,
        ))
        col.addView(button("Add contact (pair in person)") { go(Screen.Pair) })
        col.addView(button("New group") { newGroupDialog() })

        val contacts = Node.read { it.contactList().map { c -> Triple(c.id, c.name, c.verified) } }
        val groups = Node.read { it.groupList().map { g -> Triple(g.id, g.name, g.session.isActive) } }
        col.addView(label("Chats", 16f, true))
        if (contacts.isEmpty() && groups.isEmpty()) col.addView(label("Nothing yet. Add a contact to start.", 13f))
        for ((id, name, verified) in contacts) {
            col.addView(button(name + if (verified) "" else "  (not verified)") { go(Screen.Chat("c:$id")) })
        }
        for ((id, name, active) in groups) {
            col.addView(button("Group: $name" + if (active) "" else "  (removed)") { go(Screen.Chat("g:$id")) })
        }
        col.addView(label("Settings", 16f, true))
        col.addView(button("Allow running in background (battery)") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        col.addView(button("Open app settings (autostart, battery)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(button("Save diagnostic log to a file") { startLogSave() })
        col.addView(button("Stop connection") { startService(Intent(this, ChatService::class.java).setAction(ChatService.ACTION_STOP)) })
        col.addView(button("Start connection") { startForegroundService(Intent(this, ChatService::class.java)) })
    }

    private fun pair(col: LinearLayout) {
        col.addView(label("Pair in person", 20f, true))
        val done = Node.pendingPairing
        if (done != null) {
            col.addView(label("Safety code", 14f, true))
            col.addView(label(done.safetyCode, 32f, true, mono = true))
            col.addView(label(
                "Read this code out to the other person and listen to theirs. BOTH phones must show exactly the same code. " +
                    "If they differ, someone else may have used your code: tap the red button.", 14f,
            ))
            val nameBox = EditText(this).apply {
                hint = "Name for this contact"; setText(confirmName ?: done.contact.name); isSingleLine = true
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { confirmName = s.toString() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            col.addView(nameBox)
            col.addView(button("Codes match") {
                val n = (confirmName ?: done.contact.name).trim().ifEmpty { "Contact" }.take(40)
                Node.use { it.renameContact(done.contact.id, n); it.confirmVerified(done.contact.id) }
                Node.pendingPairing = null; confirmName = null; go(Screen.Home)
            })
            col.addView(button("Codes do NOT match: delete this contact") {
                Node.use { it.removeContact(done.contact.id) }
                Node.pendingPairing = null; confirmName = null; go(Screen.Home)
            })
            return
        }
        col.addView(label(
            "1. Show your code. The other person scans it (or pastes it, in the test build). You only need ONE scan: " +
                "your phone then shows the safety code by itself.\n" +
                "2. Read the safety code out loud on both phones and confirm they match.\n" +
                "Do not photograph or share the code: it contains secrets. This screen blocks screenshots.", 13f,
        ))
        val name = EditText(this).apply {
            hint = "Name for this contact"; setText(pairName); inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true
        }
        col.addView(name)
        val offer = Node.pairingOffer()
        if (offer == null) {
            col.addView(label("Waiting for the connection to be ready (can take a minute or two)…", 13f, true))
        } else {
            col.addView(ImageView(this).apply {
                setImageBitmap(qr("thornberry1:" + Base64.encodeToString(offer, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)))
                setBackgroundColor(Color.WHITE); adjustViewBounds = true
            })
            if (BuildConfig.DEBUG) {
                // TEST BUILDS ONLY: lets two copies on one phone pair without a camera. The clipboard is
                // readable by other apps, so this does not exist in release builds.
                col.addView(label("Test-only shortcut (not in release builds):", 12f, true))
                col.addView(button("Copy my code as text") {
                    val txt = "thornberry1:" + Base64.encodeToString(offer, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                    val clip = android.content.ClipData.newPlainText("code", txt)
                    if (Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                    toast("Copied (test only)")
                })
                col.addView(button("Paste their code") {
                    pairName = name.text.toString().trim().ifEmpty { "Contact" }
                    val txt = getSystemService(android.content.ClipboardManager::class.java)
                        .primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                    handleScanned(txt)
                    render()
                })
            }
            col.addView(button("Scan their code") {
                pairName = name.text.toString().trim().ifEmpty { "Contact" }
                IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("Scan their Thornberry code").setBeepEnabled(false)
                    .setOrientationLocked(false).setBarcodeImageEnabled(false).initiateScan()
            })
        }
    }

    private fun chat(col: LinearLayout, conv: String) {
        val isGroup = conv.startsWith("g:")
        val id = conv.substring(2)
        val title = Node.read { e ->
            if (isGroup) e.group(id)?.name ?: "Group" else e.contact(id)?.name ?: "Contact"
        }
        col.addView(label(title, 20f, true))
        if (isGroup) {
            col.addView(button("Where is everyone") { whereDialog(id) })
            col.addView(button("Share my location: " + (Node.sharePrecision(id)?.label ?: "Off")) { shareDialog(id) })
            col.addView(button("Members") { membersDialog(id) })
            col.addView(button("Invite a contact") { inviteDialog(id) })
        }
        chatLog = label("", 14f)
        chatScroll = ScrollView(this).apply { addView(chatLog) }
        col.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))
        val input = EditText(this).apply {
            hint = "Message"; setText(chatInputText); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { chatInputText = s.toString() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(input)
        col.addView(button("Send") {
            val t = chatInputText.trim()
            if (t.isEmpty()) return@button
            try {
                if (isGroup) Node.sendGroup(id, t) else Node.sendDirect(id, t)
                chatInputText = ""; input.setText("")
            } catch (e: Exception) { toast("Could not send: ${e.message}") }
        })
        updateChatLog()
    }

    private fun updateChatLog() {
        val s = screen as? Screen.Chat ?: return
        val log = chatLog ?: return
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
        val names = Node.read { e -> e.contactList().associate { it.id to it.name } }
        val sb = StringBuilder()
        for (m in Node.messages(s.conv)) {
            val who = if (m.outgoing) "Me" else names[m.sender] ?: m.sender.take(6)
            sb.append(fmt.format(Date(m.ts))).append("  ").append(who).append(": ").append(m.text).append("\n\n")
        }
        log.text = sb.toString()
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ dialogs

    private fun newGroupDialog() {
        val input = EditText(this).apply { hint = "Group name"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("New group").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val n = input.text.toString().trim().ifEmpty { "Group" }.take(40)
                val gid = Node.use { it.createGroup(n) }
                go(Screen.Chat("g:$gid"))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun inviteDialog(groupId: String) {
        val contacts = Node.read { it.contactList().map { c -> c.id to c.name } }
        if (contacts.isEmpty()) { toast("Pair with someone first"); return }
        AlertDialog.Builder(this).setTitle("Invite")
            .setItems(contacts.map { it.second }.toTypedArray()) { _, i ->
                try {
                    Node.use { it.inviteToGroup(groupId, contacts[i].first, System.currentTimeMillis()) }
                    toast("Invited ${contacts[i].second}")
                } catch (e: Exception) { toast("Could not invite: ${e.message}") }
            }.show()
    }

    /**
     * A list dialog with an explanation above it. (AlertDialog hides its list when a message is set,
     * which is why the explanation is part of the dialog's own view here.)
     */
    private fun choiceDialog(title: String, message: String, labels: List<String>, onPick: (Int) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        col.addView(TextView(this).apply { text = message; textSize = 13f; setPadding(0, 0, 0, dp(8)) })
        val sv = ScrollView(this).apply { addView(col) }
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(sv).setNegativeButton("Close", null).create()
        labels.forEachIndexed { i, l ->
            col.addView(Button(this).apply {
                text = l; isAllCaps = false; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setOnClickListener { dlg.dismiss(); onPick(i) }
            })
        }
        dlg.show()
    }

    private var pendingShare: Pair<String, app.thornberry.crypto.Precision>? = null

    private fun shareDialog(groupId: String) {
        val opts = listOf<app.thornberry.crypto.Precision?>(null) + app.thornberry.crypto.Precision.values().toList()
        val names = opts.map { it?.label ?: "Off" }.toTypedArray()
        choiceDialog(
            "Share my location with this group",
            "Only members of this group can see it. Nothing goes to any server. Updates every few minutes, at least every 10. " +
                "A notification stays visible while sharing.",
            names.toList(),
        ) { i ->
            val p = opts[i]
            try {
                if (p == null) {
                    Node.setSharePrecision(groupId, null); render()
                } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    enableShare(groupId, p)
                } else {
                    pendingShare = groupId to p
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
                }
            } catch (e: Exception) {
                ChatLog.err("shareDialog", e); toast("Could not change sharing: ${e.message}")
            }
        }
    }

    private fun enableShare(groupId: String, p: app.thornberry.crypto.Precision) {
        Node.setSharePrecision(groupId, p)
        startForegroundService(Intent(this, ChatService::class.java).setAction(ChatService.ACTION_LOCATION_REFRESH))
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7) return
        val pend = pendingShare; pendingShare = null
        if (pend != null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            enableShare(pend.first, pend.second)
        } else {
            toast("Location permission is needed to share. Nothing is shared.")
        }
    }

    private fun whereDialog(groupId: String) {
        val now = System.currentTimeMillis()
        data class Row(val label: String, val lat: Double?, val lon: Double?)
        val rows = Node.read { e ->
            val names = e.contactList().associate { it.id to it.name }
            val me = e.identityPublic.joinToString("") { "%02x".format(it) }
            e.locations(groupId).map { en ->
                val who = if (en.sender == me) "Me" else names[en.sender] ?: en.sender.take(8)
                val p = en.latest
                val text = when {
                    en.paused -> "$who: sharing paused"
                    p == null -> "$who: no position yet"
                    else -> {
                        val mins = ((now - p.timestamp) / 60_000L).coerceAtLeast(0)
                        val ago = if (mins < 1) "just now" else if (mins < 60) "$mins min ago" else "${mins / 60} h ago"
                        val stale = if (mins > 15) " (old)" else ""
                        "$who: %.5f, %.5f (±%d m), %s%s".format(Locale.US, p.lat, p.lon, p.accuracyM, ago, stale)
                    }
                }
                Row(text, if (en.paused) null else p?.lat, if (en.paused) null else p?.lon)
            }
        }
        if (rows.isEmpty()) { toast("Nobody is sharing in this group yet"); return }
        choiceDialog(
            "Where is everyone",
            "Tap a line to open it in your map app (that app may contact its own servers).",
            rows.map { it.label },
        ) { i ->
            val r = rows[i]
            if (r.lat != null && r.lon != null) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${r.lat},${r.lon}?q=${r.lat},${r.lon}")))
                } catch (e: Exception) { toast("No map app found") }
            }
        }
    }

    private fun membersDialog(groupId: String) {
        val rows = Node.read { e ->
            val g = e.group(groupId) ?: return@read emptyList()
            val names = e.contactList().associate { it.id to it.name }
            g.session.memberKeys().map { k ->
                val hex = k.joinToString("") { "%02x".format(it) }
                val me = k.contentEquals(e.identityPublic)
                Triple(hex, if (me) "Me" else names[hex] ?: hex.take(8), g.session.isAdmin(k))
            }
        }
        AlertDialog.Builder(this).setTitle("Members (tap to remove, admin only)")
            .setItems(rows.map { it.second + if (it.third) " (admin)" else "" }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this).setTitle("Remove ${rows[i].second}?")
                    .setMessage("They will not receive anything new. They keep what they already saw.")
                    .setPositiveButton("Remove") { _, _ ->
                        try {
                            val key = rows[i].first.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            Node.use { it.removeFromGroup(groupId, key, System.currentTimeMillis()) }
                        } catch (e: Exception) { toast("Could not remove: ${e.message}") }
                    }.setNegativeButton("Cancel", null).show()
            }.show()
    }

    // ------------------------------------------------------------ scanning

    private fun startLogSave() {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "thornberry-chat-log-$stamp.txt")
        try { startActivityForResult(i, 4301) } catch (e: Exception) { toast("No file picker available") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4301) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                Thread {
                    val msg = try {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(ChatLog.fullText().toByteArray()) }
                        "Saved"
                    } catch (e: Exception) { "Save failed: ${e.message}" }
                    runOnUiThread { toast(msg) }
                }.start()
            }
            return
        }
        val text = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.contents ?: return
        handleScanned(text)
        render()
    }

    private fun handleScanned(text: String) {
        try {
            require(text.startsWith("thornberry1:")) { "not a Thornberry code" }
            val bytes = Base64.decode(text.removePrefix("thornberry1:"), Base64.URL_SAFE or Base64.NO_PADDING)
            Node.finishPairing(bytes, pairName)
        } catch (e: Exception) {
            toast("Could not pair: ${e.message}")
        }
    }

    private fun qr(text: String): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 700, 700, hints)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
