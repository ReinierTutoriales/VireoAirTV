package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.service.KnownSenders.Decision
import io.github.jqssun.airplay.service.KnownSenders.Entry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KnownSendersTest {
    private val allowed = Entry("AA:BB", "iPhone", "iPhone15,2", allowed = true)
    private val blocked = allowed.copy(allowed = false)

    @Test
    fun `a blocked sender is refused whatever the setting`() {
        assertEquals(Decision.BLOCKED, KnownSenders.decide("AA:BB", blocked, askNew = false))
        assertEquals(Decision.BLOCKED, KnownSenders.decide("AA:BB", blocked, askNew = true))
    }

    @Test
    fun `a new sender is asked about only when asking is on`() {
        assertEquals(Decision.ASK, KnownSenders.decide("AA:BB", null, askNew = true))
        assertEquals(Decision.ADMIT, KnownSenders.decide("AA:BB", null, askNew = false))
        assertEquals(Decision.ADMIT, KnownSenders.decide("AA:BB", allowed, askNew = true))
    }

    @Test
    fun `a sender without a device id is admitted`() {
        assertEquals(Decision.ADMIT, KnownSenders.decide("", null, askNew = true))
    }

    @Test
    fun `an unchanged sender keeps the list as it is, a changed one moves to the newest`() {
        val other = Entry("CC", "iPad", "iPad13,1", true)
        val list = listOf(allowed, other)
        assertSame(list, KnownSenders.appended(list, allowed))
        assertEquals(listOf(other, blocked), KnownSenders.appended(list, blocked))
        val full = (1..KnownSenders.MAX).map { Entry("k$it", "", "", true) }
        val next = KnownSenders.appended(full, other)
        assertEquals(KnownSenders.MAX, next.size)
        assertEquals("k2", next.first().id)
    }

    @Test
    fun `entries survive format and parse`() {
        val list = listOf(allowed, Entry("CC", "", "", false))
        assertEquals(list, KnownSenders.parse(KnownSenders.format(list)))
        assertEquals(emptyList<Entry>(), KnownSenders.parse(null))
        assertEquals(listOf(Entry("X", "", "", true)), KnownSenders.parse("X"))
    }
}
