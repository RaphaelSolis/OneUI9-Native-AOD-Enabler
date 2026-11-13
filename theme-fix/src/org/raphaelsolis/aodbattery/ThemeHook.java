package org.raphaelsolis.aodbattery;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Stock ClockPack must read the installed AOD provider's theme selection. */
final class ThemeHook {
    static final String CLOCKPACK_PATH = "/system/priv-app/ClockPack_v80/ClockPack_v80.apk";
    static final String CLOCKPACK_SHA256 =
            "3bf2beb6799e1b6c36cbebd7db4709409bf73169fb71500383d0390d967463a3";
    private static final String TAG = "A16AodTheme";
    private static final Uri SELECTION = Uri.parse(
            "content://com.samsung.android.app.aodservice.provider/settings/aod_theme_content");
    private static final Set<Class<?>> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static Boolean stockVerified;
    private static boolean installing;

    static synchronized void tryClockPack(ClassLoader loader) {
        if (loader == null || installing) return;
        installing = true;
        try {
            Class<?> helper;
            try { helper = Class.forName("aod.xs2", false, loader); }
            catch (ClassNotFoundException expected) { return; }
            if (INSTALLED.contains(helper)) return;
            if (stockVerified == null) stockVerified = verifyStock();
            if (!stockVerified) return;
            ThemeViewHook.installStock(helper.getClassLoader());
            Method selected = helper.getDeclaredMethod("A", Context.class);
            if (selected.getReturnType() != int.class)
                throw new IllegalStateException("Unexpected AOD theme getter type");
            XposedBridge.hookMethod(selected, new XC_MethodHook() {
                int last = Integer.MIN_VALUE;
                boolean failureReported;
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof Context)) return;
                    Context context = (Context) param.args[0];
                    // The stock plugin initializes its own legacy AOD database. The donor
                    // provider owns the selection changed by Galaxy Themes. Read it at the
                    // normal clock refresh/sleep events, with no polling or cached default.
                    try (Cursor cursor = context.getContentResolver().query(
                            SELECTION, null, null, null, null)) {
                        if (cursor == null || !cursor.moveToFirst()) return;
                        int column = cursor.getColumnIndex("SELECTED_THEME_CLOCK_TYPE");
                        if (column < 0) return;
                        int nativeType = cursor.getInt(column);
                        if (nativeType != 0 && (nativeType < 50004 || nativeType > 50007)) return;
                        Object previous = param.getResult();
                        param.setResult(nativeType);
                        if (last != nativeType) {
                            last = nativeType;
                            log("ClockPack theme selection " + previous + " -> native AOD " + nativeType);
                        }
                    } catch (Throwable error) {
                        if (!failureReported) {
                            failureReported = true;
                            log("Native theme selection unavailable; retained stock result: "
                                    + error.getClass().getSimpleName());
                        }
                    }
                }
            });
            INSTALLED.add(helper);
            log("Stock ClockPack native AOD theme selection bridge installed");
        } catch (Throwable error) {
            log("ClockPack contract mismatch; theme bridge inactive: " + error);
        } finally {
            installing = false;
        }
    }

    private static boolean verifyStock() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(CLOCKPACK_PATH)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
        boolean matches = CLOCKPACK_SHA256.equals(hex.toString());
        if (!matches) log("Stock ClockPack changed; theme bridge inactive");
        return matches;
    }

    private static void log(String message) {
        try { Log.i(TAG, message); XposedBridge.log(TAG + ": " + message); }
        catch (Throwable ignored) {}
    }
}
