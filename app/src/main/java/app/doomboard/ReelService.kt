package app.doomboard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

// Counts Instagram Reels. Only receives events from the Instagram app (see res/xml/reel_service.xml).
// Detection, v1, needs tuning on a real phone with the debug log on:
//  1. "Are we on the Reels screen?" = a known Reels view ID exists on screen (REEL_VIEW_IDS).
//  2. "New reel?" = the vertical pager's first visible item index changed (event.fromIndex).
//     If Instagram doesn't report indexes, fall back to one count per burst of scroll events.
class ReelService : AccessibilityService() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())

    private var lastIndex = -1
    private var lastReelAt = 0L
    private var lastScrollAt = 0L
    private var inReels = false
    private var inReelsCheckedAt = 0L
    private var uploadPending = false

    override fun onServiceConnected() {
        store = Store(this)
        Debug.log("service connected")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.packageName != IG) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                inReelsCheckedAt = 0 // screen changed: re-check on the next scroll
                if (Debug.enabled) dumpViewIds()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScroll(e)
        }
    }

    private fun onScroll(e: AccessibilityEvent) {
        if (!checkInReels()) return
        val idx = e.fromIndex
        Debug.log("scroll ${e.className?.toString()?.substringAfterLast('.')} from=$idx to=${e.toIndex} items=${e.itemCount}")
        val now = SystemClock.elapsedRealtime()
        if (idx >= 0) {
            if (idx != lastIndex) { // includes the first reel shown on entering Reels, like the extension
                countReel()
                lastIndex = idx
            }
        } else if (now - lastScrollAt > BURST_GAP_MS) {
            countReel() // fallback: one count per burst of scroll events
        }
        lastScrollAt = now
    }

    private fun checkInReels(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - inReelsCheckedAt < 1500) return inReels
        inReelsCheckedAt = now
        val root = rootInActiveWindow
        val found = root != null && REEL_VIEW_IDS.any { root.findAccessibilityNodeInfosByViewId("$IG:id/$it").isNotEmpty() }
        if (found != inReels) Debug.log("on Reels screen: $found")
        if (!found) { lastIndex = -1; lastReelAt = 0L }
        inReels = found
        return found
    }

    private fun countReel() {
        val now = SystemClock.elapsedRealtime()
        // watch time ~ time since the previous reel, capped so idle time doesn't count
        val secs = if (lastReelAt == 0L) 0 else ((now - lastReelAt) / 1000).coerceAtMost(60).toInt()
        lastReelAt = now
        store.add(1, secs)
        Debug.log("REEL +1 (+${secs}s), today=${store.day().reels}")
        scheduleUpload()
    }

    // Batch uploads: at most one every 30s, and only for users who joined the leaderboard.
    private fun scheduleUpload() {
        if (uploadPending || store.profile == null) return
        uploadPending = true
        main.postDelayed({
            uploadPending = false
            thread { runCatching { Api.submit(store) }.onFailure { Debug.log("upload failed: ${it.message}") } }
        }, 30_000)
    }

    // Debug only: list the view IDs (not text) on screen, to find which ones mean "Reels".
    private fun dumpViewIds() {
        val ids = linkedSetOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || ids.size > 80) return
            n.viewIdResourceName?.substringAfter(":id/")?.let { ids += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(rootInActiveWindow, 0)
        Debug.log("view ids: " + ids.joinToString(","))
    }

    override fun onInterrupt() {}

    companion object {
        const val IG = "com.instagram.android"
        // ponytail: guessed from Instagram's internal naming; confirm with the debug log's "view ids" on a real phone.
        val REEL_VIEW_IDS = listOf("clips_viewer_view_pager", "clips_viewer_container", "clips_video_container")
        const val BURST_GAP_MS = 700L
    }
}
