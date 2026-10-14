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
        // Best-guess reconstruction: device_id stands in for the scrubbed
        // segment; the live test must verify it (alternate: "Bearer abc" alone).
        assertEquals("Bearer dev1:abc", MuseApi.refreshAuthHeader("dev1", "hatch_refresh:abc"))
    }

    @Test
    fun refreshAuthHeaderWithoutPrefix() {
        assertEquals("Bearer dev1:abc", MuseApi.refreshAuthHeader("dev1", "abc"))
    }

    @Test
    fun refreshAuthHeaderRawFormat() {
        assertEquals("Bearer abc", MuseApi.refreshAuthHeaderRaw("hatch_refresh:abc"))
        assertEquals("Bearer abc", MuseApi.refreshAuthHeaderRaw("abc"))
    }
}
