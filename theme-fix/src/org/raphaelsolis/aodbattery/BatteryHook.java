package org.raphaelsolis.aodbattery;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Legacy LSPosed module for the exact Samsung donor already used by this A16. */
public final class BatteryHook implements IXposedHookLoadPackage {
    public static final String FINGERPRINT =
            "samsung/a16nsxx/a16:17/CP2A.260605.016/A165FXXUBEZJ7:user/release-keys";
    public static final String APK_PATH =
            "/system/priv-app/SamsungAODCoexist/SamsungAODCoexist.apk";
    public static final String APK_SHA256 =
            "ea8ad221a10ce2e2a47e03cf9a5c5781e4063379f82d3ff1015b4817579b23ae";
    private static final String VIEW_CLASS =
            "com.samsung.android.uniform.widget.charginginfo.ChargingInfoView";
    private static final String TAG = "A16AodBattery";
    private static final Set<Class<?>> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static boolean observing;
    private static Boolean verified;
    private static boolean installing;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)
                && !"com.samsung.android.app.aodservice".equals(param.packageName)) return;
        if (!"SM-A165F".equals(Build.MODEL) || !FINGERPRINT.equals(Build.FINGERPRINT)) return;
        ThemeHook.tryClockPack(param.classLoader);
        // Both packages can live in SystemUI; the donor can also use a later plugin loader.
        tryInstall(param.classLoader);
        synchronized (BatteryHook.class) {
            if (observing) return;
            try {
                XposedBridge.hookAllConstructors(BaseDexClassLoader.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hook) {
                        if (!(hook.thisObject instanceof ClassLoader)) return;
                        for (Object argument : hook.args) {
                            if (argument instanceof String && ((String) argument).contains(ThemeHook.CLOCKPACK_PATH)) {
                                ThemeHook.tryClockPack((ClassLoader) hook.thisObject);
                            }
                            if (argument instanceof String && ((String) argument).contains(APK_PATH)) {
                                tryInstall((ClassLoader) hook.thisObject);
                                return;
                            }
                        }
                    }
                });
                observing = true;
            } catch (Throwable error) {
                log("Could not observe the donor class loader; hooks remain inactive", error);
            }
        }
    }

    private static synchronized void tryInstall(ClassLoader loader) {
        if (loader == null || installing) return;
        installing = true;
        try {
            Class<?> view;
            try {
                view = Class.forName(VIEW_CLASS, false, loader);
            } catch (ClassNotFoundException expected) {
                return; // SystemUI's initial loader often precedes the AOD plugin loader.
            }
            if (INSTALLED.contains(view)) return;
            if (verified == null) verified = verifyDonor();
            if (!verified) return;
            ThemeViewHook.installDonor(view.getClassLoader());
            Binding binding = new Binding(view.getClassLoader());
            binding.install();
            INSTALLED.add(view);
            log("Native AOD battery hooks installed for the supported donor", null);
        } catch (Throwable error) {
            log("Donor contract did not match; no battery hooks activated", error);
        } finally {
            installing = false;
        }
    }

    private static boolean verifyDonor() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(APK_PATH)) {
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format("%02x", value & 255));
        if (APK_SHA256.equals(actual.toString())) return true;
        log("Donor APK changed; hooks remain inactive until compatibility is checked", null);
        return false;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> owner, String name, Class<?>... types) throws Exception {
        Method result = owner.getDeclaredMethod(name, types);
        result.setAccessible(true);
        return result;
    }

    private static void log(String message, Throwable error) {
        String line = TAG + ": " + message;
        try {
            Log.i(TAG, message);
            XposedBridge.log(line);
            if (error != null) XposedBridge.log(error);
        } catch (Throwable ignored) {
            // Logging failure must never propagate into SystemUI.
        }
    }

    private static final class Binding {
        final Handler main = new Handler(Looper.getMainLooper());
        final Class<?> viewType, bottomType, componentType;
        final Field singleton, mainView, batteryData;
        final Field componentView, componentShowing, ownerComponent, ownerParent, ownerMachine, machineState;
        final Field contentRoot, batteryView, callback;
        final Field[] chargingLabels, chargingIcons;
        final Method active, plugged, usbRestricted, batteryLevel, batteryText, batteryUpdate, show;
        final Method ownerUpdate, attach, contentsUpdate, bottomUpdate, enabledUpdate;
        final Map<View, WeakReference<Object>> owners = new WeakHashMap<>();
        final Map<View, Runnable> pending = new WeakHashMap<>();
        final Map<View, Integer> hiddenIcons = new WeakHashMap<>();
        final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        volatile boolean enabled = true;
        boolean displayed;

        Binding(ClassLoader loader) throws Exception {
            viewType = Class.forName(VIEW_CLASS, false, loader);
            bottomType = Class.forName(
                    "com.samsung.android.uniform.widget.charginginfo.BottomChargingInfoView", false, loader);
            componentType = Class.forName("aod.Mp", false, loader);
            Class<?> componentBase = Class.forName("aod.H4", false, loader);
            Class<?> owner = Class.forName("aod.J", false, loader);
            Class<?> manager = Class.forName("aod.Op", false, loader);
            Class<?> data = Class.forName(
                    "com.samsung.android.uniform.widget.battery.BatteryData", false, loader);
            Class<?> state = Class.forName("aod.a3", false, loader);
            Class<?> machine = Class.forName(
                    "com.samsung.android.app.aodservice.AODStateMachine", false, loader);
            Class<?> update = Class.forName("aod.Lp", false, loader);
            Class<?> battery = Class.forName(
                    "com.samsung.android.uniform.widget.battery.a", false, loader);
            Class<?> callbackType = Class.forName("aod.Wp", false, loader);

            singleton = field(manager, "s");
            mainView = field(manager, "d");
            batteryData = field(manager, "j");
            componentView = field(componentBase, "c");
            componentShowing = field(componentBase, "d");
            ownerComponent = field(owner, "s");
            ownerParent = field(owner, "q");
            ownerMachine = field(owner, "n");
            machineState = field(machine, "b");
            contentRoot = field(viewType, "b");
            batteryView = field(viewType, "c");
            callback = field(viewType, "r");
            chargingLabels = new Field[] {
                field(viewType, "d"), field(viewType, "n"), field(viewType, "o"), field(viewType, "p")
            };
            chargingIcons = new Field[] {field(battery, "o"), field(battery, "r")};
            active = method(state, "b");
            plugged = method(data, "isBatteryPlugged");
            usbRestricted = method(data, "isUsbRestriction");
            batteryLevel = method(data, "getBatteryLevel");
            batteryText = method(battery, "getBatteryTextView");
            batteryUpdate = method(battery, "d", data);
            show = method(callbackType, "onShow");
            ownerUpdate = method(owner, "f", state);
            attach = method(componentType, "onAttachedToWindow");
            contentsUpdate = method(viewType, "j", update);
            bottomUpdate = method(bottomType, "j", update);
            enabledUpdate = method(viewType, "setBatteryInfoEnabled", boolean.class);
            // Resolve the entire donor contract before installing any hook.
            if (!View.class.isAssignableFrom(viewType) || !View.class.isAssignableFrom(battery)
                    || mainView.getType() != viewType || componentView.getType() != viewType
                    || componentShowing.getType() != boolean.class
                    || batteryData.getType() != data || machineState.getType() != state
                    || active.getReturnType() != boolean.class
                    || batteryLevel.getReturnType() != int.class) {
                throw new IllegalStateException("Unexpected donor field or method types");
            }
        }

        void install() throws Throwable {
            try {
                hooks.add(XposedBridge.hookMethod(ownerUpdate, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!enabled) return;
                        final WeakReference<Object> owner = new WeakReference<>(param.thisObject);
                        main.post(() -> safely(() -> trackOwner(owner.get())));
                    }
                }));
                hooks.add(XposedBridge.hookMethod(attach, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        safely(() -> request(componentView.get(param.thisObject)));
                    }
                }));
                XC_MethodHook updated = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        safely(() -> request(param.thisObject));
                    }
                };
                hooks.add(XposedBridge.hookMethod(contentsUpdate, updated));
                hooks.add(XposedBridge.hookMethod(bottomUpdate, updated));
                hooks.add(XposedBridge.hookMethod(enabledUpdate, updated));
            } catch (Throwable failure) {
                enabled = false;
                for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
                throw failure;
            }
        }

        void trackOwner(Object owner) throws Exception {
            if (owner == null) return;
            Object component = ownerComponent.get(owner);
            if (!componentType.isInstance(component)) return;
            Object child = componentView.get(component);
            if (!(child instanceof View)) return;
            View view = (View) child;
            owners.put(view, new WeakReference<>(owner));
            request(view);
        }

        void request(Object child) {
            if (!enabled || !(child instanceof View) || !viewType.isInstance(child)) return;
            final WeakReference<View> reference = new WeakReference<>((View) child);
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(() -> safely(() -> request(reference.get())));
                return;
            }
            View view = reference.get();
            if (view == null || pending.containsKey(view)) return;
            Runnable task = () -> safely(() -> {
                View current = reference.get();
                if (current == null) return;
                pending.remove(current);
                refresh(current);
            });
            pending.put(view, task);
            // Run after the donor's queued hide/show messages; no periodic polling.
            main.post(task);
        }

        void refresh(View view) throws Exception {
            WeakReference<Object> record = owners.get(view);
            Object owner = record == null ? null : record.get();
            if (owner == null) return;
            Object component = ownerComponent.get(owner);
            if (!componentType.isInstance(component) || componentView.get(component) != view) return;
            Object machine = ownerMachine.get(owner);
            if (machine == null) return;
            Object state = machineState.get(machine);
            Object manager = singleton.get(null);
            if (state == null || manager == null) return;
            Object data = batteryData.get(manager);
            if (data == null) return;
            int level = (Integer) batteryLevel.invoke(data);
            Object battery = batteryView.get(view);
            if (!(battery instanceof View)) return;
            if (!BatteryPolicy.shouldShow(mainView.get(manager) == view, view.isAttachedToWindow(),
                    (Boolean) active.invoke(state), (Boolean) plugged.invoke(data),
                    (Boolean) usbRestricted.invoke(data), level)) {
                if (restoreIcons(battery)) batteryUpdate.invoke(battery, data);
                return;
            }
            Object label = batteryText.invoke(battery);
            if (!(label instanceof TextView)) return;
            Object listener = callback.get(view);
            if (listener == null) return;
            // Samsung formats the percentage; this charging view retains a bolt even unplugged.
            batteryUpdate.invoke(battery, data);
            for (Field chargingIcon : chargingIcons) {
                Object icon = chargingIcon.get(battery);
                if (icon instanceof View) {
                    View child = (View) icon;
                    if (!hiddenIcons.containsKey(child)) hiddenIcons.put(child, child.getVisibility());
                    child.setVisibility(View.GONE);
                }
            }
            visible(contentRoot.get(view));
            for (Field chargingLabel : chargingLabels) {
                Object child = chargingLabel.get(view);
                if (child instanceof View) ((View) child).setVisibility(View.GONE);
            }
            visible(view);
            View batteryContainer = (View) battery;
            batteryContainer.animate().cancel();
            visible(batteryContainer);
            visible(label);
            // Notify only on a visibility transition: onShow emits a layout event which
            // can itself refresh the owner. Repeated notifications would form a loop.
            if (!componentShowing.getBoolean(component)) show.invoke(listener);
            visible(ownerParent.get(owner));
            if (!displayed) {
                displayed = true;
                log("Native battery view displayed on unplugged AOD", null);
            }
        }

        boolean restoreIcons(Object battery) throws Exception {
            boolean restored = false;
            for (Field chargingIcon : chargingIcons) {
                Object icon = chargingIcon.get(battery);
                if (!(icon instanceof View)) continue;
                View child = (View) icon;
                Integer visibility = hiddenIcons.remove(child);
                if (visibility != null) {
                    child.setVisibility(visibility);
                    restored = true;
                }
            }
            return restored;
        }

        void visible(Object object) {
            if (object instanceof View) {
                View view = (View) object;
                view.setAlpha(1f);
                view.setVisibility(View.VISIBLE);
            }
        }

        void safely(Action action) {
            if (!enabled) return;
            try {
                action.run();
            } catch (Throwable error) {
                enabled = false;
                log("Battery hook stopped after an unexpected donor error", error);
            }
        }
    }

    private interface Action {
        void run() throws Exception;
    }
}
