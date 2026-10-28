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
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.thornberry.crypto.Precision
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Programmatic UI in a familiar messaging layout: chat list, conversation with bubbles, a map reached by tapping a
 * person (avatar, name or location chip), settings. Test build; no XML layouts on purpose.
 */
class MainActivity : Activity() {

    private sealed class Screen {
        object Home : Screen()
        object Pair : Screen()
        object Settings : Screen()
        object Places : Screen()
        class Chat(val conv: String) : Screen()
        class Map(val conv: String, val focus: String?) : Screen()
    }

    private sealed class ChatItem {
        class Day(val text: String) : ChatItem()
        class Message(val m: Msg, val header: Boolean) : ChatItem()
    }

    private class ChatRow(val conv: String, val name: String, val id: String, val note: String) { var last: Msg? = null }

    private lateinit var th: Theme
    private lateinit var shell: LinearLayout
    private var insetTop = 0
    private var topBarView: LinearLayout? = null
    private var barSubtitle: TextView? = null

    private var screen: Screen = Screen.Home
    private var pairName = "Contact"
    private var confirmName: String? = null
    private var chatInputText = ""

    // chat screen
    private var chatList: ListView? = null
    private var chatBanner: TextView? = null
    private var chatAdapter: ChatAdapter? = null
    private var chatConv = ""

    // map screen
    private var mapView: MapView? = null
    private var peopleBox: LinearLayout? = null
    private var mapConv = ""

