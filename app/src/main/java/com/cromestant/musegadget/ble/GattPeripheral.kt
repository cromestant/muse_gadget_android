package com.cromestant.musegadget.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import com.cromestant.musegadget.identity.IdentityProtocol
import com.cromestant.musegadget.setup.BleTransport
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android BLE peripheral implementing [BleTransport] for the real radio.
 * This is the ONLY file in the main source set allowed android.bluetooth.*
 * imports; all protocol logic stays in pure-JVM modules.
 */
class GattPeripheral(
    private val context: Context,
    private val mac: String,
    private val onMessage: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
) : BleTransport {
    companion object {
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Assumed MTU until the stack reports the real one (160 + 3). */
        const val ASSUMED_MTU = 163
    }

    private val bluetoothManager: BluetoothManager
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val assembler = ChunkFraming.ChunkAssembler()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var notificationsEnabled = false
    @Volatile private var mtuValue = ASSUMED_MTU
    @Volatile private var pairedFlag: Byte = 0x00
    @Volatile private var advertising = false
    @Volatile private var advertiseGeneration = 0
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@GattPeripheral.device = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtuValue = ASSUMED_MTU
                assembler.reset()
                this@GattPeripheral.device = null
                notificationsEnabled = false
                onDisconnected()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == UUID.fromString(BleUuids.RX_UUID)) {
                assembler.feed(value)?.let { onMessage(it) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                notificationsEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                this@GattPeripheral.device = device
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuValue = mtu
        }
    }

    override fun mtu(): Int = mtuValue

    /**
     * Notify each packet in order with the 50 ms stagger. Must NOT run on
     * the main thread (SetupController calls it from its worker). Drops
     * silently when no device is connected or notifications are off.
     */
    override fun sendPackets(packets: List<ByteArray>) {
        val server = gattServer ?: return
        val target = device ?: return
        if (!notificationsEnabled) return
        val tx = txCharacteristic ?: return
        packets.forEachIndexed { i, p ->
            tx.value = p
            server.notifyCharacteristicChanged(target, tx, false)
            if (i < packets.lastIndex) Thread.sleep((ChunkFraming.CHUNK_STAGGER_S * 1000).toLong())
        }
    }

    override fun disconnect(delaySeconds: Double) {
        val target = device ?: return
        scheduler.schedule(
            { gattServer?.cancelConnection(target) },
            (delaySeconds * 1000).toLong(),
            TimeUnit.MILLISECONDS,
        )
    }

    /** Update the paired flag; advertises ONLY while unpaired. */
    fun setPaired(paired: Boolean) {
        pairedFlag = if (paired) 0x01 else 0x00
        if (paired) {
            stopAdvertising()
        } else {
            startAdvertising()
        }
    }

    /** False when the adapter is missing or cannot advertise; never crashes. */
    fun isPeripheralSupported(): Boolean = try {
        val adapter = bluetoothManager.adapter
        adapter != null && adapter.isMultipleAdvertisementSupported
    } catch (e: Exception) {
        false
    }

    fun isAdvertising(): Boolean = advertising
    /** Open the GATT server and start advertising (while unpaired). */
    fun start() {
        val manager = try {
            bluetoothManager
        } catch (e: Exception) {
            return
        }
        val server = manager.openGattServer(context, serverCallback) ?: return
        val service = BluetoothGattService(
            UUID.fromString(BleUuids.SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val rx = BluetoothGattCharacteristic(
            UUID.fromString(BleUuids.RX_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            UUID.fromString(BleUuids.TX_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server.addService(service)
        txCharacteristic = tx
        gattServer = server
        if (pairedFlag == 0x00.toByte()) startAdvertising()
    }

    fun stop() {
        advertiseGeneration++
        stopAdvertising()
        try {
            gattServer?.close()
        } catch (e: Exception) {
            // Best effort on shutdown.
        }
        gattServer = null
        scheduler.shutdown()
    }

    private fun startAdvertising() {
        val adapter = try {
            bluetoothManager.adapter
        } catch (e: Exception) {
            return
        } ?: return
        val advertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: Exception) {
            return
        } ?: return
        val desiredName = IdentityProtocol.bleName(mac)
        try {
            adapter.name = desiredName
        } catch (e: SecurityException) {
            // Best effort: keep the current adapter name.
        }
        // The adapter rename is asynchronous, but the scan response resolves
        // setIncludeDeviceName(true) at startAdvertising time. If we broadcast
        // immediately, the STALE name goes on air and the Muse app's
        // name-prefixed scan never matches. Wait (off the main thread) until
        // the new name reads back before broadcasting.
        val generation = ++advertiseGeneration
        scheduler.execute {
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (generation != advertiseGeneration) return@execute // superseded
                val current = try { adapter.name } catch (e: Exception) { null }
                if (current == desiredName) break
                try { Thread.sleep(150) } catch (e: InterruptedException) { return@execute }
            }
            if (generation != advertiseGeneration) return@execute
            if (gattServer == null) return@execute // stopped while waiting
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(UUID.fromString(BleUuids.SERVICE_UUID)))
                .addManufacturerData(BleUuids.PAIRED_FLAG_COMPANY_ID, byteArrayOf(pairedFlag))
                .setIncludeTxPowerLevel(false)
                .build()
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            try {
                advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            } catch (e: Exception) {
                advertising = false
            }
        }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try {
            bluetoothManager.adapter?.bluetoothLeAdvertiser
                ?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // Best effort on shutdown.
        }
    }
}
