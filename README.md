# One UI 9 Native AOD Enabler

Adds Samsung's Always On Display to devices running on One UI 9.

Tested on SM-A165F, firmware A165FXXUBEZJ7.

## Install

1. Download `OneUI9-Native-AOD-Enabler.zip` from the [AOD release](https://github.com/RaphaelSolis/OneUI9-Native-AOD-Enabler/releases/tag/v0.4-experimental).
2. Install it in KernelSU Manager and reboot.
3. Enable **Always On Display** in Settings.

Keep stock ClockPack enabled. If you use NeoZygisk, turn off **Unmount modules** for **System UI** and **Always On Display** in KernelSU.

## AOD themes

If an applied theme still shows the default clock, install `OneUI9-AOD-Theme-Fix.apk` from the [theme fix release](https://github.com/RaphaelSolis/OneUI9-Native-AOD-Enabler/releases/tag/aod-theme-fix-v0.3.1). Enable it in Vector or LSPosed for **System UI** and **Always On Display**, then reboot.

## Remove

Disable the modules and reboot.
