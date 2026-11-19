package io.github.jqssun.airplay.service

import android.content.Context
import androidx.core.content.edit

/**
 * Senders that have connected, by the AirPlay device ID they send in SETUP, each allowed or blocked.
 * A blocked sender is always refused; with "ask for new devices" on, an unknown one is asked about on
 * the TV first. Adapted from CastBay's KnownDevices and AccessPolicy (GPL-3.0, see NOTICE.md).
 */
class KnownSenders(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(val id: String, val name: String, val model: String, val allowed: Boolean)

    enum class Decision { ADMIT, ASK, BLOCKED }

    @Synchronized
    fun entries(): List<Entry> = parse(prefs.getString(KEY, null))

    fun find(id: String): Entry? = entries().firstOrNull { it.id == id }

    /** Adds or updates [entry]; nothing is written when it is stored unchanged (every connection calls this). */
    @Synchronized
    fun put(entry: Entry) {
        val current = entries()
        val next = appended(current, entry.copy(name = TrustedClients.clean(entry.name), model = TrustedClients.clean(entry.model)))
        if (next != current) prefs.edit { putString(KEY, format(next)) }
    }

    @Synchronized
    fun clear() = prefs.edit { remove(KEY) }

    companion object {
        private const val PREFS = "known_senders"
        private const val KEY = "senders"
        const val MAX = 64

        /**
         * Whether a sender may connect. A sender without a device ID can't be told apart, so only the
         * PIN can stop it: it is admitted.
         */
        fun decide(id: String, known: Entry?, askNew: Boolean): Decision = when {
            id.isEmpty() -> Decision.ADMIT
            known?.allowed == false -> Decision.BLOCKED
            known == null && askNew -> Decision.ASK
            else -> Decision.ADMIT
        }

        /** [entry] in the newest position, replacing a changed old one; the oldest drop past [MAX]. */
        fun appended(entries: List<Entry>, entry: Entry): List<Entry> =
            if (entry in entries) entries
            else (entries.filter { it.id != entry.id } + entry).takeLast(MAX)

        /** One sender per line: "id<TAB>1|0<TAB>name<TAB>model". */
        fun parse(stored: String?): List<Entry> =
            stored.orEmpty().split('\n').mapNotNull { line ->
                val f = line.split('\t')
                val id = f[0].trim()
                if (id.isEmpty()) null
                else Entry(id, f.getOrElse(2) { "" }, f.getOrElse(3) { "" }, f.getOrElse(1) { "1" } != "0")
            }.distinctBy { it.id }

        fun format(entries: List<Entry>): String =
            entries.joinToString("\n") { "${it.id}\t${if (it.allowed) 1 else 0}\t${it.name}\t${it.model}" }
    }
}
