package com.phonerouter.hotspot.hotspot

import org.json.JSONObject

/** Mirror of android.net.wifi.SoftApConfiguration constants we expose. */
object SecurityType {
    const val OPEN = 0
    const val WPA2_PSK = 1
    const val WPA3_SAE_TRANSITION = 2
    const val WPA3_SAE = 3
    fun label(v: Int) = when (v) {
        OPEN -> "Open"
        WPA2_PSK -> "WPA2"
        WPA3_SAE_TRANSITION -> "WPA2/WPA3"
        WPA3_SAE -> "WPA3"
        else -> "Unknown"
    }
}

object Band {
    const val GHZ_2 = 1
    const val GHZ_5 = 2
    const val GHZ_6 = 4
    fun label(v: Int) = when (v) {
        GHZ_2 -> "2.4G"
        GHZ_5 -> "5G"
        GHZ_6 -> "6G"
        else -> "Auto"
    }
}

data class SoftApConfig(
    val ssid: String?,
    val passphrase: String?,
    val security: Int,
    val band: Int,
    val hidden: Boolean,
    val maxClients: Int,
    val blocked: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ssid", ssid ?: "")
        put("passphrase", passphrase ?: "")
        put("security", security)
        put("securityLabel", SecurityType.label(security))
        put("band", band)
        put("bandLabel", Band.label(band))
        put("hidden", hidden)
        put("maxClients", maxClients)
    }

    companion object {
        fun from(j: JSONObject): SoftApConfig {
            val blocked = mutableListOf<String>()
            j.optJSONArray("blocked")?.let { arr ->
                for (i in 0 until arr.length()) blocked.add(arr.getString(i))
            }
            return SoftApConfig(
                ssid = j.optString("ssid").ifEmpty { null },
                passphrase = j.optString("passphrase").ifEmpty { null },
                security = j.optInt("security", SecurityType.WPA2_PSK),
                band = j.optInt("band", Band.GHZ_2),
                hidden = j.optBoolean("hidden", false),
                maxClients = j.optInt("maxClients", 0),
                blocked = blocked,
            )
        }
    }
}
