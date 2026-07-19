package app.aaps.plugins.dexcomoneplus.gatt

/**
 * GATT connect / discovery / notify routing (A6.3).
 *
 * ⚠️ ASYNC IMPACT: `BluetoothGattCallback` arrives on the binder thread.
 * Sequential work (MTU, discover, CCCD, write) must run on a single bleExecutor.
 * Platform impl must serialize GATT ops (one outstanding write/descriptor) — Android 13+
 * returns status 201 (`ERROR_GATT_WRITE_REQUEST_BUSY`) otherwise.
 * [awaitNotify] / [awaitControlNotify] / [awaitBackfillNotify] block the caller —
 * Auth/ExtraData, Control, and ProbablyBackfill use **separate** queues.
 * [disconnect] must unblock all three via an identity disconnect sentinel (not any empty
 * byte array). Do not block that executor with network or AIMI work.
 */
interface OnePlusGattClient {
    /**
     * Connect + discover + Auth/Extra CCCD until ready.
     * @param autoConnect Ob1 CONNECT path — true after hard-connect failures on flaky stacks
     */
    fun connect(deviceAddress: String, autoConnect: Boolean = false)
    fun disconnect()
    fun isConnected(): Boolean

    fun writeAuthentication(payload: ByteArray?)
    fun writeExtraData(payload: ByteArray?)
    fun writeControl(payload: ByteArray?)

    /**
     * Enable indications (preferred) / notifications on Control (EGV path).
     * Alias intent: Ob1 `setupIndication(Control)`.
     */
    fun enableControlNotifications()

    /**
     * Enable notifications on ProbablyBackfill (Ob1 `setupNotification(ProbablyBackfill)`).
     */
    fun enableBackfillNotifications()

    /** True when the remote device is Android-bonded (`BluetoothDevice.BOND_BONDED`). */
    fun isBonded(): Boolean

    /**
     * Request Android bonding (system pairing UI may appear). No-op if already bonded.
     * @return false if createBond could not be started
     */
    fun createBond(): Boolean

    /**
     * Block until next Auth or ExtraData notification (KEKS path), with source tag.
     * Prefer this over [awaitNotify] so KEKS can route Ob1-style.
     */
    fun awaitKeksNotify(timeoutMs: Long): OnePlusKeksNotify?

    /**
     * Block until next Auth or ExtraData notification (KEKS path).
     * Payload only — prefer [awaitKeksNotify] for handshake.
     */
    fun awaitNotify(timeoutMs: Long): ByteArray?

    /**
     * Block until next Control characteristic indication/notification (EGV path).
     * Separate from [awaitKeksNotify] so KEKS and EGV do not cross-consume packets.
     */
    fun awaitControlNotify(timeoutMs: Long): ByteArray?

    /**
     * Block until next ProbablyBackfill notification (history stream).
     */
    fun awaitBackfillNotify(timeoutMs: Long): ByteArray?
}

class OnePlusGattClientUnimplemented : OnePlusGattClient {
    override fun connect(deviceAddress: String, autoConnect: Boolean) {
        error("ONEPLUS_GATT_UNIMPLEMENTED: await A3 GO + A1-pinned Direct port")
    }

    override fun disconnect() = Unit
    override fun isConnected(): Boolean = false
    override fun writeAuthentication(payload: ByteArray?) = Unit
    override fun writeExtraData(payload: ByteArray?) = Unit
    override fun writeControl(payload: ByteArray?) = Unit
    override fun enableControlNotifications() = Unit
    override fun enableBackfillNotifications() = Unit
    override fun isBonded(): Boolean = false
    override fun createBond(): Boolean = false
    override fun awaitKeksNotify(timeoutMs: Long): OnePlusKeksNotify? = null
    override fun awaitNotify(timeoutMs: Long): ByteArray? = null
    override fun awaitControlNotify(timeoutMs: Long): ByteArray? = null
    override fun awaitBackfillNotify(timeoutMs: Long): ByteArray? = null
}
