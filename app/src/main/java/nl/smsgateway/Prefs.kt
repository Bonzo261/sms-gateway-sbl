package nl.smsgateway

import android.content.Context
import org.json.JSONArray

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    // --- Doorsturen ---
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var rules: MutableList<Rule>
        get() {
            val arr = JSONArray(sp.getString("rules", "[]"))
            return (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }.toMutableList()
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            sp.edit().putString("rules", arr.toString()).apply()
        }

    var lastStatus: String
        get() = sp.getString("status", "Nog niets doorgestuurd") ?: ""
        set(v) = sp.edit().putString("status", v).apply()

    // --- Heartbeat ---
    var hbEnabled: Boolean
        get() = sp.getBoolean("hb_enabled", false)
        set(v) = sp.edit().putBoolean("hb_enabled", v).apply()

    var hbUrl: String
        get() = sp.getString("hb_url", "") ?: ""
        set(v) = sp.edit().putString("hb_url", v.trim()).apply()

    /** Interval in minuten (minimaal 1) */
    var hbInterval: Int
        get() = sp.getInt("hb_interval", 15)
        set(v) = sp.edit().putInt("hb_interval", v.coerceAtLeast(1)).apply()

    var hbHeaderName: String
        get() = sp.getString("hb_hn", "") ?: ""
        set(v) = sp.edit().putString("hb_hn", v.trim()).apply()

    var hbHeaderValue: String
        get() = sp.getString("hb_hv", "") ?: ""
        set(v) = sp.edit().putString("hb_hv", v.trim()).apply()

    var hbStatus: String
        get() = sp.getString("hb_status", "Nog geen heartbeat verstuurd") ?: ""
        set(v) = sp.edit().putString("hb_status", v).apply()
}
