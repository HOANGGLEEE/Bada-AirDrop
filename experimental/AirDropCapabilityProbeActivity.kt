/*
 * Experimental Bada diagnostic activity.
 * This does not implement Apple AirDrop or AWDL.
 */
package dev.bluehouse.bada.debug

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AirDropCapabilityProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val features = listOf(
            PackageManager.FEATURE_BLUETOOTH_LE,
            PackageManager.FEATURE_WIFI,
            PackageManager.FEATURE_WIFI_DIRECT,
            "android.hardware.wifi.aware"
        )
        val report = buildString {
            appendLine("Bada AirDrop capability check")
            appendLine("This is NOT an AirDrop implementation.")
            appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Android SDK: " + Build.VERSION.SDK_INT)
            features.forEach { feature ->
                appendLine(feature + ": " + packageManager.hasSystemFeature(feature))
            }
            appendLine()
            appendLine("Wi-Fi Direct / Wi-Fi Aware / Bluetooth LE are not proof of AWDL.")
            appendLine("No iPhone discovery or file transfer is implemented.")
            appendLine("No root or Shizuku required for this test.")
        }
        setContentView(ScrollView(this).apply {
            addView(TextView(this@AirDropCapabilityProbeActivity).apply {
                text = report
                textSize = 16f
                setTextIsSelectable(true)
                setPadding(32, 32, 32, 32)
            })
        })
    }
}
