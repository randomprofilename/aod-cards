package com.aodcards

import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.widget.RemoteViews

/** Explicit diagnostic mode; observes the real host rather than a simulated layout. */
internal object SamsungHostProbe {
    private const val PROBE_INFO = "com.aodcards.PROBE_INFO"

    fun run(instrumentation: Instrumentation, context: Context, kind: String): String {
        require(kind in setOf("layout", "notification", "republish", "button", "clip", "bounds", "compound")) {
            "Unknown host probe kind: $kind"
        }
        val provider = ComponentName(context, AodCardsWidgetProvider::class.java)
        if (kind == "layout") {
            val info = context.packageManager.getReceiverInfo(provider,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            // Validate before refreshing widgets or changing the screen state.
            // Only diagnostic builds with the provider overlay support this probe.
            if ((info.metaData?.getInt(PROBE_INFO, 0) ?: 0) == 0) {
                return "SKIP: layout host probe requires the diagnostic manifest overlay " +
                    "with $PROBE_INFO provider metadata; it is unavailable in a normal build.\n"
            }
        }
        val report = StringBuilder()
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
            failure?.let { throw it }
        }
        fun bounds(stage: String) {
            val pipe = instrumentation.uiAutomation.executeShellCommand(
                "dumpsys activity service com.android.systemui/.SystemUIService")
            val dump = ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
            report.appendLine(stage)
            dump.lineSequence().filter {
                "app:id/widget_root}" in it || "app:id/widget_surface}" in it
            }.take(2).forEach { report.appendLine(it.trim()) }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", stage + "\n" + dump.lineSequence().filter { "app:id/widget_root}" in it || "app:id/widget_surface}" in it }.take(2).joinToString("\n") { it.trim() } + "\n") })
        }
        if (kind == "notification") {
            val notifications = context.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "aod_host_probe"
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.POST_NOTIFICATIONS")
            try {
                bounds("before quiet notification")
                notifications.createNotificationChannel(android.app.NotificationChannel(channelId,
                    "Temporary AOD diagnostic", android.app.NotificationManager.IMPORTANCE_MIN))
                notifications.notify("host-probe", 1, android.app.Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("AOD layout diagnostic").setContentText("Temporary local test")
                    .setTimeoutAfter(15000).build())
                Thread.sleep(1600)
                bounds("after quiet notification")
            } finally {
                notifications.cancel("host-probe", 1)
                notifications.deleteNotificationChannel(channelId)
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
            Thread.sleep(600)
            bounds("after notification cleanup")
            return report.toString()
        }
        main { NotificationListener.forceRefresh(context) }
        repeat(30) {
            if (CardState.value.connection != ConnectionState.CONNECTED) Thread.sleep(100)
        }
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(provider)
        check(ids.isNotEmpty()) { "Add a test widget before running this probe" }
        Thread.sleep(1200) // Let the requested snapshot and its throttled render finish.
        bounds("before")
        if (kind == "republish") {
            main { AodCardsWidgetProvider().onUpdate(context, manager, ids) }
            Thread.sleep(1200)
            bounds("after forced republish with the normal renderer")
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.cacheDir, "aod-republish.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            return report.toString()
        }
        if (kind == "button") {
            val layout = context.resources.getIdentifier("probe_checkbox_surface", "layout", context.packageName)
            val animator = context.resources.getIdentifier("probe_viewport", "animator", context.packageName)
            check(layout != 0 && animator != 0)
            val animate = RemoteViews::class.java.getMethod("semSetViewObjectAnimator",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            main {
                val settings = SettingsRepository(context)
                val privacy = NotificationPrivacy.read(context)
                val hidden = NotificationListener.isScreenInteractive(context) && !settings.showWhenScreenOn
                val cards = if (hidden) emptyList() else privacy.cards(NotificationListener.snapshot(), settings)
                val music = if (hidden) null else privacy.music(CardState.value.music, settings)
                ids.forEach { id ->
                    val (width, height) = WidgetRenderer.sizeFor(manager.getAppWidgetOptions(id))
                    val plan = WidgetRenderModel.build(cards, settings, music, width, height, context)
                    val views = RemoteViews(context.packageName, layout)
                    if (settings.tapToUnlockEnabled) views.setOnClickPendingIntent(R.id.widget_root, UnlockActivity.pendingIntent(context))
                    if (hidden || plan.cards.isEmpty() && plan.music == null && plan.overflow == 0) {
                        views.setInt(R.id.widget_root, "setButtonDrawable", 0)
                    } else {
                        val content = WidgetRenderer.createViews(context, plan.cards, settings, plan.music,
                            WidgetRows.scaleFactor(settings.widgetScale), width, height, plan = plan)
                        val draw = WidgetRenderer::class.java.getDeclaredMethod("drawSurface", Context::class.java,
                            RemoteViews::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                        val frame = draw.invoke(WidgetRenderer, context, content, width, height) as android.graphics.Bitmap
                        val density = context.resources.displayMetrics.density
                        val sized = android.graphics.Bitmap.createScaledBitmap(frame,
                            (width * density + .5f).toInt(), (height * density + .5f).toInt(), true)
                        sized.density = android.graphics.Bitmap.DENSITY_NONE
                        views.setIcon(R.id.widget_root, "setButtonIcon", android.graphics.drawable.Icon.createWithBitmap(sized))
                    }
                    animate.invoke(views, R.id.widget_root, animator)
                    manager.updateAppWidget(id, views)
                }
            }
            Thread.sleep(1200)
            bounds("after intrinsic button drawable with direct viewport bounds")
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.cacheDir, "aod-button.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            return report.toString()
        }
        if (kind == "clip") {
            val animate = RemoteViews::class.java.getMethod("semSetViewObjectAnimator",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val animator = context.resources.getIdentifier("probe_bounds", "animator", context.packageName)
            check(animator != 0)
            try {
                main {
                    ids.forEach { id ->
                        val settings = SettingsRepository(context)
                        val views = RemoteViews(context.packageName, if (settings.tapToUnlockEnabled)
                            R.layout.widget_cards_dynamic_tap_unlock else R.layout.widget_cards_dynamic_passive)
                        views.setFloat(R.id.widget_root, "setAlpha", 1f)
                        views.setInt(R.id.widget_root, "setBackgroundColor", 0xff303030.toInt())
                        animate.invoke(views, R.id.widget_root, animator)
                        manager.partiallyUpdateAppWidget(id, views)
                    }
                }
                Thread.sleep(900)
                bounds("after root background and direct bounds")
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.cacheDir, "aod-clip.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            } finally {
                main {
                    ids.forEach { id ->
                        val settings = SettingsRepository(context)
                        val views = RemoteViews(context.packageName, if (settings.tapToUnlockEnabled)
                            R.layout.widget_cards_dynamic_tap_unlock else R.layout.widget_cards_dynamic_passive)
                        views.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                        manager.partiallyUpdateAppWidget(id, views)
                    }
                }
            }
            return report.toString()
        }
        if (kind == "bounds" || kind == "compound") {
            val animator = context.resources.getIdentifier("probe_bounds", "animator", context.packageName)
            check(animator != 0) { "Build with the diagnostic animator resource overlay" }
            val animate = RemoteViews::class.java.getMethod("semSetViewObjectAnimator",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val compound = if (kind == "compound") RemoteViews::class.java.getMethod(
                "setTextViewCompoundDrawables", Int::class.javaPrimitiveType,
                android.graphics.drawable.Icon::class.java, android.graphics.drawable.Icon::class.java,
                android.graphics.drawable.Icon::class.java, android.graphics.drawable.Icon::class.java) else null
            main {
                ids.forEach { id ->
                    val settings = SettingsRepository(context)
                    val views = RemoteViews(context.packageName, if (kind == "compound") {
                        if (settings.tapToUnlockEnabled) R.layout.widget_cards_dynamic_tap_unlock else R.layout.widget_cards_dynamic_passive
                    } else if (settings.tapToUnlockEnabled) R.layout.widget_cards_surface_tap_unlock else R.layout.widget_cards_surface)
                    if (settings.tapToUnlockEnabled) views.setOnClickPendingIntent(R.id.widget_root,
                        UnlockActivity.pendingIntent(context))
                    val privacy = NotificationPrivacy.read(context)
                    val hidden = NotificationListener.isScreenInteractive(context) && !settings.showWhenScreenOn
                    val cards = if (hidden) emptyList() else privacy.cards(NotificationListener.snapshot(), settings)
                    val music = if (hidden) null else privacy.music(CardState.value.music, settings)
                    val (width, height) = WidgetRenderer.sizeFor(manager.getAppWidgetOptions(id))
                    val plan = WidgetRenderModel.build(cards, settings, music, width, height, context)
                    if (hidden || plan.cards.isEmpty() && plan.music == null && plan.overflow == 0) {
                        if (compound != null) compound.invoke(views, R.id.widget_overflow_center, null, null, null, null)
                        else views.setImageViewResource(R.id.widget_surface, R.drawable.widget_surface_blank)
                    } else {
                        val content = WidgetRenderer.createViews(context, plan.cards, settings, plan.music,
                            WidgetRows.scaleFactor(settings.widgetScale), width, height, plan = plan)
                        val draw = WidgetRenderer::class.java.getDeclaredMethod("drawSurface",
                            Context::class.java, RemoteViews::class.java, Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType).apply { isAccessible = true }
                        val frame = draw.invoke(WidgetRenderer, context, content, width, height) as android.graphics.Bitmap
                        if (compound != null) compound.invoke(views, R.id.widget_overflow_center,
                            android.graphics.drawable.Icon.createWithBitmap(frame), null, null, null)
                        else views.setImageViewBitmap(R.id.widget_surface, frame)
                    }
                    animate.invoke(views, R.id.widget_root, animator)
                    if (compound == null) animate.invoke(views, R.id.widget_surface, animator)
                    else {
                        views.setFloat(R.id.widget_root, "setAlpha", if (hidden) 0f else 1f)
                        views.setViewPadding(R.id.widget_root, 0, 0, 0, 0)
                        views.setViewVisibility(R.id.widget_content, android.view.View.GONE)
                        views.setViewVisibility(R.id.widget_overflow_row, android.view.View.VISIBLE)
                        views.setViewVisibility(R.id.widget_overflow_center, android.view.View.VISIBLE)
                        views.setViewVisibility(R.id.widget_overflow_left, android.view.View.GONE)
                        views.setViewVisibility(R.id.widget_overflow_right, android.view.View.GONE)
                        views.setViewPadding(R.id.widget_overflow_center, 0, 0, 0, 0)
                        views.setInt(R.id.widget_overflow_center, "setBackgroundColor", 0)
                        views.setInt(R.id.widget_overflow_center, "setGravity", android.view.Gravity.TOP or android.view.Gravity.LEFT)
                        views.setTextViewText(R.id.widget_overflow_center, "")
                        animate.invoke(views, R.id.widget_overflow_row, animator)
                        animate.invoke(views, R.id.widget_overflow_center, animator)
                    }
                    manager.updateAppWidget(id, views)
                }
            }
            Thread.sleep(1200)
            bounds("after Samsung bounds animator: " + kind)
            if (kind == "compound") {
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.cacheDir, "aod-compound.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            return report.toString()
        }
        val cache = AodCardsWidgetProvider::class.java.getDeclaredField("rendered").apply { isAccessible = true }
        fun command(command: String) {
            val pipe = instrumentation.uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
        }
        try {
            command("input keyevent KEYCODE_WAKEUP")
            Thread.sleep(1200)
            bounds("awake before reset")
            main {
                manager.updateAppWidgetProviderInfo(provider, PROBE_INFO)
                (cache.get(null) as android.util.LruCache<*, *>).evictAll()
                AodCardsWidgetProvider.renderAll(context)
            }
            Thread.sleep(1200)
            bounds("awake after reset")
            ids.forEach { id ->
                main { manager.partiallyUpdateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_cards_surface).apply {
                    setViewVisibility(R.id.widget_root, android.view.View.GONE)
                }) }
            }
            Thread.sleep(700)
            ids.forEach { id ->
                main { manager.partiallyUpdateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_cards_surface).apply {
                    setViewVisibility(R.id.widget_root, android.view.View.VISIBLE)
                }) }
            }
            Thread.sleep(1200)
            bounds("awake after staged visibility")
        } finally { command("input keyevent KEYCODE_SLEEP") }
        Thread.sleep(1500)
        bounds("AOD after staged visibility")
        try {
            main { manager.updateAppWidgetProviderInfo(provider, PROBE_INFO) }
            Thread.sleep(1200)
            bounds("after provider metadata reset")
            main {
                (cache.get(null) as android.util.LruCache<*, *>).evictAll()
                AodCardsWidgetProvider.renderAll(context)
            }
            Thread.sleep(1200)
            bounds("after reset and content update")
        } finally {
            main { manager.updateAppWidgetProviderInfo(provider, "android.appwidget.provider") }
        }
        Thread.sleep(800)
        bounds("after restoring provider metadata")
        return report.toString()
    }
}
