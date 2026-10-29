package com.aodcards

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class AodCardsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, ids, force = true)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: android.os.Bundle) = render(context, intArrayOf(widgetId), force = true)
    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { rendered.remove(it) }
        super.onDeleted(context, ids)
    }
    override fun onDisabled(context: Context) {
        rendered.evictAll()
        WidgetLayout.clearCache()
        super.onDisabled(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // LockStar keeps widget instances across app updates but may not request new views.
            // Requery active notifications so every display receives a fresh RemoteViews tree.
            NotificationListener.forceRefresh(context)
        }
    }

    companion object {
        private data class Rendered(val key: WidgetUpdateKey, val hidden: Boolean, val hasOverflow: Boolean)
        private val rendered = android.util.LruCache<Int, Rendered>(32)
        fun renderAll(context: Context) = render(context, null)

        private fun render(context: Context, requestedIds: IntArray?, force: Boolean = false) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = requestedIds ?: manager.getAppWidgetIds(ComponentName(context, AodCardsWidgetProvider::class.java))
                if (ids.isEmpty()) {
                    rendered.evictAll()
                    return
                }
                val settings = SettingsRepository(context)
                val screenInteractive = NotificationListener.isScreenInteractive(context)
                val hasAccess = CardState.hasAccess(context)
                val privacy = NotificationPrivacy.read(context)
                val snapshot = if (hasAccess) privacy.cards(NotificationListener.snapshot(), settings) else emptyList()
                val scale = WidgetRows.scaleFactor(settings.widgetScale)
                ids.forEach { widgetId ->
                    try {
                        val options = manager.getAppWidgetOptions(widgetId)
                        val hidden = screenInteractive && !settings.showWhenScreenOn
                        val cards = if (hidden) emptyList() else snapshot
                        val music = if (!hidden && settings.musicPillEnabled && hasAccess) privacy.music(CardState.value.music, settings) else null
                        val size = WidgetRenderer.sizeFor(options)
                        val width = size.first
                        val height = size.second
                        val key = WidgetUpdateKey.create(context, cards, music, settings, width, height, hasAccess)
                        val previous = rendered.get(widgetId)
                        // A full replacement clears secrets from the host on visibility changes.
                        if (!force && previous?.key == key && hidden == previous.hidden) return@forEach
                        val plan = WidgetRenderModel.build(cards, settings, music, width, height, context)
                        val views = WidgetRenderer.createViews(context, cards, settings, music, scale, width, height,
                            screenInteractive, plan, enableUnlockTap = true)
                        // LockStar can measure its remote child with a zero-sized
                        // parent spec while restoring an AOD container. Explicit
                        // bounds prevent MATCH_PARENT/WRAP_CONTENT collapsing to 0.
                        views.setViewLayoutWidth(R.id.widget_root, width.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
                        if (height > 0) views.setViewLayoutHeight(R.id.widget_root, height.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
                        manager.updateAppWidget(widgetId, views)
                        rendered.put(widgetId, Rendered(key, hidden, plan.overflow > 0))
                    } catch (e: Exception) {
                        Log.w("AODCards", "Could not render widget instance $widgetId", e)
                    }
                }
            } catch (e: Exception) {
                Log.w("AODCards", "Could not update notification widgets", e)
            }
        }

    }
}
