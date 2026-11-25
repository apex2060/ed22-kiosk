package org.ed22.kiosk

import android.content.Context
import org.json.JSONArray

/** `kiosk.storage.*`: small string key/value store that survives clearing the WebView's data. */
object NativeStorage {
    private const val FILE = "kiosk_page_storage"
    const val MAX_KEY_CHARS = 128
    const val MAX_VALUE_CHARS = 65_536
    const val MAX_KEYS = 500

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): String? = prefs(ctx).getString(checkKey(key), null)

    fun set(ctx: Context, key: String, value: String) {
        checkKey(key)
        require(value.length <= MAX_VALUE_CHARS) { "Value is longer than $MAX_VALUE_CHARS characters" }
        val p = prefs(ctx)
        require(p.contains(key) || p.all.size < MAX_KEYS) { "Storage is full ($MAX_KEYS keys)" }
        p.edit().putString(key, value).apply()
    }

    fun remove(ctx: Context, key: String) = prefs(ctx).edit().remove(checkKey(key)).apply()

    fun keys(ctx: Context) = JSONArray(prefs(ctx).all.keys.sorted())

    /** Drops keys for which [drop] is true (used at the end of a House of Learning visit). */
    fun clearKeys(ctx: Context, drop: (String) -> Boolean) {
        val p = prefs(ctx)
        val ed = p.edit()
        var any = false
        for (k in p.all.keys) if (drop(k)) { ed.remove(k); any = true }
        if (any) ed.apply()
    }

    private fun checkKey(key: String): String {
        require(key.isNotEmpty() && key.length <= MAX_KEY_CHARS) { "Key must be 1 to $MAX_KEY_CHARS characters" }
        return key
    }
}
