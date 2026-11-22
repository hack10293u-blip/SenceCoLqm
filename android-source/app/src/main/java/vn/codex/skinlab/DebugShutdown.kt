package vn.codex.skinlab

// Return before disabling ADB; the detached child survives loss of the transport.
internal const val DEBUG_SHUTDOWN_COMMAND = "nohup /system/bin/sh -c 'sleep 1; settings put global development_settings_enabled 0; settings put global adb_enabled 0; settings put global adb_wifi_enabled 0' </dev/null >/dev/null 2>&1 &"
