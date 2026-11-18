package io.github.jqssun.airplay.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrustedClientsTest {
    @Test
    fun `a key moves to the newest position and the oldest drop past the limit`() {
        assertEquals(listOf("b", "a"), TrustedClients.appended(listOf("a", "b"), "a"))
        val full = (1..TrustedClients.MAX).map { "k$it" }
        val next = TrustedClients.appended(full, "new")
        assertEquals(TrustedClients.MAX, next.size)
        assertEquals("k2", next.first())
        assertEquals("new", next.last())
    }

    @Test
    fun `stored text tolerates blanks and duplicates`() {
        assertEquals(listOf("a", "b"), TrustedClients.parse("a\n\n b \na\n"))
        assertEquals(emptyList<String>(), TrustedClients.parse(null))
    }
}
