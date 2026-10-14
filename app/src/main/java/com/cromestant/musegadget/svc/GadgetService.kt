package com.cromestant.musegadget.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import com.cromestant.musegadget.ble.GattPeripheral
import com.cromestant.musegadget.identity.IdentityProtocol
import com.cromestant.musegadget.identity.TokenStore
import com.cromestant.musegadget.link.GadgetLink
import com.cromestant.musegadget.pairing.PairingSession
import com.cromestant.musegadget.setup.Credentials
import com.cromestant.musegadget.setup.DeviceIdentity
import com.cromestant.musegadget.setup.NetworkInfo
import com.cromestant.musegadget.setup.SetupController
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Foreground service owning GattPeripheral + SetupController + PairingSession.
 * API 29: plain foreground service, no foregroundServiceType needed at
 * targetSdk 29. Advertise ONLY while unpaired.
 */
class GadgetService : Service() {
    companion object {
        const val CHANNEL_ID = "musegadget_ble"
        const val NOTIF_ID = 1
        const val FIRMWARE_VERSION = "1.0"

        // Singleton for the single-activity UI; set in onCreate, cleared in onDestroy.
        @Volatile var instance: GadgetService? = null
            private set
    }

    private val binder = LocalBinder()
    private var peripheral: GattPeripheral? = null
    private var setup: SetupController? = null
    private var pairing: PairingSession? = null
    private var mac: String = ""
    @Volatile private var supported: Boolean = false
    @Volatile private var paired: Boolean = false

    /** Link session orchestrator; created in onCreate, started/stopped with the service. */
    lateinit var gadgetLink: GadgetLink
        private set

    inner class LocalBinder : Binder() {
        fun service(): GadgetService = this@GadgetService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        val store = TokenStore(this)
        mac = store.loadOrGenerateMac { IdentityProtocol.generateMac(randomMacBytes()) }
        val nodeId = IdentityProtocol.nodeId(mac)
        val deviceId = IdentityProtocol.deviceId(mac)
        pairing = PairingSession(nodeId, deviceId, mac, FIRMWARE_VERSION, store.load())
        val network = object : NetworkInfo {
            override fun isOnline(): Boolean = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("api.muse.ai", 443), 1500)
                    true
                }
            } catch (e: Exception) {
                false
            }

            override fun currentConnectionEntry(): Map<String, Any?> {
                // Mirror the official client: report the real SSID when known and
                // mark the entry open (secure=false) so the Muse app skips the
                // password field -- the device is already online and ignores
                // whatever credentials come back.
                val ssid = try {
                    val wm = getSystemService(WIFI_SERVICE) as WifiManager
                    wm.connectionInfo?.ssid
                        ?.removeSurrounding("\"")
                        ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                } catch (e: Exception) {
                    null
                }
                return mapOf(
                    "ssid" to (ssid ?: "current connection"),
                    "rssi" to -40,
                    "secure" to false,
                )
            }
        }
        // No-op provision stub: records credentials locally for now.
        val provision: (Credentials, ((() -> Boolean) -> Boolean)) -> Unit = { creds, commit ->
            val ok = commit { true }
            if (ok) {
                getSharedPreferences("musegadget_provision", MODE_PRIVATE).edit()
                    .putString("username", creds.username)
                    .putString("api_url_v2", creds.apiUrlV2)
                    .putString("noise_host", creds.noiseHost)
                    .putString("access_token", creds.accessToken)
                    .putString("refresh_token", creds.refreshToken)
                    .putLong("token_issued_at", System.currentTimeMillis())
                    .apply()
            }
        }
        gadgetLink = GadgetLink(this).also { link ->
            link.onUnpaired = {
                paired = false
                try {
                    peripheral?.setPaired(false)
                } catch (_: Exception) {
                }
            }
        }
        // Peripheral needs onMessage/onDisconnect wired to the controller;
        // create peripheral first with deferred lookup.
        val periph = GattPeripheral(
            this, mac,
            onMessage = { msg -> setup?.onWrite(msg) },
            onDisconnected = { setup?.onDisconnect() },
        )
        peripheral = periph
        setup = SetupController(
            pairing!!,
            DeviceIdentity(nodeId, deviceId, mac),
            FIRMWARE_VERSION,
            transport = periph,
            network = network,
            provision = provision,
            onComplete = { paired = true; periph.setPaired(true) },
        )
        supported = try {
            periph.isPeripheralSupported()
        } catch (e: Exception) {
            false
        }
        // Waits for provisioning internally; safe to start unconditionally.
        gadgetLink.start()
        startFg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        start()
        return START_STICKY
    }

    /** Open GATT server and advertise while unpaired; no-op when unsupported. */
    fun start() {
        val periph = peripheral ?: return
        if (!supported) return
        try {
            periph.start()
        } catch (e: Exception) {
            return
        }
        setup?.start()
        try {
            // API-29 gotcha: location services must be ON (not just permission)
            // for advertising; isMultipleAdvertisementSupported() gate above.
            // MTU defaults to 23 until negotiated (ASSUMED_MTU=163 handled in
            // GattPeripheral); adapter.name set needs BLUETOOTH_ADMIN (guarded).
            periph.setPaired(paired)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    fun isPeripheralSupported(): Boolean = supported

    fun isAdvertising(): Boolean = try {
        peripheral?.isAdvertising() == true
    } catch (e: Exception) {
        false
    }

    fun setPaired(p: Boolean) {
        paired = p
        try {
            peripheral?.setPaired(p)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    fun isPaired(): Boolean = paired

    fun isProvisioned(): Boolean = try {
        gadgetLink.isProvisioned()
    } catch (e: Exception) {
        false
    }

    fun bleName(): String = try {
        IdentityProtocol.bleName(mac)
    } catch (e: Exception) {
        "MuseGadget"
    }

    override fun onDestroy() {
        try { gadgetLink.stop() } catch (e: Exception) { }
        try { setup?.stop() } catch (e: Exception) { }
        try { peripheral?.stop() } catch (e: Exception) { }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MuseGadget BLE", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MuseGadget BLE")
                .setContentText("Advertising as ${bleName()}")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MuseGadget BLE")
                .setContentText("Advertising as ${bleName()}")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
        }
        startForeground(NOTIF_ID, notif)
    }

    private fun randomMacBytes(): ByteArray =
        ByteArray(6).also { SecureRandom().nextBytes(it) }
}
