package jp.hi7.sshtermproto

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Connection presets (host/port/user only — never the password).
 * Stored as plain JSON in SharedPreferences; this is deliberately NOT
 * encrypted because no secret ever lands here. Still, treat host/user
 * as non-trivial metadata: this is app-private storage, not a vault.
 */
data class Preset(val name: String, val host: String, val port: Int, val user: String)

object Presets {
    private const val TAG = "Presets"
    private const val PREFS = "ssh_presets"
    private const val KEY_LIST = "list"
    private const val KEY_LAST = "last_preset_name"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(ctx: Context): List<Preset> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Preset>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Preset(o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("user")))
            }
            out
        } catch (e: Exception) {
            // Corrupted/foreign data must never brick the app at startup.
            Log.w(TAG, "corrupt preset list, resetting", e)
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, presets: List<Preset>) {
        val arr = JSONArray()
        for (p in presets) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("host", p.host)
            o.put("port", p.port)
            o.put("user", p.user)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    /** Insert, or overwrite an existing preset with the same name. */
    fun upsert(ctx: Context, preset: Preset) {
        require(isValidPort(preset.port)) { "invalid port ${preset.port}" }
        val current = loadAll(ctx).toMutableList()
        val idx = current.indexOfFirst { it.name == preset.name }
        if (idx >= 0) current[idx] = preset else current.add(preset)
        saveAll(ctx, current)
    }

    fun delete(ctx: Context, name: String) {
        val current = loadAll(ctx).filterNot { it.name == name }
        saveAll(ctx, current)
        if (getLastPresetName(ctx) == name) clearLastPresetName(ctx)
    }

    fun setLastPresetName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_LAST, name).apply()
    }

    fun getLastPresetName(ctx: Context): String? = prefs(ctx).getString(KEY_LAST, null)

    fun clearLastPresetName(ctx: Context) {
        prefs(ctx).edit().remove(KEY_LAST).apply()
    }

    /** Last *successful* connection (host/port/user), kept separately from
     *  named presets so the fields auto-fill even without a saved preset. */
    fun saveLastConnection(ctx: Context, host: String, port: Int, user: String) {
        prefs(ctx).edit()
            .putString("last_host", host)
            .putInt("last_port", port)
            .putString("last_user", user)
            .apply()
    }

    fun loadLastConnection(ctx: Context): Preset? {
        val p = prefs(ctx)
        val host = p.getString("last_host", null) ?: return null
        val user = p.getString("last_user", "") ?: ""
        val port = p.getInt("last_port", 22)
        return Preset(name = "", host = host, port = port, user = user)
    }
}
