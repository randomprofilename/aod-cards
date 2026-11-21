package com.aodcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache

/** A dark accent from the player's app icon, independent of album/video artwork. */
internal object MusicCardColor {
    private const val NEUTRAL = 0xD9171719.toInt()
    private val colors = LruCache<String, Int>(64)

    fun forPackage(context: Context, packageName: String): Int = colors.get(packageName) ?: run {
        val color = runCatching {
            val icon = context.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            try {
                icon.setBounds(0, 0, bitmap.width, bitmap.height)
                icon.draw(Canvas(bitmap))
                fromIcon(bitmap)
            } finally { bitmap.recycle() }
        }.getOrDefault(NEUTRAL)
        colors.put(packageName, color)
        color
    }

    internal fun fromIcon(bitmap: Bitmap): Int {
        val weights = FloatArray(24)
        val hues = FloatArray(24)
        val saturations = FloatArray(24)
        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            Color.colorToHSV(pixel, hsv)
            // Ignore transparent padding and neutral white/black logo backgrounds.
            if (Color.alpha(pixel) < 128 || hsv[1] < .2f || hsv[2] < .18f) continue
            val bucket = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            val weight = Color.alpha(pixel) / 255f * hsv[1]
            weights[bucket] += weight
            hues[bucket] += hsv[0] * weight
            saturations[bucket] += hsv[1] * weight
        }
        val dominant = weights.indices.maxBy { weights[it] }
        val weight = weights[dominant]
        if (weight == 0f) return NEUTRAL
        // AOD dims the whole widget again. Keep an opaque, recognisable accent
        // instead of a nearly black translucent tint, while limiting luminance
        // across hues (green/yellow need more darkening than red/blue).
        val accent = floatArrayOf(hues[dominant] / weight,
            (saturations[dominant] / weight).coerceIn(.65f, .90f), .62f)
        var color = Color.HSVToColor(accent)
        while (luminance(color) > .08) {
            accent[2] *= .95f
            color = Color.HSVToColor(accent)
        }
        return color
    }

    private fun luminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
        }
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) +
            .0722 * linear(Color.blue(color))
    }
}
