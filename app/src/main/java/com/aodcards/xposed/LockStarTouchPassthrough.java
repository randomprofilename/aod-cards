package com.aodcards.xposed;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/** Per-manager gesture state; no dependency on the optional Xposed runtime. */
public final class LockStarTouchPassthrough {
    private static final ComponentName PROVIDER = new ComponentName(
            "com.aodcards", "com.aodcards.AodCardsWidgetProvider");
    private static final String OVERLAY =
            "com.samsung.systemui.lockstar.presentation.view.core.widget.OverlayDimContainer";
    private final Matrix global = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] point = new float[2];
    private long downTime = Long.MIN_VALUE;
    private int deviceId;
    private int source;
    private boolean bypass;

    public boolean shouldBypass(View root, MotionEvent event) {
        boolean sameGesture = downTime == event.getDownTime()
                && deviceId == event.getDeviceId() && source == event.getSource();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !sameGesture) {
            downTime = event.getDownTime();
            deviceId = event.getDeviceId();
            source = event.getSource();
            bypass = false;
            // SystemUI may ask more than once for the same DOWN. Classify once,
            // using live geometry so folding/reinflation cannot leave stale bounds.
            ViewGroup extras = findExtraRoot(root);
            if (extras != null) {
                View item = topItemAt(extras, event.getRawX(), event.getRawY());
                bypass = item != null && targetAt(item, root, event.getRawX(), event.getRawY());
            }
            return bypass;
        }
        // Keep the decision after UP/CANCEL as well: callers can query those
        // events repeatedly. Only a new DOWN can initiate a bypassed gesture.
        return sameGesture && bypass;
    }

    private ViewGroup findExtraRoot(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        if (isTargetWrapper(group)) {
            View overlay = (View) group.getParent();
            ViewParent itemParent = overlay.getParent();
            if (itemParent instanceof ViewGroup) {
                ViewGroup item = (ViewGroup) itemParent;
                // LockStar 9: extra-content root -> item root -> overlay -> wrapper -> host.
                if (item.getChildCount() == 1 && item.getChildAt(0) == overlay
                        && item.getParent() instanceof ViewGroup) {
                    return (ViewGroup) item.getParent();
                }
            }
        }
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ViewGroup found = findExtraRoot(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private View topItemAt(ViewGroup extras, float x, float y) {
        View top = null;
        // Android dispatches higher Z first; later child indices win equal Z.
        // Conservatively preserve another item's interaction if it overlaps ours,
        // even if that item's custom touch implementation would decline the event.
        for (int i = extras.getChildCount() - 1; i >= 0; i--) {
            View item = extras.getChildAt(i);
            if (contains(item, x, y) && (top == null || item.getZ() > top.getZ())) top = item;
        }
        return top;
    }

    private boolean targetAt(View view, View root, float x, float y) {
        if (!contains(view, x, y) || !(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        if (isTargetWrapper(group)) {
            // Respect visibility and bounds all the way to the SystemUI root.
            View ancestor = group;
            while (ancestor != root) {
                if (!contains(ancestor, x, y) || !(ancestor.getParent() instanceof View)) return false;
                ancestor = (View) ancestor.getParent();
            }
            return contains(root, x, y);
        }
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            if (targetAt(group.getChildAt(i), root, x, y)) return true;
        }
        return false;
    }

    private boolean contains(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        global.reset();
        view.transformMatrixToGlobal(global);
        if (!global.invert(inverse)) return false;
        point[0] = x;
        point[1] = y;
        inverse.mapPoints(point);
        return point[0] >= 0 && point[1] >= 0
                && point[0] < view.getWidth() && point[1] < view.getHeight();
    }

    private static boolean isTargetWrapper(ViewGroup view) {
        if (view.getChildCount() != 1) return false;
        View child = view.getChildAt(0);
        if (!(child instanceof AppWidgetHostView)) return false;
        AppWidgetProviderInfo info = ((AppWidgetHostView) child).getAppWidgetInfo();
        if (info == null || !PROVIDER.equals(info.provider)) return false;
        ViewParent parent = view.getParent();
        return parent != null && OVERLAY.equals(parent.getClass().getName());
    }
}
