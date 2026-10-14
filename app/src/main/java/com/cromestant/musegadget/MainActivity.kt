package com.cromestant.musegadget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cromestant.musegadget.audio.AndroidPcmSource
import com.cromestant.musegadget.audio.LinkVoiceTransport
import com.cromestant.musegadget.audio.PttController
import com.cromestant.musegadget.identity.TokenStore
import com.cromestant.musegadget.link.VoiceTurnListener
import com.cromestant.musegadget.svc.GadgetService

/**
 * Single-activity UI: gate / token / status / PTT screens.
 * Framework widgets only, programmatic layout. API 29 permission flow:
 * BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION, RECORD_AUDIO via
 * requestPermissions with rationale; LocationManager.isLocationEnabled()
 * must be ON or BLE advertising silently fails (blocking dialog).
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 41
        private val WANTED = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    private lateinit var store: TokenStore
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = Handler(Looper.getMainLooper())

    private lateinit var gateView: TextView
    private lateinit var permView: TextView
    private lateinit var tokenHelp: TextView
    private lateinit var tokenInput: EditText
    private lateinit var tokenSave: Button
    private lateinit var tokenMsg: TextView
    private lateinit var tokenSaved: TextView
    private lateinit var tokenClear: Button
    private lateinit var statusView: TextView
    private lateinit var pttButton: Button
    private lateinit var pttResult: TextView
    private lateinit var refreshBtn: Button

    private var ptt: PttController? = null
    private val voiceListener = object : VoiceTurnListener {
        override fun onAck() {
            ui.post { pttResult.text = "${pttResult.text}\n(sent, listening...)" }
        }

        override fun onPartial(text: String) {
            ui.post { pttResult.text = "Muse: $text" }
        }

        override fun onFinal(text: String) {
            ui.post { pttResult.text = if (text.isEmpty()) "(no reply text)" else "Muse: $text" }
        }

        override fun onError(error: String) {
            ui.post { pttResult.text = "Link error: $error" }
        }
    }
    private val voiceTransport: LinkVoiceTransport by lazy {
        LinkVoiceTransport(link = { GadgetService.instance?.gadgetLink }, listener = voiceListener)
    }
    private var ticking = false
    private var recordStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        gateView = TextView(this).apply { visibility = View.GONE }
        permView = TextView(this)
        tokenHelp = TextView(this).apply {
            text = "Paste your SDK token from gadgets.muse.ai. It is stored only on this device and never logged or transmitted except inside the pairing protocol."
        }
        tokenInput = EditText(this).apply { hint = "mgst_..." }
        tokenSave = Button(this).apply { text = "Save token" }
        tokenMsg = TextView(this)
        tokenSaved = TextView(this)
        tokenClear = Button(this).apply { text = "Clear token" }
        statusView = TextView(this)
        pttButton = Button(this).apply {
            text = "TAP TO TALK"
            textSize = 24f
            minimumHeight = 220
        }
        pttResult = TextView(this).apply {
            textSize = 26f
            setLineSpacing(8f, 1.15f)
            setPadding(24, 24, 24, 24)
        }
        refreshBtn = Button(this).apply { text = "Refresh status" }

        tokenSave.setOnClickListener { saveToken() }
        tokenClear.setOnClickListener { store.clear(); renderToken() }
        pttButton.setOnClickListener { onPttTap() }
        refreshBtn.setOnClickListener { renderStatus() }

        for (v in listOf(gateView, permView, tokenHelp, tokenInput, tokenSave, tokenMsg, tokenSaved, tokenClear, statusView, pttButton, pttResult, refreshBtn)) {
            root.addView(v)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        try {
            startService(Intent(this, GadgetService::class.java))
        } catch (e: Exception) {
            // Best effort; gate screen reports the state.
        }
        checkLocationSetting()
        renderToken()
        // Service binds async; poll briefly for status.
        ui.postDelayed({ renderStatus() }, 800)
        renderPermSummary()
    }

    // ---- permissions (API 29) ----

    private fun missingPerms(): List<String> =
        WANTED.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissions() {
        val missing = missingPerms()
        if (missing.isNotEmpty()) {
            if (missing.any { shouldShowRequestPermissionRationale(it) }) {
                permView.text = "Bluetooth/Location/Microphone are needed: BLE advertising needs Bluetooth+Location, PTT needs the microphone."
            }
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
        renderPermSummary()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, grants: IntArray) {
        if (code == REQ_PERMS) renderPermSummary()
    }

    private fun renderPermSummary() {
        val missing = missingPerms()
        permView.text = if (missing.isEmpty()) {
            "Permissions: all granted."
        } else {
            val degraded = mutableListOf<String>()
            if (missing.any { it.contains("LOCATION") || it.contains("BLUETOOTH") }) {
                degraded += "no BLE advertise"
            }
            if (missing.contains(Manifest.permission.RECORD_AUDIO)) degraded += "no mic"
            "Missing: ${missing.joinToString(", ")} (degraded: ${degraded.joinToString(", ")})"
        }
    }

    private fun checkLocationSetting() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val on = try {
            lm.isLocationEnabled
        } catch (e: Exception) {
            true
        }
        if (!on) {
            AlertDialog.Builder(this)
                .setTitle("Location is OFF")
                .setMessage("Location must be turned ON for BLE advertising (Android requirement).")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (e: Exception) {
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    // ---- token ----

    private fun saveToken() {
        val t = tokenInput.text.toString().trim()
        if (store.save(t)) {
            tokenMsg.text = "Saved."
            tokenInput.text.clear()
        } else {
            tokenMsg.text = "Malformed token (expected mgst_... from gadgets.muse.ai)."
        }
        renderToken()
    }

    private fun renderToken() {
        val saved = store.load()
        if (saved == null) {
            tokenSaved.text = "No token saved."
            tokenInput.visibility = View.VISIBLE
            tokenSave.visibility = View.VISIBLE
        } else {
            // Show only masked: first 8 chars + ...
            tokenSaved.text = "Saved: ${saved.take(8)}..."
            tokenInput.visibility = View.GONE
            tokenSave.visibility = View.GONE
        }
    }

    // ---- status + gate ----

    private fun renderStatus() {
        val svc = GadgetService.instance
        if (svc == null) {
            statusView.text = "Service: starting..."
            return
        }
        if (!svc.isPeripheralSupported()) {
            gateView.visibility = View.VISIBLE
            gateView.text = "This Portal Mini cannot do BLE advertising (peripheral mode unsupported) — live pairing with the Muse app will not work on this unit."
            pttButton.isEnabled = false
            tokenInput.isEnabled = false
            tokenSave.isEnabled = false
        } else {
            gateView.visibility = View.GONE
            pttButton.isEnabled = true
            tokenInput.isEnabled = true
            tokenSave.isEnabled = true
        }
        statusView.text = "BLE: ${svc.bleName()} | advertising=${svc.isAdvertising()} | paired=${svc.isPaired()}"
    }

    // ---- PTT ----

    private fun pttController(): PttController {
        var c = ptt
        if (c == null) {
            c = PttController(
                source = AndroidPcmSource(),
                transport = voiceTransport,
                onAutoStop = { ui.post { pttResult.text = "${pttResult.text}\n(auto-stopped at 15s)" } },
                onTooShort = { ui.post { pttResult.text = "Didn't catch that (hold at least 0.3s)" } },
            )
            ptt = c
        }
        return c
    }

    private fun onPttTap() {
        val c = pttController()
        if (c.currentState() == PttController.State.RECORDING) {
            stopTick()
            val ok = c.stop()
            pttButton.text = "TAP TO TALK"
            resetPttButtonColor()
            if (ok) showCapture(c, auto = false)
            renderStatus()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pttResult.text = "Microphone permission denied — PTT unavailable."
            return
        }
        if (!c.start()) {
            pttResult.text = "Mic unavailable."
            return
        }
        // Barge-in: stop any spoken reply before recording the next turn.
        try {
            GadgetService.instance?.gadgetLink?.stopTtsPlayback()
        } catch (_: Exception) {
        }
        recordStartMs = System.currentTimeMillis()
        pttButton.text = "RECORDING 0.0s — TAP TO STOP"
        pttButton.setBackgroundColor(0xFFCC0000.toInt())
        startTick()
    }

    private fun showCapture(c: PttController, auto: Boolean) {
        val info = c.lastCaptureInfo ?: return
        val secs = info.durationMs / 1000.0
        pttResult.text = "Captured ${"%.1f".format(secs)}s, ${info.pcmBytes}B PCM -> ${info.b64Chars} base64 chars voice_note (sending via gadget link...)${if (auto) " [auto-stop]" else ""}"
    }

    private fun startTick() {
        ticking = true
        ticker.post(object : Runnable {
            override fun run() {
                val c = ptt ?: return
                if (!ticking || c.currentState() != PttController.State.RECORDING) return
                val el = System.currentTimeMillis() - recordStartMs
                pttButton.text = "RECORDING ${"%.1f".format(el / 1000.0)}s — TAP TO STOP"
                if (c.checkAutoStop()) {
                    ticking = false
                    pttButton.text = "TAP TO TALK"
                    resetPttButtonColor()
                    showCapture(c, auto = true)
                    renderStatus()
                    return
                }
                ticker.postDelayed(this, 200)
            }
        })
    }

    private fun stopTick() {
        ticking = false
    }

    private fun resetPttButtonColor() {
        pttButton.setBackgroundColor(0xFFCCCCCC.toInt())
    }
}
