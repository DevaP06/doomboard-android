package app.doomboard

import android.util.Log
import java.time.LocalTime

// In-app debug log (toggle at the bottom of the main screen) for tuning Reels detection on a real phone.
// Also goes to logcat under the tag "Doomboard". Never logs text from Instagram, only event info and view IDs.
object Debug {
    @Volatile var enabled = false
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(s: String) {
        Log.d("Doomboard", s)
        if (!enabled) return
        lines.addLast("${LocalTime.now().withNano(0)} $s")
        while (lines.size > 60) lines.removeFirst()
    }

    @Synchronized
    fun text() = lines.reversed().joinToString("\n")
}
