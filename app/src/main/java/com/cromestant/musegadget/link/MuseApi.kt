package com.cromestant.musegadget.link

import com.cromestant.musegadget.common.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Muse device-API client over OkHttp, port of
 * `ref/muse-gadget-sdk/linux/src/musegadget/muse_api.py`.
 *
 * Token values are never logged: only counts, statuses, and shapes.
 */
object MuseApi {
    const val DEFAULT_ROOT = "https://api.muse.ai"
    private const val FETCH_PATH = "/fetch_vms"
    private const val REFRESH_PATH = "/device_token/refresh"

    /** One leased VM, mirroring the dicts built in `muse_api.py:95-108`. */
    data class VmInfo(
        val vmUrl: String,
        val vmAuthToken: String,
        val vmName: String,
        val vmId: String,
        val isDefault: Boolean,
    )

    /** Rotated device token pair, mirroring `refresh_device_token` in `muse_api.py:137`. */
    data class DeviceTokens(val accessToken: String, val refreshToken: String)

    /**
     * API root, mirroring `api_root` in `muse_api.py:35`.
     * Blank (after trim) falls back to the Muse API.
     */
    fun apiRoot(apiUrlV2: String): String {
        val t = apiUrlV2.trim().trimEnd('/')
        return if (t.isEmpty()) DEFAULT_ROOT else t
    }

    /** Fixed UA for this device (Python reports model/Python version; we are one build). */
    fun userAgent(): String = "musegadget/1.0 (Android)"

    /**
     * Refresh Authorization header.
     *
     * The reference's header format is `Bearer <SCRUBBED>:<raw>` where
     * `raw = refreshToken.substringAfterLast(":")` (the `hatch_refresh:`
     * prefix some app versions prepend must be stripped — doubling it gets
     * rejected; see `muse_api.py:152-154`). The scrubbed segment is
     * unrecoverable from the reference tree. BEST-GUESS reconstruction:
     * `device_id` is used for the scrubbed segment — it is the
     * credential-adjacent identifier in scope on both the Python
     * (`refresh_device_token(..., device_id, ...)` at `muse_api.py:137`) and
     * ESP32 call paths. The live on-device test must verify this: if the
     * server 401s, the documented alternate is `Bearer $raw` alone.
     * The format is isolated in this one function so the live test can flip it.
     */
    fun refreshAuthHeader(deviceId: String, refreshToken: String): String {
        val raw = refreshToken.substringAfterLast(":")
        return "Bearer $deviceId:$raw"
    }

    /** Alternate format: raw token alone. Tried when the device-bound format gets 401. */
    fun refreshAuthHeaderRaw(refreshToken: String): String {
        return "Bearer " + refreshToken.substringAfterLast(":")
    }

