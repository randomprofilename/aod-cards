package com.aodcards.xposed;

import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewGroup;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Optional entry point, loaded by LSPosed, never by the normal app process. */
public final class AodTouchModule extends XposedModule {
    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName()) || !param.isFirstPackage()) return;
        try {
            // LockStar's NewPluginLockStarImpl.isTouchable() dispatches into its
            // extra-content tree. Its clickable contents_container consumes DOWN
            // even if our RemoteViews has no click action. Declining dispatch at
            // that wrapper lets SystemUI select its normal swipe-unlock handler.
            // The wrapper inherits this method, so hook the framework declaration
            // and apply a strict view/provider check before changing its result.
            hook(ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (LockStarTouchPassthrough.shouldSkipDispatch((ViewGroup) chain.getThisObject())) {
                            return false;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, "AodCardsTouch", "LockStar widget touch passthrough installed");
        } catch (ReflectiveOperationException exception) {
            log(Log.ERROR, "AodCardsTouch", "Could not install touch passthrough", exception);
        }
    }
}