    private val refresh: () -> Unit = {
        Updater.maybeCheckOnLaunch(this)
        if (Node.pendingPairing != null && screen !is Screen.Pair) render()
        else when (screen) {
            is Screen.Chat -> refreshChat()
            is Screen.Map -> refreshMap()
            else -> render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hides the screen from screenshots, screen recording and the recent-apps preview
        // (pairing QR codes contain secrets, and so do conversations).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Node.init(this)
        th = Theme(Ui.isDark(this))
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(th.bg)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                insetTop = bars.top
                v.setPadding(bars.left, 0, bars.right, bars.bottom)
                topBarView?.setPadding(dp(4), insetTop, dp(4), 0)
                insets
            }
        }
        setContentView(shell)
        try {
            window.insetsController?.setSystemBarsAppearance(
                if (th.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Node.ready()) startForegroundService(Intent(this, ChatService::class.java))
        if (savedInstanceState == null) Updater.launchCheckPending = true
        render()
    }

    override fun onResume() { super.onResume(); Node.listeners.add(refresh); Updater.maybeCheckOnLaunch(this); render() }
    override fun onPause() { Node.listeners.remove(refresh); super.onPause() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (val s = screen) {
            is Screen.Home -> super.onBackPressed()
            is Screen.Map -> go(Screen.Chat(s.conv))
            else -> {
                if (s is Screen.Pair) Node.cancelPairing()
                Node.pendingPairing = null; confirmName = null
                go(Screen.Home)
            }
        }
    }

    private fun go(s: Screen) {
        val prev = screen
        val keepsDraft = s is Screen.Chat && ((prev as? Screen.Chat)?.conv == s.conv || (prev as? Screen.Map)?.conv == s.conv)
        if (s is Screen.Chat && !keepsDraft) chatInputText = ""
        screen = s
        render()
    }

    // ------------------------------------------------------------ rendering

    private fun render() {
        // A pairing finished (here, or by someone scanning our code): always show its safety code.
        if (Node.pendingPairing != null && screen !is Screen.Pair) screen = Screen.Pair
        shell.removeAllViews()
        topBarView = null; barSubtitle = null; chatList = null; chatBanner = null; chatAdapter = null
        mapView = null; peopleBox = null
        when (val s = screen) {
            is Screen.Home -> homeScreen()
            is Screen.Pair -> pairScreen()
            is Screen.Settings -> settingsScreen()
            is Screen.Places -> placesScreen()
            is Screen.Chat -> chatScreen(s.conv)
            is Screen.Map -> mapScreen(s.conv, s.focus)
        }
    }

    // ------------------------------------------------------------ small view helpers

    private fun label(text: String, size: Float = 14f, bold: Boolean = false, mono: Boolean = false, dim: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size
            setTextColor(if (dim) th.textDim else th.text)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(text: String, filled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; stateListAnimator = null
        setTextColor(if (filled) Color.WHITE else th.link)
        background = if (filled) Ui.rounded(th.primary, dp(24)) else Ui.rounded(th.tint(th.primary, 0x22), dp(24))
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.addButton(text: String, filled: Boolean = true, onClick: () -> Unit) {
        addView(button(text, filled, onClick), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun avatar(name: String, id: String, sizeDp: Int) = TextView(this).apply {
        text = (name.trim().firstOrNull()?.uppercase() ?: "?")
        textSize = sizeDp * 0.4f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER; background = Ui.circle(th.colorFor(id))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun iconText(glyph: String, sizeSp: Float, onClick: (View) -> Unit) = TextView(this).apply {
        text = glyph; textSize = sizeSp; setTextColor(th.onPrimary); gravity = Gravity.CENTER
        setPadding(dp(12), dp(14), dp(12), dp(14))
        setOnClickListener { onClick(it) }
    }

    private fun topBar(
        title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
        avatarOf: kotlin.Pair<String, String>? = null, onTitle: (() -> Unit)? = null,
        actions: List<kotlin.Pair<String, (View) -> Unit>> = emptyList(),
    ) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(th.primary); elevation = dp(4).toFloat()
            setPadding(dp(4), insetTop, dp(4), 0)
        }
        if (onBack != null) bar.addView(iconText("←", 24f) { onBack() }) else bar.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        if (avatarOf != null) {
            bar.addView(avatar(avatarOf.first, avatarOf.second, 38), LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(TextView(this).apply {
            text = title; textSize = 18f; setTextColor(th.onPrimary); setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        })
        val sub = TextView(this).apply {
            text = subtitle ?: ""; textSize = 12f; setTextColor(th.onPrimaryDim)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        col.addView(sub)
        barSubtitle = sub
        if (onTitle != null) col.setOnClickListener { onTitle() }
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((g, f) in actions) bar.addView(iconText(g, 22f, f))
        topBarView = bar
        shell.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun setSubtitle(text: String?) {
        barSubtitle?.apply { this.text = text ?: ""; visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE }
    }

    private fun scrollColumn(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        val sv = ScrollView(this).apply { addView(col) }
        shell.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun timeLabel(ts: Long): String {
        val now = System.currentTimeMillis()
        val day = 24L * 3600_000
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ts)) == it.format(Date(now)) }
        return if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        else if (now - ts < 6 * day) SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
        else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }

    // ------------------------------------------------------------ chat list

    private fun homeScreen() {
        val status = when {
            Node.loadError != null -> "Locked"
            Node.torState == "CONNECTED" -> "Connected through Tor"
            else -> "Connecting… (${Node.torState.lowercase()})"
        }
        topBar("Thornberry", status, actions = listOf("📌" to { _: View -> go(Screen.Places) }, "⚙" to { _: View -> go(Screen.Settings) }))
        val upd = Updater.available
        if (upd != null || Updater.status.startsWith("Downloading") || Updater.status.startsWith("Update failed") || Updater.status.startsWith("Waiting")) {
            shell.addView(TextView(this).apply {
                text = if (Updater.busy || upd == null) Updater.status else "⬆ Update available: ${upd.versionName}. Tap to install."
                textSize = 13f; setTextColor(th.link); setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(th.tint(th.primary, 0x1E))
                setOnClickListener { startUpdate() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val frame = FrameLayout(this)
        shell.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(ScrollView(this).apply { addView(list) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        Node.loadError?.let {
            list.addView(label(it, 15f, true).apply { setPadding(dp(16), dp(24), dp(16), dp(8)) })
            return
        }
        val rows = Node.read { e ->
            e.contactList().map { ChatRow("c:${it.id}", it.name, it.id, if (it.verified) "" else "not verified") } +
                e.groupList().filter { !Node.isDmName(it.name) }.map { ChatRow("g:${it.id}", it.name, it.id, if (it.session.isActive) "" else "removed") }
        }
        rows.forEach { it.last = Node.messages(it.conv).lastOrNull() }
        val sorted = rows.sortedWith(compareByDescending<ChatRow> { it.last?.ts ?: 0L }.thenBy { it.name.lowercase() })
        if (sorted.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(32), dp(80), dp(32), 0) }
            empty.addView(label("No chats yet", 20f, true))
            empty.addView(label("Pair with someone in person to start chatting and sharing locations. No account, no server.", 14f, dim = true).apply { gravity = Gravity.CENTER })
            empty.addButton("Add contact (pair in person)") { go(Screen.Pair) }
            list.addView(empty)
        }
        for (r in sorted) {
            list.addView(chatRowView(r))
            list.addView(View(this).apply { setBackgroundColor(th.divider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(76) })
        }

        val fab = TextView(this).apply {
            text = "+"; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = Ui.circle(th.primary); elevation = dp(6).toFloat()
            setOnClickListener {
                PopupMenu(this@MainActivity, it).apply {
                    menu.add("Add contact (pair in person)"); menu.add("New group")
                    setOnMenuItemClickListener { mi ->
                        if (mi.title.toString().startsWith("Add")) go(Screen.Pair) else newGroupDialog()
                        true
                    }
                }.show()
            }
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(18)) })
    }

    private fun chatRowView(r: ChatRow): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { go(Screen.Chat(r.conv)) }
        }
        row.addView(avatar(r.name, r.id, 48), LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = r.name + if (r.note.isNotEmpty()) "  ·  ${r.note}" else ""
            textSize = 16f; setTextColor(th.text); setTypeface(typeface, Typeface.BOLD); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        })
        val last = r.last
        mid.addView(TextView(this).apply {
            text = if (last == null) "No messages yet" else (if (last.outgoing) "You: " else "") + last.text.replace('\n', ' ')
            textSize = 14f; setTextColor(th.textDim); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (last != null) right.addView(TextView(this).apply { text = timeLabel(last.ts); textSize = 12f; setTextColor(th.textDim) })
        if (Node.sharePrecisionFor(r.conv) != null) right.addView(TextView(this).apply { text = "📍"; textSize = 12f })
        row.addView(right)
        return row
    }

    // ------------------------------------------------------------ conversation

    private fun presenceMap(conv: String): Map<String, kotlin.Pair<Long, Boolean>> {
        val now = System.currentTimeMillis()
        val out = HashMap<String, kotlin.Pair<Long, Boolean>>()
        for (en in Node.locationEntries(conv)) {
            val p = en.latest
            if (en.paused) out[en.sender] = (-1L) to true
            else if (p != null) out[en.sender] = ((now - p.timestamp) / 60_000L).coerceAtLeast(0) to false
        }
        return out
    }

    private fun presenceText(pr: kotlin.Pair<Long, Boolean>): String = when {
        pr.second -> "📍 paused"
        pr.first < 1 -> "📍 now"
        pr.first < 60 -> "📍 ${pr.first} min ago"
        else -> "📍 ${pr.first / 60} h ago"
    }

    private fun chatScreen(conv: String) {
        chatConv = conv
        val isGroup = conv.startsWith("g:")
        val id = conv.substring(2)
        val title = Node.read { e -> if (isGroup) e.group(id)?.name ?: "Group" else e.contact(id)?.name ?: "Contact" }
        topBar(
            title, chatSubtitle(conv), onBack = { go(Screen.Home) }, avatarOf = title to id,
            onTitle = { go(Screen.Map(conv, if (isGroup) null else id)) },
            actions = listOf(
                "📍" to { _: View -> go(Screen.Map(conv, null)) },
                "⋮" to { v: View -> chatMenu(v, conv, isGroup) },
            ),
        )
        val banner = TextView(this).apply {
            textSize = 13f; setTextColor(th.live); setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(th.tint(th.live, 0x22)); setOnClickListener { shareDialog(conv) }
        }
        chatBanner = banner
        shell.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val adapter = ChatAdapter(conv, isGroup)
        chatAdapter = adapter
        val list = ListView(this).apply {
            divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT; setBackgroundColor(th.bg); isFocusable = false
            this.adapter = adapter; clipToPadding = false; setPadding(0, dp(6), 0, dp(6))
        }
        chatList = list
        shell.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setBackgroundColor(th.surface); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val input = EditText(this).apply {
            hint = "Message"; setHintTextColor(th.textDim); setTextColor(th.text)
            background = Ui.rounded(th.inputBg, dp(22)); setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5; setText(chatInputText)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { chatInputText = s.toString() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val send = TextView(this).apply {
            text = "➤"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = Ui.circle(th.primary)
            setOnClickListener {
                val t = chatInputText.trim()
                if (t.isEmpty()) return@setOnClickListener
                try {
                    if (isGroup) Node.sendGroup(id, t) else Node.sendDirect(id, t)
                    chatInputText = ""; input.setText("")
                    refreshChat(forceBottom = true)
                } catch (e: Exception) { toast("Could not send: ${e.message}") }
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
        shell.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshChat(forceBottom = true)
    }

    private fun chatSubtitle(conv: String): String? {
        val isGroup = conv.startsWith("g:")
        val id = conv.substring(2)
        val presence = presenceMap(conv)
        return if (isGroup) {
            val n = Node.read { it.group(id)?.session?.memberKeys()?.size ?: 0 }
            val sharing = presence.count { !it.value.second }
            "$n members" + if (sharing > 0) "  ·  $sharing sharing location" else ""
        } else {
            val pr = presence[id]
            val verified = Node.read { it.contact(id)?.verified ?: true }
            when {
                pr != null -> presenceText(pr).replace("📍 ", "sharing location · ").replace("now", "just now")
                !verified -> "not verified"
                else -> null
            }
        }
    }

    private fun chatMenu(anchor: View, conv: String, isGroup: Boolean) {
        PopupMenu(this, anchor).apply {
            menu.add("Share my location…")
            if (isGroup) { menu.add("Members"); menu.add("Invite a contact") }
            setOnMenuItemClickListener { mi ->
                when (mi.title.toString()) {
                    "Share my location…" -> shareDialog(conv)
                    "Members" -> membersDialog(conv.substring(2))
                    else -> inviteDialog(conv.substring(2))
                }
                true
            }
        }.show()
    }

    private fun refreshChat(forceBottom: Boolean = false) {
        val list = chatList ?: return
        val adapter = chatAdapter ?: return
        val atBottom = forceBottom || list.count == 0 || list.lastVisiblePosition >= list.count - 2
        adapter.reload()
        setSubtitle(chatSubtitle(chatConv))
        val mine = Node.sharePrecisionFor(chatConv)
        chatBanner?.apply {
            visibility = if (mine == null) View.GONE else View.VISIBLE
            text = "📍 You are sharing your location here (${mine?.label}). Tap to change."
        }
        if (atBottom) list.post { list.setSelection(adapter.count - 1) }
    }

    private inner class ChatAdapter(val conv: String, val isGroup: Boolean) : BaseAdapter() {
        var items: List<ChatItem> = emptyList()
        var names: Map<String, String> = emptyMap()
        var presence: Map<String, kotlin.Pair<Long, Boolean>> = emptyMap()

        fun reload() {
            names = Node.read { e -> e.contactList().associate { it.id to it.name } }
            presence = presenceMap(conv)
            val out = ArrayList<ChatItem>()
            val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
            var lastDay = ""
            var prev: Msg? = null
            for (m in Node.messages(conv)) {
                val d = dayFmt.format(Date(m.ts))
                if (d != lastDay) { out.add(ChatItem.Day(d)); lastDay = d; prev = null }
                out.add(ChatItem.Message(m, !m.outgoing && (prev == null || prev.outgoing || prev.sender != m.sender)))
                prev = m
            }
            items = out
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            when (val it = items[position]) {
                is ChatItem.Day -> dayView(it.text)
                is ChatItem.Message -> messageView(conv, isGroup, it.m, it.header, names, presence)
            }
    }

    private fun dayView(text: String): View {
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)) }
        box.addView(TextView(this).apply {
            this.text = text; textSize = 12f; setTextColor(th.textDim)
            background = Ui.rounded(th.tint(th.textDim, 0x22), dp(12)); setPadding(dp(12), dp(4), dp(12), dp(4))
        })
        return box
    }

    private fun messageView(
        conv: String, isGroup: Boolean, m: Msg, header: Boolean,
        names: Map<String, String>, presence: Map<String, kotlin.Pair<Long, Boolean>>,
    ): View {
        val mine = m.outgoing
        val name = names[m.sender] ?: ("Member " + m.sender.take(4))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(dp(8), dp(if (header) 8 else 2), dp(8), dp(2))
        }
        if (!mine && isGroup) {
            val slot: View = if (header) avatar(name, m.sender, 34).also { it.setOnClickListener { openPerson(conv, m.sender) } } else View(this)
            row.addView(slot, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(6) })
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bubble(if (mine) th.bubbleMine else th.bubbleOther, mine, dp(16), dp(4))
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }
        if (!mine && header) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (isGroup) head.addView(TextView(this).apply {
                text = name; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(th.colorForText(m.sender))
                setOnClickListener { openPerson(conv, m.sender) }
            })
            val pr = presence[m.sender]
            if (pr != null) {
                val fresh = !pr.second && pr.first <= 15
                head.addView(TextView(this).apply {
                    text = presenceText(pr); textSize = 11f
                    setTextColor(if (fresh) th.live else th.textDim)
                    background = Ui.rounded(th.tint(if (fresh) th.live else th.textDim, 0x22), dp(10))
                    setPadding(dp(8), dp(2), dp(8), dp(2)); setOnClickListener { openPerson(conv, m.sender) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (isGroup) leftMargin = dp(8) })
            }
            if (head.childCount > 0) bubble.addView(head)
        }
        val body = TextView(this).apply {
            text = m.text; textSize = 16f; setTextColor(if (mine) Color.WHITE else th.text)
            includeFontPadding = false
        }
        // Give the text an explicit width taken from its own paint plus slack. (Letting the view size itself clipped the
        // last letter on this phone, and a TextView clips to its content area, so padding does not help.)
        val maxTextW = (resources.displayMetrics.widthPixels * 0.72).toInt()
        val widest = m.text.split('\n').maxOf { body.paint.measureText(it) }
        val textW = minOf(Math.ceil(widest.toDouble()).toInt() + dp(12), maxTextW)
        bubble.addView(body, LinearLayout.LayoutParams(textW, ViewGroup.LayoutParams.WRAP_CONTENT))
        bubble.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts)); textSize = 11f
            setTextColor(if (mine) 0xB3FFFFFF.toInt() else th.textDim)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun Theme.colorForText(id: String): Int {
        val c = colorFor(id)
        return if (dark) Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2) else c
    }

    private fun openPerson(conv: String, sender: String) = go(Screen.Map(conv, sender))

    // ------------------------------------------------------------ map

    private fun mapScreen(conv: String, focus: String?) {
        mapConv = conv
        val isGroup = conv.startsWith("g:")
        val id = conv.substring(2)
        val title = Node.read { e -> if (isGroup) e.group(id)?.name ?: "Group" else e.contact(id)?.name ?: "Contact" }
        topBar(title, "Map", onBack = { go(Screen.Chat(conv)) })

        val frame = FrameLayout(this)
        val mv = MapView(this)
        mapView = mv
        mv.focus(focus)
        mv.onPlacePick = { lat, lon -> savePlaceDialog(lat, lon) }
        mv.onPlaceEdit = { id -> Node.places().firstOrNull { it.id == id }?.let { placeProperties(it) } }
        mv.onPlaceMoved = { id, lat, lon ->
            try { Node.movePlace(id, lat, lon); toast("Place moved") } catch (e: Exception) { toast("Could not move: ${e.message}"); refresh() }
        }
        frame.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun round(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 20f; setTextColor(th.text); gravity = Gravity.CENTER
            background = Ui.circle(th.surface); elevation = dp(3).toFloat(); setOnClickListener { f() }
        }
        ctl.addView(round("+") { mv.zoomBy(1.0) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(8) })
        ctl.addView(round("−") { mv.zoomBy(-1.0) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(8) })
        ctl.addView(round("◎") { mv.fit() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        frame.addView(ctl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(12), dp(12)) })
        shell.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val people = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peopleBox = people
        panel.addView(people)
        panel.addView(CheckBox(this).apply {
            text = "Show my own position on the map"; setTextColor(th.text)
            isChecked = Node.mapShowMe()
            setOnCheckedChangeListener { _, v -> Node.setMapShowMe(v); refreshMap() }
        })
        panel.addView(label("Places: press and hold an empty spot to save one (home, school) and get alerts when people arrive or leave. Press and hold its pin to edit it; press and drag the pin to move it.", 12f, dim = true))
        panel.addView(label(
            "Hides your marker on YOUR screen only. People see you according to \"Share my location\" in the chat menu. " +
                "Map pictures: © OpenStreetMap contributors, fetched through Tor.", 11f, dim = true,
        ))
        shell.addView(ScrollView(this).apply { addView(panel); setBackgroundColor(th.surface) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        refreshMap()
    }

    private fun refreshMap() {
        val mv = mapView ?: return
        val box = peopleBox ?: return
        val conv = mapConv
        val now = System.currentTimeMillis()
        val showMe = Node.mapShowMe()
        val entries = Node.locationEntries(conv)
        val data = Node.read { e ->
            val names = e.contactList().associate { it.id to it.name }
            val me = e.identityPublic.joinToString("") { "%02x".format(it) }
            val ids = if (conv.startsWith("g:")) e.group(conv.substring(2))?.session?.memberKeys()?.map { k -> k.joinToString("") { "%02x".format(it) } } ?: emptyList()
            else listOf(me, conv.substring(2))
            Triple(names, me, ids)
        }
        val (names, me, ids) = data
        val markers = ArrayList<MapView.Marker>()
        box.removeAllViews()
        // Me first, then the others in the order of the member list.
        for (pid in (listOf(me) + ids.filter { it != me }).distinct()) {
            val isMe = pid == me
            val who = if (isMe) "Me" else names[pid] ?: ("Member " + pid.take(4))
            val en = entries.firstOrNull { it.sender == pid }
            val p = en?.latest
            val age = if (p == null) 0L else ((now - p.timestamp) / 60_000L).coerceAtLeast(0)
            val ageText = if (age < 1) "just now" else if (age < 60) "$age min ago" else "${age / 60} h ago"
            val status = when {
                en == null || (p == null && !en.paused) -> "Not sharing with you"
                en.paused -> "Sharing paused"
                else -> "±${p!!.accuracyM} m · $ageText" + if (age > 15) " (old)" else ""
            }
            val shown = p != null && en != null && !en.paused && !(isMe && !showMe)
            if (shown) markers.add(MapView.Marker(pid, who, p!!.lat, p.lon, p.accuracyM, age, en!!.trail.map { doubleArrayOf(it.lat, it.lon) }, isMe))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    if (shown) mv.focus(pid) else toast(if (isMe) "You are not sharing here yet (chat menu, Share my location)" else "$who is not sharing their location with you")
                }
            }
            row.addView(avatar(who, pid, 36), LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = who; textSize = 15f; setTextColor(th.text); setTypeface(typeface, Typeface.BOLD) })
            col.addView(TextView(this).apply {
                text = status; textSize = 13f
                setTextColor(if (shown && age <= 15) th.live else th.textDim)
            })
            row.addView(col)
            box.addView(row)
        }
        mv.setPlaces(Node.places().map { MapView.PlaceShape(it.id, it.name, it.lat, it.lon, it.radiusM) })
        mv.setMarkers(markers)
    }

    // ------------------------------------------------------------ pairing

    private fun pairScreen() {
        topBar("Add contact", "Pair in person", onBack = {
            Node.cancelPairing(); Node.pendingPairing = null; confirmName = null; go(Screen.Home)
        })
        val col = scrollColumn()
        val done = Node.pendingPairing
        if (done != null) {
            col.addView(label("Safety code", 14f, true))
            col.addView(label(done.safetyCode, 34f, true, mono = true))
            col.addView(label(
                "Read this code out to the other person and listen to theirs. BOTH phones must show exactly the same code. " +
                    "If they differ, someone else may have used your code: tap the red button.", 14f,
            ))
            val nameBox = styledEdit("Name for this contact", confirmName ?: done.contact.name) { confirmName = it }
            col.addView(nameBox)
            col.addButton("Codes match") {
                val n = (confirmName ?: done.contact.name).trim().ifEmpty { "Contact" }.take(40)
                Node.use { it.renameContact(done.contact.id, n); it.confirmVerified(done.contact.id) }
                Node.pendingPairing = null; confirmName = null; go(Screen.Home)
            }
            col.addView(button("Codes do NOT match: delete this contact", filled = false) {
                Node.use { it.removeContact(done.contact.id) }
                Node.pendingPairing = null; confirmName = null; go(Screen.Home)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            return
        }
        col.addView(label(
            "1. Show your code. The other person scans it (or pastes it, in the test build). You only need ONE scan: " +
                "your phone then shows the safety code by itself.\n" +
                "2. Read the safety code out loud on both phones and confirm they match.\n" +
                "Do not photograph or share the code: it contains secrets. This screen blocks screenshots.", 13f, dim = true,
        ))
        val name = styledEdit("Name for this contact", pairName) { pairName = it.trim().ifEmpty { "Contact" } }
        col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val offer = Node.pairingOffer()
        if (offer == null) {
            col.addView(label("Waiting for the connection to be ready (can take a minute or two)…", 13f, true))
        } else {
            col.addView(ImageView(this).apply {
                setImageBitmap(qr("thornberry1:" + Base64.encodeToString(offer, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)))
                setBackgroundColor(Color.WHITE); adjustViewBounds = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            col.addButton("Scan their code") {
                IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("Scan their Thornberry code").setBeepEnabled(false)
                    .setOrientationLocked(false).setBarcodeImageEnabled(false).initiateScan()
            }
            if (BuildConfig.DEBUG) {
                // TEST BUILDS ONLY: lets two copies on one phone pair without a camera. The clipboard is
                // readable by other apps, so this does not exist in release builds.
                col.addView(label("Test-only shortcut (not in release builds):", 12f, true, dim = true))
                col.addButton("Copy my code as text", filled = false) {
                    val txt = "thornberry1:" + Base64.encodeToString(offer, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                    val clip = android.content.ClipData.newPlainText("code", txt)
                    if (Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                    toast("Copied (test only)")
                }
                col.addButton("Paste their code", filled = false) {
                    val txt = getSystemService(android.content.ClipboardManager::class.java)
                        .primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                    handleScanned(txt)
                    render()
                }
            }
        }
    }

    private fun styledEdit(hintText: String, initial: String, onChange: (String) -> Unit) = EditText(this).apply {
        hint = hintText; setText(initial); isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
        setHintTextColor(th.textDim); setTextColor(th.text)
        background = Ui.rounded(th.inputBg, dp(14)); setPadding(dp(14), dp(12), dp(14), dp(12))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { onChange(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ------------------------------------------------------------ places (geofences)

    private fun savePlaceDialog(lat: Double, lon: Double) {
        if (Node.placesUnreadable()) { toast("Saved places could not be read; nothing was changed"); return }
        val input = EditText(this).apply { hint = "Name (Home, School…)"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Save this spot as a place")
            .setMessage("You get an alert when people arrive or leave. Places stay on this phone only; nobody else is told.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Place" }.take(40)
                try {
                    Node.addPlace(name, lat, lon, 200)
                    toast("Saved \"$name\" (200 m). Press and hold its pin to change the size or who to alert about.")
                    refresh()
                } catch (e: Exception) { toast("Could not save: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun placesScreen() {
        topBar("Places", "Alerts are worked out on this phone", onBack = { go(Screen.Home) })
        val col = scrollColumn()
        if (Node.placesUnreadable()) col.addView(label("Saved places could not be read, so they are off. Nothing was overwritten.", 14f, true))
        col.addView(label(
            "Open a chat's map and press and hold on a spot to save a place. You are alerted when someone sharing their location " +
                "with you arrives or leaves. Places are never sent to anyone.", 13f, dim = true,
        ))
        col.addView(label(
            "People who share at \"About 1 km\" or \"City level\" cannot trigger alerts, because the app cannot tell whether they are " +
                "inside or outside. \"About 100 m\" works for places of 200 m or more.", 12f, dim = true,
        ))
        val names = Node.read { e -> e.contactList().associate { it.id to it.name } }
        val places = Node.places()
        col.addView(label("Your places", 16f, true).apply { setPadding(0, dp(12), 0, 0) })
        if (places.isEmpty()) col.addView(label("None yet.", 14f, dim = true))
        for (pl in places) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { placeProperties(pl) }
            }
            row.addView(label(pl.name, 16f, true))
            val who = pl.watch?.let { w -> if (w.isEmpty()) "nobody" else w.joinToString { names[it] ?: "a contact" } } ?: "everyone sharing with you"
            row.addView(label("${pl.radiusM} m radius · alerts for $who", 13f, dim = true))
            col.addView(row)
            col.addView(View(this).apply { setBackgroundColor(th.divider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        col.addView(label("Recent activity", 16f, true).apply { setPadding(0, dp(16), 0, 0) })
        val events = Node.placeEvents().take(30)
        if (events.isEmpty()) col.addView(label("Nothing yet.", 14f, dim = true))
        for (ev in events) {
            val who = names[ev.person] ?: "A member"
            col.addView(label(
                "$who ${if (ev.arrived) "arrived at" else "left"} ${ev.placeName} · ${timeLabel(ev.atMs)}" + if (ev.notify) "" else " (earlier)",
                14f,
            ))
        }
    }

    private fun radiusForProgress(p: Int): Int {
        val r = 50.0 * Math.pow(100.0, p / 100.0) // 50 m to 5 km on a log scale
        return when { r < 200 -> Math.round(r / 10.0) * 10; r < 1000 -> Math.round(r / 50.0) * 50; else -> Math.round(r / 100.0) * 100 }.toInt()
            .coerceIn(app.thornberry.crypto.PlaceBook.MIN_RADIUS_M, app.thornberry.crypto.PlaceBook.MAX_RADIUS_M)
    }

    private fun progressForRadius(r: Int): Int =
        Math.round(Math.log(r / 50.0) / Math.log(100.0) * 100).toInt().coerceIn(0, 100)

    private fun radiusText(r: Int) = if (r >= 1000) "%.1f km".format(Locale.US, r / 1000.0) else "$r m"

    /** Name, size slider, who to alert about, delete. The size shows live on the map while the slider moves. */
    private fun placeProperties(pl: app.thornberry.crypto.Place) {
        if (Node.placesUnreadable()) { toast("Saved places could not be read; nothing was changed"); return }
        val contacts = Node.read { it.contactList().map { c -> c.id to c.name } }
        var watch: Set<String>? = pl.watch
        var radius = pl.radiusM
        val mv = mapView
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val nameBox = EditText(this).apply { setText(pl.name); hint = "Name"; isSingleLine = true }
        col.addView(nameBox)
        val radiusLabel = TextView(this).apply { text = "Size (radius): ${radiusText(radius)}"; textSize = 14f; setPadding(0, dp(14), 0, dp(4)) }
        col.addView(radiusLabel)
        col.addView(android.widget.SeekBar(this).apply {
            max = 100; progress = progressForRadius(radius)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    radius = radiusForProgress(p)
                    radiusLabel.text = "Size (radius): ${radiusText(radius)}"
                    mv?.setPreview(pl.id, radius)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })
        fun watchText() = "Alert me about: " + (watch?.let { w ->
            if (w.isEmpty()) "nobody" else contacts.filter { it.first in w }.joinToString { it.second }.ifEmpty { "nobody" }
        } ?: "everyone sharing with me")
        val whoBtn = Button(this).apply { isAllCaps = false; text = watchText(); textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        whoBtn.setOnClickListener {
            if (contacts.isEmpty()) { toast("Pair with someone first"); return@setOnClickListener }
            val checked = BooleanArray(contacts.size) { watch?.contains(contacts[it].first) ?: true }
            AlertDialog.Builder(this).setTitle("Alert me about…")
                .setMultiChoiceItems(contacts.map { it.second }.toTypedArray(), checked) { _, k, v -> checked[k] = v }
                .setPositiveButton("OK") { _, _ ->
                    val sel = contacts.indices.filter { checked[it] }.map { contacts[it].first }.toSet()
                    watch = if (sel.size == contacts.size) null else sel
                    whoBtn.text = watchText()
                }.setNegativeButton("Cancel", null).show()
        }
        col.addView(whoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.addView(TextView(this).apply {
            text = "To move it, press and drag its pin on the map."; textSize = 12f; setPadding(0, dp(10), 0, 0)
        })
        val dlg = AlertDialog.Builder(this).setTitle("Place")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                try {
                    Node.updatePlace(pl.id, nameBox.text.toString().trim().ifEmpty { pl.name }.take(40), radius, watch)
                    refresh()
                } catch (e: Exception) { toast("Could not save: ${e.message}") }
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this).setTitle("Delete ${pl.name}?")
                    .setPositiveButton("Delete") { _, _ -> try { Node.removePlace(pl.id); refresh() } catch (e: Exception) { toast("Could not delete: ${e.message}") } }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Cancel", null).create()
        dlg.setOnDismissListener { mv?.setPreview(null, 0) }
        dlg.show()
        // Keep the map visible behind the dialog so the circle can be seen changing size.
        dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    // ------------------------------------------------------------ updates

    private fun startUpdate() {
        val upd = Updater.available ?: return
        if (Updater.busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("Allow updates")
                .setMessage("Android needs your permission for Thornberry to install its own updates. Turn on \"Allow from this source\", then come back and tap the update again.")
                .setPositiveButton("Open settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) { toast("Could not open settings") }
                }.setNegativeButton("Cancel", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Install ${upd.versionName}?")
            .setMessage(
                "Downloads %.1f MB from GitHub through Tor, checks it matches the published checksum, is newer, and is signed by the same key, then Android asks you to confirm. ".format(Locale.US, upd.size / 1e6) +
                    "Your chats and contacts are kept. Reopen Thornberry after it updates.\n\nTest build: this trusts the GitHub repository.",
            )
            .setPositiveButton("Download and install") { _, _ -> Updater.downloadAndInstall(this); refresh() }
            .setNegativeButton("Later", null).show()
    }

    // ------------------------------------------------------------ settings

    private fun settingsScreen() {
        topBar("Settings", "Thornberry test build 0.7.0", onBack = { go(Screen.Home) })
        val col = scrollColumn()
        col.addView(label("Test data only. No server. Not independently reviewed.", 12f, dim = true))
        col.addView(label(
            "Tor: ${Node.torState}\nMy address: ${if (Node.onion.isEmpty()) "(not published yet)" else Node.onion.take(12) + "…"}\n" +
                "Crypto self-test on this phone: ${Node.selfTest}", 12f, mono = true,
        ))
        col.addView(label("Connection", 16f, true).apply { setPadding(0, dp(16), 0, 0) })
        col.addButton("Allow running in background (battery)") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        col.addButton("Open app settings (autostart, battery)", filled = false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        col.addButton("Stop connection", filled = false) { startService(Intent(this, ChatService::class.java).setAction(ChatService.ACTION_STOP)) }
        col.addButton("Start connection", filled = false) { startForegroundService(Intent(this, ChatService::class.java)) }

        col.addView(label("Map", 16f, true).apply { setPadding(0, dp(16), 0, 0) })
        TileCache.init(applicationContext.filesDir)
        val cacheLabel = label("", 12f, dim = true)
        fun updateCacheLabel() {
            val mb = TileCache.sizeBytes() / 1024.0 / 1024.0
            cacheLabel.text = "Saved map areas on this phone: %.1f MB (max %d MB)".format(Locale.US, mb, TileCache.MAX_BYTES / 1024 / 1024)
        }
        col.addView(CheckBox(this).apply {
            text = "Save map areas I view (encrypted) so they load faster and work offline"; setTextColor(th.text)
            isChecked = Node.mapCacheEnabled()
            setOnCheckedChangeListener { _, v ->
                Node.setMapCacheEnabled(v)
                if (!v) { TileCache.clear(); updateCacheLabel() }
            }
        })
        col.addView(cacheLabel)
        updateCacheLabel()
        col.addButton("Clear saved map areas", filled = false) { TileCache.clear(); updateCacheLabel(); toast("Cleared") }
        col.addView(label(
            "Only places you have already looked at are saved (no bulk download). Saved tiles are encrypted and cannot be " +
                "listed by place, but they show how much you have viewed. Clearing destroys them.", 11f, dim = true,
        ))

        col.addView(label("Updates", 16f, true).apply { setPadding(0, dp(16), 0, 0) })
        col.addView(label("Installed: ${BuildConfig.VERSION_NAME}\n${Updater.status}", 12f, dim = true))
        col.addView(CheckBox(this).apply {
            text = "Check for updates when the app opens (through Tor)"; setTextColor(th.text)
            isChecked = Updater.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Updater.setEnabled(this@MainActivity, v) }
        })
        col.addButton("Check now", filled = false) {
            if (Node.torState != "CONNECTED") toast("Waiting for Tor to connect") else Updater.checkNow(this)
        }
        if (Updater.available != null) col.addButton("Install ${Updater.available?.versionName}") { startUpdate() }

        col.addView(label("Diagnostics", 16f, true).apply { setPadding(0, dp(16), 0, 0) })
        col.addButton("Save diagnostic log to a file", filled = false) { startLogSave() }
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

    private var pendingShare: kotlin.Pair<String, Precision>? = null

    private fun shareDialog(conv: String) {
        val opts = listOf<Precision?>(null) + Precision.values().toList()
        val who = if (conv.startsWith("g:")) "members of this group" else Node.read { it.contact(conv.substring(2))?.name ?: "this contact" }
        choiceDialog(
            "Share my location here",
            "Only $who can see it. Nothing goes to any server. Updates every few minutes, at least every 10; while you travel " +
                "your route is included. A notification stays visible while sharing.\n\nCurrently: " +
                (Node.sharePrecisionFor(conv)?.label ?: "Off"),
            opts.map { it?.label ?: "Off" },
        ) { i ->
            val p = opts[i]
            try {
                if (p == null) {
                    Node.setSharePrecisionFor(conv, null); refresh()
                } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    enableShare(conv, p)
                } else {
                    pendingShare = conv to p
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
                }
            } catch (e: Exception) {
                ChatLog.err("shareDialog", e); toast("Could not change sharing: ${e.message}")
            }
        }
    }

    private fun enableShare(conv: String, p: Precision) {
        Node.setSharePrecisionFor(conv, p)
        startForegroundService(Intent(this, ChatService::class.java).setAction(ChatService.ACTION_LOCATION_REFRESH))
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7) return
        val pend = pendingShare; pendingShare = null
        if (pend != null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { enableShare(pend.first, pend.second) } catch (e: Exception) { ChatLog.err("enableShare", e); toast("Could not start sharing: ${e.message}") }
        } else {
            toast("Location permission is needed to share. Nothing is shared.")
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

    // ------------------------------------------------------------ scanning and files

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
