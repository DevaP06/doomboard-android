package app.doomboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var font: Typeface
    private val main = Handler(Looper.getMainLooper())

    private lateinit var scroll: ScrollView
    private lateinit var greeting: TextView
    private lateinit var roast: TextView
    private lateinit var onboardBox: LinearLayout
    private lateinit var mood: TextView
    private lateinit var count: TextView
    private lateinit var title: TextView
    private lateinit var stats: TextView
    private lateinit var bars: LinearLayout
    private lateinit var kpiGrid: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var serviceBox: LinearLayout
    private lateinit var boardBox: LinearLayout
    private lateinit var debugText: TextView

    private var mode: String? = null // "join" (opted-out user came back) | "rename"
    private var boardLoadedAt = 0L
    private var statsSignature: String? = null // skip re-rendering stats when nothing changed

    // Refresh every second while visible; reload the board every 30s.
    private val tick = object : Runnable {
        override fun run() {
            renderStats()
            renderGreeting()
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
        greeting = text("", 22f).apply { gravity = Gravity.START }
        roast = text("", 14f, alpha = 0.75f).apply { gravity = Gravity.START }
        col.addView(greeting, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(roast, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        onboardBox = vbox()
        col.addView(onboardBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        mood = text("", 44f)
        count = text("0", 72f, PINK)
        col.addView(mood, lp(top = 10))
        col.addView(count)
        col.addView(text("reels today", 14f, alpha = 0.7f))
        title = text("", 16f).apply { background = pill(Color.WHITE); setPadding(dp(14), dp(4), dp(14), dp(4)) }
        col.addView(title, lp(top = 8))
        stats = text("", 14f)
        col.addView(stats, lp(top = 8))
        bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(bars, LinearLayout.LayoutParams(MATCH_PARENT, dp(90)).apply { topMargin = dp(12) })
        col.addView(text("reels, last 7 days", 11f, alpha = 0.6f))
        col.addView(text("📊 Insights", 18f), lp(top = 18))
        kpiGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kpiGrid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(divider())
        settingsBox = vbox()
        col.addView(settingsBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

        scroll = ScrollView(this).apply {
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
        statsSignature = null
        renderGreeting()
        renderService()
        renderSettings()
        renderBoard()
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    // --- stats ---
    private fun renderStats() {
        val today = LocalDate.now()
        val hour = LocalTime.now().hour
        val limit = store.limit
        val sig = "${store.raw()}|$limit|$today|$hour"
        if (sig == statsSignature) return
        statsSignature = sig

        val all = store.allDays()
        val d = all[today] ?: Day()
        val (_, emoji, t) = Fun.titleFor(d.reels)
        mood.text = emoji
        count.text = d.reels.toString()
        count.setTextColor(if (limit in 1..d.reels) RED else PINK)
        title.text = if (limit > 0) "$t · ${d.reels}/$limit" else t
        val avg = if (d.reels > 0) d.secs / d.reels else 0
        stats.text = "⏱ ${d.secs / 60} min · ⚡ ${avg}s/reel · 🧠 rot ${Fun.rotScore(d.reels, d.secs)}/100"

        // 7-day bars: today pink, days over the limit red
        bars.removeAllViews()
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val reels = week.map { all[it]?.reels ?: 0 }
        val max = maxOf(1, reels.max())
        week.forEachIndexed { i, day ->
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            val color = when { limit in 1 until reels[i] -> RED; i == 6 -> PINK; else -> 0xFFD6CCFF.toInt() }
            val bar = View(this).apply { background = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat() } }
            c.addView(bar, LinearLayout.LayoutParams(dp(22), maxOf(dp(2), reels[i] * dp(60) / max)))
            c.addView(text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), 11f))
            bars.addView(c, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }

        // KPI tiles, two per row
        kpiGrid.removeAllViews()
        Stats.kpis(all, today, hour, limit).chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { k ->
                val tile = vbox().apply {
                    background = GradientDrawable().apply { setColor(0xB3FFFFFF.toInt()); cornerRadius = dp(16).toFloat() }
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                }
                tile.addView(text("${k.emoji} ${k.value}", 20f))
                tile.addView(text(k.label, 11f, alpha = 0.7f))
                row.addView(tile, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            kpiGrid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    // --- settings: daily limit alerts + pop-up counter ---
    private fun renderSettings() {
        if (::onboardBox.isInitialized) renderOnboarding() // setting a limit ticks the checklist
        settingsBox.removeAllViews()
        val limit = store.limit
        settingsBox.addView(text("⏰ Daily doomscroll limit", 16f))
        settingsBox.addView(text(
            if (limit > 0) "Alerts at ${Math.round(limit * 0.8)} and $limit reels, then every 20 more."
            else "No limit set. Set one to get alerts and streaks.", 12f, alpha = 0.7f), lp(top = 4))

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = if (limit > 0) "$limit" else "e.g. 100"
            filters = arrayOf(InputFilter.LengthFilter(4))
            typeface = font
            textSize = 16f
            setTextColor(INK)
            gravity = Gravity.CENTER
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(dp(100), WRAP_CONTENT))
        row.addView(button("Set") {
            val v = input.text.toString().toIntOrNull()
            if (v == null || v !in 1..5000) {
                Toast.makeText(this, "Pick a number from 1 to 5000", Toast.LENGTH_SHORT).show()
                return@button
            }
            store.limit = v
            askNotificationPermission()
            statsSignature = null
            renderSettings()
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        if (limit > 0) row.addView(link("turn off") { store.limit = 0; statsSignature = null; renderSettings() })
        settingsBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        val popup = CheckBox(this).apply {
            text = "Show a pop-up counter while scrolling Reels"
            isChecked = store.overlay
            typeface = font
            setTextColor(INK)
            setOnCheckedChangeListener { _, on -> store.overlay = on }
        }
        settingsBox.addView(popup, lp(top = 10))
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // --- greeting + first-run setup checklist ---
    private fun renderGreeting() {
        greeting.text = Fun.greeting(LocalTime.now().hour, store.profile?.nick)
        roast.text = Fun.roast(store.day().reels, store.limit)
    }

    private fun renderOnboarding() {
        onboardBox.removeAllViews()
        val counting = serviceEnabled()
        val limitSet = store.limit > 0
        val boardChosen = store.profile != null || store.optedOut
        if (!store.onboarded && counting && limitSet && boardChosen) {
            store.onboarded = true
            Toast.makeText(this, "You're all set! Go scroll (responsibly) 🫡", Toast.LENGTH_LONG).show()
        }
        if (store.onboarded) { onboardBox.visibility = View.GONE; return }

        onboardBox.visibility = View.VISIBLE
        onboardBox.background = GradientDrawable().apply { setColor(0xD9FFFFFF.toInt()); cornerRadius = dp(20).toFloat() }
        onboardBox.setPadding(dp(16), dp(14), dp(16), dp(10))
        onboardBox.addView(text("✨ Let's get you set up", 18f))
        onboardBox.addView(text("${listOf(counting, limitSet, boardChosen).count { it }} of 3 done", 12f, alpha = 0.6f))

        setupStep(counting, "Turn on Reel counting", "So Doomboard can notice your swipes in Instagram")
            ?.addView(button("Turn on") { showDisclosure() })
        setupStep(limitSet, "Set a daily limit", "Get a nudge before you're fully cooked")?.let { box ->
            val chips = LinearLayout(this)
            for (n in listOf(50, 100, 200)) chips.addView(button("$n") {
                store.limit = n
                askNotificationPermission()
                statsSignature = null
                renderSettings()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
            box.addView(chips)
        }
        setupStep(boardChosen, "Join the leaderboard, or stay private", "Your call, you can change it later")
            ?.addView(button("Choose") { scroll.smoothScrollTo(0, boardBox.top) })

        onboardBox.addView(link("skip setup") { store.onboarded = true; renderOnboarding() })
    }

    // One checklist row. Returns a container for the step's action when it isn't done yet.
    private fun setupStep(done: Boolean, title: String, hint: String): LinearLayout? {
        val row = LinearLayout(this).apply { setPadding(0, dp(10), 0, 0) }
        row.addView(text(if (done) "✅" else "⬜", 18f), LinearLayout.LayoutParams(dp(34), WRAP_CONTENT))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(title, 15f, alpha = if (done) 0.5f else 1f).apply { gravity = Gravity.START })
        if (!done) body.addView(text(hint, 12f, alpha = 0.65f).apply { gravity = Gravity.START })
        row.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        onboardBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (done) return null
        val action = LinearLayout(this).apply { setPadding(dp(34), dp(6), 0, dp(4)) }
        onboardBox.addView(action, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return action
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
        renderOnboarding() // joining / staying private ticks the checklist
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
        private val RED = 0xFFE5383B.toInt()
    }
}
