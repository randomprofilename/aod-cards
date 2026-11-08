package com.aodcards.xposed;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Optional entry point, loaded by LSPosed, never by the normal app process. */
public final class AodTouchModule extends XposedModule {
    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName()) || !param.isFirstPackage()) return;
        try {
            Class<?> manager = param.getClassLoader().loadClass(
                    "com.android.systemui.lockstar.PluginLockStarManager");
            Field root = manager.getDeclaredField("mRootView");
            root.setAccessible(true);
            WeakHashMap<Object, LockStarTouchPassthrough> gestures = new WeakHashMap<>();
            // This check dispatches into LockStar. Bypass BEFORE the original
            // method runs, so our widget never receives the initial DOWN.
            // Touch classification runs on the UI thread; state is per manager.
            hook(manager.getDeclaredMethod("isTouchable", MotionEvent.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object instance = chain.getThisObject();
                        LockStarTouchPassthrough gesture = gestures.get(instance);
                        if (gesture == null) {
                            gesture = new LockStarTouchPassthrough();
                            gestures.put(instance, gesture);
                        }
                        if (gesture.shouldBypass((View) root.get(instance),
                                (MotionEvent) chain.getArgs().get(0))) return false;
                        return chain.proceed();
                    });
            log(Log.INFO, "AodCardsTouch", "LockStar isTouchable passthrough installed");
        } catch (ReflectiveOperationException exception) {
            log(Log.ERROR, "AodCardsTouch", "Could not install touch passthrough", exception);
        }
    }
}
