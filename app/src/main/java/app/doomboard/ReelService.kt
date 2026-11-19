package app.doomboard

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.concurrent.thread

// Counts Instagram Reels. Only receives events from the Instagram app (see res/xml/reel_service.xml).
//  1. "On the Reels screen?" = a known Reels view ID exists on screen (REEL_VIEW_IDS).
//  2. "New reel?" = the vertical pager's visible item index changed (event.fromIndex).
//     If Instagram doesn't report indexes, fall back to one count per burst of scroll events.
// Tested on Samsung SM-E156B / Android 16: index-based detection counts one per swipe.
class ReelService : AccessibilityService() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())

    private var lastIndex = -1
    private var lastReelAt = 0L   // for watch time; reset when leaving Reels
    private var lastCountAt = 0L  // for sessions; survives leaving Reels
    private var lastScrollAt = 0L
    private var inReels = false
    private var inReelsCheckedAt = 0L
    private var uploadPending = false

    private var overlay: TextView? = null
    private val hideOverlay = Runnable { overlay?.visibility = View.GONE }

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
        if (!found) {
            lastIndex = -1
            lastReelAt = 0L
            main.post(hideOverlay)
        }
        inReels = found
        return found
    }

    private fun countReel() {
        val now = SystemClock.elapsedRealtime()
        // watch time ~ time since the previous reel, capped so idle time doesn't count
        val secs = if (lastReelAt == 0L) 0 else ((now - lastReelAt) / 1000).coerceAtMost(60).toInt()
        val newSession = lastCountAt == 0L || now - lastCountAt > SESSION_GAP_MS
        lastReelAt = now
        lastCountAt = now
        val day = store.add(1, secs, newSession)
        Debug.log("REEL +1 (+${secs}s${if (newSession) ", new session" else ""}), today=${day.reels}")
        showOverlay(day.reels)
        Stats.limitAlert(day.reels, store.limit)?.let(::notifyLimit)
        scheduleUpload()
    }

    // --- pop-up counter: drawn by the accessibility service itself, so no "draw over apps" permission ---
    private fun showOverlay(reels: Int) {
        if (!store.overlay) return
        main.post {
            val tv = overlay ?: createOverlay() ?: return@post
            val limit = store.limit
            val over = limit in 1..reels
            tv.text = if (over) "🛑 $reels / $limit" else "${Fun.titleFor(reels).second} $reels reels"
            (tv.background as GradientDrawable).setColor(if (over) 0xFFE5383B.toInt() else 0xFFFF5FA2.toInt())
            tv.visibility = View.VISIBLE
            tv.scaleX = 1.35f
            tv.scaleY = 1.35f
            tv.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
            main.removeCallbacks(hideOverlay)
            main.postDelayed(hideOverlay, OVERLAY_MS)
        }
    }

    private fun createOverlay(): TextView? = runCatching {
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = resources.getFont(R.font.fredoka)
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(0xFFFF5FA2.toInt()) }
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // never takes focus or touches: taps go straight through to Instagram
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (56 * d).toInt()
        }
        getSystemService(WindowManager::class.java).addView(tv, lp)
        overlay = tv
        tv
    }.onFailure { Debug.log("overlay failed: ${it.message}") }.getOrNull()

    // --- limit alerts ---
    private fun notifyLimit(msg: String) {
        Debug.log("limit alert: $msg")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Doomscroll limit alerts", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Debug.log("no notification permission")
            return
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_doomboard)
            .setContentTitle("Doomboard")
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
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

    override fun onDestroy() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    companion object {
        const val IG = "com.instagram.android"
        // ponytail: guessed from Instagram's internal naming; one matched on Android 16. Re-check with the debug log if Instagram updates.
        val REEL_VIEW_IDS = listOf("clips_viewer_view_pager", "clips_viewer_container", "clips_video_container")
        const val BURST_GAP_MS = 700L
        const val SESSION_GAP_MS = 5 * 60_000L // 5 min without a new reel = next session
        const val OVERLAY_MS = 4_000L
        const val CHANNEL = "limit"
    }
}
