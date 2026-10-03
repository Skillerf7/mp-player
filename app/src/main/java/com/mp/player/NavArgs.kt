package com.mp.player

import android.util.Base64

/**
 * Navigations-Argumente (Interpret-/Ordner-/Playlist-Namen) duerfen Zeichen wie "/", "?", "%" oder
 * "=" enthalten. Direkt in eine Route geschrieben fuehrt das zu "Navigation destination not found"
 * und damit zum Absturz. Deshalb werden Werte URL-sicher (Base64) kodiert.
 */
object NavArgs {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), FLAGS)

    fun decode(value: String?): String = try {
        if (value.isNullOrEmpty()) "" else String(Base64.decode(value, FLAGS), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    /** Route zu einer gefilterten Titelliste, z. B. alle Titel eines Interpreten. */
    fun filterRoute(field: String, value: String): String = "category/filter:$field:${encode(value)}"

    fun playlistRoute(name: String): String = "playlist/${encode(name)}"
}
