package app.doomboard

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

// On-device storage: daily counts + leaderboard profile. Same shape as the extension's storage.
class Store(ctx: Context) {
    data class Day(val reels: Int, val secs: Int)
    data class Profile(val id: String, val nick: String)

    private val p = ctx.applicationContext.getSharedPreferences("doomboard", Context.MODE_PRIVATE)

    private fun days() = JSONObject(p.getString("days", "{}")!!)

    fun day(date: LocalDate = LocalDate.now()): Day =
        days().optJSONObject(date.toString())?.let { Day(it.optInt("reels"), it.optInt("secs")) } ?: Day(0, 0)

    @Synchronized
    fun add(reels: Int, secs: Int) {
        val all = days()
        val key = LocalDate.now().toString()
        val d = all.optJSONObject(key) ?: JSONObject().put("reels", 0).put("secs", 0)
        d.put("reels", d.optInt("reels") + reels).put("secs", d.optInt("secs") + secs)
        p.edit().putString("days", all.put(key, d).toString()).apply()
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
}
