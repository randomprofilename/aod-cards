package com.aodcards

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import kotlin.math.ceil

/** Shows the complete widget at the available inline width. */
class WidgetPreviewStage(context: Context) : FrameLayout(context) {
    private var sourceWidth = 0
    internal var contentScale = 1f
        private set

    init {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), context.getColor(R.color.ui_border))
        }
        clipToOutline = true
    }

    fun show(views: RemoteViews, widthDp: Int) {
        removeAllViews()
        sourceWidth = dp(widthDp)
        addView(views.apply(context, this))
        requestLayout()
    }

    fun showMessage(message: String) {
        removeAllViews()
        sourceWidth = 0
        addView(TextView(context).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.ui_muted))
            setPadding(dp(12), dp(16), dp(12), dp(16))
            minHeight = dp(112)
        })
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(48))
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(width, resolveSize(dp(112), heightMeasureSpec))
            return
        }
        val childWidth = sourceWidth.takeIf { it > 0 } ?: availableWidth
        child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val childHeight = child.measuredHeight.coerceAtLeast(1)
        child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
        val parentLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(1)
        val widthScale = availableWidth.toFloat() / childWidth
        contentScale = minOf(widthScale, parentLimit.toFloat() / childHeight)
        val desiredHeight = ceil(childHeight * contentScale).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val scaledWidth = child.measuredWidth * contentScale
        val scaledHeight = child.measuredHeight * contentScale
        val x = paddingLeft + ((width - paddingLeft - paddingRight - scaledWidth) / 2).toInt()
        val y = paddingTop + ((height - paddingTop - paddingBottom - scaledHeight) / 2).toInt()
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = contentScale
        child.scaleY = contentScale
        child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
