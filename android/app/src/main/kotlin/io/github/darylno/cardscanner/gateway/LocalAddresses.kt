package io.github.darylno.cardscanner.gateway

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The phone's LAN addresses guests can reach the gateway on: IPv4 of up, non-loopback
 * interfaces, minus Tailscale (100.64.0.0/10 — guests are by definition not on the
 * tailnet), link-local and cellular/VPN interfaces nobody on the LAN can reach.
 * Wi-Fi client (wlan0) comes first, then the hotspot (ap0 / swlan0 / wlan1 …), then Ethernet.
 */
object LocalAddresses {

    /** One interface's view, so the filtering/ordering is testable without real NICs. */
    data class Iface(val name: String, val up: Boolean, val loopback: Boolean, val ipv4: List<String>)

    /** Addresses of the real interfaces, best first. Empty when there is no LAN. */
    fun list(): List<String> {
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
                Iface(
                    name = ni.name ?: "",
                    up = runCatching { ni.isUp }.getOrDefault(false),
                    loopback = runCatching { ni.isLoopback }.getOrDefault(false),
                    ipv4 = ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        return select(ifaces)
    }

    /** Filters and orders [ifaces]; see the class doc. */
    fun select(ifaces: List<Iface>): List<String> =
        ifaces.asSequence()
            .filter { it.up && !it.loopback && !isExcludedName(it.name) }
            .sortedWith(compareBy({ rank(it.name) }, { it.name }))
            .flatMap { i -> i.ipv4.asSequence() }
            .filter { !isTailscale(it) && !it.startsWith("127.") && !it.startsWith("169.254.") && it != "0.0.0.0" }
            .distinct()
            .toList()

    /** The URL a guest opens (and the QR encodes). */
    fun joinUrl(ip: String, port: Int, code: String): String = "http://$ip:$port/join?code=$code"

    /** True for 100.64.0.0/10 (Tailscale / CGNAT). */
    fun isTailscale(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    private fun rank(name: String): Int = when {
        name == "wlan0" -> 0
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") || name.startsWith("wlan") -> 1
        name.startsWith("eth") -> 2
        else -> 3
    }

    private val EXCLUDED_PREFIXES = listOf("rmnet", "ccmni", "pdp", "v4-rmnet", "tun", "tailscale", "dummy", "ip6tnl", "sit", "p2p-dev")
    private fun isExcludedName(name: String) = EXCLUDED_PREFIXES.any { name.startsWith(it) }
}
