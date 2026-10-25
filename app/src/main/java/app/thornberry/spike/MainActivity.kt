package app.thornberry.spike

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Test screen for the Tor feasibility spike. Test data only. Shows Tor state, this copy's onion
 * address, and a live event tail; lets you paste the peer's address and share a summary.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var tail: TextView
    private lateinit var peerInput: EditText
    private lateinit var intervalInput: EditText

    private val refresh = object : Runnable {
        override fun run() {
            status.text = buildStatus()
            tail.text = SpikeLog.recentLines(40).joinToString("\n")
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        val prefs = getSharedPreferences("spike", MODE_PRIVATE)
        val pad = dp(16)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun label(text: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        col.addView(label("Thornberry spike (test build)", 20f, true))
        col.addView(label("Version 0.0.2-spike. Test data only. Not for real use.", 12f))

        status = label("", 13f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Start") { startSpike() })
        row1.addView(button("Stop") {
            startService(Intent(this, SpikeService::class.java).setAction(SpikeService.ACTION_STOP))
        })
        col.addView(row1)

        col.addView(button("Copy my onion address") {
            val addr = SpikeState.onion
            if (addr.isEmpty()) {
                toast("Not published yet")
            } else {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("onion", addr))
                toast("Copied")
            }
        })

        col.addView(label("Peer onion address (paste the other copy's address):", 13f))
        peerInput = EditText(this).apply {
            setText(prefs.getString("peer", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            textSize = 12f
        }
        col.addView(peerInput)

        col.addView(label("Ping interval in minutes (1 to 60):", 13f))
        intervalInput = EditText(this).apply {
            setText(prefs.getInt("intervalMin", 2).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        col.addView(intervalInput)

        col.addView(button("Save peer and interval (restart service to apply interval)") {
            val interval = intervalInput.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 2
            prefs.edit()
                .putString("peer", peerInput.text.toString().trim())
                .putInt("intervalMin", interval)
                .apply()
            toast("Saved")
        })

        col.addView(label("Background settings (needed on Xiaomi):", 13f, true))
        col.addView(button("Battery optimization exemption") { requestBatteryExemption() })
        col.addView(button("Open app settings (autostart, battery)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        })

        col.addView(label("Results:", 13f, true))
        col.addView(button("Share summary and recent log") {
            val text = SpikeLog.summary() + "\n--- recent events ---\n" +
                SpikeLog.recentLines(150).joinToString("\n")
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    "Share summary",
                ),
            )
        })
        col.addView(button("Clear log") {
            SpikeLog.clear()
            toast("Log cleared")
        })

        tail = label("", 11f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0) }
        col.addView(tail)

        val scroll = ScrollView(this).apply {
            addView(col)
            // Android 15+ draws app content behind the system bars; pad around them.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun startSpike() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, SpikeService::class.java))
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun buildStatus(): String {
        val onion = SpikeState.onion
        return buildString {
            append("Service: ").append(if (SpikeState.serviceRunning) "running" else "stopped").append('\n')
            append("Tor: ").append(SpikeState.torState).append(" (bootstrap ").append(SpikeState.bootstrap).append("%)\n")
            append("Last ping: ").append(SpikeState.lastPing).append('\n')
            append("My address: ").append(if (onion.isEmpty()) "(not published)" else onion)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
