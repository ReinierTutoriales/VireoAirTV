package io.github.jqssun.airplay.service

import android.content.Context
import androidx.core.content.edit

/**
 * Senders that paired with the PIN, oldest first: their Ed25519 key (base64) and the name they gave
 * when pairing. Kept across restarts so a paired iPhone or Mac is not asked for the PIN again; the
 * server's own key (airplay.pem) persists too. Same idea as UxPlay's -reg file. Each one can be
 * forgotten from Settings.
 */
class TrustedClients(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(val key: String, val name: String)

    fun entries(): List<Entry> = parse(prefs.getString(KEY, null))

    fun all(): List<String> = entries().map { it.key }

    fun add(publicKey: String, name: String) = save(appended(entries(), Entry(publicKey, clean(name))))

    fun remove(publicKey: String) = save(entries().filter { it.key != publicKey })

    fun clear() = prefs.edit { remove(KEY) }

    private fun save(entries: List<Entry>) = prefs.edit { putString(KEY, format(entries)) }

    companion object {
        private const val PREFS = "trusted_clients"
        private const val KEY = "keys"
        // the native list holds the same number and drops the oldest
        const val MAX = 32

        /** [entry] moved to the newest position; the oldest drop past [MAX]. */
        fun appended(entries: List<Entry>, entry: Entry): List<Entry> =
            (entries.filter { it.key != entry.key } + entry).takeLast(MAX)

        /** One sender per line, "key<TAB>name"; lines from before names were kept hold only the key. */
        fun parse(stored: String?): List<Entry> =
            stored.orEmpty().split('\n')
                .map { line -> line.split('\t', limit = 2).let { Entry(it[0].trim(), it.getOrElse(1) { "" }.trim()) } }
                .filter { it.key.isNotEmpty() }
                .distinctBy { it.key }

        fun format(entries: List<Entry>): String = entries.joinToString("\n") { "${it.key}\t${it.name}" }

        /** A name is shown in a list: no line breaks or tabs, and not endless. */
        fun clean(name: String): String = name.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(64)
    }
}