    /**
     * Leased VMs plus the HTTP status, mirroring `fetch_vms_with_status` in
     * `muse_api.py:59`. Returns `(vms, status)`; `(emptyList(), null)` on
     * transport/parse failure. Never logs the token.
     */
    fun fetchVms(accessToken: String, root: String, http: OkHttpClient): Pair<List<VmInfo>, Int?> {
        val req = Request.Builder()
            .url(root + FETCH_PATH)
            .header("Authorization", "Bearer $accessToken")
            .header("X-API-Version", "1.0.0")
            .header("User-Agent", userAgent())
            .get()
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val status = resp.code
                if (!resp.isSuccessful) return emptyList<VmInfo>() to status
                val text = try {
                    resp.body?.string() ?: return emptyList<VmInfo>() to status
                } catch (e: Exception) {
                    return emptyList<VmInfo>() to null
                }
                val data = try {
                    Json.parse(text)
                } catch (e: Exception) {
                    return emptyList<VmInfo>() to null
                }
                if (data !is Map<*, *>) return emptyList<VmInfo>() to status
                @Suppress("UNCHECKED_CAST")
                val m = data as Map<String, Any?>
                if (isTruthy(m["error_title"]) || isTruthy(m["backend_error_code"])) {
                    return emptyList<VmInfo>() to status
                }
                val vmList = m["vm_list"]
                if (vmList !is List<*>) return emptyList<VmInfo>() to status
                val vms = ArrayList<VmInfo>()
                for (entry in vmList) {
                    if (entry !is Map<*, *>) continue
                    @Suppress("UNCHECKED_CAST")
                    val e = entry as Map<String, Any?>
                    val vmUrl = (e["vm_ws_url"] as? String ?: e["vm_url"] as? String ?: "")
                    val vmToken = e["vm_auth_token"] as? String ?: ""
                    if (vmUrl.trim().isEmpty() || vmToken.trim().isEmpty()) continue
                    vms.add(
                        VmInfo(
                            vmUrl = vmUrl,
                            vmAuthToken = vmToken,
                            vmName = e["vm_name"] as? String ?: "",
                            vmId = e["vm_id"] as? String ?: "",
                            isDefault = isTruthy(e["default"]),
                        ),
                    )
                }
                return vms to status
            }
        } catch (e: Exception) {
            return emptyList<VmInfo>() to null
        }
    }

    /**
     * Rotate the device token pair, mirroring `refresh_device_token` in
     * `muse_api.py:137`. The access token is never presented on this
     * endpoint (see `muse_api.py:148-150`). Tries the device-bound auth
     * header first, then the raw-token format; 401 on both means re-pair.
     * Returns `(tokens, status)`; `(null, null)` on transport failure.
     */
    fun refreshDeviceToken(
        refreshToken: String,
        deviceId: String,
        root: String,
        http: OkHttpClient,
    ): Pair<DeviceTokens?, Int?> {
        val body = Json.stringify(mapOf("device_id" to deviceId))
            .toRequestBody("application/json".toMediaType())
        val headers = listOf(
            refreshAuthHeader(deviceId, refreshToken),
            refreshAuthHeaderRaw(refreshToken)
        )
        var lastStatus: Int? = null
        for ((i, authHeader) in headers.withIndex()) {
            val req = Request.Builder()
                .url(root + REFRESH_PATH)
                .header("Authorization", authHeader)
                .header("User-Agent", userAgent())
                .post(body)
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    val status = resp.code
                    System.out.println("MuseGadgetAuth: refresh attempt " + (i + 1) +
                        " -> HTTP " + status)
                    if (status == 401 || !resp.isSuccessful) {
                        lastStatus = status
                        return@use
                    }
                    val parsed = parseRefreshResponse(resp)
                    if (parsed != null) {
                        System.out.println("MuseGadgetAuth: refresh succeeded on attempt " + (i + 1))
                        return parsed to status
                    }
                    lastStatus = status
                }
            } catch (e: Exception) {
                System.out.println("MuseGadgetAuth: refresh attempt " + (i + 1) +
                    " threw " + e.javaClass.simpleName)
            }
        }
        return null to lastStatus
    }

    /** Parse a successful refresh response into tokens, or null if malformed. */
    private fun parseRefreshResponse(resp: okhttp3.Response): DeviceTokens? {
        val text = try {
            resp.body?.string() ?: return null
        } catch (e: Exception) {
            return null
        }
        var data: Any? = try {
            Json.parse(text)
        } catch (e: Exception) {
            return null
        }
        if (data is Map<*, *> && data["payload"] is Map<*, *>) data = data["payload"]
        if (data !is Map<*, *>) return null
        @Suppress("UNCHECKED_CAST")
        val m = data as Map<String, Any?>
        val access = m["access_token"] as? String ?: ""
        val refresh = m["refresh_token"] as? String ?: ""
        if (access.trim().isEmpty() || refresh.trim().isEmpty()) return null
        return DeviceTokens(access, refresh)
    }

    /** Shared HTTP client: 15 s timeouts, 20 s WS ping (mirrors `PING_INTERVAL_S`). */
    fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun isTruthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is String -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0
        else -> true
    }
}
