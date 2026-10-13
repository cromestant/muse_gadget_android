package com.cromestant.musegadget.identity

import java.util.Locale

/**
 * Stable device identity naming, mirroring identity.py.
 * Pure JVM: no Android imports.
 *
 * Never use model "link", device_family "link", or "device.ota": the
 * server pushes ESP32 OTA firmware to every "link" device.
 */
object IdentityProtocol {
    const val NODE_ID_PREFIX = "homelink-"
    const val BLE_NAME_PREFIX = "MuseGadget"
    const val MODEL = "hatch_link"
    const val DEVICE_ID_PREFIX = "hatch-link:"

    private val MAC_RE = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    fun isValidMac(mac: String): Boolean = MAC_RE.matches(mac)

    /** Last 6 lowercase hex digits of the MAC (colons stripped). */
    fun suffix(mac: String): String =
        mac.replace(":", "").takeLast(6).lowercase(Locale.ROOT)

    fun nodeId(mac: String): String = NODE_ID_PREFIX + suffix(mac)

    /** Linux style, NO hyphen: apps compare text after the prefix with text after "homelink-". */
    fun bleName(mac: String): String = BLE_NAME_PREFIX + suffix(mac).uppercase(Locale.ROOT)

    fun deviceId(mac: String): String = DEVICE_ID_PREFIX + mac

    /** Random locally-administered unicast MAC from 6 random bytes. */
    fun generateMac(random6: ByteArray): String {
        require(random6.size == 6) { "need 6 random bytes" }
        val o = random6.copyOf()
        o[0] = ((o[0].toInt() and 0xFC) or 0x02).toByte()
        return o.joinToString(":") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
