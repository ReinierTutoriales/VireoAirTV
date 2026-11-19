package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.discovery.Announcement
import io.github.jqssun.airplay.service.AirPlayService.ServerState

/**
 * Logs → Network check: why an iPhone may not find or reach the receiver. Adapted from CastBay's
 * NetworkCheck: it only reads what the receiver already knows (its state, the network, what each
 * DNS-SD announcement did), so it never restarts anything or interrupts a cast. What the TV cannot see
 * from its side, such as a router that isolates Wi-Fi clients, is reported as "can't verify".
 */
object NetworkCheck {
    enum class Outcome { PASS, PROBLEM, CANT_VERIFY }

    enum class Item { RECEIVER, NETWORK, AIRPLAY_SERVICE, RAOP_SERVICE, SAME_NETWORK }

    enum class Reason {
        RECEIVER_RUNNING, RECEIVER_OFF, RECEIVER_ERROR,
        NETWORK_WIFI, NETWORK_ETHERNET, NETWORK_OTHER, NETWORK_NO_ADDRESS, NETWORK_NONE,
        SERVICE_REGISTERED, SERVICE_OWN, SERVICE_PENDING, SERVICE_FAILED, SERVICE_OFF, SERVICE_USB, SERVICE_NOT_USED,
        EXTERNAL,
    }

    enum class NetworkType { WIFI, ETHERNET, OTHER, NONE }

    data class Result(val item: Item, val outcome: Outcome, val reason: Reason, val detail: String = "")

    data class Input(
        val serverState: ServerState,
        val networkType: NetworkType,
        val addresses: List<String>,
        /** By service type; empty while the server is stopped. */
        val announcements: Map<String, Announcement>,
        val usbOnly: Boolean,
        val port: Int,
    )

    fun classify(input: Input): List<Result> = listOf(
        when (input.serverState) {
            ServerState.RUNNING -> Result(Item.RECEIVER, Outcome.PASS, Reason.RECEIVER_RUNNING, "${input.port}")
            ServerState.ERROR -> Result(Item.RECEIVER, Outcome.PROBLEM, Reason.RECEIVER_ERROR)
            else -> Result(Item.RECEIVER, Outcome.PROBLEM, Reason.RECEIVER_OFF)
        },
        network(input),
        service(Item.AIRPLAY_SERVICE, "_airplay._tcp", input),
        service(Item.RAOP_SERVICE, "_raop._tcp", input),
        // client isolation, guest networks, VLANs: only the phone's side shows them
        Result(Item.SAME_NETWORK, Outcome.CANT_VERIFY, Reason.EXTERNAL),
    )

    private fun network(input: Input): Result {
        if (input.networkType == NetworkType.NONE) return Result(Item.NETWORK, Outcome.PROBLEM, Reason.NETWORK_NONE)
        // senders find and reach the TV over IPv4; without one the TV may still be joining
        val ipv4 = input.addresses.filter { '.' in it }
        if (ipv4.isEmpty()) return Result(Item.NETWORK, Outcome.CANT_VERIFY, Reason.NETWORK_NO_ADDRESS)
        val list = ipv4.joinToString(", ")
        return when (input.networkType) {
            NetworkType.WIFI -> Result(Item.NETWORK, Outcome.PASS, Reason.NETWORK_WIFI, list)
            NetworkType.ETHERNET -> Result(Item.NETWORK, Outcome.PASS, Reason.NETWORK_ETHERNET, list)
            else -> Result(Item.NETWORK, Outcome.CANT_VERIFY, Reason.NETWORK_OTHER, list)
        }
    }

    private fun service(item: Item, type: String, input: Input): Result {
        if (input.serverState != ServerState.RUNNING) return Result(item, Outcome.PROBLEM, Reason.SERVICE_OFF, type)
        if (input.usbOnly) return Result(item, Outcome.CANT_VERIFY, Reason.SERVICE_USB, type)
        return when (val a = input.announcements[type]) {
            Announcement.Registered -> Result(item, Outcome.PASS, Reason.SERVICE_REGISTERED, type)
            Announcement.OwnResponder -> Result(item, Outcome.PASS, Reason.SERVICE_OWN, type)
            is Announcement.Failed -> Result(item, Outcome.PROBLEM, Reason.SERVICE_FAILED, "$type (${a.code})")
            Announcement.Pending -> Result(item, Outcome.CANT_VERIFY, Reason.SERVICE_PENDING, type)
            // audio-only advertising can be off by setting: the AirPlay service alone is enough then
            null -> if (type == "_raop._tcp") Result(item, Outcome.PASS, Reason.SERVICE_NOT_USED, type)
                    else Result(item, Outcome.PROBLEM, Reason.SERVICE_OFF, type)
        }
    }
}
