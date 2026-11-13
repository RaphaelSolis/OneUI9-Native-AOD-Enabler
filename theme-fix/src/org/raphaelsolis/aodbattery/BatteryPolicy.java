package org.raphaelsolis.aodbattery;

/** Conditions for exposing the existing main AOD battery view. */
public final class BatteryPolicy {
    private BatteryPolicy() {}

    public static boolean shouldShow(boolean mainView, boolean attached, boolean aodActive,
                                     boolean plugged, boolean usbRestricted, int level) {
        return mainView && attached && aodActive && !plugged && !usbRestricted
                && level >= 0 && level <= 100;
    }
}
