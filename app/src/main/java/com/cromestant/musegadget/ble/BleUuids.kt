package com.cromestant.musegadget.ble

/**
 * BLE GATT identifiers, mirroring ble_server.py.
 * Pure JVM: no Android imports.
 */
object BleUuids {
    const val SERVICE_UUID = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
    const val RX_UUID = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"
    const val TX_UUID = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"

    /** Manufacturer company id used in advertisements (paired flag follows). */
    const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
}
