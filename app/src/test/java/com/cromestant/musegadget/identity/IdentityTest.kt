package com.cromestant.musegadget.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    private val mac = "02:00:00:00:00:01"

    @Test
    fun vectorMacNaming() {
        assertEquals("000001", IdentityProtocol.suffix(mac))
        assertEquals("homelink-000001", IdentityProtocol.nodeId(mac))
        assertEquals("MuseGadget000001", IdentityProtocol.bleName(mac))
        assertEquals("hatch-link:02:00:00:00:00:01", IdentityProtocol.deviceId(mac))
        assertEquals("hatch_link", IdentityProtocol.MODEL)
        assertTrue(IdentityProtocol.isValidMac(mac))
    }

    @Test
    fun invalidMacsRejected() {
        assertFalse(IdentityProtocol.isValidMac(""))
        assertFalse(IdentityProtocol.isValidMac("02:00:00:00:00:0"))
        assertFalse(IdentityProtocol.isValidMac("02:00:00:00:00:0G"))
        assertFalse(IdentityProtocol.isValidMac("02-00-00-00-00-01"))
        assertFalse(IdentityProtocol.isValidMac("02:00:00:00:00:01 "))
    }

    @Test
    fun generatedMacIsLocallyAdministeredUnicast() {
        val gen = IdentityProtocol.generateMac(byteArrayOf(0xFF.toByte(), 0x11, 0x22, 0x33, 0x44, 0x55))
        assertEquals("fe:11:22:33:44:55", gen)
        assertTrue(IdentityProtocol.isValidMac(gen))
    }
}
