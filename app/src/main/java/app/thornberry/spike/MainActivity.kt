package app.thornberry.spike

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.TextView

/** Pipeline check only: proves the build, install and launch path works. No networking, no data. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 18f
            text = "Thornberry spike\n" +
                "Build pipeline check: OK\n" +
                "Version ${BuildConfigInfo.VERSION}\n" +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Device ${Build.MANUFACTURER} ${Build.MODEL}"
        }
        setContentView(view)
    }
}

private object BuildConfigInfo {
    const val VERSION = "0.0.1-spike"
}
