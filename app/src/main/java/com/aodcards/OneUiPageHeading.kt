package com.aodcards

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

/** Shared typography and spacing for all three tab headings. */
class OneUiPageHeading(context: Context, title: String, subtitle: String, onAbout: (() -> Unit)? = null) : LinearLayout(context) {
    init {
        fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
        val wide = resources.configuration.screenWidthDp >= 600
        orientation = VERTICAL
        setPadding(0, dp(16), 0, dp(20))
        minimumHeight = dp(108)
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = title
            textSize = if (wide) 24f else 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(context.getColor(R.color.ui_text))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (onAbout != null) titleRow.addView(TextView(context).apply {
            text = "ⓘ"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(context.getColor(R.color.ui_accent))
            contentDescription = context.getString(R.string.localized_016)
            setOnClickListener { onAbout() }
        }, LayoutParams(dp(48), dp(48)))
        addView(titleRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = subtitle
            textSize = 14f
            setTextColor(context.getColor(R.color.ui_muted))
            setPadding(0, dp(6), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
