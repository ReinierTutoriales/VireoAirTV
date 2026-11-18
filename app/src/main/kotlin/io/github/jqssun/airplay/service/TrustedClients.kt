package io.github.jqssun.airplay.service

import android.content.Context
import androidx.core.content.edit

/**
 * Ed25519 keys (base64) of senders that paired with the PIN, oldest first. Kept across restarts so a
 * paired iPhone or Mac is not asked for the PIN again; the server's own key (airplay.pem) persists too.
 * Same idea as UxPlay's -reg file. Cleared from Settings.
 */
class TrustedClients(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<String> = parse(prefs.getString(KEY, null))

    fun add(publicKey: String) {
        val updated = appended(all(), publicKey)
        prefs.edit { putString(KEY, updated.joinToString("\n")) }
    }

    fun clear() = prefs.edit { remove(KEY) }

    companion object {
        private const val PREFS = "trusted_clients"
        private const val KEY = "keys"
        // the native list holds the same number and drops the oldest
        const val MAX = 32

        /** [key] moved to the newest position; the oldest drop past [MAX]. */
        fun appended(keys: List<String>, key: String): List<String> = (keys - key + key).takeLast(MAX)

        fun parse(stored: String?): List<String> =
            stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
