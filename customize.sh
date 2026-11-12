#!/system/bin/sh

case "${KSU:-}" in
  true|1) ;;
  *) abort "Install this module with KernelSU." ;;
esac

[ "$(getprop ro.product.model)" = SM-A165F ] || abort "This package was tested on SM-A165F only."
[ "$(getprop ro.build.fingerprint)" = 'samsung/a16nsxx/a16:17/CP2A.260605.016/A165FXXUBEZJ7:user/release-keys' ] || abort "Firmware changed; this package needs a compatibility check."
[ "$(getprop ro.build.version.oneui)" = 90000 ] || abort "This package requires OneUI 9."

[ "$(pm path com.samsung.android.app.clockpack)" = 'package:/system/priv-app/ClockPack_v80/ClockPack_v80.apk' ] || abort "The stock ClockPack must be present."
if pm list packages -d --user 0 | grep -qx 'package:com.samsung.android.app.clockpack'; then
  abort "Enable the stock ClockPack before installing."
fi

OLD_TRIAL=/data/adb/modules/a16_native_aod_trial
if [ -d "$OLD_TRIAL" ] && [ ! -f "$OLD_TRIAL/disable" ] && [ ! -f "$OLD_TRIAL/remove" ]; then
  abort "Disable the old a16_native_aod_trial module first."
fi

AOD_PATH=$(pm path com.samsung.android.app.aodservice 2>/dev/null)
case "$AOD_PATH" in
  ''|'package:/system/priv-app/SamsungAODCoexist/SamsungAODCoexist.apk') ;;
  *) abort "Another AOD package is installed. Resolve it before installing this module." ;;
esac

APK_SUM=$(sha256sum "$MODPATH/system/priv-app/SamsungAODCoexist/SamsungAODCoexist.apk")
[ "${APK_SUM%% *}" = ea8ad221a10ce2e2a47e03cf9a5c5781e4063379f82d3ff1015b4817579b23ae ] || abort "The Samsung AOD APK does not match this release."

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/collect-test-logs.sh" 0 0 0755

ui_print "OneUI 9 Native AOD Enabler"
ui_print "Keep the stock ClockPack enabled. Reboot to activate."
ui_print "With NeoZygisk, turn off Unmount modules for AOD and System UI."
