package app.doomboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

// On-device storage: daily counts (with per-hour buckets and sessions), settings, leaderboard profile.
class Store(ctx: Context) {
    data class Profile(val id: String, val nick: String)

    private val p = ctx.applicationContext.getSharedPreferences("doomboard", Context.MODE_PRIVATE)

    fun raw(): String = p.getString("days", "{}")!!
    private fun days() = JSONObject(raw())

    private fun parse(o: JSONObject) = Day(
        reels = o.optInt("reels"),
        secs = o.optInt("secs"),
        hours = o.optJSONArray("h")?.let { a -> List(24) { a.optInt(it) } } ?: List(24) { 0 },
        sessions = o.optInt("sessions"),
    )

    fun day(date: LocalDate = LocalDate.now()): Day = days().optJSONObject(date.toString())?.let(::parse) ?: Day()

    fun allDays(): Map<LocalDate, Day> {
        val o = days()
        return o.keys().asSequence()
            .mapNotNull { k -> runCatching { LocalDate.parse(k) to parse(o.getJSONObject(k)) }.getOrNull() }
            .toMap()
    }

    @Synchronized
    fun add(reels: Int, secs: Int, newSession: Boolean): Day {
        val all = days()
        val key = LocalDate.now().toString()
        val d = all.optJSONObject(key) ?: JSONObject()
        val h = d.optJSONArray("h") ?: JSONArray(List(24) { 0 })
        val hour = LocalTime.now().hour
        h.put(hour, h.optInt(hour) + reels)
        d.put("reels", d.optInt("reels") + reels).put("secs", d.optInt("secs") + secs).put("h", h)
        if (newSession) d.put("sessions", d.optInt("sessions") + 1)
        p.edit().putString("days", all.put(key, d).toString()).apply()
        return parse(d)
    }

    var profile: Profile?
        get() = p.getString("profile_id", null)?.let { Profile(it, p.getString("profile_nick", "")!!) }
        set(v) {
            val e = p.edit()
            if (v == null) e.remove("profile_id").remove("profile_nick")
            else e.putString("profile_id", v.id).putString("profile_nick", v.nick)
            e.apply()
        }

    var optedOut: Boolean
        get() = p.getBoolean("opted_out", false)
        set(v) { p.edit().putBoolean("opted_out", v).apply() }

    // Daily reel limit for alerts. 0 = off.
    var limit: Int
        get() = p.getInt("limit", 0)
        set(v) { p.edit().putInt("limit", v).apply() }

    // Show the pop-up counter over Instagram while scrolling Reels.
    var overlay: Boolean
        get() = p.getBoolean("overlay", true)
        set(v) { p.edit().putBoolean("overlay", v).apply() }
}
