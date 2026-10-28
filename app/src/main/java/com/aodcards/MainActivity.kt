package com.aodcards

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val BG get() = getColor(R.color.ui_background)
    private val SURFACE get() = getColor(R.color.ui_surface)
    private val CONTROL get() = getColor(R.color.ui_control)
    private val SURFACE_BORDER get() = getColor(R.color.ui_border)
    private val BLUE get() = getColor(R.color.ui_accent)
    private val INK get() = getColor(R.color.ui_text)
    private val MUTED get() = getColor(R.color.ui_muted)
    private val LINE get() = getColor(R.color.ui_border)
    private val GREEN get() = getColor(R.color.ui_success)
    private val AMBER get() = getColor(R.color.ui_warning)
    private lateinit var settings: SettingsRepository
    private lateinit var notificationStatus: TextView
    private lateinit var notificationDot: View
    private lateinit var batteryStatus: TextView
    private lateinit var batteryDot: View
    private lateinit var batteryButton: TextView
    private var previewHost: WidgetPreviewStage? = null
    private var lastPreviewKey: WidgetUpdateKey? = null
    private var lastPreviewMessage: String? = null
    private val demoTime = System.currentTimeMillis()
    private var previewRenderCount = 0
    private val demoSenderAvatar by lazy {
        val drawable = getDrawable(R.drawable.demo_person_avatar)!!
        android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(android.graphics.Canvas(bitmap))
        }
    }
    private var filterView: AppFilterView? = null
    private var previewWidgetId = -1
    private var lineChoices: ViewGroup? = null
    private val statusLabels = mutableListOf<TextView>()
    private val stateObserver: () -> Unit = {
        updateConnectionStatus()
        if (previewMode == PREVIEW_REAL) {
            // A hidden page must not retain an old notification tree or update key.
            if (::tabPages.isInitialized && tabPages[TAB_CUSTOMIZE].visibility != View.VISIBLE) {
                lastPreviewKey = null
                lastPreviewMessage = null
                previewHost?.removeAllViews()
            } else renderPreview()
        }
        filterView?.reload()
    }
    private var previewMode = PREVIEW_EXAMPLES
    private lateinit var previewExamplesButton: TextView
    private lateinit var previewRealButton: TextView
    private var selectedTab = TAB_CUSTOMIZE
    private lateinit var tabPages: List<View>
    private lateinit var tabButtons: List<LinearLayout>
    private lateinit var tabIcons: List<NavGlyphView>
    private lateinit var tabLabels: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        previewMode = getSharedPreferences("ui", MODE_PRIVATE).getInt("preview_mode", PREVIEW_EXAMPLES)
        updatePreviewSecurity()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (android.os.Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = SURFACE_BORDER
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        selectedTab = getSharedPreferences("ui", MODE_PRIVATE).getInt("main_tab",
            if (CardState.hasAccess(this)) TAB_CUSTOMIZE else TAB_ABOUT).coerceIn(TAB_CUSTOMIZE, TAB_FILTER)
        previewWidgetId = getSharedPreferences("ui", MODE_PRIVATE).getInt("preview_widget", -1)
        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val barsTop: Int
            val barsBottom: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                barsTop = bars.top
                barsBottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                barsTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                barsBottom = insets.systemWindowInsetBottom
            }
            view.setPadding(0, barsTop, 0, barsBottom)
            insets
        }
        tabPages = listOf(customizePage(), aboutPage(), filterPage())
        val pageHost = FrameLayout(this)
        tabPages.forEachIndexed { index, page ->
            page.visibility = if (index == selectedTab) View.VISIBLE else View.GONE
            pageHost.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(pageHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bottomNavigation(pageHost), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80), Gravity.BOTTOM))
        setContentView(root)
        root.requestApplyInsets()
        updateTabButtons()
        updateContentControls()
        (tabPages[selectedTab] as? ScrollView)?.post { (tabPages[selectedTab] as? ScrollView)?.scrollTo(0, 0) }
    }

    private fun scrollPage(build: (LinearLayout) -> Unit): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = false
            clipToPadding = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(108))
        }
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        build(content)
        return scroll
    }

    private fun customizePage(): View {
        fun addPreview(page: LinearLayout) {
            page.addView(OneUiPageHeading(this@MainActivity, "AOD Cards", getString(R.string.localized_003)) { showAboutDialog() }, wrap())
            page.addView(connectionSummary(), cardMargins(bottom = 12))
            page.addView(previewCard(), cardMargins(bottom = 16))
        }
        fun addSettings(page: LinearLayout) {
            page.addView(widgetSettingsCard(), cardMargins(bottom = 20))
        }
        if (resources.configuration.screenWidthDp < 600) return scrollPage { page ->
            addPreview(page)
            addSettings(page)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val previewPanel: View = if (resources.configuration.screenHeightDp < 600) {
                scrollPage(::addPreview)
            } else LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(16), dp(12), dp(92))
                addView(OneUiPageHeading(this@MainActivity, "AOD Cards", getString(R.string.localized_003)) { showAboutDialog() }, wrap())
                addView(connectionSummary(), cardMargins(bottom = 10))
                addView(previewCard(fitHeight = true), LinearLayout.LayoutParams(-1, 0, 1f))
            }
            addView(previewPanel, LinearLayout.LayoutParams(0, -1, 1f))
            addView(scrollPage(::addSettings), LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun connectionSummary(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(SURFACE, dp(20), null)
        val status = text("", 13f, MUTED).apply {
            setPadding(dp(14), dp(6), dp(4), dp(6))
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTab(TAB_ABOUT) }
            statusLabels += this
        }
        addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(getString(R.string.localized_004), 13f, BLUE, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            contentDescription = getString(R.string.localized_005)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(30, 62, 145, 255)), null,
                rounded(Color.WHITE, dp(20), null))
            setOnClickListener { forceRefreshNotifications() }
        }, LinearLayout.LayoutParams(-2, -2))
    }

    private fun aboutPage() = scrollPage { content ->
        content.addView(OneUiPageHeading(this, getString(R.string.localized_006), getString(R.string.localized_007)), wrap())
        content.addView(text(getString(R.string.screen_visibility_description), 14f, INK), cardMargins(bottom = 12))
        content.addView(notificationAccessCard(), cardMargins(bottom = 12))
        content.addView(batteryCard(), cardMargins(bottom = 18))
        content.addView(sectionHeading(getString(R.string.localized_009), getString(R.string.localized_010)))
        content.addView(lockStarCard(), cardMargins(bottom = 12))
    }

    private fun filterPage(): View = AppFilterView(this, settings) {
        NotificationListener.refreshSnapshot(this)
    }.also { filterView = it }

    private fun bottomNavigation(pageHost: View): View {
        val labels = listOf(getString(R.string.localized_011), getString(R.string.localized_012), getString(R.string.localized_013))
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(6))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val icons = mutableListOf<NavGlyphView>()
        val captions = mutableListOf<TextView>()
        tabButtons = labels.mapIndexed { index, label ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(3), dp(4), dp(3))
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(index) }
                val icon = NavGlyphView(this@MainActivity, index)
                val caption = text(label, 11f, MUTED, true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                }
                icons += icon
                captions += caption
                addView(icon, LinearLayout.LayoutParams(dp(21), dp(21)))
                addView(caption, wrap())
                bar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
        }
        tabIcons = icons
        tabLabels = captions
        val frostedBar = FrameLayout(this).apply {
            background = rounded(SURFACE, dp(32), null)
            clipToOutline = true
            elevation = dp(4).toFloat()
            addView(NavigationBackdrop(this@MainActivity, pageHost), FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, -1))
        }
        rail.addView(frostedBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return rail
    }

    private fun selectTab(index: Int) {
        if (index !in tabPages.indices) return
        selectedTab = index
        tabPages.forEachIndexed { pageIndex, page -> page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE }
        if (index != TAB_CUSTOMIZE && previewMode == PREVIEW_REAL) {
            previewHost?.removeAllViews()
            lastPreviewKey = null
            lastPreviewMessage = null
        }
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("main_tab", index).apply()
        updateTabButtons()
        if (index == TAB_CUSTOMIZE) renderPreview()
    }

    private fun updateTabButtons() {
        if (!::tabButtons.isInitialized) return
        tabButtons.forEachIndexed { index, button ->
            val selected = index == selectedTab
            button.isSelected = selected
            button.contentDescription = listOf(getString(R.string.localized_011), getString(R.string.localized_012), getString(R.string.localized_013))[index]
            if (android.os.Build.VERSION.SDK_INT >= 30) button.stateDescription = if (selected) getString(R.string.localized_014) else getString(R.string.localized_015)
            button.background = if (selected) rounded(CONTROL, dp(25), null) else ColorDrawable(Color.TRANSPARENT)
            tabIcons[index].active = selected
            tabLabels[index].setTextColor(if (selected) BLUE else MUTED)
        }
    }

    private inner class NavGlyphView(context: android.content.Context, private val kind: Int) : View(context) {
        var active = false
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            paint.color = if (active) BLUE else MUTED
            when (kind) {
                0 -> {
                    for ((offset, knob) in listOf(-6f to -3f, 0f to 4f, 6f to -1f)) {
                        val y = cy + offset * d
                        canvas.drawLine(cx - 7f * d, y, cx + 7f * d, y, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx + knob * d, y, 1.8f * d, paint)
                        paint.style = Paint.Style.STROKE
                    }
                }
                1 -> {
                    canvas.drawCircle(cx, cy, 8f * d, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy - 3.5f * d, 1.1f * d, paint)
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(cx, cy - .5f * d, cx, cy + 4f * d, paint)
                }
                else -> {
                    val path = android.graphics.Path().apply {
                        moveTo(cx - 8f * d, cy - 7f * d)
                        lineTo(cx + 8f * d, cy - 7f * d)
                        lineTo(cx + 2f * d, cy)
                        lineTo(cx + 2f * d, cy + 6f * d)
                        lineTo(cx - 2f * d, cy + 8f * d)
                        lineTo(cx - 2f * d, cy)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    private fun showAboutDialog() {
        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: Exception) { "" }
        val dialog = android.app.Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = rounded(SURFACE, dp(28), null)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(ImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val headingText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headingText.addView(text("AOD Cards", 21f, INK, true), wrap())
        headingText.addView(text(getString(R.string.about_version, version), 13f, MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        }, wrap())
        heading.addView(headingText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        content.addView(heading, wrap())
        content.addView(text(getString(R.string.about_description), 15f, INK).apply {
            setPadding(0, dp(20), 0, dp(20))
        }, wrap())
        content.addView(text(getString(R.string.about_tested_device), 13f, MUTED).apply {
            setPadding(0, 0, 0, dp(20))
        }, wrap())
        val github = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(38, 120, 185, 255)),
                rounded(CONTROL, dp(18), null), null
            )
            isFocusable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_github_url))))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, R.string.about_github_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
        github.addView(text("GitHub", 15f, INK, true), wrap())
        github.addView(text(getString(R.string.about_github_repository), 13f, BLUE).apply {
            setPadding(0, dp(4), 0, 0)
        }, wrap())
        content.addView(github, wrap())
        content.addView(actionButton(getString(android.R.string.ok), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(minOf(dp(400), resources.displayMetrics.widthPixels - dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onResume() {
        super.onResume()
        val enabled = CardState.hasAccess(this)
        notificationStatus.text = if (enabled) getString(R.string.localized_017) else getString(R.string.localized_018)
        notificationStatus.setTextColor(if (enabled) GREEN else AMBER)
        notificationDot.background = circle(if (enabled) GREEN else AMBER)

        val exempt = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = if (exempt) getString(R.string.localized_019) else getString(R.string.localized_020)
        batteryStatus.setTextColor(if (exempt) GREEN else AMBER)
        batteryDot.background = circle(if (exempt) GREEN else AMBER)
        batteryButton.text = if (exempt) getString(R.string.localized_021) else getString(R.string.localized_022)
        CardState.observe(stateObserver)
        if (enabled) NotificationListener.refreshSnapshot(this)
        else NotificationListener.clearUnavailable(this)
        updateConnectionStatus()
        renderPreview()
        updateWidget()
    }

    override fun onPause() {
        CardState.removeObserver(stateObserver)
        if (previewMode == PREVIEW_REAL) {
            previewHost?.removeAllViews()
            lastPreviewKey = null
            lastPreviewMessage = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        CardState.removeObserver(stateObserver)
        filterView?.close()
        super.onDestroy()
    }

    private fun updateConnectionStatus() {
        val snapshot = CardState.value
        val message = when {
            !CardState.hasAccess(this) -> getString(R.string.localized_023)
            snapshot.connection == ConnectionState.ERROR -> getString(R.string.localized_024)
            snapshot.connection != ConnectionState.CONNECTED -> getString(R.string.localized_025)
            else -> {
                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(snapshot.updatedAt))
                getString(R.string.connected_updated, time)
            }
        }
        statusLabels.forEach {
            it.text = message
            it.contentDescription = message
            val color = when {
                snapshot.connection == ConnectionState.CONNECTED && CardState.hasAccess(this) -> GREEN
                else -> AMBER
            }
            val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(dp(8), dp(8)) }
            it.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            it.compoundDrawablePadding = dp(10)
        }
        if (::notificationStatus.isInitialized) {
            val connected = CardState.hasAccess(this) && snapshot.connection == ConnectionState.CONNECTED
            notificationStatus.text = when {
                !CardState.hasAccess(this) -> getString(R.string.localized_018)
                connected -> getString(R.string.localized_026)
                snapshot.connection == ConnectionState.ERROR -> getString(R.string.localized_027)
                else -> getString(R.string.localized_028)
            }
            notificationStatus.setTextColor(if (connected) GREEN else AMBER)
            notificationDot.background = circle(if (connected) GREEN else AMBER)
        }
    }

    private fun notificationAccessCard(): View {
        val card = surfaceCard()
        card.addView(text(getString(R.string.localized_029), 16f, INK, true), wrap())
        card.addView(text(getString(R.string.localized_030), 13f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(14))
        }, wrap())
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        notificationDot = View(this).apply { background = circle(AMBER) }
        row.addView(notificationDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        notificationStatus = text(getString(R.string.localized_031), 14f, AMBER, true).apply { setPadding(dp(9), 0, 0, 0) }
        row.addView(notificationStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(actionButton(getString(R.string.localized_032), false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, LinearLayout.LayoutParams(dp(112), dp(48)))
        card.addView(row, wrap())
        return card
    }

    private fun batteryCard(): View {
        val card = surfaceCard()
        card.addView(text(getString(R.string.localized_033), 16f, INK, true), wrap())
        card.addView(text(getString(R.string.localized_034), 13f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(14))
        }, wrap())
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        batteryDot = View(this).apply { background = circle(AMBER) }
        row.addView(batteryDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        batteryStatus = text(getString(R.string.localized_035), 14f, AMBER, true).apply { setPadding(dp(9), 0, dp(8), 0) }
        row.addView(batteryStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        batteryButton = actionButton(getString(R.string.localized_022), false) { requestBatteryExemption() }
        row.addView(batteryButton, LinearLayout.LayoutParams(dp(112), dp(48)))
        card.addView(row, wrap())
        return card
    }

    private fun widgetSettingsCard(): View {
        val groups = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scaleOptions = listOf(0 to getString(R.string.localized_036), 1 to getString(R.string.localized_037), 2 to getString(R.string.localized_038))
        groups.addView(settingsGroup(getString(R.string.localized_039), getString(R.string.localized_040)))
        val sizeCard = surfaceCard(compact = true)
        addSegmentedChoices(sizeCard, getString(R.string.localized_041), scaleOptions.map { (value, label) -> SegmentedChoice(ChoiceGlyph.SCALE, value, label) }, scaleOptions.indexOfFirst { it.first == settings.widgetScale }) {
            settings.widgetScale = scaleOptions[it].first
            renderPreview()
            updateWidget()
        }
        addSeparator(sizeCard)
        lineChoices = addSegmentedChoices(sizeCard, getString(R.string.localized_042), listOf(SegmentedChoice(ChoiceGlyph.TEXT_LINES, 2, getString(R.string.localized_043)), SegmentedChoice(ChoiceGlyph.TEXT_LINES, 3, getString(R.string.localized_044))), settings.maxTextLines - 2) {
            settings.maxTextLines = it + 2
            renderPreview()
            updateWidget()
        }
        groups.addView(sizeCard, cardMargins(bottom = 8))

        groups.addView(settingsGroup(getString(R.string.localized_045), getString(R.string.localized_046)))
        groups.addView(text(getString(R.string.privacy_settings_warning), 13f, getColor(R.color.ui_warning_bar_text)).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(getColor(R.color.ui_warning_bar), dp(14), getColor(R.color.ui_warning_bar_border))
        }, cardMargins(bottom = 8))
        val contentCard = surfaceCard(compact = true)
        addSegmentedChoices(contentCard, getString(R.string.localized_047), listOf(
            SegmentedChoice(ChoiceGlyph.TEXT_LINES, 3, getString(R.string.content_full)),
            SegmentedChoice(ChoiceGlyph.TEXT_LINES, 2, getString(R.string.content_sender)),
            SegmentedChoice(ChoiceGlyph.TEXT_LINES, 1, getString(R.string.content_app))), settings.contentMode) {
            settings.contentMode = it
            updateContentControls()
            renderPreview()
            NotificationListener.refreshSnapshot(this)
        }
        contentCard.addView(text(getString(R.string.content_modes_hint), 12f, MUTED), margin(top = 8))
        addSeparator(contentCard)
        contentCard.addView(switchRow(getString(R.string.compact_mode), getString(R.string.localized_048), settings.compactMode) {
            settings.compactMode = it
            renderPreview()
            updateWidget()
        }, wrap())
        addSeparator(contentCard)
        contentCard.addView(switchRow(getString(R.string.localized_049), getString(R.string.localized_050), settings.musicPillEnabled) {
            settings.musicPillEnabled = it
            renderPreview()
            NotificationListener.refreshSnapshot(this)
        }, wrap())
        groups.addView(contentCard, cardMargins(bottom = 8))

        groups.addView(settingsGroup(getString(R.string.localized_051), getString(R.string.localized_052)))
        val behaviorCard = surfaceCard(compact = true)
        addSegmentedChoices(behaviorCard, getString(R.string.localized_001), listOf(SegmentedChoice(ChoiceGlyph.ONGOING, 0, getString(R.string.localized_053)), SegmentedChoice(ChoiceGlyph.ONGOING, 1, getString(R.string.localized_054)), SegmentedChoice(ChoiceGlyph.ONGOING, 2, getString(R.string.localized_055))), settings.ongoingMode, subtitle = getString(R.string.ongoing_examples)) {
            settings.ongoingMode = it
            NotificationListener.refreshSnapshot(this)
            renderPreview()
        }
        addSeparator(behaviorCard)
        behaviorCard.addView(switchRow(getString(R.string.show_on_screen_on), getString(R.string.show_on_screen_on_hint), settings.showWhenScreenOn) {
            settings.showWhenScreenOn = it
            renderPreview()
            updateWidget()
        }, wrap())
        addSeparator(behaviorCard)
        addSegmentedChoices(behaviorCard, getString(R.string.localized_056), listOf(SegmentedChoice(ChoiceGlyph.BADGE_POSITION, 0, getString(R.string.localized_057)), SegmentedChoice(ChoiceGlyph.BADGE_POSITION, 1, getString(R.string.localized_058)), SegmentedChoice(ChoiceGlyph.BADGE_POSITION, 2, getString(R.string.localized_059))), settings.overflowBadgePosition) {
            settings.overflowBadgePosition = it
            renderPreview()
            updateWidget()
        }
        groups.addView(behaviorCard, cardMargins(bottom = 12))
        return groups
    }

    private fun settingsGroup(title: String, subtitle: String): View = sectionHeading(title, subtitle)


    private fun lockStarCard(): View {
        val card = surfaceCard()
        listOf(
            "1" to getString(R.string.localized_060),
            "2" to getString(R.string.lockstar_preview_enable),
            "3" to getString(R.string.localized_062),
            "4" to getString(R.string.lockstar_preview_disable)
        ).forEachIndexed { index, (number, caption) ->
            val row = LinearLayout(this).apply { gravity = Gravity.TOP; orientation = LinearLayout.HORIZONTAL }
            val numberView = TextView(this).apply {
                text = number
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(BLUE)
                background = circle(CONTROL)
            }
            row.addView(numberView, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(text(caption, 14f, INK).apply { setPadding(dp(11), dp(3), 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row, margin(bottom = if (index == 3) 0 else 14))
        }
        card.addView(actionButton(getString(R.string.open_lockstar), false) { openLockStar() }, margin(top = 16))
        return card
    }

    private fun openLockStar() {
        val lockStar = Intent("com.samsung.systemui.lockstar.action.SETTINGS")
            .setPackage("com.samsung.systemui.lockstar")
        try {
            startActivity(lockStar)
            return
        } catch (_: android.content.ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        val goodLock = packageManager.getLaunchIntentForPackage("com.samsung.android.goodlock")
        if (goodLock != null) {
            try {
                startActivity(goodLock)
                Toast.makeText(this, R.string.open_lockstar_from_goodlock, Toast.LENGTH_LONG).show()
                return
            } catch (_: android.content.ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, R.string.lockstar_unavailable, Toast.LENGTH_LONG).show()
    }

    private enum class ChoiceGlyph { TEXT_LINES, BADGE_POSITION, ONGOING, SCALE }
    private data class SegmentedChoice(val glyph: ChoiceGlyph, val index: Int, val label: String)

    private inner class ChoiceIcon(
        context: android.content.Context,
        private val kind: ChoiceGlyph,
        private val index: Int
    ) : View(context) {
        var active = false
            set(value) { field = value; invalidate() }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val color = if (active) BLUE else MUTED
            val cx = width / 2f
            val cy = height / 2f
            val d = density
            paint.color = color
            paint.strokeWidth = 1.8f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE

            when (kind) {
                ChoiceGlyph.SCALE -> {
                    val sizes = mapOf(3 to (8f to 9f), 0 to (11f to 12f), 1 to (15f to 16f), 2 to (18f to 20f))
                    val (w, h) = sizes[index.coerceIn(0, 3)] ?: (15f to 16f)
                    canvas.drawRoundRect(RectF(cx - w * d / 2f, cy - h * d / 2f, cx + w * d / 2f, cy + h * d / 2f), 3f * d, 3f * d, paint)
                    canvas.drawLine(cx - w * d * .28f, cy - 1f * d, cx + w * d * .28f, cy - 1f * d, paint)
                    canvas.drawLine(cx - w * d * .28f, cy + 3f * d, cx + w * d * .12f, cy + 3f * d, paint)
                }
                ChoiceGlyph.TEXT_LINES -> {
                    val count = index
                    val gap = if (count == 2) 6f else 5f
                    for (line in 0 until count) {
                        val y = cy + (line - (count - 1) / 2f) * gap * d
                        val halfWidth = if (line == count - 1) 5f else 7f
                        canvas.drawLine(cx - halfWidth * d, y, cx + halfWidth * d, y, paint)
                    }
                }
                ChoiceGlyph.BADGE_POSITION -> {
                    val bounds = RectF(cx - 10f * d, cy - 7f * d, cx + 10f * d, cy + 7f * d)
                    canvas.drawRoundRect(bounds, 4f * d, 4f * d, paint)
                    val badgeX = cx + (index - 1) * 6f * d
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(badgeX, cy, 2.4f * d, paint)
                }
                ChoiceGlyph.ONGOING -> drawOngoingGlyph(canvas, cx, cy, d, color)
            }
        }

        private fun drawOngoingGlyph(canvas: Canvas, cx: Float, cy: Float, d: Float, color: Int) {
            paint.color = color
            paint.style = Paint.Style.STROKE
            when (index) {
                0 -> {
                    canvas.drawRoundRect(RectF(cx - 9f * d, cy - 6f * d, cx + 9f * d, cy + 6f * d), 3f * d, 3f * d, paint)
                    canvas.drawLine(cx - 5f * d, cy - 1f * d, cx + 5f * d, cy - 1f * d, paint)
                    canvas.drawLine(cx - 5f * d, cy + 3f * d, cx + 2f * d, cy + 3f * d, paint)
                }
                1 -> {
                    canvas.drawOval(RectF(cx - 9f * d, cy - 5f * d, cx + 9f * d, cy + 5f * d), paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, 2.3f * d, paint)
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(cx - 8f * d, cy - 8f * d, cx + 8f * d, cy + 8f * d, paint)
                }
                else -> {
                    canvas.drawRoundRect(RectF(cx - 5f * d, cy - 9f * d, cx + 9f * d, cy + 5f * d), 3f * d, 3f * d, paint)
                    canvas.drawRoundRect(RectF(cx - 9f * d, cy - 5f * d, cx + 5f * d, cy + 9f * d), 3f * d, 3f * d, paint)
                    canvas.drawLine(cx - 5f * d, cy, cx + 1f * d, cy, paint)
                }
            }
        }
    }

    private fun addSegmentedChoices(
        parent: LinearLayout,
        title: String,
        choices: List<SegmentedChoice>,
        selected: Int,
        subtitle: String? = null,
        changed: (Int) -> Unit
    ): ViewGroup {
        parent.addView(text(title, 15f, INK, true), wrap())
        if (subtitle != null) parent.addView(text(subtitle, 12f, MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        }, wrap())
        val buttons = mutableListOf<LinearLayout>()
        val icons = mutableListOf<ChoiceIcon>()
        fun refreshSelection(index: Int) {
            buttons.forEachIndexed { buttonIndex, button ->
                val active = buttonIndex == index
                button.isSelected = active
                if (android.os.Build.VERSION.SDK_INT >= 30) button.stateDescription = if (active) getString(R.string.localized_014) else getString(R.string.localized_015)
                button.background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(38, 120, 185, 255)),
                    rounded(if (active) CONTROL else Color.TRANSPARENT, dp(16), null),
                    null
                )
                icons[buttonIndex].active = active
                (button.getChildAt(1) as TextView).setTextColor(if (active) BLUE else INK)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        choices.forEachIndexed { index, choice ->
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                isClickable = true
                isFocusable = true
                contentDescription = choice.label
                val icon = ChoiceIcon(this@MainActivity, choice.glyph, choice.index)
                icons += icon
                addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(text(choice.label, 13f, INK).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                }
                setOnClickListener {
                    refreshSelection(index)
                    changed(index)
                }
            }
            buttons += button
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
        parent.addView(row, margin(top = 6))
        refreshSelection(selected.coerceIn(choices.indices))
        return row
    }

    private fun updateContentControls() {
        lineChoices?.let { group ->
            val enabled = settings.contentMode == 0
            group.alpha = if (enabled) 1f else .4f
            for (index in 0 until group.childCount) group.getChildAt(index).isEnabled = enabled
        }
    }

    private fun switchRow(title: String, subtitle: String, checked: Boolean, changed: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 16f, INK, false), wrap())
        labels.addView(text(subtitle, 12f, MUTED).apply { setPadding(0, dp(3), 0, 0) }, wrap())
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = OneUiSwitch(this).apply {
            contentDescription = title
            minWidth = dp(48)
            minHeight = dp(48)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.minimumHeight = dp(56)
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return row
    }

    private fun sectionHeading(title: String, subtitle: String): View {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(10)) }
        group.addView(text(title, 16f, INK, true), wrap())
        group.addView(text(subtitle, 12f, MUTED).apply { setPadding(0, dp(4), 0, 0) }, wrap())
        return group
    }

    private fun surfaceCard(compact: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(if (compact) 14 else 18), dp(if (compact) 12 else 18), dp(if (compact) 14 else 18), dp(if (compact) 12 else 18))
        background = rounded(SURFACE, dp(28), null)
    }

    private fun actionButton(title: String, primary: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        val fill = if (primary) BLUE else CONTROL
        val ink = if (primary) Color.WHITE else BLUE
        val shape = rounded(fill, dp(24), null)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(30, 32, 74, 128)), shape, null)
        setTextColor(ink)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun addSeparator(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(LINE) }, margin(top = 10, bottom = 10).apply { height = dp(1) })
    }

    private fun previewCard(fitHeight: Boolean = false): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(text(getString(R.string.preview_title), 16f, INK, true), margin(bottom = 12))
        val modeSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(CONTROL, dp(24), null)
        }
        previewExamplesButton = previewModeButton(getString(R.string.localized_064)) { selectPreviewMode(PREVIEW_EXAMPLES) }
        previewRealButton = previewModeButton(getString(R.string.preview_personal)) { selectPreviewMode(PREVIEW_REAL) }
        modeSelector.addView(previewExamplesButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        modeSelector.addView(previewRealButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(modeSelector, margin(bottom = 10))

        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, AodCardsWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            if (previewWidgetId !in ids) previewWidgetId = ids.first()
            // A selector only helps when there is an actual choice of widget instances.
            if (ids.size > 1) card.addView(Spinner(this).apply {
                contentDescription = getString(R.string.localized_065)
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    ids.mapIndexed { index, id ->
                        val (w, h) = WidgetRenderer.sizeFor(manager.getAppWidgetOptions(id))
                        getString(R.string.preview_widget_size, index + 1, w, if (h > 0) h.toString() else getString(R.string.auto_size))
                    })
                setSelection(ids.indexOf(previewWidgetId))
                onItemSelectedListener = listener { position ->
                    previewWidgetId = ids[position]
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("preview_widget", previewWidgetId).apply()
                    renderPreview()
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val host = WidgetPreviewStage(this)
        previewHost = host
        if (fitHeight) {
            card.addView(FrameLayout(this).apply {
                addView(host, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else card.addView(host, wrap())
        updatePreviewModeButtons()
        renderPreview()
        return card
    }

    private fun forceRefreshNotifications() {
        val enabled = CardState.hasAccess(this)
        if (!enabled) {
            Toast.makeText(this, getString(R.string.localized_066), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        NotificationListener.forceRefresh(this)
        Toast.makeText(this, getString(R.string.localized_067), Toast.LENGTH_SHORT).show()
    }

    private fun previewModeButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun selectPreviewMode(mode: Int) {
        if (previewMode == mode) return
        previewMode = mode
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("preview_mode", mode).apply()
        updatePreviewModeButtons()
        renderPreview()
    }

    private fun updatePreviewModeButtons() {
        if (!::previewExamplesButton.isInitialized || !::previewRealButton.isInitialized) return
        listOf(previewExamplesButton to (previewMode == PREVIEW_EXAMPLES), previewRealButton to (previewMode == PREVIEW_REAL)).forEach { (button, selected) ->
            button.isSelected = selected
            if (android.os.Build.VERSION.SDK_INT >= 30) button.stateDescription = if (selected) getString(R.string.localized_014) else getString(R.string.localized_015)
            button.setTextColor(if (selected) INK else MUTED)
            button.background = if (selected) rounded(SURFACE, dp(22), null) else ColorDrawable(Color.TRANSPARENT)
        }
    }

    private fun updatePreviewSecurity() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (previewMode == PREVIEW_REAL) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun renderPreview() {
        updatePreviewSecurity()
        val stage = previewHost ?: return
        // Read the latest snapshot when this page becomes visible again.
        if (::tabPages.isInitialized && tabPages[TAB_CUSTOMIZE].visibility != View.VISIBLE) return
        val snapshot = CardState.value
        val sample = previewMode == PREVIEW_EXAMPLES
        val cards = if (sample) samplePreviewCards() else snapshot.cards
        val music = if (sample && settings.musicPillEnabled) MusicTrack("com.google.android.apps.youtube.music", getString(R.string.demo_music_title), getString(R.string.demo_music_artist), null)
            else if (settings.musicPillEnabled) snapshot.music else null
        if (!sample && cards.isEmpty() && music == null) {
            val message = when {
                !CardState.hasAccess(this) -> getString(R.string.localized_068)
                snapshot.connection == ConnectionState.ERROR -> getString(R.string.localized_069)
                snapshot.connection != ConnectionState.CONNECTED -> getString(R.string.localized_070)
                snapshot.blockedCount > 0 -> getString(R.string.localized_071)
                else -> getString(R.string.localized_072)
            }
            if (lastPreviewMessage != message || lastPreviewKey != null) {
                stage.showMessage(message)
                previewRenderCount++
            }
            lastPreviewMessage = message
            lastPreviewKey = null
            return
        }
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, AodCardsWidgetProvider::class.java))
        val id = previewWidgetId.takeIf { it in ids } ?: ids.firstOrNull()
        val (width, height) = id?.let { WidgetRenderer.sizeFor(manager.getAppWidgetOptions(it)) } ?: (WidgetRenderer.DEFAULT_WIDTH_DP to WidgetRenderer.DEFAULT_HEIGHT_DP)
        val key = WidgetUpdateKey.create(this, cards, music, settings, width, height, CardState.hasAccess(this))
        if (lastPreviewKey == key && lastPreviewMessage == null) return
        val views = WidgetRenderer.createViews(this, cards, settings, music,
            WidgetRows.scaleFactor(settings.widgetScale), width, height)
        stage.show(views, width)
        lastPreviewKey = key
        lastPreviewMessage = null
        previewRenderCount++
    }

    private fun samplePreviewCards(): List<NotificationCard> {
        val now = demoTime
        val cards = mutableListOf(
            NotificationCard(
                key = "example-telegram-summary", packageName = "org.telegram.messenger", appName = "Telegram",
                senderName = null, title = getString(R.string.localized_073), text = getString(R.string.localized_074),
                postTime = now, ongoing = false, previewImage = null, senderImage = demoSenderAvatar,
                groupCount = 2
            ),
            NotificationCard(
                key = "example-github-sms", packageName = "com.google.android.apps.messaging", appName = getString(R.string.localized_075),
                senderName = "GitHub", title = "", text = getString(R.string.localized_076),
                postTime = now - 60_000, ongoing = false, previewImage = null, senderImage = null
            ),
            NotificationCard(
                key = "example-gmail", packageName = "com.google.android.gm", appName = "Gmail",
                senderName = "Taylor", title = getString(R.string.localized_077), text = getString(R.string.localized_078),
                postTime = now - 120_000, ongoing = false, previewImage = null, senderImage = null
            ),
            NotificationCard(
                key = "example-calendar", packageName = "com.google.android.calendar", appName = getString(R.string.localized_079),
                senderName = null, title = getString(R.string.localized_080), text = getString(R.string.localized_081),
                postTime = now - 180_000, ongoing = false, previewImage = null, senderImage = null
            )
        )
        when (settings.ongoingMode) {
            0 -> cards.add(0, NotificationCard(
                key = "example-ongoing", packageName = "com.spotify.music", appName = "Spotify",
                senderName = null, title = "Daft Punk", text = "One More Time",
                postTime = now - 300_000, ongoing = true, previewImage = null, senderImage = null
            ))
            2 -> cards.add(0, NotificationCard(
                key = "example-ongoing-group", packageName = "com.spotify.music", appName = "Spotify",
                senderName = null, title = "", text = getString(R.string.localized_082),
                postTime = now - 300_000, ongoing = true, previewImage = null, senderImage = null,
                groupCount = 4, isOngoingGroup = true
            ))
        }
        return cards
    }

    private fun requestBatteryExemption() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            openBatterySettings()
            return
        }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        try { startActivity(request) } catch (_: Exception) { openBatterySettings() }
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try { startActivity(intent) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }


    private fun updateWidget() {
        AodCardsWidgetProvider.renderAll(this)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = true
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }
    private fun cardMargins(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun listener(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
    }

    companion object {
        private const val TAB_CUSTOMIZE = 0
        private const val TAB_ABOUT = 1
        private const val TAB_FILTER = 2
        private const val PREVIEW_EXAMPLES = 0
        private const val PREVIEW_REAL = 1

    }
}
