package com.aodcards

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Switch

/** Native checkable controls with the dimensions and neutral surfaces of One UI. */
class OneUiSwitch(context: Context) : Switch(context) {
    init {
        fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
        fun track(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(11).toFloat()
            setSize(dp(36), dp(22))
            setColor(color)
        }
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(context.getColor(R.color.ui_accent)))
            addState(intArrayOf(), track(context.getColor(R.color.ui_switch_off)))
        }
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(dp(18), dp(18))
            setColor(android.graphics.Color.WHITE)
        }
        thumbDrawable = android.graphics.drawable.InsetDrawable(thumb, 0, dp(2), 0, dp(2))
        switchMinWidth = dp(36)
        showText = false
        splitTrack = false
        minWidth = dp(48)
        minHeight = dp(48)
    }
}
