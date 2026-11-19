package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.service.TrustedClients.Entry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrustedClientsTest {
    @Test
    fun `a sender moves to the newest position and the oldest drop past the limit`() {
        val a = Entry("a", "iPhone")
        val b = Entry("b", "iPad")
        assertEquals(listOf(b, Entry("a", "iPhone 17")), TrustedClients.appended(listOf(a, b), Entry("a", "iPhone 17")))
        val full = (1..TrustedClients.MAX).map { Entry("k$it", "") }
        val next = TrustedClients.appended(full, Entry("new", "Mac"))
        assertEquals(TrustedClients.MAX, next.size)
        assertEquals("k2", next.first().key)
        assertEquals("new", next.last().key)
    }

    @Test
    fun `keys stored before names were kept still load, without a name`() {
        assertEquals(listOf(Entry("a", ""), Entry("b", "")), TrustedClients.parse("a\n\n b \na\n"))
        assertEquals(emptyList<Entry>(), TrustedClients.parse(null))
    }

    @Test
    fun `names survive a round trip and cannot break the format`() {
        val entries = listOf(Entry("a", TrustedClients.clean("Reinier's\tiPhone\n")), Entry("b", "iPad de casa"))
        assertEquals("Reinier's iPhone", entries[0].name)
        assertEquals(entries, TrustedClients.parse(TrustedClients.format(entries)))
    }
}
