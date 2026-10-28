package com.aodcards

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import java.util.concurrent.Executors

/** Filter changes are persisted immediately; no draft can be lost on recreation. */
class AppFilterView(context: Context, private val settings: SettingsRepository, private val changed: () -> Unit) : LinearLayout(context) {
    private data class App(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?, val active: Boolean)
    private val executor = Executors.newSingleThreadExecutor()
    private var apps = emptyList<App>()
    private var shown = emptyList<App>()
    private var query = ""
    private var section = 0
    private val summary = label(context.getString(R.string.filter_loading), 13f).apply { setTextColor(context.getColor(R.color.ui_muted)) }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val app = shown[position]
            val row = (recycled as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { setColor(context.getColor(R.color.ui_surface)); cornerRadius = dp(28).toFloat() }
                addView(ImageView(context), LayoutParams(dp(36), dp(36)))
                addView(label("", 16f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                addView(CheckBox(context).apply {
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    buttonTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(context.getColor(R.color.ui_accent), context.getColor(R.color.ui_muted)))
                }, LayoutParams(dp(48), dp(48)))
            }
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (row.getChildAt(1) as TextView).text = app.label
            val blocked = app.pkg in settings.blockedPackages
            (row.getChildAt(2) as CheckBox).isChecked = blocked
            row.contentDescription = context.getString(R.string.filter_accessibility, app.label, context.getString(if (blocked) R.string.notifications_hidden else R.string.notifications_allowed))
            if (android.os.Build.VERSION.SDK_INT >= 30) row.stateDescription = if (blocked) context.getString(R.string.localized_083) else context.getString(R.string.localized_084)
            return row
        }
    }

    private val undo by lazy { control(context.getString(R.string.filter_undo)).apply { visibility = GONE } }
    private var loadedPackages = emptySet<String>()
    private var loadedActive = emptySet<String>()

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(16), dp(12), dp(10))
        addView(OneUiPageHeading(context, context.getString(R.string.filter_title),
            context.getString(R.string.filter_hint)), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val searchRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; background = shape(R.color.ui_control) }
        val search = EditText(context).apply {
            hint = context.getString(R.string.filter_search)
            setSingleLine()
            textSize = 16f
            setTextColor(context.getColor(R.color.ui_text))
            setHintTextColor(context.getColor(R.color.ui_muted))
            contentDescription = context.getString(R.string.filter_search)
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(14), 0, dp(14), 0)
        }
        searchRow.addView(search, LayoutParams(0, dp(48), 1f))
        searchRow.addView(control("×").apply {
            contentDescription = context.getString(R.string.filter_clear)
            setOnClickListener { search.setText("") }
        }, LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        addView(searchRow)
        val sections = LinearLayout(context)
        val sectionButtons = mutableListOf<TextView>()
        fun updateSections() {
            sectionButtons.forEachIndexed { index, button ->
                val selected = index == section
                button.isSelected = selected
                button.setTextColor(context.getColor(if (selected) R.color.ui_text else R.color.ui_muted))
                button.background = shape(if (selected) R.color.ui_surface else R.color.ui_control)
                if (android.os.Build.VERSION.SDK_INT >= 30) button.stateDescription = if (selected) context.getString(R.string.localized_014) else context.getString(R.string.localized_015)
            }
        }
        listOf(R.string.filter_all, R.string.filter_active, R.string.filter_blocked).forEachIndexed { index, title ->
            val button = control(context.getString(title)).apply {
                textSize = 13f
                setOnClickListener { section = index; updateSections(); filter() }
            }
            sectionButtons += button
            sections.addView(button, LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(6) })
        }
        updateSections()
        addView(sections, LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        addView(summary, LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val list = ListView(context).apply {
            setPadding(0, 0, 0, dp(80))
            clipToPadding = false
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(4)
            adapter = this@AppFilterView.adapter
            setOnItemClickListener { _, _, position, _ ->
                val pkg = shown[position].pkg
                val previous = settings.blockedPackages
                settings.blockedPackages = previous.toMutableSet().apply { if (!add(pkg)) remove(pkg) }
                filter()
                changed()
                undo.visibility = VISIBLE
                undo.setOnClickListener {
                    settings.blockedPackages = previous
                    undo.visibility = GONE
                    filter()
                    changed()
                }
            }
        }
        addView(list, LayoutParams(-1, 0, 1f))
        addView(undo, LayoutParams(-1, dp(48)).apply { bottomMargin = dp(80) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                filter()
            }
        })
        reload()
    }

    fun reload() {
        val active = CardState.value.sourcePackages
        val packages = active + settings.blockedPackages
        if (apps.isNotEmpty() && packages == loadedPackages && active == loadedActive) return
        loadedPackages = packages
        loadedActive = active
        executor.execute {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(0).filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName }.toSet()
            val loaded = (installed + packages).filter { it != context.packageName }.map { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                App(pkg, info?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                    runCatching { pm.getApplicationIcon(pkg) }.getOrNull(), pkg in active)
            }.sortedBy { it.label.lowercase() }
            post { apps = loaded; filter() }
        }
    }

    private fun filter() {
        val blocked = settings.blockedPackages
        shown = apps.filter { app ->
            (section == 0 || section == 1 && app.active || section == 2 && app.pkg in blocked) &&
                (app.label.contains(query, true) || app.pkg.contains(query, true))
        }
        summary.text = if (shown.isEmpty()) context.getString(R.string.filter_empty) else context.getString(R.string.filter_summary, blocked.size, shown.size)
        adapter.notifyDataSetChanged()
    }

    fun close() { executor.shutdownNow() }
    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(context.getColor(color))
        cornerRadius = dp(24).toFloat()
    }
    private fun control(value: String) = label(value, 14f).apply {
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.ui_accent))
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x2678B9FF), shape(R.color.ui_control), null)
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48)
    }
    private fun label(value: String, size: Float) = TextView(context).apply { text = value; textSize = size; setTextColor(context.getColor(R.color.ui_text)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
