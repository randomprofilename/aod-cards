package com.aodcards

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/** Dependency-free Android regression suite; never changes the user's preferences. */
class RegressionInstrumentation : Instrumentation() {
    private lateinit var isolated: Context
    private lateinit var settings: SettingsRepository
    private var pickerOnly = false
    private var privacyOnly = false
    private var wakeTestWindow = false

    override fun onCreate(arguments: Bundle?) {
        privacyOnly = arguments?.getString("privacy_only") == "true"
        pickerOnly = arguments?.getString("picker_only") == "true"
        wakeTestWindow = arguments?.getString("wake_test_window") == "true"
        super.onCreate(arguments)
        start()
    }
    override fun callActivityOnCreate(activity: Activity, state: Bundle?) {
        if (wakeTestWindow) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        super.callActivityOnCreate(activity, state)
    }
    override fun newActivity(loader: ClassLoader, className: String, intent: Intent): Activity {
        val activity = super.newActivity(loader, className, intent)
        if (intent.getBooleanExtra("regression_light_theme", false)) {
            activity.applyOverrideConfiguration(android.content.res.Configuration().apply {
                uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO
            })
        }
        return activity
    }
    override fun onStart() {
        val result = Bundle()
        try {
            isolated = object : ContextWrapper(targetContext) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("regression_$name", mode)
            }
            isolated.getSharedPreferences("settings", 0).edit().clear().commit()
            settings = SettingsRepository(isolated)
            if (privacyOnly) {
                onMain { checkCoreRegression() }
                result.putString("stream", "PASS: all core regressions, privacy policy matrix, filtering before grouping, hidden RemoteViews cleanup, cache revision and size guards, rendering")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (pickerOnly) {
                onMain { checkLocalization(); checkPickerPreview(); checkScreenVisibilitySetting(); checkDemoAvatar(); checkGrouping(); checkGroupedCardLayout(); checkConversationTail() }
                result.putString("stream", "PASS: picker preview, localization, screen visibility, demo avatar, grouping, and conversation tail")
                finish(Activity.RESULT_OK, result)
                return
            }
            var failure: Throwable? = null
            onMain {
                try { checkCoreRegression() } catch (e: Throwable) { failure = e }
            }
            failure?.let { throw it }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            onMain { checkPreviewUpdates(activity) }
            // Package discovery is asynchronous and must not block Activity construction.
            var appsLoaded = false
            for (attempt in 0 until 100) {
                onMain {
                    val filter = MainActivity::class.java.getDeclaredField("filterView").apply { isAccessible = true }.get(activity)
                    val apps = AppFilterView::class.java.getDeclaredField("apps").apply { isAccessible = true }.get(filter) as List<*>
                    appsLoaded = apps.isNotEmpty()
                }
                if (appsLoaded) break
                Thread.sleep(50)
            }
            check(appsLoaded) { "Application filter did not finish loading" }
            checkNavigationBackdrop(activity)
            onMain {
                val root = activity.window.decorView
                if (root.width > 0 && root.height > 0) {
                    fun layout() {
                        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    }
                    fun capture(name: String) {
                        layout()
                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(targetContext.cacheDir, "audit-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    @Suppress("UNCHECKED_CAST")
                    val pages = MainActivity::class.java.getDeclaredField("tabPages").apply { isAccessible = true }.get(activity) as List<View>
                    pages.forEachIndexed { index, view -> view.visibility = if (index == 0) View.VISIBLE else View.GONE }
                    MainActivity::class.java.getDeclaredField("previewMode").apply { isAccessible = true }.set(activity, 0)
                    MainActivity::class.java.getDeclaredMethod("updatePreviewModeButtons").apply { isAccessible = true }.invoke(activity)
                    layout()
                    MainActivity::class.java.getDeclaredMethod("renderPreview").apply { isAccessible = true }.invoke(activity)
                    layout()
                    val host = MainActivity::class.java.getDeclaredField("previewHost").apply { isAccessible = true }.get(activity) as FrameLayout
                    check(host.childCount == 1)
                    check(host.getChildAt(0).visibility == View.VISIBLE)
                    capture("widget")
                    val settingsScroll = settingsScroll(pages[0])
                    val previewPosition = IntArray(2).also { host.getLocationOnScreen(it) }
                    settingsScroll.scrollTo(0, settingsScroll.height)
                    if (pages[0] !is android.widget.ScrollView) {
                        check((pages[0] as android.widget.LinearLayout).childCount == 2)
                        val panel = (pages[0] as android.widget.LinearLayout).getChildAt(0) as android.view.ViewGroup
                        check(panel !is android.widget.ScrollView)
                        fun checkPanelBounds(view: View) {
                            if (view.visibility != View.VISIBLE) return
                            val bounds = android.graphics.Rect()
                            view.getDrawingRect(bounds)
                            panel.offsetDescendantRectToMyCoords(view, bounds)
                            check(bounds.top >= 0 && bounds.bottom <= panel.height) { "Left panel content is clipped: $bounds / ${panel.height}" }
                            if (view is android.view.ViewGroup && view !== host) for (i in 0 until view.childCount) checkPanelBounds(view.getChildAt(i))
                        }
                        for (i in 0 until panel.childCount) checkPanelBounds(panel.getChildAt(i))
                        val previewContent = host.getChildAt(0)
                        check(previewContent.top + previewContent.height * previewContent.scaleY <= host.height - host.paddingBottom + 1)
                        check(previewPosition.contentEquals(IntArray(2).also { host.getLocationOnScreen(it) })) {
                            "Scrolling settings moves the preview in the two-column layout"
                        }
                    }
                    capture("settings")
                    fun firstSwitch(view: View): OneUiSwitch? {
                        if (view is OneUiSwitch) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                            firstSwitch(view.getChildAt(i))?.let { return it }
                        }
                        return null
                    }
                    firstSwitch(pages[0])?.let { toggle ->
                        val bounds = android.graphics.Rect()
                        toggle.getDrawingRect(bounds)
                        settingsScroll.offsetDescendantRectToMyCoords(toggle, bounds)
                        settingsScroll.scrollTo(0,
                            (bounds.top - 120 * targetContext.resources.displayMetrics.density).toInt().coerceAtLeast(0))
                        capture("switches")
                    }
                    for (index in 1..2) {
                        pages.forEachIndexed { i, view -> view.visibility = if (i == index) View.VISIBLE else View.GONE }
                        capture(if (index == 1) "access" else "filter")
                    }
                }
                activity.finish()
            }
            waitForIdleSync()
            captureLightTheme()
            result.putString("stream", "PASS: refresh queue, measurement cache, update keys, bitmap cleanup, hidden content cleanup, deferred preview, system grouping, ongoing settings, RemoteViews 12→1→0, music removal, screen visibility, privacy, overflow, large font, navigation backdrop updates, Activity launch")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", e.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync {
            try { block() } catch (e: Throwable) { failure = e }
        }
        failure?.let { throw it }
    }

    private fun checkCoreRegression() {
        val failures = mutableListOf<String>()
        val checks: List<Pair<String, () -> Unit>> = listOf(
            "privacy" to ::checkPrivacy,
            "widget unlock interaction" to ::checkWidgetUnlockInteraction,
            "refresh queue" to ::checkRefreshQueue,
            "optimized rendering" to ::checkOptimizedRendering,
            "localization" to ::checkLocalization,
            "picker" to ::checkPickerPreview,
            "screen visibility" to ::checkScreenVisibilitySetting,
            "demo avatar" to ::checkDemoAvatar,
            "grouping" to ::checkGrouping,
            "grouped layout" to ::checkGroupedCardLayout,
            "conversation tail" to ::checkConversationTail,
            "rendering" to ::checkRendering,
            "avatars" to ::checkAvatars,
            "packing" to ::checkPacking,
            "header width" to ::checkHeaderWidth,
            "preview stage" to ::checkPreviewStage,
            "scale transitions" to ::checkScaleTransitions
        )
        checks.forEach { (name, run) ->
            try { run() } catch (e: Throwable) { failures += "$name: ${e.stackTraceToString()}" }
        }
        check(failures.isEmpty()) { failures.joinToString("\n\n") }
    }

    private fun checkWidgetUnlockInteraction() {
        val host = FrameLayout(isolated)
        fun views(live: Boolean) = WidgetRenderer.createViews(isolated, listOf(card("tap")),
            settings, null, 1f, 398, 289, enableUnlockTap = live)
        val previous = settings.tapToUnlockEnabled
        try {
            check(settings.tapToUnlockEnabled) { "Unlock on tap must be on by default" }
            val widgetHost = android.appwidget.AppWidgetHostView(isolated)
            val enabledViews = views(true)
            widgetHost.updateAppWidget(enabledViews)
            val enabledRoot = widgetHost.getChildAt(0)
            check(enabledRoot.hasOnClickListeners() && enabledRoot.isClickable) { "Enabled live widget must handle taps, including gaps" }
            val enabledKey = WidgetUpdateKey.create(isolated, emptyList(), null, settings, 398, 289, true)
            settings.tapToUnlockEnabled = false
            check(!SettingsRepository(isolated).tapToUnlockEnabled) { "Unlock setting was not persisted" }
            val disabledKey = WidgetUpdateKey.create(isolated, emptyList(), null, settings, 398, 289, true)
            check(enabledKey != disabledKey) { "Toggling unlock must invalidate the widget update cache" }
            val disabledViews = views(true)
            check(disabledViews.layoutId != enabledViews.layoutId) { "Tap behavior must replace the host tree" }
            widgetHost.updateAppWidget(disabledViews)
            val disabledRoot = widgetHost.getChildAt(0)
            check(disabledRoot !== enabledRoot && !disabledRoot.hasOnClickListeners() && !disabledRoot.isClickable) {
                "Disabling unlock must clear the existing host action"
            }
            settings.tapToUnlockEnabled = true
            val preview = views(false).apply(isolated, host)
            check(!preview.hasOnClickListeners() && !preview.isClickable) { "Preview must not launch unlock even when enabled" }
        } finally {
            settings.tapToUnlockEnabled = previous
        }

        val action = UnlockActivity.pendingIntent(isolated)
        check(action.isActivity && action.isImmutable && action.creatorPackage == targetContext.packageName)
        val info = targetContext.packageManager.getActivityInfo(
            android.content.ComponentName(targetContext, UnlockActivity::class.java),
            android.content.pm.PackageManager.ComponentInfoFlags.of(0))
        check(!info.exported) { "Other apps must not launch the unlock activity directly" }
        check(info.taskAffinity.isNullOrEmpty()) { "Unlock must not share the settings activity task" }
        check(info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK) {
            "Repeated taps must reuse the pending unlock instead of creating competing requests"
        }
        check(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    private fun checkPrivacy() {
        val previous = WidgetStyle.read(settings)
        try {
            val public = android.app.Notification.VISIBILITY_PUBLIC
            val private = android.app.Notification.VISIBILITY_PRIVATE
            val secret = android.app.Notification.VISIBILITY_SECRET
            val noOverride = android.service.notification.NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE
            val unlocked = NotificationPrivacy(false, false, false)
            val locked = NotificationPrivacy(true, true, false)
            val allowed = locked.copy(showPrivateContent = true)
            check(unlocked.mode(secret, secret) == 0)
            check(locked.mode(public, noOverride) == 0)
            check(locked.mode(private, noOverride) == 2)
            check(allowed.mode(private, noOverride) == 0)
            check(allowed.mode(public, private) == 2)
            check(allowed.mode(public, secret) == null)
            check(allowed.mode(secret, public) == null)
            check(locked.copy(showNotifications = false).mode(public, public) == null)
            check(locked.mode(12345, noOverride) == 2)
            settings.contentMode = 0
            settings.ongoingMode = 2
            settings.blockedPackages = emptySet()
            settings.musicPillEnabled = true
            val picture = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val sensitive = card("secret").copy(text = "SYNTHETIC-SECRET", senderImage = picture,
                previewImage = picture, lockscreenVisibility = private)
            val redacted = locked.cards(listOf(sensitive), settings).single()
            check(redacted.senderName == null && redacted.senderImage == null &&
                redacted.previewImage == null && redacted.text.isEmpty() && redacted.title.isEmpty())
            check(locked.cards(listOf(sensitive.copy(lockscreenVisibility = secret)), settings).isEmpty())
            settings.contentMode = 2
            check(allowed.cards(listOf(sensitive), settings).single().text.isEmpty())
            settings.contentMode = 0
            val blocked = sensitive.copy(packageName = "blocked.app", ongoing = true)
            val visible = sensitive.copy(key = "allowed", packageName = "allowed.app", title = "", text = "ALLOWED", ongoing = true)
            settings.blockedPackages = setOf("blocked.app")
            // Uses an old, unfiltered snapshot: no worker/notification event is required.
            val (cards, _) = WidgetRenderModel.prepare(listOf(blocked, visible), settings, null, isolated)
            check(cards.single().text == "ALLOWED") { "Excluded source leaked into an ongoing summary" }
            val track = MusicTrack("blocked.app", "SYNTHETIC-TRACK", "Artist", picture, lockscreenAllowed = true)
            check(allowed.music(track, settings) == null)
            check(locked.music(track.copy(packageName = "allowed.app"), settings) == null)
            check(allowed.music(track.copy(packageName = "allowed.app", lockscreenAllowed = false), settings) == null)
            settings.ongoingMode = 0
            val views = WidgetRenderer.createViews(isolated, listOf(sensitive), settings,
                track, 1f, 360, 4000, true).apply(isolated, FrameLayout(isolated))
            WidgetRows.notificationRows.forEach { ids ->
                listOf(ids.sender, ids.title, ids.text).forEach { id ->
                    check(views.findViewById<TextView>(id).text.isEmpty()) { "Hidden host tree retained text" }
                }
                listOf(ids.avatar, ids.image, ids.groupThumbnail).forEach { id ->
                    check(views.findViewById<android.widget.ImageView>(id).drawable == null)
                }
            }
            check(views.findViewById<TextView>(R.id.music_title).text.isEmpty())
        } finally {
            settings.contentMode = previous.contentMode
            settings.ongoingMode = previous.ongoing
            settings.musicPillEnabled = previous.music
            settings.blockedPackages = previous.blocked
        }
    }

    private fun checkRefreshQueue() {
        data class Scheduled(val task: Runnable, val at: Long)
        var now = 0L
        val scheduled = mutableListOf<Scheduled>()
        val starts = mutableListOf<Pair<Long, Int>>()
        val queue = RefreshQueue(
            post = { task, delay -> scheduled += Scheduled(task, now + delay) },
            remove = { task -> scheduled.removeAll { it.task === task } },
            start = { token -> starts += now to token }
        )
        fun advance(to: Long) {
            while (true) {
                val next = scheduled.minByOrNull { it.at }?.takeIf { it.at <= to } ?: break
                scheduled.remove(next)
                now = next.at
                next.task.run()
            }
            now = to
        }
        queue.request()
        advance(50); queue.request()
        advance(100); queue.request()
        advance(120)
        check(starts.size == 1 && starts.single().first == 120L) { "Events postponed the first deadline" }
        val token = starts.single().second
        repeat(100) { queue.request() }
        advance(1000)
        check(starts.size == 1 && queue.running && !queue.pending) { "Concurrent refreshes were queued" }
        check(queue.revision == token) { "An ordinary event cancelled a publishable snapshot" }
        queue.complete()
        advance(1120)
        check(starts.size == 2)
        queue.request(0, invalidate = true)
        check(queue.revision != starts.last().second) { "Settings did not invalidate the old snapshot" }
        queue.cancel()
        queue.request(0)
        advance(2000)
        check(starts.size == 2) { "Reconnect overlapped the old worker" }
        queue.complete()
        advance(2120)
        check(starts.size == 3 && starts.last().second == queue.revision)
        queue.complete()
        queue.request()
        queue.cancel()
        advance(3000)
        check(starts.size == 3 && !queue.running && !queue.pending)
    }

    private fun checkOptimizedRendering() {
        val previous = WidgetStyle.read(settings)
        try {
            settings.contentMode = 0
            settings.compactMode = false
            settings.widgetScale = 1
            settings.maxTextLines = 2

            settings.musicPillEnabled = true
            settings.overflowBadgePosition = 1
            val picture = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffaabbcc.toInt()) }
            val cards = (1..14).map { card("optimized-$it").copy(previewImage = picture, senderImage = picture) }
            val music = MusicTrack("player", "Track", "Artist", picture)
            fun key() = WidgetUpdateKey.create(isolated, cards, music, settings, 360, 4000, true)
            val originalKey = key()
            check(originalKey == key())
            picture.eraseColor(0xff112233.toInt())
            check(originalKey != key()) { "Mutated pixels did not invalidate the widget" }
            val beforeSettings = key()
            settings.maxTextLines = 3
            check(beforeSettings != key())
            settings.maxTextLines = 2

            settings.contentMode = 2
            val appOnly = key()
            val hiddenChanges = cards.map { it.copy(title = "Hidden title", text = "Hidden text", senderImage = null) }
            check(appOnly == WidgetUpdateKey.create(isolated, hiddenChanges, music.copy(title = "Hidden track"),
                settings, 360, 4000, true)) { "Hidden content invalidated an app-only widget" }
            settings.contentMode = 0

            WidgetLayout.clearCache()
            val first = WidgetRenderModel.build(cards, settings, music, 360, 4000, isolated)
            val measured = WidgetLayout.measurementCount
            check(first == WidgetRenderModel.build(cards, settings, music, 360, 4000, isolated))
            check(WidgetLayout.measurementCount == measured) { "Unchanged rows were measured again" }
            WidgetRenderModel.build(cards, settings, music, 300, 4000, isolated)
            check(WidgetLayout.measurementCount > measured) { "Width change reused stale geometry" }
            val beforeFont = WidgetLayout.measurementCount
            WidgetRenderModel.build(cards, settings, music, 360, 4000, fontContext(1.6f))
            check(WidgetLayout.measurementCount > beforeFont) { "Font scale reused stale geometry" }
            val beforeLines = WidgetLayout.measurementCount
            settings.maxTextLines = 3
            WidgetRenderModel.build(cards, settings, music, 360, 4000, isolated)
            check(WidgetLayout.measurementCount > beforeLines) { "Text line setting reused stale geometry" }
            settings.maxTextLines = 2

            fun views(items: List<NotificationCard>, track: MusicTrack? = music) =
                WidgetRenderer.createViews(isolated, items, settings, track, 1f, 360, 4000)
            val root = views(cards).apply(isolated, FrameLayout(isolated))
            val image = root.findViewById<android.widget.ImageView>(R.id.card_image_0)
            WidgetRenderer.createViews(isolated, cards, settings, music, 1f, 360, 4000, true).reapply(isolated, root)
            check(root.findViewById<View>(R.id.widget_content).visibility == View.INVISIBLE)
            check(root.findViewById<View>(R.id.widget_overflow_row).visibility == View.GONE)
            check(image.drawable == null) { "Hidden widget retained an attachment" }
            views(cards).reapply(isolated, root)
            check(root.findViewById<View>(R.id.widget_content).visibility == View.VISIBLE)
            check(root.findViewById<View>(R.id.widget_overflow_row).visibility == View.VISIBLE)
            views(listOf(cards.first().copy(previewImage = null, senderImage = null)), null).reapply(isolated, root)
            check(image.drawable == null) { "Removed attachment remained in the ImageView" }
            check(root.findViewById<android.widget.ImageView>(R.id.app_icon_badge_0).drawable == null)
            check(root.findViewById<android.widget.ImageView>(R.id.music_art).drawable == null)
            WidgetRows.notificationRows.drop(1).forEach { ids ->
                listOf(ids.avatar, ids.badgeIcon, ids.image, ids.groupThumbnail).forEach { id ->
                    check(root.findViewById<android.widget.ImageView>(id).drawable == null) { "Hidden row retained bitmap" }
                }
                check(root.findViewById<TextView>(ids.text).text.isEmpty())
            }
            views(emptyList(), null).reapply(isolated, root)
            check(root.findViewById<android.widget.ImageView>(R.id.avatar_main_0).drawable == null)
            views(emptyList(), null).reapply(isolated, root)
            check(root.findViewById<View>(R.id.widget_overflow_row).visibility == View.GONE)
        } finally {
            settings.contentMode = previous.contentMode
            settings.compactMode = previous.compact
            settings.widgetScale = previous.scale
            settings.maxTextLines = previous.lines
            settings.musicPillEnabled = previous.music
            settings.overflowBadgePosition = previous.overflowPosition
            WidgetLayout.clearCache()
        }
    }

    private fun checkPreviewUpdates(activity: Activity) {
        val type = MainActivity::class.java
        val select = type.getDeclaredMethod("selectTab", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val mode = type.getDeclaredField("previewMode").apply { isAccessible = true }
        val tab = type.getDeclaredField("selectedTab").apply { isAccessible = true }
        val count = type.getDeclaredField("previewRenderCount").apply { isAccessible = true }
        val oldSnapshot = CardState.value
        val oldMode = mode.getInt(activity)
        val oldTab = tab.getInt(activity)
        try {
            mode.setInt(activity, 1)
            select.invoke(activity, 0)
            val snapshot = CardsSnapshot(cards = listOf(card("preview-refresh")), connection = ConnectionState.CONNECTED)
            CardState.publish(snapshot)
            val visibleCount = count.getInt(activity)
            check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            CardState.publish(snapshot.copy(updatedAt = 100))
            check(count.getInt(activity) == visibleCount) { "Timestamp-only update rebuilt preview" }
            select.invoke(activity, 1)
            CardState.publish(snapshot.copy(cards = listOf(card("new-preview").copy(text = "Latest text"))))
            check(count.getInt(activity) == visibleCount) { "Hidden preview was rebuilt" }
            select.invoke(activity, 0)
            check(count.getInt(activity) == visibleCount + 1) { "Returning to preview did not show latest snapshot" }
        } finally {
            mode.setInt(activity, oldMode)
            type.getDeclaredMethod("updatePreviewSecurity").apply { isAccessible = true }.invoke(activity)
            CardState.publish(oldSnapshot)
            select.invoke(activity, oldTab)
        }
    }

    private fun checkLocalization() {
        fun localized(language: String): Context {
            val config = android.content.res.Configuration(isolated.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            return isolated.createConfigurationContext(config)
        }
        // The interface and demo content stay in English on non-English devices.
        for (language in listOf("en", "ru", "de", "ar")) {
            val context = localized(language)
            check(context.getString(R.string.preview_title) == "Preview")
            check(context.getString(R.string.localized_073) == "2 new messages")
            check(context.getString(R.string.localized_079) == "Calendar")
            val summary = context.getString(R.string.filter_summary, 2, 3)
            check(summary.startsWith("Blocked: ") && summary.contains(" · Showing: "))
        }
    }

    private fun checkPickerPreview() {
        check(WidgetRenderer.sizeFor(android.os.Bundle()) == (398 to 289))
        val preview = android.widget.RemoteViews(isolated.packageName, R.layout.widget_picker_preview)
            .apply(isolated, FrameLayout(isolated))
        check(preview.findViewById<View>(R.id.music_pill).visibility == View.VISIBLE)
        check(preview.findViewById<TextView>(R.id.music_title).text == isolated.getString(R.string.demo_music_title))
        for (id in listOf(R.id.picker_demo_row_0, R.id.picker_demo_row_1, R.id.picker_demo_row_2, R.id.picker_demo_row_3)) {
            check(preview.findViewById<View>(id).visibility == View.VISIBLE)
        }
        val density = isolated.resources.displayMetrics.density
        preview.measure(View.MeasureSpec.makeMeasureSpec((398 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((289 * density).toInt(), View.MeasureSpec.EXACTLY))
        preview.layout(0, 0, preview.measuredWidth, preview.measuredHeight)
        check(preview.findViewById<View>(R.id.picker_demo_row_3).bottom <= preview.height) { "Picker preview is clipped" }
        if (pickerOnly) {
            val bitmap = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
            preview.draw(Canvas(bitmap))
            File(targetContext.cacheDir, "picker-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun checkScreenVisibilitySetting() {
        check(!settings.showWhenScreenOn)
        val previousMusic = settings.musicPillEnabled
        settings.musicPillEnabled = true
        val cards = (1..14).map { card(it.toString()) }
        val music = MusicTrack("player", "Track", "Artist", null)
        fun render(interactive: Boolean) = WidgetRenderer.createViews(isolated, cards, settings, music,
            1f, 360, 4000, interactive).apply(isolated, FrameLayout(isolated))
        var root = render(true)
        check(root.findViewById<View>(R.id.widget_content).visibility == View.INVISIBLE)
        check(root.findViewById<View>(R.id.widget_overflow_row).visibility == View.GONE)
        settings.showWhenScreenOn = true
        check(SettingsRepository(isolated).showWhenScreenOn)
        root = render(true)
        check(root.findViewById<View>(R.id.widget_content).visibility == View.VISIBLE)
        check(root.findViewById<View>(R.id.music_pill).visibility == View.VISIBLE)
        check(root.findViewById<View>(R.id.card_shell_0).visibility == View.VISIBLE)
        check(root.findViewById<View>(R.id.widget_overflow_row).visibility == View.VISIBLE)
        settings.showWhenScreenOn = false
        check(render(false).findViewById<View>(R.id.widget_content).visibility == View.VISIBLE)
        settings.musicPillEnabled = previousMusic
    }

    private fun checkDemoAvatar() {
        val avatar = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        isolated.getDrawable(R.drawable.demo_person_avatar)!!.apply {
            setBounds(0, 0, 64, 64)
            draw(Canvas(avatar))
        }
        val sample = card("telegram").copy(packageName = "org.telegram.messenger", senderImage = avatar)
        fun render() = WidgetRenderer.createViews(isolated, listOf(sample), settings, null, 1f, 360, 400)
            .apply(isolated, FrameLayout(isolated))
        settings.compactMode = false
        val badge = render().findViewById<View>(R.id.app_icon_badge_frame_0)
        check(badge.visibility == View.VISIBLE)
        check((badge.background as android.graphics.drawable.ColorDrawable).color == android.graphics.Color.TRANSPARENT)
        check(badge.paddingLeft == 0 && badge.paddingRight == 0)
        settings.compactMode = true
        check(render().findViewById<View>(R.id.app_icon_badge_frame_0).visibility == View.GONE)
        settings.compactMode = false
        avatar.recycle()
    }

    private fun checkGroupedCardLayout() {
        val avatar = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val grouped = card("grouped").copy(senderImage = avatar, groupCount = 2, isCollapsedGroup = true)
        fun views(item: NotificationCard) = WidgetRenderer.createViews(isolated, listOf(item), settings, null, 1f, 360, 400)
        settings.compactMode = false
        val root = views(grouped).apply(isolated, FrameLayout(isolated))
        check(root.findViewById<View>(R.id.app_icon_badge_frame_0).visibility == View.GONE)
        check(root.findViewById<View>(R.id.card_author_count_0).visibility == View.GONE)
        val image = root.findViewById<android.widget.ImageView>(R.id.card_group_thumbnail_0)
        check(image.visibility == View.VISIBLE)
        check((image.drawable as android.graphics.drawable.BitmapDrawable).bitmap.getPixel(32, 32) == android.graphics.Color.MAGENTA)
        check(root.findViewById<TextView>(R.id.card_group_count_right_0).text == "2")
        for (width in listOf(180, 360, 430)) {
            var expectedPositions: List<Int>? = null
            for (count in listOf(2, 19, 123)) {
            val item = grouped.copy(senderName = "A very long notification title that should fit", groupCount = count)
            val view = WidgetRenderer.createViews(isolated, listOf(item), settings, null, 1f, width, 400)
                .apply(isolated, FrameLayout(isolated))
            val density = isolated.resources.displayMetrics.density
            view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val shell = view.findViewById<android.view.ViewGroup>(R.id.card_shell_0)
            val column = view.findViewById<View>(R.id.card_content_column_0)
            val thumb = view.findViewById<View>(R.id.card_group_thumbnail_0)
            val counter = view.findViewById<View>(R.id.card_group_count_right_0)
            check(column.right <= thumb.left && thumb.right <= counter.left && counter.right <= shell.width) {
                "Grouped card content overlaps or is clipped: width=$width count=$count"
            }
            val positions = listOf(thumb.left, thumb.right, counter.left, counter.right)
            if (expectedPositions == null) expectedPositions = positions
            else check(positions == expectedPositions) { "Thumbnail or counter shifted: width=$width count=$count" }
            check((counter as TextView).gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK == android.view.Gravity.CENTER_HORIZONTAL)
            }
        }
        settings.compactMode = true
        views(grouped).reapply(isolated, root)
        check(image.visibility == View.GONE)
        check(root.findViewById<View>(R.id.card_group_count_right_0).visibility == View.VISIBLE)
        settings.compactMode = false
        views(grouped.copy(groupCount = 1, isCollapsedGroup = false)).reapply(isolated, root)
        check(root.findViewById<View>(R.id.card_group_count_right_0).visibility == View.GONE)
        check(root.findViewById<View>(R.id.app_icon_badge_frame_0).visibility == View.VISIBLE)
        avatar.recycle()
    }

    private fun checkConversationTail() {
        settings.contentMode = 0
        val messages = "Old message\n" + "A long message that wraps across multiple lines ".repeat(4) + "LATEST"
        for (fontScale in listOf(1f, 1.5f)) for (width in listOf(180, 360, 430)) for (lines in 2..3) {
            val context = fontContext(fontScale)
            settings.maxTextLines = lines
            val sample = card("conversation").copy(text = messages, isConversation = true, groupCount = 3)
            val view = WidgetRenderer.createViews(context, listOf(sample), settings, null, 1f, width, 1000)
                .apply(context, FrameLayout(context))
            view.measure(View.MeasureSpec.makeMeasureSpec((width * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val body = view.findViewById<TextView>(R.id.card_text_0)
            check(body.text.toString().endsWith("LATEST"))
            check(!body.text.toString().contains("Old message"))
            check(body.layout.lineCount <= lines && body.layout.getEllipsisCount(body.layout.lineCount - 1) == 0) {
                "Latest text clipped: width=$width font=$fontScale lines=$lines"
            }
            val nonConversation = sample.copy(isConversation = false)
            val ordinary = WidgetRenderer.createViews(context, listOf(nonConversation), settings, null, 1f, width, 1000)
                .apply(context, FrameLayout(context)).findViewById<TextView>(R.id.card_text_0)
            check(ordinary.text.toString() == messages)
        }
        settings.maxTextLines = 2
    }

    private fun checkNavigationBackdrop(activity: Activity) {
        lateinit var pages: List<View>
        lateinit var scroll: android.widget.ScrollView
        lateinit var backdrop: NavigationBackdrop
        var originalVisibility = emptyList<Int>()
        var originalScroll = 0
        onMain {
            @Suppress("UNCHECKED_CAST")
            pages = MainActivity::class.java.getDeclaredField("tabPages").apply { isAccessible = true }.get(activity) as List<View>
            originalVisibility = pages.map { it.visibility }
            pages.forEachIndexed { index, view -> view.visibility = if (index == 0) View.VISIBLE else View.GONE }
            scroll = settingsScroll(pages[0])
            originalScroll = scroll.scrollY
            scroll.scrollTo(0, 0)
            fun find(view: View): NavigationBackdrop? {
                if (view is NavigationBackdrop) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                    find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            backdrop = checkNotNull(find(activity.window.decorView))
        }
        fun renderedFrame() {
            val ready = java.util.concurrent.CountDownLatch(1)
            onMain { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { ready.countDown() } } }
            check(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Frame did not render" }
            waitForIdleSync()
        }
        fun pixels(): IntArray {
            var result = IntArray(0)
            onMain {
                val bitmap = NavigationBackdrop::class.java.getDeclaredField("snapshot").apply { isAccessible = true }.get(backdrop) as? Bitmap
                checkNotNull(bitmap) { "Navigation backdrop has no captured page" }
                result = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(result, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            return result
        }
        renderedFrame()
        val before = pixels()
        onMain { scroll.scrollTo(0, (160 * activity.resources.displayMetrics.density).toInt()) }
        renderedFrame()
        val changed = !before.contentEquals(pixels())

        // PixelCopy includes GPU RenderEffect; Canvas screenshots do not.
        lateinit var screenshot: Bitmap
        onMain { screenshot = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888) }
        val copied = java.util.concurrent.CountDownLatch(1)
        var status = -1
        onMain {
            android.view.PixelCopy.request(activity.window, screenshot, { status = it; copied.countDown() },
                android.os.Handler(android.os.Looper.getMainLooper()))
        }
        check(copied.await(5, java.util.concurrent.TimeUnit.SECONDS) && status == android.view.PixelCopy.SUCCESS) {
            "Could not capture hardware navigation backdrop: $status"
        }
        val mode = if (pages[0] is android.widget.ScrollView) "narrow" else "wide"
        File(targetContext.cacheDir, "audit-blur-$mode.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        check(changed) { "Widget backdrop did not update after scrolling its settings pane: scroll=${scroll.scrollY}, mode=$mode" }
        onMain {
            scroll.scrollTo(0, originalScroll)
            pages.forEachIndexed { index, view -> view.visibility = originalVisibility[index] }
        }
        renderedFrame()
    }

    private fun settingsScroll(page: View): android.widget.ScrollView =
        if (page is android.widget.ScrollView) page
        else (page as android.widget.LinearLayout).getChildAt(1) as android.widget.ScrollView

    private fun captureLightTheme() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("regression_light_theme", true))
        waitForIdleSync()
        onMain {
            check(activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_NO)
            val toggle = OneUiSwitch(activity)
            var changes = 0
            toggle.setOnCheckedChangeListener { _, _ -> changes++ }
            toggle.isChecked = true
            check(toggle.isChecked)
            toggle.isChecked = false
            check(!toggle.isChecked && changes == 2)
            val root = activity.window.decorView
            if (root.width > 0 && root.height > 0) {
                fun layout() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                }
                @Suppress("UNCHECKED_CAST")
                val pages = MainActivity::class.java.getDeclaredField("tabPages").apply { isAccessible = true }.get(activity) as List<View>
                pages.forEachIndexed { i, page -> page.visibility = if (i == 0) View.VISIBLE else View.GONE }
                MainActivity::class.java.getDeclaredField("previewMode").apply { isAccessible = true }.set(activity, 0)
                MainActivity::class.java.getDeclaredMethod("updatePreviewModeButtons").apply { isAccessible = true }.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("renderPreview").apply { isAccessible = true }.invoke(activity)
                layout()
                settingsScroll(pages[0]).scrollTo(0, pages[0].height)
                layout()
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(targetContext.cacheDir, "audit-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            activity.finish()
        }
        waitForIdleSync()
    }

    private fun card(key: String, ongoing: Boolean = false) =
        NotificationCard(key, "com.example.test", "App", "Same name", "Title", "Message text",
            key.hashCode().toLong(), ongoing, null, null)

    private fun checkGrouping() {
        // Matching authors alone must not merge unrelated notifications.
        val unidentified = listOf(card("1"), card("2"))
        check(CardGrouper.prepare(unidentified, 0).size == 2)
        val differentApps = listOf(card("1"), card("2").copy(packageName = "another.app"))
        check(CardGrouper.prepare(differentApps, 0).size == 2)
        val mixed = listOf(card("regular"), card("permanent1", true), card("permanent2", true))
        check(CardGrouper.prepare(mixed, 0).size == 3)
        check(CardGrouper.prepare(mixed, 1).single().key == "regular")
        check(CardGrouper.prepare(mixed, 2).single { it.isOngoingGroup }.groupCount == 2)
        val system = listOf(card("summary").copy(systemGroupKey = "g", isSystemGroupSummary = true),
            card("child1").copy(systemGroupKey = "g"), card("child2").copy(systemGroupKey = "g"))
        check(CardGrouper.prepare(system, 0).single().groupCount == 2)
        // Auto-group summaries are often ongoing even for dismissible messages.
        val autoGroup = system.map { if (it.isSystemGroupSummary) it.copy(ongoing = true) else it }
        val messages = CardGrouper.prepare(autoGroup, 1)
        check(messages.single().groupCount == 2)
        check(!messages.single().ongoing)
        check(messages.single().text.contains("Message text"))
        // A permanent item in the same system group must not promote ordinary items.
        val mixedGroup = autoGroup.map { if (it.key == "child2") it.copy(ongoing = true) else it }
        val hidePermanent = CardGrouper.prepare(mixedGroup, 1)
        check(hidePermanent.single().key == "child1")
        check(hidePermanent.single().groupCount == 1)
        val showPermanent = CardGrouper.prepare(mixedGroup, 0)
        check(showPermanent.size == 2)
        check(showPermanent.count { it.ongoing } == 1)
        val groupPermanent = CardGrouper.prepare(mixedGroup, 2)
        check(groupPermanent.size == 2)
        check(groupPermanent.single { !it.ongoing }.key == "child1")
        // Missing summaries and standalone notifications must remain visible.
        val withoutSummary = CardGrouper.prepare(system.drop(1).mapIndexed { index, item ->
            item.copy(postTime = index.toLong(), text = "message $index")
        }, 1)
        check(withoutSummary.size == 1 && withoutSummary.single().groupCount == 2)
        check(withoutSummary.single().text == "message 1")
        check(CardGrouper.prepare(system.drop(1).mapIndexed { index, item ->
            item.copy(systemGroupKey = "conversation$index")
        }, 1).size == 2)
        val bundled = "system:instagram:auto-group"
        val aKey = NotificationGrouping.groupKey(true, "thread-a", bundled, true)
        val bKey = NotificationGrouping.groupKey(true, "thread-b", bundled, true)
        check(aKey != bKey)
        check(NotificationGrouping.groupKey(true, null, bundled, true) == null)
        val chats = listOf(card("chat-a"), card("chat-b")).mapIndexed { index, item ->
            item.copy(isConversation = true, systemGroupKey = if (index == 0) aKey else bKey)
        }
        check(CardGrouper.prepare(chats, 1).size == 2)
        val sameChat = CardGrouper.prepare(chats.map { it.copy(systemGroupKey = aKey) }, 1).single()
        check(sameChat.groupCount == 2 && !sameChat.isCollapsedGroup)
        check(NotificationGrouping.groupKey(false, "photo-group", "photos-system-group", true) == "photos-system-group")
        check(CardGrouper.prepare(listOf(card("standalone")), 1).size == 1)
    }

    private fun fontContext(fontScale: Float): Context {
        val config = android.content.res.Configuration(isolated.resources.configuration).apply {
            this.fontScale = fontScale
        }
        return object : ContextWrapper(isolated.createConfigurationContext(config)) {
            override fun getSharedPreferences(name: String, mode: Int) = isolated.getSharedPreferences(name, mode)
        }
    }

    private fun checkRendering() {
        settings.contentMode = 0
        settings.musicPillEnabled = true
        settings.ongoingMode = 0
        val cards = (1..14).map { card(it.toString()) }
        val music = MusicTrack("com.example.player", "Track", "Artist", null, true)
        fun views(list: List<NotificationCard>, track: MusicTrack? = null, interactive: Boolean = false) =
            WidgetRenderer.createViews(isolated, list, settings, track, 1f, 360, 4000, interactive)
        val root = views(cards, music).apply(isolated, FrameLayout(isolated))
        val content = root.findViewById<android.view.ViewGroup>(R.id.widget_content)
        check(content.getChildAt(0).id == R.id.widget_music_container) { "Music must appear above notifications" }
        check(WidgetRows.notificationRows.all { root.findViewById<View>(it.shell).visibility == View.VISIBLE })
        check(root.findViewById<android.view.ViewGroup>(R.id.widget_music_container).childCount == 1)
        check(root.findViewById<TextView>(R.id.music_artist).text.startsWith(isolated.getString(R.string.music_paused)))
        check(root.findViewById<TextView>(R.id.widget_overflow_center).text.toString() == "+2")
        views(cards.take(1)).reapply(isolated, root)
        check(root.findViewById<View>(R.id.music_pill).visibility == View.GONE)
        check(WidgetRows.notificationRows.drop(1).all { root.findViewById<View>(it.shell).visibility == View.GONE })
        views(emptyList()).reapply(isolated, root)
        check(WidgetRows.notificationRows.all { root.findViewById<View>(it.shell).visibility == View.GONE })
        check(root.findViewById<View>(R.id.widget_overflow_center).visibility == View.GONE)
        check(root.findViewById<View>(R.id.widget_overflow_row).visibility == View.GONE)
        views(listOf(card("group", true).copy(isOngoingGroup = true))).reapply(isolated, root)
        check(root.findViewById<View>(R.id.card_group_badge_0).visibility == View.VISIBLE)
        views(emptyList()).reapply(isolated, root)
        check(root.findViewById<View>(R.id.card_group_badge_0).visibility == View.GONE)
        views(cards, music, true).reapply(isolated, root)
        check(root.visibility == View.VISIBLE)
        check(root.findViewById<View>(R.id.widget_content).visibility == View.INVISIBLE)
        check(root.findViewById<View>(R.id.widget_overflow_row).visibility == View.GONE)
        val retainedMusic = root.findViewById<View>(R.id.music_pill)
        fun measuredSize(): Pair<Int, Int> {
            root.measure(View.MeasureSpec.makeMeasureSpec((360 * isolated.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            return root.measuredWidth to root.measuredHeight
        }
        // Production sets explicit root bounds, independent of hidden content.
        root.layoutParams = android.view.ViewGroup.LayoutParams(360, 4000)
        measuredSize()
        check(root.findViewById<TextView>(R.id.music_title).text.isEmpty())
        check(root.findViewById<TextView>(R.id.card_text_0).text.isEmpty())
        views(cards, music).reapply(isolated, root)
        check(measuredSize().second > 0)
        check(root.findViewById<View>(R.id.music_pill) === retainedMusic) { "Music was replaced during the AOD transition" }
        check(root.findViewById<View>(R.id.widget_content).visibility == View.VISIBLE)
        views(cards.take(1)).reapply(isolated, root)
        check(root.visibility == View.VISIBLE)
        check(root.findViewById<View>(R.id.card_shell_0).visibility == View.VISIBLE)

        settings.contentMode = 1
        views(cards.take(1), music).reapply(isolated, root)
        check(root.findViewById<TextView>(R.id.card_title_0).visibility == View.GONE)
        check(root.findViewById<TextView>(R.id.card_text_0).visibility == View.GONE)
        check(root.findViewById<TextView>(R.id.app_name_0).text.toString() == "Same name")
        check(root.findViewById<View>(R.id.music_pill).visibility == View.GONE)
        settings.contentMode = 2
        views(cards.take(1)).reapply(isolated, root)
        check(root.findViewById<TextView>(R.id.app_name_0).text.toString() == "App")
        settings.contentMode = 0
        val small = WidgetRenderModel.build(cards, settings, null, 180, 300, fontContext(1f))
        val large = WidgetRenderModel.build(cards, settings, null, 180, 300, fontContext(1.6f))
        // Integer packing can give the same count at one particular height.
        // Verify font growth across boundaries instead of relying on a single one.
        val fontPacking = (200..600 step 20).map { height ->
            WidgetRenderModel.build(cards, settings, null, 180, height, fontContext(1f)).cards.size to
                WidgetRenderModel.build(cards, settings, null, 180, height, fontContext(1.6f)).cards.size
        }
        check(large.cards.size <= small.cards.size && fontPacking.all { (normal, enlarged) -> enlarged <= normal })
        check(fontPacking.any { (normal, enlarged) -> enlarged < normal }) { "Font scale did not affect packing: $fontPacking" }
        check(small.cards.size + small.overflow == cards.size)
        // Long messages and system-group snippets must be measured as 2–3 visible
        // lines, not as their entire unabridged text (which used to hide all cards).
        for (lines in 2..3) {
            settings.maxTextLines = lines
            val long = card("long").copy(title = "", text = (1..300).joinToString("\n") { "Message $it" })
            val short = long.copy(text = (1..lines).joinToString("\n") { "Message $it" })
            for (track in listOf(null, music)) {
                val longPlan = WidgetRenderModel.build(listOf(long), settings, track, 356, 265, isolated)
                val shortPlan = WidgetRenderModel.build(listOf(short), settings, track, 356, 265, isolated)
                check(longPlan.cards.size == 1) { "Full text disappeared with $lines visible lines" }
                check(longPlan.cards.size == shortPlan.cards.size)
            }
            val paragraph = card("paragraph").copy(text = "Long message ".repeat(1000))
            check(WidgetRenderModel.build(listOf(paragraph), settings, null, 356, 265, isolated).cards.size == 1)
        }
        settings.maxTextLines = 2
        val hiddenGroup = WidgetRenderModel.build(listOf(CardGrouper.groupOngoing(listOf(card("x", true)))!!),
            settings.apply { contentMode = 2 }, null, 360, 400, isolated)
        check(hiddenGroup.cards.single().appName.isNotBlank())
        settings.contentMode = 0
        val picture = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val illustrated = cards.map { it.copy(previewImage = picture, senderImage = picture) }
        val budgeted = WidgetRenderModel.build(illustrated, settings, null, 360, 4000, isolated)
        check(budgeted.cards.sumOf { (it.previewImage?.allocationByteCount ?: 0) +
            (it.senderImage?.allocationByteCount ?: 0) } <= 2 * 1024 * 1024)
        settings.compactMode = true
        val compact = WidgetRenderModel.build(illustrated, settings, music, 360, 4000, isolated)
        check(compact.cards.all { it.previewImage == null && it.senderImage == null })
    }

    @Suppress("DEPRECATION")
    private fun checkAvatars() {
        val avatar = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val picture = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val notification = android.app.Notification.Builder(targetContext, "regression")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Author").setContentText("Message")
            .setLargeIcon(avatar).setStyle(android.app.Notification.BigPictureStyle().bigPicture(picture)).build()
        val sbn = android.service.notification.StatusBarNotification("example.sender", "example.sender",
            1, null, android.os.Process.myUid(), 0, 0, notification,
            android.os.Process.myUserHandle(), System.currentTimeMillis())
        val service = NotificationListener()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, targetContext)
        try {
            val read = NotificationListener::class.java.getDeclaredMethod("readCard",
                android.service.notification.StatusBarNotification::class.java, Set::class.java, Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            fun parse(withPreview: Boolean) = read.invoke(service, sbn, emptySet<String>(), withPreview, 0, 0) as NotificationCard
            val late = parse(false) // Same path used after the first 24 notifications.
            check(late.senderImage?.getPixel(32, 32) == android.graphics.Color.MAGENTA)
            check(late.previewImage == null)
            val full = parse(true) // A cached avatar must not suppress a newly needed preview.
            check(full.previewImage != null)
            check(parse(false).senderImage != null)
            check(parse(false).previewImage == null)
            val appOnly = read.invoke(service, sbn, emptySet<String>(), true, 2, 0) as NotificationCard
            check(appOnly.senderImage == null && appOnly.previewImage == null && appOnly.senderName == null && appOnly.text.isEmpty())
            val senderOnly = read.invoke(service, sbn, emptySet<String>(), true, 1, 0) as NotificationCard
            check(senderOnly.text.isEmpty() && senderOnly.title.isEmpty() && senderOnly.previewImage == null)
            val older = card("older").copy(senderImage = avatar, systemGroupKey = "avatar-group")
            val newer = card("newer").copy(senderImage = null, systemGroupKey = "avatar-group", postTime = Long.MAX_VALUE)
            val summary = card("summary").copy(systemGroupKey = "avatar-group", isSystemGroupSummary = true)
            check(CardGrouper.prepare(listOf(summary, older, newer), 0).single().senderImage != null)
            check(CardGrouper.prepare(listOf(summary, older, newer.copy(senderName = "Another sender")), 0)
                .single().senderImage == null) { "Group borrowed a different sender's photo" }
            settings.compactMode = false
            settings.contentMode = 0
            val views = WidgetRenderer.createViews(isolated, listOf(late), settings, null, 1f, 360, 265)
            val root = views.apply(isolated, FrameLayout(isolated))
            check(root.findViewById<View>(R.id.app_icon_badge_frame_0).visibility == View.VISIBLE)
            val drawable = root.findViewById<android.widget.ImageView>(R.id.avatar_main_0).drawable as android.graphics.drawable.BitmapDrawable
            check(drawable.bitmap.getPixel(32, 32) == android.graphics.Color.MAGENTA)

            // Telegram can deliver multiple messages as one MessagingStyle
            // notification. The badge counts messages, not Android records.
            val author = android.app.Person.Builder().setName("Anna").build()
            val self = android.app.Person.Builder().setName("Me").build()
            fun parseMessages(count: Int, number: Int, id: Int): NotificationCard {
                val style = android.app.Notification.MessagingStyle(self)
                for (index in 1..count) style.addMessage("Message $index", index.toLong(), author)
                val n = android.app.Notification.Builder(targetContext, "regression")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).setNumber(number)
                    .setWhen(1_700_000_000_000L).setShowWhen(id != 4).build()
                val item = android.service.notification.StatusBarNotification("example.sender", "example.sender", id, null,
                    android.os.Process.myUid(), 0, 0, n, android.os.Process.myUserHandle(), System.currentTimeMillis())
                return read.invoke(service, item, emptySet<String>(), false, 0, 0) as NotificationCard
            }
            val twoMessages = parseMessages(2, 0, 2)
            check(twoMessages.groupCount == 2)
            check(twoMessages.displayTime == 1_700_000_000_000L)
            check(twoMessages.displayTime != twoMessages.postTime)
            val twoViews = WidgetRenderer.createViews(isolated, listOf(twoMessages), settings, null, 1f, 360, 265)
            twoViews.reapply(isolated, root)
            check(root.findViewById<TextView>(R.id.card_author_count_0).text.toString() == "2")
            check(root.findViewById<View>(R.id.card_author_count_0).visibility == View.VISIBLE)
            check(root.findViewById<TextView>(R.id.card_time_0).text.toString() ==
                android.text.format.DateFormat.getTimeFormat(isolated).format(java.util.Date(1_700_000_000_000L)))
            val hiddenTime = parseMessages(1, 0, 4)
            check(hiddenTime.displayTime == null)
            WidgetRenderer.createViews(isolated, listOf(hiddenTime), settings, null, 1f, 360, 265).reapply(isolated, root)
            check(root.findViewById<View>(R.id.card_time_0).visibility == View.GONE)
            val oneMessage = parseMessages(1, 0, 3)
            check(oneMessage.groupCount == 1)
            check(parseMessages(2, 7, 4).groupCount == 7)
            val systemGroup = CardGrouper.prepare(listOf(
                twoMessages.copy(key = "summary", systemGroupKey = "g", isSystemGroupSummary = true, groupCount = 100),
                twoMessages.copy(key = "a", systemGroupKey = "g"),
                twoMessages.copy(key = "b", systemGroupKey = "g", groupCount = 3)), 0).single()
            check(systemGroup.groupCount == 5)
            check(WidgetRenderModel.build(listOf(systemGroup), settings, null, 360, 48, isolated).overflow == 5)
            WidgetRenderer.createViews(isolated, listOf(oneMessage), settings, null, 1f, 360, 265).reapply(isolated, root)
            check(root.findViewById<View>(R.id.card_author_count_0).visibility == View.GONE)
            check(root.findViewById<View>(R.id.card_time_0).visibility == View.VISIBLE)
            val mark = NotificationListener::class.java.getDeclaredMethod("markSourceChanged", String::class.java)
                .apply { isAccessible = true }
            repeat(2050) { mark.invoke(service, "synthetic-source-$it") }
            val versions = NotificationListener::class.java.getDeclaredField("sourceChanges")
                .apply { isAccessible = true }.get(service) as Map<*, *>
            check(versions.size <= 1024) { "Source metadata grew without bound while the worker was blocked" }
            val queue = NotificationListener::class.java.getDeclaredField("refreshQueue")
                .apply { isAccessible = true }.get(service) as RefreshQueue
            queue.cancel()
            NotificationListener::class.java.getDeclaredMethod("clearCardCache").apply { isAccessible = true }.invoke(service)
            read.invoke(service, sbn, emptySet<String>(), true, 0, 0) // old revision after policy change
            val cache = NotificationListener::class.java.getDeclaredField("cardCache")
                .apply { isAccessible = true }.get(service) as android.util.LruCache<*, *>
            check(cache.size() == 0) { "Canceled parse repopulated the notification cache" }
        } finally {
            val thread = NotificationListener::class.java.getDeclaredField("workerThread").apply { isAccessible = true }.get(service) as android.os.HandlerThread
            thread.quitSafely()
        }
    }

    private fun checkPacking() {
        settings.contentMode = 0
        settings.compactMode = false
        settings.musicPillEnabled = true
        val items = (1..6).map { index -> card("packing$index").copy(
            title = if (index % 2 == 0) "Title" else "",
            text = if (index % 3 == 0) "Short message" else "Message with long text ".repeat(20)) }
        for (fontScale in listOf(1f, 1.5f)) {
            val config = android.content.res.Configuration(targetContext.resources.configuration).apply { this.fontScale = fontScale }
            val context = object : ContextWrapper(targetContext.createConfigurationContext(config)) {
                override fun getSharedPreferences(name: String, mode: Int) = isolated.getSharedPreferences(name, mode)
            }
            val density = context.resources.displayMetrics.density
            for (size in 0..2) for (height in listOf(180, 265)) for (withMusic in listOf(false, true)) {
                settings.widgetScale = size
                val scale = WidgetRows.scaleFactor(size)
                val track = if (withMusic) MusicTrack("player", "Track", "Author", null) else null
                val plan = WidgetRenderModel.build(items, settings, track, 356, height, context)
                fun measured(views: android.widget.RemoteViews): Int {
                    val root = views.apply(context, FrameLayout(context))
                    root.measure(View.MeasureSpec.makeMeasureSpec((356 * density + .5f).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    return root.measuredHeight
                }
                val views = WidgetRenderer.createViews(context, items, settings, track, scale, 356, height)
                val available = (height * density + .5f).toInt()
                check(measured(views) <= available) { "Widget clips: font=$fontScale size=$size height=$height music=$withMusic" }
                if (plan.cards.size < items.size) {
                    val nextCount = plan.cards.size + 1
                    val next = WidgetRenderer.createViews(context, items.take(nextCount), settings, track, scale, 356, 4000)
                    if (nextCount < items.size) {
                        next.setViewVisibility(R.id.widget_overflow_row, View.VISIBLE)
                        next.setViewVisibility(R.id.widget_overflow_center, View.VISIBLE)
                        next.setTextViewText(R.id.widget_overflow_center, "+1")
                    }
                    check(measured(next) > available) { "A complete extra card could fit: font=$fontScale size=$size height=$height music=$withMusic" }
                }
            }
        }
        settings.widgetScale = 1
    }

    private fun checkHeaderWidth() {
        settings.contentMode = 0
        settings.compactMode = false
        for (fontScale in listOf(1f, 1.5f)) {
            val narrowerWidths = mutableMapOf<Pair<Int, Int>, Int>()
            val config = android.content.res.Configuration(targetContext.resources.configuration).apply { this.fontScale = fontScale }
            val context = object : ContextWrapper(targetContext.createConfigurationContext(config)) {
                override fun getSharedPreferences(name: String, mode: Int) = isolated.getSharedPreferences(name, mode)
            }
            val density = context.resources.displayMetrics.density
            for (size in 0..2) for (width in listOf(180, 356, 600)) for (count in listOf(1, 2, 12, 123)) {
                settings.widgetScale = size
                val item = card("wide-header").copy(senderName = "Very long sender name ".repeat(20), groupCount = count)
                val views = WidgetRenderer.createViews(context, listOf(item), settings, null,
                    WidgetRows.scaleFactor(size), width, 1000)
                val root = views.apply(context, FrameLayout(context))
                root.measure(View.MeasureSpec.makeMeasureSpec((width * density + .5f).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val name = root.findViewById<TextView>(R.id.app_name_0)
                val parent = name.parent as android.view.ViewGroup
                val badge = root.findViewById<View>(R.id.card_author_count_0)
                val time = root.findViewById<TextView>(R.id.card_time_0)
                check(name.right <= time.left) { "Author overlaps time: $width/$size/$fontScale" }
                check(time.right <= parent.width) { "Time leaves the header: $width/$size/$fontScale" }
                if (count > 1) check(name.right <= badge.left && badge.right <= time.left) { "Group count overlaps name/time: $width/$size/$fontScale" }
                if (width == 356) narrowerWidths[size to count] = name.width
                if (width == 600) check(name.width > narrowerWidths.getValue(size to count)) { "Author width did not grow with available space" }
                if (width == 600 && size == 1 && fontScale == 1f) {
                    check(name.width > 180 * density) { "Author still has the fixed 180dp cap" }
                }
                val body = root.findViewById<TextView>(R.id.card_text_0)
                val column = root.findViewById<View>(R.id.card_content_column_0)
                check(body.width == column.width) { "Message body does not fill the text column" }
                val short = item.copy(senderName = "Buddy ♥", title = "", text = "H\nH")
                WidgetRenderer.createViews(context, listOf(short), settings, null, WidgetRows.scaleFactor(size), width, 1000).reapply(context, root)
                root.measure(View.MeasureSpec.makeMeasureSpec((width * density + .5f).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val previous = if (count > 1) badge else name
                check(time.left - previous.right <= 8 * WidgetRows.scaleFactor(size) * density) { "Time is detached from the author/count: $width/$size/$fontScale/$count" }
                if (width == 356 && size == 1 && fontScale == 1f && count == 2) {
                    val screenshot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(screenshot))
                    File(targetContext.cacheDir, "audit-notification-header.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            }
        }
        settings.widgetScale = 1
    }

    private fun checkPreviewStage() {
        settings.contentMode = 0
        settings.compactMode = false
        settings.widgetScale = 1
        val density = isolated.resources.displayMetrics.density
        val items = (1..8).map { card("preview$it") }
        val views = WidgetRenderer.createViews(isolated, items, settings, null, 1f, 393, 261)
        fun measure(stage: WidgetPreviewStage, width: Int) {
            stage.measure(View.MeasureSpec.makeMeasureSpec((width * density + .5f).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            stage.layout(0, 0, stage.measuredWidth, stage.measuredHeight)
        }
        for (width in listOf(180, 356, 600)) {
            val stage = WidgetPreviewStage(isolated)
            stage.show(views, 393)
            measure(stage, width)
            val child = stage.getChildAt(0)
            val right = child.left + child.width * child.scaleX
            val bottom = child.top + child.height * child.scaleY
            check(child.left >= stage.paddingLeft)
            check(child.top >= stage.paddingTop)
            check(right <= stage.width - stage.paddingRight + 1)
            check(bottom <= stage.height - stage.paddingBottom + 1) { "Preview clips its footer" }
            check(kotlin.math.abs(child.left - (stage.width - right)) <= 2) { "Preview is not horizontally centered" }
            check(child.scaleX == child.scaleY)
            val natural = views.apply(isolated, FrameLayout(isolated))
            natural.measure(View.MeasureSpec.makeMeasureSpec(child.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            check(child.height == natural.measuredHeight) { "Preview reserves empty widget host height" }
            check(stage.height - stage.paddingTop - stage.paddingBottom - child.height * child.scaleY < 2f) {
                "Preview leaves empty space below its content"
            }
            check(kotlin.math.abs(child.left - stage.paddingLeft) <= 1) { "Preview leaves an unused side gutter" }
            check(kotlin.math.abs(right - (stage.width - stage.paddingRight)) <= 1) { "Preview does not fill the available width" }
            check(child.findViewById<View>(R.id.widget_overflow_row).visibility == View.VISIBLE)
            stage.showMessage("No notifications")
            measure(stage, width)
            check(stage.childCount == 1 && stage.getChildAt(0) is TextView)
            check(stage.height <= (160 * density + 1).toInt()) { "Empty preview retained its old height" }
        }

    }

    private fun checkScaleTransitions() {
        settings.contentMode = 0
        settings.compactMode = false
        settings.musicPillEnabled = true
        val items = (1..14).map { card("scale$it").copy(groupCount = 2) }
        val music = MusicTrack("player", "Track", "Author", null)
        fun views(size: Int): android.widget.RemoteViews {
            settings.widgetScale = size
            return WidgetRenderer.createViews(isolated, items, settings, music, WidgetRows.scaleFactor(size), 356, 4000)
        }
        val density = isolated.resources.displayMetrics.density
        fun layout(root: View) {
            root.measure(View.MeasureSpec.makeMeasureSpec((356 * density + .5f).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        val normal = views(1).apply(isolated, FrameLayout(isolated))
        layout(normal)
        val normalFont = normal.findViewById<TextView>(R.id.card_text_0).textSize
        val normalAvatar = normal.findViewById<View>(R.id.avatar_main_0).width
        val reused = views(1).apply(isolated, FrameLayout(isolated))
        val dimensions = listOf(R.id.avatar_container_0, R.id.avatar_main_0, R.id.app_icon_badge_frame_0,
            R.id.card_author_count_0, R.id.card_group_badge_0, R.id.music_art_container, R.id.music_activity,
            R.id.widget_overflow_center, R.id.widget_overflow_row)
        val fonts = listOf(R.id.app_name_0, R.id.card_title_0, R.id.card_text_0, R.id.card_time_0,
            R.id.card_author_count_0, R.id.widget_overflow_center, R.id.music_title, R.id.music_artist)
        for (size in listOf(0, 1, 2, 1, 0, 2, 1)) {
            views(size).reapply(isolated, reused)
            layout(reused)
            val font = reused.findViewById<TextView>(R.id.card_text_0).textSize
            val avatar = reused.findViewById<View>(R.id.avatar_main_0).width
            when (size) {
                0 -> { check(font < normalFont); check(avatar < normalAvatar) }
                2 -> { check(font > normalFont); check(avatar > normalAvatar) }
                1 -> {
                    check(kotlin.math.abs(font - normalFont) < .01f)
                    check(avatar == normalAvatar)
                    check(reused.paddingLeft == normal.paddingLeft)
                    for (id in fonts) check(kotlin.math.abs(reused.findViewById<TextView>(id).textSize - normal.findViewById<TextView>(id).textSize) < .01f)
                    for (id in dimensions) {
                        val actual = reused.findViewById<View>(id)
                        val expected = normal.findViewById<View>(id)
                        check(actual.layoutParams.width == expected.layoutParams.width)
                        check(actual.layoutParams.height == expected.layoutParams.height)
                        check(actual.paddingLeft == expected.paddingLeft)
                    }
                }
            }
        }
        settings.widgetScale = 1
    }
}
