package com.cromestant.musegadget.link

import org.junit.Assert.assertEquals
import org.junit.Test

class MuseApiTest {
    @Test
    fun apiRootTrimsTrailingSlash() {
        assertEquals("https://x", MuseApi.apiRoot("https://x/"))
    }

    @Test
    fun apiRootBlankFallsBack() {
        assertEquals("https://api.muse.ai", MuseApi.apiRoot(""))
        assertEquals("https://api.muse.ai", MuseApi.apiRoot("   "))
    }

    @Test
    fun refreshAuthHeaderStripsPrefix() {
        val result = MuseApi.refreshAuthHeader("hatch_refresh:abc")
        assertEquals(true, result.startsWith("Bearer "))
        assertEquals(true, result.endsWith(":abc"))
    }

    @Test
    fun refreshAuthHeaderWithoutPrefix() {
        val result = MuseApi.refreshAuthHeader("abc")
        assertEquals(true, result.startsWith("Bearer "))
        assertEquals(true, result.endsWith(":abc"))
    }

}
