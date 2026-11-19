package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.discovery.Announcement
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.service.NetworkCheck.Item
import io.github.jqssun.airplay.service.NetworkCheck.NetworkType
import io.github.jqssun.airplay.service.NetworkCheck.Outcome
import io.github.jqssun.airplay.service.NetworkCheck.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NetworkCheckTest {
    private val ok = NetworkCheck.Input(
        ServerState.RUNNING, NetworkType.WIFI, listOf("192.168.1.200", "fe80::1"),
        mapOf("_airplay._tcp" to Announcement.Registered, "_raop._tcp" to Announcement.Registered),
        usbOnly = false, port = 7000,
    )
    private fun reasons(input: NetworkCheck.Input) = NetworkCheck.classify(input).associate { it.item to it.reason }

    @Test
    fun `a running receiver on Wi-Fi passes and the router side stays unverified`() {
        val results = NetworkCheck.classify(ok)
        assertEquals(listOf(Outcome.PASS, Outcome.PASS, Outcome.PASS, Outcome.PASS, Outcome.CANT_VERIFY), results.map { it.outcome })
        assertEquals("192.168.1.200", results.first { it.item == Item.NETWORK }.detail)
    }

    @Test
    fun `an NsdManager failure is a problem, the own responder is not`() {
        val failed = ok.copy(announcements = mapOf("_airplay._tcp" to Announcement.Failed(0), "_raop._tcp" to Announcement.OwnResponder))
        assertEquals(Reason.SERVICE_FAILED, reasons(failed)[Item.AIRPLAY_SERVICE])
        assertEquals(Reason.SERVICE_OWN, reasons(failed)[Item.RAOP_SERVICE])
    }

    @Test
    fun `no network and a stopped receiver are both reported`() {
        val r = reasons(ok.copy(serverState = ServerState.STOPPED, networkType = NetworkType.NONE, announcements = emptyMap()))
        assertEquals(Reason.RECEIVER_OFF, r[Item.RECEIVER])
        assertEquals(Reason.NETWORK_NONE, r[Item.NETWORK])
        assertEquals(Reason.SERVICE_OFF, r[Item.AIRPLAY_SERVICE])
    }

    @Test
    fun `an address-less network cannot be verified yet`() {
        assertEquals(Reason.NETWORK_NO_ADDRESS, reasons(ok.copy(addresses = listOf("fe80::1")))[Item.NETWORK])
    }
}
