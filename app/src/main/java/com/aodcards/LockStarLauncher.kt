package com.aodcards

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews

/** Uses the same LockStar entry point as the app's Open LockStar button. */
internal object LockStarLauncher {
    private val setLongClick by lazy {
        runCatching {
            RemoteViews::class.java.getMethod("semSetOnLongClickPendingIntent",
                Int::class.javaPrimitiveType, PendingIntent::class.java)
        }.getOrNull()
    }

    fun settingsIntent(): Intent = Intent("com.samsung.systemui.lockstar.action.SETTINGS")
        .setPackage("com.samsung.systemui.lockstar")

    internal fun intent(context: Context): Intent? = accessibleIntent(context, settingsIntent())
        ?: context.packageManager.getLaunchIntentForPackage("com.samsung.android.goodlock")
            ?.let { accessibleIntent(context, it) }

    private fun accessibleIntent(context: Context, intent: Intent): Intent? {
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo ?: return null
        if (!info.exported || !info.enabled || !info.applicationInfo.enabled) return null
        if (info.permission != null && context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED)
            return null
        return intent.setComponent(ComponentName(info.packageName, info.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun bind(context: Context, views: RemoteViews): Boolean {
        val method = setLongClick ?: return false
        val intent = intent(context) ?: return false
        val options = ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        val action = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
        return runCatching {
            method.invoke(views, R.id.widget_root, action)
            true
        }.getOrDefault(false)
    }
}
