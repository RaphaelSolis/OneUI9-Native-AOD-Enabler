package org.raphaelsolis.aodbattery;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Mounts Samsung's existing theme view when stock ClockPack leaves it unattached. */
final class ThemeViewHook {
    private static final String TAG = "A16AodTheme";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> STOCK = Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static final Set<Class<?>> DONOR = Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static final Set<View> CLOCKS = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static WeakReference<Object> ui = new WeakReference<>(null);
    private static Field machine, state, dozing;
    private static Method active, clock, type, category;
    private static WeakReference<View> hidden = new WeakReference<>(null);
    private static WeakReference<View> mounted = new WeakReference<>(null);
    private static int visibility;
    private static boolean queued;
    private static WeakReference<ViewGroup> mountedParent = new WeakReference<>(null);
    private static int nativeVisibility;
    private static float nativeAlpha;

    static synchronized void installStock(ClassLoader loader) {
        try {
            Class<?> view = Class.forName("com.samsung.android.uniform.widget.clock.AbsClockView", false, loader);
            if (STOCK.contains(view)) return;
            Field isDozing = view.getDeclaredField("r0");
            isDozing.setAccessible(true);
            Method getCategory = view.getDeclaredMethod("getCategory");
            Method changed = view.getDeclaredMethod("setDozing", boolean.class);
            Method attached = view.getDeclaredMethod("onAttachedToWindow");
            Method detached = view.getDeclaredMethod("onDetachedFromWindow");
            if (!View.class.isAssignableFrom(view) || isDozing.getType() != boolean.class)
                throw new IllegalStateException("Stock clock contract mismatch");
            XC_MethodHook callback = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View current = (View) param.thisObject;
                    MAIN.post(() -> {
                        CLOCKS.add(current);
                        request();
                    });
                }
            };
            XC_MethodHook.Unhook first = XposedBridge.hookMethod(changed, callback);
            java.util.List<XC_MethodHook.Unhook> installed = new java.util.ArrayList<>();
            installed.add(first);
            try {
                installed.add(XposedBridge.hookMethod(attached, callback));
                installed.add(XposedBridge.hookMethod(detached, callback));
            } catch (Throwable failure) {
                for (XC_MethodHook.Unhook hook : installed) hook.unhook();
                throw failure;
            }
            dozing = isDozing;
            category = getCategory;
            STOCK.add(view);
            log("Stock clock attachment bridge installed");
        } catch (Throwable failure) { log("Stock view bridge inactive: " + failure); }
    }

    static synchronized void installDonor(ClassLoader loader) {
        try {
            Class<?> owner = Class.forName("aod.R3", false, loader);
            if (DONOR.contains(owner)) return;
            Class<?> nativeState = Class.forName("aod.a3", false, loader);
            Class<?> configuration = Class.forName("com.samsung.android.app.aodservice.content.AODConfiguration", false, loader);
            Class<?> nativeMachine = Class.forName("com.samsung.android.app.aodservice.AODStateMachine", false, loader);
            Method changed = owner.getDeclaredMethod("f", nativeState, nativeState, configuration);
            Field getMachine = owner.getDeclaredField("b");
            Field getState = nativeMachine.getDeclaredField("b");
            Method isActive = nativeState.getDeclaredMethod("b");
            Method getClock = owner.getDeclaredMethod("i");
            Class<?> clockView = getClock.getReturnType();
            Method getType = Class.forName("com.samsung.android.uniform.widget.clock.AbsClockView", false, loader)
                    .getDeclaredMethod("getClockType");
            Method getInner = clockView.getDeclaredMethod("getClockView");
            Method contentsChanged = clockView.getDeclaredMethod("b", Class.forName("aod.ft", false, loader));
            getMachine.setAccessible(true);
            getState.setAccessible(true);
            XC_MethodHook.Unhook stateHook = XposedBridge.hookMethod(changed, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    WeakReference<Object> reference = new WeakReference<>(param.thisObject);
                    MAIN.post(() -> { ui = reference; request(); });
                }
            });
            try {
                XposedBridge.hookMethod(contentsChanged, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        MAIN.post(ThemeViewHook::request);
                    }
                });
            } catch (Throwable failure) { stateHook.unhook(); throw failure; }
            machine = getMachine;
            state = getState;
            active = isActive;
            clock = getClock;
            // The container's child carries the native theme ClockType.
            type = getType;
            innerClock = getInner;
            DONOR.add(owner);
            log("Native themed clock attachment bridge installed");
        } catch (Throwable failure) { log("Native view bridge inactive: " + failure); }
    }
    private static Method innerClock;

    private static void request() {
        if (queued) return;
        queued = true;
        MAIN.post(() -> {
            queued = false;
            try { refresh(); }
            catch (Throwable failure) {
                restore();
                log("Theme attachment skipped; restored stock clock: " + failure);
            }
        });
    }

    private static void refresh() throws Exception {
        Object owner = ui.get();
        View nativeView = null;
        boolean enabled = false;
        if (owner != null && machine != null && active != null && clock != null) {
            Object current = state.get(machine.get(owner));
            if (current != null && (Boolean) active.invoke(current)) {
                Object result = clock.invoke(owner);
                if (result instanceof View) {
                    nativeView = (View) result;
                    Object child = innerClock.invoke(result);
                    if (child != null) {
                        int selected = (Integer) type.invoke(child);
                        enabled = selected >= 50004 && selected <= 50007;
                    }
                }
            }
        }
        View old = hidden.get();
        View previous = mounted.get();
        if (!enabled || old != null && (!old.isAttachedToWindow() || !dozing.getBoolean(old)
                || previous != nativeView || previous.getParent() != mountedParent.get())) {
            restore();
            if (!enabled) return;
        }
        if (hidden.get() != null) {
            // Stock refreshes can re-show their clock during AOD. Keep only the selected view visible.
            hidden.get().setVisibility(View.GONE);
            return;
        }
        if (nativeView == null || dozing == null || category == null) return;
        for (View view : CLOCKS) {
            if (!view.isAttachedToWindow() || !dozing.getBoolean(view)) continue;
            Object kind = category.invoke(view);
            if (!(kind instanceof Enum<?>) || !"LOCK_SCREEN".equals(((Enum<?>) kind).name())) continue;
            if (!(view.getParent() instanceof ViewGroup) || view.getVisibility() != View.VISIBLE) continue;
            if (nativeView.getParent() != null) return;
            ViewGroup parent = (ViewGroup) view.getParent();
            ViewGroup.LayoutParams params = view.getLayoutParams();
            int original = view.getVisibility();
            // Keep the stock layout position and Samsung's theme renderer/update callbacks.
            nativeVisibility = nativeView.getVisibility();
            nativeAlpha = nativeView.getAlpha();
            visibility = original;
            hidden = new WeakReference<>(view);
            mounted = new WeakReference<>(nativeView);
            mountedParent = new WeakReference<>(parent);
            parent.addView(nativeView, new ViewGroup.LayoutParams(params.width, params.height));
            nativeView.setVisibility(View.VISIBLE);
            nativeView.setAlpha(1f);
            view.setVisibility(View.GONE);
            log("Mounted native themed AOD clock in stock clock container");
            return;
        }
    }

    private static void restore() {
        View nativeView = mounted.get();
        ViewGroup parent = mountedParent.get();
        if (nativeView != null && parent != null && nativeView.getParent() == parent) {
            parent.removeView(nativeView);
            nativeView.setVisibility(nativeVisibility);
            nativeView.setAlpha(nativeAlpha);
        }
        View original = hidden.get();
        if (original != null) original.setVisibility(visibility);
        hidden.clear();
        mounted.clear();
        mountedParent.clear();
        if (original != null) log("Restored stock clock after themed AOD");
    }
    private static void log(String text) {
        try { Log.i(TAG, text); XposedBridge.log(TAG + ": " + text); }
        catch (Throwable ignored) {}
    }
}
