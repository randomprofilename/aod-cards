package com.aodcards.xposed;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/** View-tree policy kept separate from the optional Xposed runtime. */
public final class LockStarTouchPassthrough {
    private static final ComponentName PROVIDER = new ComponentName(
            "com.aodcards", "com.aodcards.AodCardsWidgetProvider");
    private static final String OVERLAY =
            "com.samsung.systemui.lockstar.presentation.view.core.widget.OverlayDimContainer";

    private LockStarTouchPassthrough() {}

    public static boolean shouldSkipDispatch(ViewGroup view) {
        // Only LockStar's per-widget wrapper: never the whole LockStar tree,
        // other widgets, or an AOD Cards host on another surface.
        if (view.getChildCount() != 1) return false;
        View child = view.getChildAt(0);
        if (!(child instanceof AppWidgetHostView)) return false;
        AppWidgetProviderInfo info = ((AppWidgetHostView) child).getAppWidgetInfo();
        if (info == null || !PROVIDER.equals(info.provider)) return false;
        ViewParent parent = view.getParent();
        return parent != null && OVERLAY.equals(parent.getClass().getName());
    }
}
