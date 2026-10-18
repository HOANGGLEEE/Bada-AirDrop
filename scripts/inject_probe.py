#!/usr/bin/env python3
"""Inject a launcher-accessible, debug-only hardware inventory into upstream Bada."""
from pathlib import Path

source = Path("experimental/AirDropCapabilityProbeActivity.kt")
target = Path("source/app/src/debug/kotlin/dev/bluehouse/bada/debug/AirDropCapabilityProbeActivity.kt")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
manifest = Path("source/app/src/debug/AndroidManifest.xml")
if not manifest.exists():
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application></application></manifest>',
        encoding="utf-8",
    )
xml = manifest.read_text(encoding="utf-8")
permissions = """<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
"""
if "android.permission.NEARBY_WIFI_DEVICES" not in xml:
    xml = xml.replace("<application", permissions + "<application", 1)

if "AirDropCapabilityProbeActivity" not in xml:
    if "</application>" not in xml:
        raise RuntimeError("Upstream debug manifest format not understood")
    activity = """<activity android:name=".debug.AirDropCapabilityProbeActivity"
        android:label="AirDrop Capability Check"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
    """
    xml = xml.replace("</application>", activity + "</application>")
manifest.write_text(xml, encoding="utf-8")
print("Debug-only capability activity installed; no AirDrop transport added.")
