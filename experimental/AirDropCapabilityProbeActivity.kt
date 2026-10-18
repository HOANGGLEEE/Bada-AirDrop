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
import android.widget.Spinner
import android.widget.ArrayAdapter
import kotlin.math.roundToInt
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.content.Context
import java.net.NetworkInterface
import java.util.Collections
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AirDropCapabilityProbeActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var report: TextView
    private lateinit var button: Button
    private lateinit var p2pButton: Button
    private var p2pStatus = "Not tested"
    private var p2pPeers = "Not queried"
    private lateinit var mode: Spinner
    private val sessions = mutableMapOf<String, Summary>()
    private data class Summary(
        val packets: Int, val apple: Int, val rssi: Int, val samples: Int,
        val types: Map<Int, Int>, val rssiByType: Map<Int, Pair<Int, Int>>,
        val rssiBins: Map<String, Int>
    )
    private val typeRssi = mutableMapOf<Int, Pair<Int, Int>>()
    private val rssiBins = mutableMapOf<String, Int>()
    private val payloadLengths = mutableMapOf<Int, MutableMap<Int, Int>>()
    private val trials = mutableMapOf<String, MutableList<Summary>>()
    private var sessionCounter = 0
    private var selectedMode = "iPhone Bluetooth OFF"
    private var rssiSum = 0
    private var rssiSamples = 0
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
                rssiSum += result.rssi
                rssiSamples++
                val type = bytes.firstOrNull()?.toInt()?.and(0xff) ?: -1
                appleTypes[type] = (appleTypes[type] ?: 0) + 1
                val sizes = payloadLengths.getOrPut(type) { mutableMapOf() }
                sizes[bytes.size] = (sizes[bytes.size] ?: 0) + 1
                val prev = typeRssi[type] ?: Pair(0, 0)
                typeRssi[type] = Pair(prev.first + result.rssi, prev.second + 1)
                val bin = when {
                    result.rssi >= -60 -> "strong (>= -60 dBm)"
                    result.rssi >= -75 -> "medium (-74..-60 dBm)"
                    else -> "weak (< -75 dBm)"
                }
                rssiBins[bin] = (rssiBins[bin] ?: 0) + 1
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
        mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@AirDropCapabilityProbeActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("iPhone Bluetooth OFF", "iPhone Bluetooth ON + AirDrop Everyone"))
        }
        root.addView(mode)
        root.addView(button)
        p2pButton = Button(this).apply {
            text = "Test Wi-Fi Direct peer discovery (not AirDrop)"
            setOnClickListener { startP2pTest() }
        }
        root.addView(p2pButton)
        root.addView(report)
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
        render()
    }

    private fun requestAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
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
        if (requestCode == 103) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startP2pTest()
            else { p2pStatus = "Nearby Wi-Fi permission denied"; render() }
        }
        if (requestCode == 102) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                lastError = "Bluetooth scan permission denied"
                render()
            }
        }
    }

    private fun startP2pTest() {
        val required = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(required) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(required), 103)
            return
        }
        try {
            val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (manager == null) {
                p2pStatus = "Wi-Fi Direct service unavailable"
                render()
                return
            }
            val channel = manager.initialize(this, mainLooper, null)
            if (channel == null) {
                p2pStatus = "Wi-Fi Direct channel initialization failed"
                render()
                return
            }
            p2pStatus = "Requesting peer discovery (Wi-Fi Direct, NOT AWDL)"
            p2pPeers = "Awaiting result"
            render()
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    p2pStatus = "Discovery request accepted (NOT proof of connection)"
                    render()
                    handler.postDelayed({
                        try {
                            manager.requestPeers(channel) { peers ->
                                p2pPeers = "Visible Wi-Fi Direct peers: " + peers.deviceList.size +
                                    " (identities intentionally hidden)"
                                render()
                            }
                        } catch (e: Exception) {
                            p2pPeers = "Peer query error: " + e.javaClass.simpleName
                            render()
                        }
                    }, 10000)
                }
                override fun onFailure(reason: Int) {
                    p2pStatus = "Wi-Fi Direct discovery rejected: reason=$reason"
                    render()
                }
            })
        } catch (e: Exception) {
            p2pStatus = "Wi-Fi Direct error: " + e.javaClass.simpleName
            render()
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
        selectedMode = mode.selectedItem.toString()
        total = 0
        applePackets = 0
        rssiSum = 0
        rssiSamples = 0
        appleTypes.clear()
        typeRssi.clear()
        rssiBins.clear()
        payloadLengths.clear()
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
        if (scanning) {
            val summary = Summary(total, applePackets, rssiSum, rssiSamples,
                appleTypes.toMap(), typeRssi.toMap(), rssiBins.toMap())
            sessions[selectedMode] = summary
            trials.getOrPut(selectedMode) { mutableListOf() }.add(summary)
            sessionCounter++
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
            appendLine("Bada - nearby BLE diagnostic v6")
            appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Android SDK: " + Build.VERSION.SDK_INT)
            features.forEach { appendLine(it + ": " + packageManager.hasSystemFeature(it)) }
            appendLine()
            appendLine("Scanning: $scanning ($selectedMode)")
            appendLine("Choose OFF/ON, scan each for 15 seconds.")
            appendLine("Each scan adds a trial; most recent OFF/ON also shown.")
            appendLine("Completed trials: $sessionCounter")
            appendLine("Wi-Fi Direct active test (NOT AWDL):")
            appendLine("  $p2pStatus")
            appendLine("  $p2pPeers")
            appendLine("  Android Location setting may need to be ON for peer discovery.")
            appendLine("  Successful Wi-Fi Direct discovery does not establish AirDrop support.")
            appendLine("Wi-Fi capability (NOT AWDL):")
            appendLine("Wi-Fi Aware declared: " + packageManager.hasSystemFeature("android.hardware.wifi.aware"))
            appendLine("Wi-Fi Direct declared: " + packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
            appendLine("AWDL support: UNKNOWN; cannot be established from Android feature flags.")
            appendLine("Network interface diagnostic (names only, no addresses):")
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                if (interfaces == null) appendLine("  Interface list unavailable")
                else {
                    val entries = Collections.list(interfaces).sortedBy { it.name }
                    if (entries.isEmpty()) appendLine("  No interfaces exposed")
                    entries.forEach { nic ->
                        val up = try { nic.isUp } catch (_: Exception) { false }
                        val wirelessName = nic.name.startsWith("wlan") ||
                            nic.name.startsWith("p2p") || nic.name.startsWith("aware") ||
                            nic.name.startsWith("awdl")
                        appendLine("  " + nic.name + " | up=" + up +
                            " | candidateWiFiName=" + wirelessName)
                    }
                }
            } catch (e: Exception) {
                appendLine("  Interface inspection unavailable: " + e.javaClass.simpleName)
            }
            appendLine("An interface name cannot prove AWDL driver support.")
            appendLine()
            appendLine("BLE advertisements observed: $total")
            appendLine("Apple company ID (0x004C) advertisements: $applePackets")
            appendLine("Apple manufacturer data first-byte counts:")
            if (appleTypes.isEmpty()) appendLine("None")
            else appleTypes.toSortedMap().forEach { (type, count) ->
                appendLine("0x" + type.toString(16).padStart(2, '0') + ": $count")
            }
            if (lastError.isNotBlank()) appendLine("Error: $lastError")
            appendLine("Apple RSSI histogram:")
            rssiBins.toSortedMap().forEach { (bin, count) -> appendLine("  $bin: $count") }
            appendLine("Apple manufacturer payload sizes (bytes) by first byte:")
            payloadLengths.toSortedMap().forEach { (type, sizes) ->
                appendLine("  0x" + type.toString(16) + ": " +
                    sizes.toSortedMap().entries.joinToString { it.key.toString() + "B=" + it.value })
            }
            appendLine("Payload size is not an AirDrop protocol identification.")
            appendLine("Per Apple type mean RSSI:")
            typeRssi.toSortedMap().forEach { (type, pair) ->
                appendLine("  0x" + type.toString(16) + ": " +
                    (if (pair.second > 0) (pair.first / pair.second).toString() else "n/a") + " dBm")
            }
            appendLine()
            appendLine("Saved comparison (Apple packets, mean RSSI):")
            listOf("iPhone Bluetooth OFF", "iPhone Bluetooth ON + AirDrop Everyone").forEach { key ->
                val item = sessions[key]
                appendLine(key + ": " + (item?.apple?.toString() ?: "not scanned") +
                    " | " + (if (item != null && item.samples > 0) (item.rssi / item.samples).toString() + " dBm" else "RSSI unavailable"))
                if (item != null) appendLine("  types: " + item.types.toSortedMap().entries.joinToString { "0x" + it.key.toString(16) + "=" + it.value })
                val history = trials[key].orEmpty()
                appendLine("  trial counts: " + history.joinToString { it.apple.toString() })
                if (history.isNotEmpty()) {
                    val avg = history.map { it.apple }.average()
                    appendLine("  average Apple packets/trial: " + String.format(java.util.Locale.US, "%.1f", avg))
                }
            }
            val off = sessions["iPhone Bluetooth OFF"]
            val on = sessions["iPhone Bluetooth ON + AirDrop Everyone"]
            if (off != null && on != null) {
                appendLine("ON - OFF Apple packet difference: " + (on.apple - off.apple))
                appendLine("Packet counts vary naturally; this is not proof of iPhone identity.")
                val offTrials = trials["iPhone Bluetooth OFF"].orEmpty()
                val onTrials = trials["iPhone Bluetooth ON + AirDrop Everyone"].orEmpty()
                if (offTrials.size >= 2 && onTrials.size >= 2) {
                    val delta = onTrials.map { it.apple }.average() - offTrials.map { it.apple }.average()
                    appendLine("Mean ON - OFF across trials: " + String.format(java.util.Locale.US, "%.1f", delta))
                }
            }
            appendLine()
            appendLine("Test with iPhone AirDrop set to Everyone for 10 minutes.")
            appendLine("Apple BLE packets may also come from watches, headphones, or other devices.")
            appendLine("This cannot identify an iPhone or confirm AirDrop or AWDL.")
            appendLine("No device addresses, IP addresses, or raw payloads are stored. Aggregate results are in memory only. No file transfer is implemented.")
            appendLine("No Shizuku or root needed for this scan.")
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}
