package app.aaps.plugins.dexcomoneplus.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Platform GATT client for ONE+ / G7 Direct family UUIDs.
 *
 * Auth/ExtraData for KEKS; Control for EGV (Ob1 style).
 *
 * Mirrors Ob1/xDrip constraints:
 * - **one outstanding GATT op** at a time (avoids Android 13+ status 201 /
 *   [BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY])
 * - CCCD: ExtraData notify → Authentication **indication** before ready
 * - ExtraData writes use [WRITE_TYPE_NO_RESPONSE]; Auth uses [WRITE_TYPE_DEFAULT]
 *
 * ⚠️ ASYNC IMPACT: callbacks on binder thread; await* / connect / writes block bleExecutor only.
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
    /** True while an optional post-ready MTU request is in flight (do not re-discover). */
    @Volatile private var pendingPostReadyMtu = false
    @Volatile private var lastCloseElapsedMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val readyLatch = AtomicReferenceLatch()
    /** Tagged Auth/ExtraData frames for KEKS (Ob1 routes by UUID). */
    private val keksQueue = LinkedBlockingQueue<TaggedKeksNotify>(32)
    private val controlQueue = LinkedBlockingQueue<ByteArray>(32)
    private val backfillQueue = LinkedBlockingQueue<ByteArray>(64)

    /** Serializes writeCharacteristic / writeDescriptor from bleExecutor. */
    private val gattOpLock = Any()
    private val pendingOpLatch = AtomicReferenceLatch()
    private val pendingOpStatus = AtomicInteger(BluetoothGatt.GATT_SUCCESS)

    /**
     * CCCD setup state machine (runs on binder callbacks — must not block waiting for itself).
     * Ob1 order: ExtraData notification, then Authentication indication.
     */
    private enum class CccdPhase { NONE, EXTRA, AUTH, DONE }

    @Volatile private var cccdPhase = CccdPhase.NONE

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(g)) {
                Log.d(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: ignore stale gatt state=$newState status=$status",
                )
                return
            }
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: gatt state=$newState status=$status",
            )
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (_: Throwable) {
                }
                // Juggluco: wait out BOND_BONDING before discoverServices.
                val bond = g.device?.bondState ?: BluetoothDevice.BOND_NONE
                if (bond == BluetoothDevice.BOND_BONDING) {
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.SESSION}: wait BOND_BONDING before discover",
                    )
                    scheduleDiscoverWhenBondReady(g)
                } else {
                    beginPostConnect(g)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                readyLatch.fail("disconnected status=$status")
                completePendingOp(BluetoothGatt.GATT_FAILURE)
                poisonNotifyQueues()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(g)) return
            Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: mtu=$mtu status=$status")
            if (pendingPostReadyMtu) {
                pendingPostReadyMtu = false
                return
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyLatch.fail("discover status=$status")
                return
            }
            val delayMs = profile.postDiscoverDelayMs
            if (delayMs > 0L) {
                Log.d(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: post-discover settle ${delayMs}ms",
                )
                // Never sleep on the binder thread — schedule CCCD after Ob1-style pause.
                mainHandler.postDelayed({ beginCccdSetup(g) }, delayMs)
            } else {
                beginCccdSetup(g)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!isCurrentGatt(g)) return
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: descriptorWrite uuid=${descriptor.uuid} status=$status phase=$cccdPhase",
            )
            // bleExecutor-driven CCCD (Control / Backfill) uses the op latch.
            if (cccdPhase == CccdPhase.DONE || cccdPhase == CccdPhase.NONE) {
                completePendingOp(status)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyLatch.fail("CCCD write status=$status phase=$cccdPhase")
                cccdPhase = CccdPhase.NONE
                return
            }
            when (cccdPhase) {
                CccdPhase.EXTRA -> {
                    cccdPhase = CccdPhase.AUTH
                    if (!submitAuthCccd(g)) {
                        readyLatch.fail("Auth CCCD submit failed")
                        cccdPhase = CccdPhase.NONE
                    }
                }
                CccdPhase.AUTH -> {
                    cccdPhase = CccdPhase.DONE
                    readyLatch.complete()
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.SESSION}: gatt ready control=${controlChar != null} " +
                            "backfill=${backfillChar != null}",
                    )
                    maybeRequestMtuAfterReady(g)
                }
                else -> Unit
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrentGatt(g)) return
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: charWrite uuid=${characteristic.uuid} status=$status",
            )
            completePendingOp(status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (!isCurrentGatt(g)) return
            onNotify(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!isCurrentGatt(g)) return
            onNotify(characteristic.uuid, value)
        }

        private fun onNotify(uuid: UUID, value: ByteArray?) {
            if (value == null) return
            val copy = value.copyOf()
            when (uuid) {
                OnePlusBluetoothUuids.Authentication ->
                    keksQueue.offer(TaggedKeksNotify(OnePlusKeksNotifySource.AUTHENTICATION, copy))
                OnePlusBluetoothUuids.ExtraData ->
                    keksQueue.offer(TaggedKeksNotify(OnePlusKeksNotifySource.EXTRA_DATA, copy))
                OnePlusBluetoothUuids.Control -> controlQueue.offer(copy)
                OnePlusBluetoothUuids.ProbablyBackfill -> backfillQueue.offer(copy)
            }
        }
    }

    override fun connect(deviceAddress: String, autoConnect: Boolean) {
        // Close any prior GATT without poisoning queues — then clear. Previously
        // clear()+disconnectInternal() re-inserted empty sentinels so KEKS awaitNotify
        // returned immediately ("notify timeout step=0" in the same ms as Auth write).
        disconnectInternal(poisonQueues = false)
        awaitPostCloseSettle()
        keksQueue.clear()
        controlQueue.clear()
        backfillQueue.clear()
        readyLatch.reset()
        cccdPhase = CccdPhase.NONE
        pendingPostReadyMtu = false
        pendingOpLatch.reset()
        if (deviceAddress.isBlank()) {
            error("ONEPLUS_GATT: device address blank — scan UI required")
        }
        val adapter = bluetoothManager.adapter
            ?: error("ONEPLUS_GATT: Bluetooth adapter null")
        if (!adapter.isEnabled) {
            error("ONEPLUS_GATT: Bluetooth disabled")
        }
        val handoff = profile.scanHandoffMs.coerceAtLeast(0L)
        if (handoff > 0L) {
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: scan→connect handoff ${handoff}ms",
            )
            sleepQuiet(handoff)
        }
        val remote: BluetoothDevice = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (_: Throwable) {
            error("ONEPLUS_GATT: invalid address")
        }
        device = remote
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: connectGatt autoConnect=$autoConnect " +
                "mtuOnConnect=${profile.requestMtuOnConnect}",
        )
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            remote.connectGatt(appContext, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            remote.connectGatt(appContext, autoConnect, callback)
        }
        val timeout = if (autoConnect) {
            profile.connectTimeoutMs.coerceAtLeast(60_000L)
        } else {
            profile.connectTimeoutMs
        }
        val ok = readyLatch.await(timeout)
        if (!ok) {
            disconnectInternal(poisonQueues = true)
            error("ONEPLUS_GATT: connect/discover timeout ${timeout}ms")
        }
        val err = readyLatch.error
        if (err != null) {
            disconnectInternal(poisonQueues = true)
            error("ONEPLUS_GATT: $err")
        }
        // Drop CCCD-setup noise / late disconnect sentinels before KEKS awaits Auth notifies.
        keksQueue.clear()
        controlQueue.clear()
        backfillQueue.clear()
    }

    override fun disconnect() {
        disconnectInternal(poisonQueues = true)
    }

    override fun isConnected(): Boolean = connected && gatt != null

    override fun writeAuthentication(payload: ByteArray?) {
        // Ob1: Authentication uses WRITE_TYPE_DEFAULT.
        writeChar(
            characteristic = authChar,
            payload = payload,
            label = "Auth",
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    }

    override fun writeExtraData(payload: ByteArray?) {
        if (payload == null || payload.isEmpty()) return
        // Ob1 doNext: ExtraData in ≤20-byte chunks, WRITE_TYPE_NO_RESPONSE, short gaps.
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + EXTRA_DATA_CHUNK, payload.size)
            writeChar(
                characteristic = extraChar,
                payload = payload.copyOfRange(offset, end),
                label = "ExtraData",
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            offset = end
            if (offset < payload.size) {
                sleepQuiet(EXTRA_DATA_CHUNK_GAP_MS)
            }
        }
        sleepQuiet(EXTRA_DATA_AFTER_MS)
    }

    override fun writeControl(payload: ByteArray?) {
        writeChar(
            characteristic = controlChar,
            payload = payload,
            label = "Control",
            writeType = null,
        )
    }

    override fun enableControlNotifications() {
        val g = gatt ?: error("ONEPLUS_GATT: not connected")
        val c = controlChar ?: error("ONEPLUS_GATT: Control characteristic missing")
        // Ob1 uses indications on Control.
        runBlockingDescriptorWrite(g, c, indicate = true)
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: Control indications enabled")
    }

    override fun enableBackfillNotifications() {
        val g = gatt ?: error("ONEPLUS_GATT: not connected")
        val c = backfillChar ?: error("ONEPLUS_GATT: ProbablyBackfill characteristic missing")
        // Ob1 uses notifications (not indications) on ProbablyBackfill.
        runBlockingDescriptorWrite(g, c, indicate = false)
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
        // Juggluco: createBond(TRANSPORT_LE) via reflection — critical on Samsung.
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: createBond TRANSPORT_LE")
        return createBondLe(d)
    }

    private fun beginPostConnect(g: BluetoothGatt) {
        if (profile.requestMtuOnConnect) {
            val mtu = profile.preferredMtu.coerceIn(23, 517)
            if (!g.requestMtu(mtu)) {
                g.discoverServices()
            }
        } else {
            // Ob1-like: skip MTU on connect (Samsung status 147/fragile link).
            g.discoverServices()
        }
    }

    private fun scheduleDiscoverWhenBondReady(g: BluetoothGatt) {
        val deadline = SystemClock.elapsedRealtime() + BOND_BONDING_WAIT_MS
        fun tick() {
            if (!isCurrentGatt(g) || !connected) return
            val state = try {
                g.device?.bondState
            } catch (_: Throwable) {
                null
            }
            if (state == null || state != BluetoothDevice.BOND_BONDING) {
                beginPostConnect(g)
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                readyLatch.fail("bond stuck in BOND_BONDING")
                return
            }
            mainHandler.postDelayed({ tick() }, 100L)
        }
        mainHandler.post { tick() }
    }

    private fun createBondLe(device: BluetoothDevice): Boolean {
        return try {
            val intType = Int::class.javaPrimitiveType ?: Integer.TYPE
            val method = device.javaClass.getMethod("createBond", intType)
            val ok = method.invoke(device, BluetoothDevice.TRANSPORT_LE) as Boolean
            if (!ok) {
                Log.w(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: createBond(TRANSPORT_LE) returned false — fallback",
                )
                device.createBond()
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: createBond(TRANSPORT_LE) unavailable: ${t.message}",
            )
            device.createBond()
        }
    }

    override fun awaitKeksNotify(timeoutMs: Long): OnePlusKeksNotify? {
        val v = keksQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (v === KEKS_DISCONNECT_SENTINEL) return null
        return OnePlusKeksNotify(v.source, v.payload)
    }

    override fun awaitNotify(timeoutMs: Long): ByteArray? = awaitKeksNotify(timeoutMs)?.payload

    override fun awaitControlNotify(timeoutMs: Long): ByteArray? = pollNotify(controlQueue, timeoutMs)

    override fun awaitBackfillNotify(timeoutMs: Long): ByteArray? = pollNotify(backfillQueue, timeoutMs)

    /**
     * Only the identity [DISCONNECT_SENTINEL] means "stop waiting".
     * Do not treat any empty [ByteArray] as disconnect — that raced with connect().
     */
    private fun pollNotify(queue: LinkedBlockingQueue<ByteArray>, timeoutMs: Long): ByteArray? {
        val v = queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (v === DISCONNECT_SENTINEL) return null
        return v
    }

    private fun beginCccdSetup(g: BluetoothGatt) {
        if (!isCurrentGatt(g)) return
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
        if (extraChar != null) {
            cccdPhase = CccdPhase.EXTRA
            if (!submitEnableCccd(g, extraChar!!, indicate = false)) {
                readyLatch.fail("ExtraData CCCD submit failed")
            }
        } else {
            cccdPhase = CccdPhase.AUTH
            if (!submitAuthCccd(g)) {
                readyLatch.fail("Auth CCCD submit failed")
            }
        }
    }

    private fun maybeRequestMtuAfterReady(g: BluetoothGatt) {
        if (profile.requestMtuOnConnect) return
        val mtu = profile.preferredMtu.coerceIn(23, 517)
        if (mtu <= 23) return
        pendingPostReadyMtu = true
        if (!g.requestMtu(mtu)) {
            pendingPostReadyMtu = false
        } else {
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: post-ready requestMtu=$mtu",
            )
        }
    }

    private fun awaitPostCloseSettle() {
        val settle = profile.postCloseSettleMs.coerceAtLeast(0L)
        if (settle <= 0L || lastCloseElapsedMs <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - lastCloseElapsedMs
        val remaining = settle - elapsed
        if (remaining > 0L) {
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: post-close settle ${remaining}ms (floor=${settle}ms)",
            )
            sleepQuiet(remaining)
        }
    }

    private fun refreshGatt(g: BluetoothGatt?) {
        if (!profile.useGattRefresh || g == null) return
        try {
            val method = BluetoothGatt::class.java.getMethod("refresh")
            val ok = method.invoke(g) as? Boolean ?: false
            Log.d(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: gatt.refresh()=$ok")
        } catch (t: Throwable) {
            Log.d(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: gatt.refresh unavailable: ${t.message}")
        }
    }

    private fun submitAuthCccd(g: BluetoothGatt): Boolean {
        val auth = authChar ?: return false
        // Ob1 KEKS path: setupIndication(Authentication); fall back to notify if needed.
        val indicate = auth.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        return submitEnableCccd(g, auth, indicate = indicate)
    }

    /**
     * Non-blocking CCCD submit for the discover-time state machine (binder thread).
     */
    private fun submitEnableCccd(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        indicate: Boolean,
    ): Boolean {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(OnePlusBluetoothUuids.CharacteristicUpdateNotification)
            ?: return false
        val enable = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeDescriptor(cccd, enable)
            status == BluetoothStatusCodes.SUCCESS || status == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun runBlockingDescriptorWrite(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        indicate: Boolean,
    ) {
        synchronized(gattOpLock) {
            pendingOpLatch.reset()
            pendingOpStatus.set(BluetoothGatt.GATT_SUCCESS)
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(OnePlusBluetoothUuids.CharacteristicUpdateNotification)
                ?: error("ONEPLUS_GATT: CCCD missing")
            val enable = if (indicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = g.writeDescriptor(cccd, enable)
                if (status != BluetoothStatusCodes.SUCCESS && status != BluetoothGatt.GATT_SUCCESS) {
                    error("ONEPLUS_GATT: writeDescriptor status=$status")
                }
                true
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!submitted) {
                error("ONEPLUS_GATT: writeDescriptor failed")
            }
            if (!pendingOpLatch.await(GATT_OP_TIMEOUT_MS)) {
                error("ONEPLUS_GATT: writeDescriptor timeout")
            }
            val err = pendingOpLatch.error
            if (err != null) error("ONEPLUS_GATT: $err")
            val status = pendingOpStatus.get()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error("ONEPLUS_GATT: descriptor status=$status")
            }
        }
    }

    private fun writeChar(
        characteristic: BluetoothGattCharacteristic?,
        payload: ByteArray?,
        label: String,
        writeType: Int?,
    ) {
        if (payload == null || payload.isEmpty()) return
        val g = gatt ?: error("ONEPLUS_GATT: not connected ($label)")
        val c = characteristic ?: error("ONEPLUS_GATT: missing $label characteristic")
        val resolvedType = writeType ?: if (
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        synchronized(gattOpLock) {
            pendingOpLatch.reset()
            pendingOpStatus.set(BluetoothGatt.GATT_SUCCESS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = g.writeCharacteristic(c, payload, resolvedType)
                if (status != BluetoothStatusCodes.SUCCESS && status != BluetoothGatt.GATT_SUCCESS) {
                    // 201 = ERROR_GATT_WRITE_REQUEST_BUSY — surface clearly for logcat.
                    error("ONEPLUS_GATT: write $label status=$status")
                }
            } else {
                @Suppress("DEPRECATION")
                c.value = payload
                @Suppress("DEPRECATION")
                c.writeType = resolvedType
                @Suppress("DEPRECATION")
                if (!g.writeCharacteristic(c)) {
                    error("ONEPLUS_GATT: write $label failed")
                }
            }
            if (!pendingOpLatch.await(GATT_OP_TIMEOUT_MS)) {
                error("ONEPLUS_GATT: write $label timeout")
            }
            val err = pendingOpLatch.error
            if (err != null) error("ONEPLUS_GATT: write $label $err")
            val cbStatus = pendingOpStatus.get()
            if (cbStatus != BluetoothGatt.GATT_SUCCESS) {
                error("ONEPLUS_GATT: write $label callback status=$cbStatus")
            }
            Log.d(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: wrote $label ${payload.size}b")
        }
    }

    private fun completePendingOp(status: Int) {
        pendingOpStatus.set(status)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pendingOpLatch.fail("status=$status")
        } else {
            pendingOpLatch.complete()
        }
    }

    /** Ignore binder callbacks from a GATT instance we already closed/replaced. */
    private fun isCurrentGatt(g: BluetoothGatt): Boolean = g === gatt

    private fun disconnectInternal(poisonQueues: Boolean) {
        connected = false
        cccdPhase = CccdPhase.NONE
        pendingPostReadyMtu = false
        mainHandler.removeCallbacksAndMessages(null)
        completePendingOp(BluetoothGatt.GATT_FAILURE)
        if (poisonQueues) {
            poisonNotifyQueues()
        }
        // Detach before close so async DISCONNECTED on the old instance cannot fail the
        // next connect()'s readyLatch (classic Android BLE race → ONEPLUS_GATT: disconnected).
        val closing = gatt
        gatt = null
        device = null
        authChar = null
        extraChar = null
        controlChar = null
        backfillChar = null
        refreshGatt(closing)
        try {
            closing?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            closing?.close()
        } catch (_: Throwable) {
        }
        if (closing != null) {
            lastCloseElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun poisonNotifyQueues() {
        keksQueue.offer(KEKS_DISCONNECT_SENTINEL)
        controlQueue.offer(DISCONNECT_SENTINEL)
        backfillQueue.offer(DISCONNECT_SENTINEL)
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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

    /** Internal KEKS queue frame; [KEKS_DISCONNECT_SENTINEL] is identity-checked. */
    private class TaggedKeksNotify(
        val source: OnePlusKeksNotifySource,
        val payload: ByteArray,
    )

    companion object {
        private const val GATT_OP_TIMEOUT_MS = 10_000L
        private const val EXTRA_DATA_CHUNK = 20
        private const val EXTRA_DATA_CHUNK_GAP_MS = 40L
        private const val BOND_BONDING_WAIT_MS = 45_000L
        /** Ob1 sleeps ~500ms after ExtraData chunks before Auth write. */
        private const val EXTRA_DATA_AFTER_MS = 500L

        /** Identity sentinel for Control/Backfill await* unblock on disconnect. */
        private val DISCONNECT_SENTINEL = ByteArray(0)

        /** Identity sentinel for KEKS await* unblock on disconnect. */
        private val KEKS_DISCONNECT_SENTINEL = TaggedKeksNotify(
            OnePlusKeksNotifySource.AUTHENTICATION,
            ByteArray(0),
        )
    }
}
