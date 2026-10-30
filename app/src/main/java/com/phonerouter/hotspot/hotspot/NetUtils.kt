package com.phonerouter.hotspot.hotspot

import com.phonerouter.hotspot.Prefs
import com.phonerouter.hotspot.root.RootShell

/** Network interface helpers for the SoftAp (hotspot) link. */
object NetUtils {

    private val IFACE_REGEX = Regex("^[a-zA-Z0-9_.-]+$")

    fun sanitizeIface(iface: String): String =
        if (IFACE_REGEX.matches(iface)) iface else Prefs.AP_IFACE

    /** Interface that currently owns the gateway IP, else the configured default. */
    fun apInterface(): String {
        val res = RootShell.run("ip -4 -o addr show 2>/dev/null")
        if (res.ok) {
            // lines like: "25: wlan1    inet 192.168.43.1/24 ..."
            for (line in res.out.lines()) {
                if (line.contains(Prefs.GATEWAY)) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) return sanitizeIface(parts[1])
                }
            }
        }
        return Prefs.AP_IFACE
    }

    /** True if the gateway IP is assigned to some interface right now. */
    fun gatewayPresent(): Boolean {
        val res = RootShell.run("ip -4 -o addr show 2>/dev/null")
        return res.ok && res.out.contains(Prefs.GATEWAY)
    }

    /**
     * All /24 IPv4 prefixes (e.g. "192.168.119.") currently assigned on [iface].
     */
    fun apIPv4Prefixes(iface: String): List<String> {
        val safeIface = sanitizeIface(iface)
        val res = RootShell.run("ip -4 -o addr show dev $safeIface 2>/dev/null")
        if (!res.ok) return emptyList()
        val prefixes = mutableListOf<String>()
        val re = Regex("""inet (\d+\.\d+\.\d+)\.\d+/(\d+)""")
        for (line in res.out.lines()) {
            val m = re.find(line) ?: continue
            val (net, bits) = m.destructured
            if (bits.toIntOrNull() == 24) prefixes.add("$net.")
        }
        return prefixes.distinct()
    }

    data class Traffic(val rxBytes: Long, val txBytes: Long)

    /**
     * Cumulative rx/tx bytes for [iface]. Read via root: an unprivileged app only sees
     * its own network namespace in /proc/net/dev and would read zero for wlan1.
     */
    fun traffic(iface: String): Traffic {
        val safeIface = sanitizeIface(iface)
        try {
            val res = RootShell.run("cat /proc/net/dev 2>/dev/null")
            if (!res.ok) return Traffic(0, 0)
            val line = res.out.lines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$safeIface:") }
                ?: return Traffic(0, 0)
            // fields after "iface:": rxBytes(0) rxPackets ... txBytes(8) ...
            val f = line.substringAfter(':').trim().split(Regex("\\s+"))
            if (f.size >= 9) {
                return Traffic(f[0].toLongOrNull() ?: 0L, f[8].toLongOrNull() ?: 0L)
            }
        } catch (_: Throwable) {
        }
        return Traffic(0, 0)
    }
}
