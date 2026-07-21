package app.aaps.plugins.dexcomoneplus.scan

/**
 * BLE scan façade for ONE+ / G7-family ADV (A6.2).
 *
 * ⚠️ ASYNC IMPACT: Android `ScanCallback` runs on the binder thread;
 * [OnePlusScanListener.onDevice] may be invoked off the main thread — hop to main for UI.
 */
fun interface OnePlusScanListener {
    fun onDevice(result: OnePlusScanResult)
}

interface OnePlusBleScanner {
    fun startScan(listener: OnePlusScanListener)
    fun stopScan()
    fun isScanning(): Boolean

    /**
     * Block until [address] is seen in ADV (or timeout). Uses a **private** LE [ScanCallback]
     * so UI [stopScan] cannot cancel it.
     *
     * ⚠️ ASYNC IMPACT: blocks caller; binder delivers results.
     */
    fun awaitTargetMac(address: String, timeoutMs: Long): OnePlusScanResult? = null
}

/**
 * No-op scanner for unit tests / Stub driver path.
 */
class OnePlusBleScannerStub : OnePlusBleScanner {
    @Volatile
    private var scanning = false

    override fun startScan(listener: OnePlusScanListener) {
        scanning = true
    }

    override fun stopScan() {
        scanning = false
    }

    override fun isScanning(): Boolean = scanning
}
