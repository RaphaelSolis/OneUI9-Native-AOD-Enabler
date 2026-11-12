# OneUI 9 Native AOD Enabler

Enables Samsung's native Always On Display on OneUI 9. It uses Samsung AOD 8.9.36.23 alongside the phone's stock ClockPack.

Tested on **Galaxy A16 (SM-A165F), A165FXXUBEZJ7, Android 17 / OneUI 9**. The installer checks this firmware before installing. Other builds need their own compatibility checks and feature files.

## Installation

1. Use KernelSU with working module mounts. This phone uses `meta-overlayfs`.
2. Download `OneUI9-Native-AOD-Enabler.zip` from [Releases](https://github.com/RaphaelSolis/OneUI9-Native-AOD-Enabler/releases).
3. Install the ZIP in KernelSU Manager and reboot.
4. Open **Settings → Lock screen and AOD → Always On Display** and enable it.

Keep the stock ClockPack enabled.

If you use NeoZygisk, turn off **Unmount modules** for **Always On Display** and **System UI** in KernelSU's app profiles. Otherwise, those apps can lose access to the module's AOD APK and crash. On this phone, they share the same UID, so one profile covers both.

The battery percentage while unplugged uses a separate Xposed hook. That hook is not included here.

## Removal

Disable this module in KernelSU Manager and reboot. You can then remove it.

## Module details

```ini
id=a16_native_aod_coexist
name=OneUI 9 Native AOD Enabler
version=0.4-experimental
versionCode=4
author=Raphael Solis
description=Enables Always On Display on devices running on OneUI 9.
```

## Build

Run `python3 build.py`. The ZIP and its SHA-256 checksum will be saved in `dist/`. Python 3 is the only build dependency; the Samsung APK is already included.

Boot diagnostics are saved locally to `/data/adb/a16-aod-v4/test-diagnostics.txt`.

The included Samsung APK is unmodified and belongs to Samsung.
