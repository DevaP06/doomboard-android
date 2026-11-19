package app.doomboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.UUID

// Same Supabase leaderboard functions as the browser extension. All calls block: use a background thread.
object Api {
    private const val BASE = "https://uroqjdpvjqnyokrkwqox.supabase.co"
    private const val KEY = "sb_publishable_-jnSoduh_Z594HWUb-fhPA_0aX4P99T" // public key, safe to ship

    class ApiError(body: String) : Exception(body)

    private fun rpc(fn: String, args: JSONObject): String {
        val c = URL("$BASE/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.doOutput = true
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(args.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (!ok) throw ApiError(body)
            return body
        } finally {
            c.disconnect()
        }
    }

    fun submit(store: Store) {
        val p = store.profile ?: return // nothing is ever sent unless the user joined
        val d = store.day()
        rpc("submit_score", JSONObject().put("p_user", p.id).put("p_day", LocalDate.now().toString())
            .put("p_reels", d.reels).put("p_secs", d.secs))
    }

    // Claim the nickname on the server first; only then save the profile locally.
    fun join(store: Store, nick: String) {
        val id = UUID.randomUUID().toString()
        rpc("join_board", JSONObject().put("p_user", id).put("p_nick", nick))
        store.profile = Store.Profile(id, nick)
        store.optedOut = false
        runCatching { submit(store) }
    }

    fun rename(store: Store, nick: String) {
        val p = store.profile ?: return
        rpc("rename_me", JSONObject().put("p_user", p.id).put("p_nick", nick))
        store.profile = p.copy(nick = nick)
    }

    fun leave(store: Store) {
        val p = store.profile ?: return
        rpc("delete_me", JSONObject().put("p_user", p.id))
        store.profile = null
        store.optedOut = true
    }

    fun board(store: Store): JSONArray {
        runCatching { submit(store) } // push my latest count first so my row matches
        val id: Any = store.profile?.id ?: JSONObject.NULL
        return JSONArray(rpc("leaderboard", JSONObject().put("p_day", LocalDate.now().toString()).put("p_user", id)))
    }
}
