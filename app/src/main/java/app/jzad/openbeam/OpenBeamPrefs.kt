package app.jzad.openbeam

import android.content.Context
import java.util.UUID

object OpenBeamPrefs {
    private const val PREFS = "openbeam_prefs"
    private const val KEY_TILE_READY = "tile_ready"
    private const val KEY_SESSION_TOKEN = "session_token"

    fun isTileReady(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TILE_READY, false)
    }

    fun setTileReady(context: Context, ready: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TILE_READY, ready)
            .apply()
    }

    fun getOrCreateSessionToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_SESSION_TOKEN, null)
        if (!current.isNullOrBlank()) return current
        val token = UUID.randomUUID().toString().substring(0, 8).uppercase()
        prefs.edit().putString(KEY_SESSION_TOKEN, token).apply()
        return token
    }
}
