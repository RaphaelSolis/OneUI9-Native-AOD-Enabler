#!/system/bin/sh
# Read-only Android diagnostics; only the output file is written.
A16_LOG_DIR=/data/adb/a16-aod-v4
mkdir -p "$A16_LOG_DIR"
chmod 0700 "$A16_LOG_DIR"
A16_LOG_FILE="$A16_LOG_DIR/test-diagnostics.txt"
(
  date
  getprop ro.build.display.id
  pm path com.samsung.android.app.clockpack
  pm path com.samsung.android.app.aodservice
  settings get system aod_mode
  settings get system aod_show_state
  settings get secure doze_always_on
  cmd package query-services --brief -a com.samsung.systemui.action.PLUGIN_FACE_WIDGET
  cmd package query-services --brief -a com.android.systemui.action.PLUGIN_AOD
  dumpsys aod
  logcat -d -v threadtime -b main -b system -b crash -t 800     PluginFaceWidgetManager:V DozeService:V PluginAODManager:V     PluginAODImpl:V ClockPack:V AndroidRuntime:E '*:S'
) > "$A16_LOG_FILE" 2>&1
chmod 0600 "$A16_LOG_FILE"
printf 'Saved: %s\n' "$A16_LOG_FILE"
