package com.cromestant.musegadget.pairing

/** Pairing v5 constants, mirroring pairing.py. We are the device side; policy confirm_app. */
object PairingConstants {
    const val PAIRING_VERSION = 5
    const val MODEL = "hatch_link"
    const val SUITE = "p256-hkdf-sha256-aes-gcm-v1"
    const val POLICY_CONFIRM_APP = "confirm_app"
    const val POLICY_CONFIRM_PRESS = "confirm_press"
    const val AUTH_COMMUNITY = "none"
    const val AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
    const val BUTTON_CONFIRM_TIMEOUT_S = 60
    const val RECORD_LABEL = "hatch-link ble setup v1"
    val SESSION_ID_LABEL: ByteArray = "hatch-link session id v1".toByteArray(Charsets.US_ASCII)
    const val TO_DEVICE = 0
    const val FROM_DEVICE = 1
    const val TAG_BYTES = 16
}
