package app.aaps.plugins.dexcomoneplus.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Platform GATT client for ONE+ / G7 Direct family UUIDs.
 *
 * Auth/ExtraData for KEKS; Control for EGV (Ob1 style).
 *
 * ⚠️ ASYNC IMPACT: callbacks on binder thread; await* / connect block bleExecutor only.
 */
@SuppressLint("MissingPermission")
class OnePlusGattClientAndroid(
    private val appContext: Context,
    private val profile: OemDeviceProfile = DeviceProfileRegistry.resolve(),
) : OnePlusGattClient {

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var authChar: BluetoothGattCharacteristic? = null
    @Volatile private var extraChar: BluetoothGattCharacteristic? = null
    @Volatile private var controlChar: BluetoothGattCharacteristic? = null
    @Volatile private var backfillChar: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false

    private val readyLatch = AtomicReferenceLatch()
    private val authQueue = LinkedBlockingQueue<ByteArray>(32)
    private val controlQueue = LinkedBlockingQueue<ByteArray>(32)
    private val backfillQueue = LinkedBlockingQueue<ByteArray>(64)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: gatt state=$newState status=$status",
            )
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                val mtu = profile.preferredMtu.coerceIn(23, 517)
                if (!g.requestMtu(mtu)) {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                readyLatch.fail("disconnected")
                authQueue.offer(ByteArray(0))
                controlQueue.offer(ByteArray(0))
                backfillQueue.offer(ByteArray(0))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: mtu=$mtu status=$status")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyLatch.fail("discover status=$status")
                return
            }
            val service = g.getService(OnePlusBluetoothUuids.CgmService)
            if (service == null) {
                readyLatch.fail("CGM service missing")
                return
            }
            authChar = service.getCharacteristic(OnePlusBluetoothUuids.Authentication)
            extraChar = service.getCharacteristic(OnePlusBluetoothUuids.ExtraData)
            controlChar = service.getCharacteristic(OnePlusBluetoothUuids.Control)
            backfillChar = service.getCharacteristic(OnePlusBluetoothUuids.ProbablyBackfill)
            if (authChar == null) {
                readyLatch.fail("Authentication characteristic missing")
                return
            }
            enableCccd(g, authChar!!, indicate = false)
            extraChar?.let { enableCccd(g, it, indicate = false) }
            readyLatch.complete()
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: gatt ready control=${controlChar != null} " +
                    "backfill=${backfillChar != null}",
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onNotify(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotify(characteristic.uuid, value)
        }

        private fun onNotify(uuid: UUID, value: ByteArray?) {
            if (value == null) return
            val copy = value.copyOf()
            when (uuid) {
                OnePlusBluetoothUuids.Authentication, OnePlusBluetoothUuids.ExtraData -> authQueue.offer(copy)
                OnePlusBluetoothUuids.Control -> controlQueue.offer(copy)
                OnePlusBluetoothUuids.ProbablyBackfill -> backfillQueue.offer(copy)
            }
        }
    }

    override fun connect(deviceAddress: String) {
        authQueue.clear()
        controlQueue.clear()
        backfillQueue.clear()
        readyLatch.reset()
        if (deviceAddress.isBlank()) {
            error("ONEPLUS_GATT: device address blank — scan UI required")
        }
        val adapter = bluetoothManager.adapter
            ?: error("ONEPLUS_GATT: Bluetooth adapter null")
        if (!adapter.isEnabled) {
            error("ONEPLUS_GATT: Bluetooth disabled")
        }
        val remote: BluetoothDevice = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (_: Throwable) {
            error("ONEPLUS_GATT: invalid address")
        }
        disconnectInternal()
        device = remote
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            remote.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            remote.connectGatt(appContext, false, callback)
        }
        val ok = readyLatch.await(profile.connectTimeoutMs)
        if (!ok) {
            disconnectInternal()
            error("ONEPLUS_GATT: connect/discover timeout ${profile.connectTimeoutMs}ms")
        }
        val err = readyLatch.error
        if (err != null) {
            disconnectInternal()
            error("ONEPLUS_GATT: $err")
        }
    }

    override fun disconnect() {
        disconnectInternal()
    }

    override fun isConnected(): Boolean = connected && gatt != null

    override fun writeAuthentication(payload: ByteArray?) {
        writeChar(authChar, payload, "Auth")
    }

    override fun writeExtraData(payload: ByteArray?) {
        writeChar(extraChar, payload, "ExtraData")
    }

    override fun writeControl(payload: ByteArray?) {
        writeChar(controlChar, payload, "Control")
    }

    override fun enableControlNotifications() {
        val g = gatt ?: error("ONEPLUS_GATT: not connected")
        val c = controlChar ?: error("ONEPLUS_GATT: Control characteristic missing")
        // Ob1 uses indications on Control.
        enableCccd(g, c, indicate = true)
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: Control indications enabled")
    }

    override fun enableBackfillNotifications() {
        val g = gatt ?: error("ONEPLUS_GATT: not connected")
        val c = backfillChar ?: error("ONEPLUS_GATT: ProbablyBackfill characteristic missing")
        // Ob1 uses notifications (not indications) on ProbablyBackfill.
        enableCccd(g, c, indicate = false)
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: Backfill notifications enabled")
    }

    override fun isBonded(): Boolean {
        val d = device ?: return false
        return d.bondState == BluetoothDevice.BOND_BONDED
    }

    override fun createBond(): Boolean {
        val d = device ?: error("ONEPLUS_GATT: no device for bond")
        if (d.bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: already bonded")
            return true
        }
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: createBond requested")
        return d.createBond()
    }

    override fun awaitNotify(timeoutMs: Long): ByteArray? {
        val v = authQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        return if (v.isEmpty()) null else v
    }

    override fun awaitControlNotify(timeoutMs: Long): ByteArray? {
        val v = controlQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        return if (v.isEmpty()) null else v
    }

    override fun awaitBackfillNotify(timeoutMs: Long): ByteArray? {
        val v = backfillQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        return if (v.isEmpty()) null else v
    }

    private fun writeChar(characteristic: BluetoothGattCharacteristic?, payload: ByteArray?, label: String) {
        if (payload == null || payload.isEmpty()) return
        val g = gatt ?: error("ONEPLUS_GATT: not connected ($label)")
        val c = characteristic ?: error("ONEPLUS_GATT: missing $label characteristic")
        val writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeCharacteristic(c, payload, writeType)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error("ONEPLUS_GATT: write $label status=$status")
            }
        } else {
            @Suppress("DEPRECATION")
            c.value = payload
            @Suppress("DEPRECATION")
            c.writeType = writeType
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(c)) {
                error("ONEPLUS_GATT: write $label failed")
            }
        }
        Log.d(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: wrote $label ${payload.size}b")
    }

    private fun enableCccd(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        indicate: Boolean,
    ) {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(OnePlusBluetoothUuids.CharacteristicUpdateNotification)
            ?: return
        val enable = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun disconnectInternal() {
        connected = false
        // Unblock await* waiters (empty = disconnect sentinel).
        authQueue.offer(ByteArray(0))
        controlQueue.offer(ByteArray(0))
        backfillQueue.offer(ByteArray(0))
        try {
            gatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        device = null
        authChar = null
        extraChar = null
        controlChar = null
        backfillChar = null
    }

    private class AtomicReferenceLatch {
        @Volatile var error: String? = null
            private set
        private var latch = CountDownLatch(1)

        fun reset() {
            error = null
            latch = CountDownLatch(1)
        }

        fun complete() {
            error = null
            latch.countDown()
        }

        fun fail(reason: String) {
            error = reason
            latch.countDown()
        }

        fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
