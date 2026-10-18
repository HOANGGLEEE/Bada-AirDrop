/*
 * Experimental Bluetooth LE scanner. Detects Apple manufacturer advertisements
 * (company ID 0x004C), NOT iPhone identity or confirmed AirDrop/AWDL support.
 */
package dev.bluehouse.bada.debug

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AirDropCapabilityProbeActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var report: TextView
    private lateinit var button: Button
    private var scanning = false
    private var total = 0
    private var applePackets = 0
    private var lastError = ""
    private val appleTypes = mutableMapOf<Int, Int>()
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            total++
            val bytes = result.scanRecord?.getManufacturerSpecificData(0x004C)
            if (bytes != null) {
                applePackets++
                val type = bytes.firstOrNull()?.toInt()?.and(0xff) ?: -1
                appleTypes[type] = (appleTypes[type] ?: 0) + 1
            }
            if (total % 5 == 0 || bytes != null) render()
        }
        override fun onScanFailed(errorCode: Int) {
            lastError = "Bluetooth scan error: $errorCode"
            stopScan()
        }
    }
    private val stopRunnable = Runnable { stopScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            fitsSystemWindows = true
        }
        button = Button(this).apply {
            text = "Scan nearby BLE (15 seconds)"
            setOnClickListener { if (scanning) stopScan() else requestAndStart() }
        }
        report = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 16)
        }
        root.addView(button)
        root.addView(report)
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
        render()
    }

    private fun requestAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 102)
        else startScan()
    }

    @Deprecated("Runtime permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                lastError = "Bluetooth scan permission denied"
                render()
            }
        }
    }

    private fun startScan() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter?.isEnabled != true) {
            lastError = "Turn on Bluetooth first"
            render()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            lastError = "Bluetooth LE scanner unavailable"
            render()
            return
        }
        total = 0
        applePackets = 0
        appleTypes.clear()
        lastError = ""
        try {
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            scanning = true
            handler.postDelayed(stopRunnable, 15000)
        } catch (e: SecurityException) {
            lastError = "Scan permission error: " + e.message
        } catch (e: Exception) {
            lastError = "Scan failed: " + e.javaClass.simpleName
        }
        render()
    }

    private fun stopScan() {
        handler.removeCallbacks(stopRunnable)
        if (scanning) {
            try {
                val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: SecurityException) { }
        }
        scanning = false
        render()
    }

    private fun render() {
        button.text = if (scanning) "Stop scan" else "Scan nearby BLE (15 seconds)"
        val features = listOf(
            PackageManager.FEATURE_BLUETOOTH_LE,
            PackageManager.FEATURE_WIFI,
            PackageManager.FEATURE_WIFI_DIRECT,
            "android.hardware.wifi.aware"
        )
        report.text = buildString {
            appendLine("Bada - nearby BLE diagnostic v2")
            appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Android SDK: " + Build.VERSION.SDK_INT)
            features.forEach { appendLine(it + ": " + packageManager.hasSystemFeature(it)) }
            appendLine()
            appendLine("Scanning: $scanning")
            appendLine("BLE advertisements observed: $total")
            appendLine("Apple company ID (0x004C) advertisements: $applePackets")
            appendLine("Apple manufacturer data first-byte counts:")
            if (appleTypes.isEmpty()) appendLine("None")
            else appleTypes.toSortedMap().forEach { (type, count) ->
                appendLine("0x" + type.toString(16).padStart(2, '0') + ": $count")
            }
            if (lastError.isNotBlank()) appendLine("Error: $lastError")
            appendLine()
            appendLine("Test with iPhone AirDrop set to Everyone for 10 minutes.")
            appendLine("Apple BLE packets may also come from watches, headphones, or other devices.")
            appendLine("This cannot identify an iPhone or confirm AirDrop or AWDL.")
            appendLine("No device addresses are stored. No file transfer is implemented.")
            appendLine("No Shizuku or root needed for this scan.")
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}
