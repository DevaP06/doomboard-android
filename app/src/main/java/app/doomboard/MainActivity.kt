package app.doomboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var font: Typeface
    private val main = Handler(Looper.getMainLooper())

    private lateinit var mood: TextView
    private lateinit var count: TextView
    private lateinit var title: TextView
    private lateinit var stats: TextView
    private lateinit var bars: LinearLayout
    private lateinit var serviceBox: LinearLayout
    private lateinit var boardBox: LinearLayout
    private lateinit var debugText: TextView

    private var mode: String? = null // "join" (opted-out user came back) | "rename"
    private var boardLoadedAt = 0L

    // Refresh every second while visible; reload the board every 30s.
    private val tick = object : Runnable {
        override fun run() {
            renderStats()
            if (Debug.enabled) debugText.text = Debug.text()
            if (store.profile != null && mode == null && System.currentTimeMillis() - boardLoadedAt > 30_000) renderBoard()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        font = resources.getFont(R.font.fredoka)

        val col = vbox().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        mood = text("", 44f)
        count = text("0", 72f, PINK)
        col.addView(mood)
        col.addView(count)
        col.addView(text("reels today", 14f, alpha = 0.7f))
        title = text("", 16f).apply { background = pill(Color.WHITE); setPadding(dp(14), dp(4), dp(14), dp(4)) }
        col.addView(title, lp(top = 8))
        stats = text("", 14f)
        col.addView(stats, lp(top = 8))
        bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(bars, LinearLayout.LayoutParams(MATCH_PARENT, dp(90)).apply { topMargin = dp(12) })
        col.addView(text("reels, last 7 days", 11f, alpha = 0.6f))
        serviceBox = vbox()
        col.addView(serviceBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        col.addView(divider())
        boardBox = vbox()
        col.addView(boardBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(divider())
        // Hidden developer toggle: long-press the big number to show/hide the debug log.
        debugText = text("", 10f).apply { gravity = Gravity.START; typeface = Typeface.MONOSPACE; visibility = View.GONE }
        count.setOnLongClickListener {
            Debug.enabled = !Debug.enabled
            debugText.visibility = if (Debug.enabled) View.VISIBLE else View.GONE
            debugText.text = Debug.text()
            Toast.makeText(this, "debug log ${if (Debug.enabled) "on" else "off"}", Toast.LENGTH_SHORT).show()
            true
        }
        col.addView(debugText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            addView(col)
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFE4F1.toInt(), 0xFFE6E0FF.toInt()))
        }
        // Keep content clear of the status and navigation bars (edge-to-edge on new Android).
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = systemBarInsets(insets)
            v.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        renderService()
        renderBoard()
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    // --- stats ---
    private fun renderStats() {
        val d = store.day()
        val (_, emoji, t) = Fun.titleFor(d.reels)
        mood.text = emoji
        count.text = d.reels.toString()
        title.text = t
        val avg = if (d.reels > 0) d.secs / d.reels else 0
        stats.text = "⏱ ${d.secs / 60} min · ⚡ ${avg}s/reel · 🧠 rot ${Fun.rotScore(d.reels, d.secs)}/100"

        bars.removeAllViews()
        val week = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
        val reels = week.map { store.day(it).reels }
        val max = maxOf(1, reels.max())
        week.forEachIndexed { i, day ->
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            val bar = View(this).apply {
                background = GradientDrawable().apply { setColor(if (i == 6) PINK else 0xFFD6CCFF.toInt()); cornerRadius = dp(4).toFloat() }
            }
            c.addView(bar, LinearLayout.LayoutParams(dp(22), maxOf(dp(2), reels[i] * dp(60) / max)))
            c.addView(text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), 11f))
            bars.addView(c, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
    }

    // --- turning on the counter (Accessibility Service) ---
    private fun serviceEnabled(): Boolean {
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val full = "$packageName/${ReelService::class.java.name}"
        return on.split(':').any { it.equals(full, true) || it.equals("$packageName/.ReelService", true) }
    }

    private fun renderService() {
        serviceBox.removeAllViews()
        if (serviceEnabled()) {
            serviceBox.addView(text("✅ Counting Reels in Instagram", 13f, alpha = 0.7f))
            return
        }
        serviceBox.addView(text("Reel counting is off", 15f))
        serviceBox.addView(button("Turn on counting") { showDisclosure() }, lp(top = 6))
    }

    // Prominent disclosure before sending the user to Accessibility settings.
    private fun showDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Turn on Reel counting")
            .setMessage(getString(R.string.disclosure))
            .setPositiveButton("Agree & open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    // --- leaderboard: undecided -> join screen; opted out -> invite; joined -> board ---
    private fun renderBoard() {
        boardBox.removeAllViews()
        val profile = store.profile
        if (mode != null || (profile == null && !store.optedOut)) return renderJoin(profile)
        if (profile == null) {
            boardBox.addView(text("Your counts stay on this phone.", 12f, alpha = 0.6f))
            boardBox.addView(button("🏆 Join the leaderboard") { mode = "join"; renderBoard() }, lp(top = 6))
            return
        }
        boardBox.addView(text("🏆 Today's top scrollers", 16f))
        val rows = vbox()
        boardBox.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        rows.addView(text("loading…", 13f))
        boardBox.addView(text("You're ${profile.nick}", 14f), lp(top = 8))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(link("rename") { mode = "rename"; renderBoard() })
        actions.addView(link("leave & delete my data") { confirmLeave() })
        boardBox.addView(actions)
        boardLoadedAt = System.currentTimeMillis()

        thread {
            val r = runCatching { Api.board(store) }
            runOnUiThread {
                rows.removeAllViews()
                r.onFailure { rows.addView(text("couldn't load 😢", 13f)) }
                r.onSuccess { arr ->
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        val row = LinearLayout(this).apply {
                            setPadding(dp(10), dp(3), dp(10), dp(3))
                            if (x.optBoolean("is_me")) background = pill(Color.WHITE)
                        }
                        // Nicknames come from other users: plain TextView text, never HTML.
                        row.addView(text("#${x.optInt("rank")}  ${x.optString("nickname")}", 14f).apply { gravity = Gravity.START },
                            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        row.addView(text("${x.optInt("reels")}", 14f))
                        rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    }
                }
            }
        }
    }

    private fun renderJoin(profile: Store.Profile?) {
        val rename = mode == "rename"
        boardBox.addView(text(if (rename) "✏️ Pick a new nickname" else "🏆 Join the global leaderboard?", 16f))
        if (!rename) boardBox.addView(text(getString(R.string.join_info), 13f, alpha = 0.85f).apply { gravity = Gravity.START }, lp(top = 6))

        val input = EditText(this).apply {
            setText(if (rename) profile?.nick else Fun.randomNick())
            filters = arrayOf(InputFilter.LengthFilter(20))
            setSingleLine()
            typeface = font
            textSize = 16f
            setTextColor(INK)
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(link("🎲") { input.setText(Fun.randomNick()) })
        boardBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        val err = text("", 12f, 0xFFD6336C.toInt())
        lateinit var go: Button
        go = button(if (rename) "Save" else "Join leaderboard") {
            val nick = input.text.toString().trim().replace(Regex("\\s+"), " ")
            if (nick.length < 2) { err.text = "2–20 characters please"; return@button }
            go.isEnabled = false
            thread {
                val r = runCatching { if (rename) Api.rename(store, nick) else Api.join(store, nick) }
                runOnUiThread {
                    go.isEnabled = true
                    r.onSuccess { mode = null; renderBoard() }
                    r.onFailure { e ->
                        val m = e.message ?: ""
                        err.text = when {
                            "nickname_taken" in m -> "That name is taken 😅 try another"
                            "name_not_allowed" in m -> "Let's keep it friendly — try another name"
                            e is IOException -> "couldn't reach the server — check your connection"
                            else -> "server error — please try again later"
                        }
                    }
                }
            }
        }
        boardBox.addView(go, lp(top = 8))
        boardBox.addView(link(if (rename) "cancel" else "no thanks, stay private") {
            if (store.profile == null) store.optedOut = true
            mode = null
            renderBoard()
        })
        boardBox.addView(err)
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle("Leave the leaderboard?")
            .setMessage("This deletes your nickname and scores from the server. Your counts on this phone stay.")
            .setPositiveButton("Leave") { _, _ ->
                thread {
                    val r = runCatching { Api.leave(store) }
                    runOnUiThread {
                        if (r.isFailure) Toast.makeText(this, "couldn't reach the server — try again", Toast.LENGTH_SHORT).show()
                        renderBoard()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- small view helpers ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(99).toFloat() }

    private fun text(s: String, size: Float, color: Int = INK, alpha: Float = 1f) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        this.alpha = alpha
        typeface = font
        gravity = Gravity.CENTER
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        typeface = font
        setTextColor(Color.WHITE)
        background = pill(PINK)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setOnClickListener { onClick() }
    }

    private fun link(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(INK)
        alpha = 0.65f
        typeface = font
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x55F3B6D6)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(2)).apply { topMargin = dp(16); bottomMargin = dp(16) }
    }

    @Suppress("DEPRECATION")
    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
        else insets.systemWindowInsetTop to insets.systemWindowInsetBottom

    companion object {
        private val PINK = 0xFFFF5FA2.toInt()
        private val INK = 0xFF5B3A5E.toInt()
    }
}
