package com.aodcards

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import java.io.FileNotFoundException

/** Only synthetic demo pixels cross this provider; it never reads notification state. */
class EditorPreviewProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.path != "/preview") throw FileNotFoundException()
        val width = uri.getQueryParameter("width")?.toIntOrNull() ?: throw FileNotFoundException()
        val height = uri.getQueryParameter("height")?.toIntOrNull() ?: throw FileNotFoundException()
        if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 4L * 1024 * 1024)
            throw FileNotFoundException()
        val appContext = checkNotNull(context)
        // Capture Binder identity before the pipe writer moves to another thread.
        val editor = isEditorCaller(appContext, Binder.getCallingUid())
        return openPipeHelper(uri, "image/png", null, editor) { output, _, _, _, showDemo ->
            val bitmap = render(appContext, width, height, showDemo == true)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }

    companion object {
        private const val EDITOR = "com.samsung.android.app.dressroom"
        internal fun isEditorCaller(context: Context, uid: Int): Boolean = runCatching {
            context.packageManager.getApplicationInfo(EDITOR, 0).uid == uid
        }.getOrDefault(false)

        internal fun uri(context: Context, width: Int, height: Int): Uri = Uri.Builder()
            .scheme("content").authority(context.packageName + ".editorpreview").path("preview")
            .appendQueryParameter("width", width.toString())
            .appendQueryParameter("height", height.toString()).build()

        internal fun demoViews(context: Context, widthDp: Int, heightDp: Int): RemoteViews {
            val settings = SettingsRepository(context)
            fun card(key: String, pkg: String, app: String, sender: String?, title: String, text: String) =
                NotificationCard(key, pkg, app, sender, title, text, 0L, false, null, null,
                    displayTime = null)
            val cards = listOf(
                card("demo-telegram", "org.telegram.messenger", "Telegram", null,
                    context.getString(R.string.localized_073), context.getString(R.string.localized_074)),
                card("demo-github", "com.google.android.apps.messaging", context.getString(R.string.localized_075),
                    "GitHub", "", context.getString(R.string.localized_076)),
                card("demo-gmail", "com.google.android.gm", "Gmail", "Taylor",
                    context.getString(R.string.localized_077), context.getString(R.string.localized_078)),
                card("demo-calendar", "com.google.android.calendar", context.getString(R.string.localized_079), null,
                    context.getString(R.string.localized_080), context.getString(R.string.localized_081))
            )
            val music = if (settings.musicPillEnabled) MusicTrack("com.google.android.apps.youtube.music",
                context.getString(R.string.demo_music_title), context.getString(R.string.demo_music_artist), null) else null
            return WidgetRenderer.createViews(context, cards, settings, music,
                WidgetRows.scaleFactor(settings.widgetScale), widthDp, heightDp)
        }

        internal fun render(context: Context, width: Int, height: Int, editor: Boolean): Bitmap {
            // Live SystemUI and all other callers receive no notification/demo pixels.
            if (!editor) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val displayDensity = context.resources.displayMetrics.density
            val widthDp = (width / displayDensity + .5f).toInt().coerceAtLeast(1)
            val heightDp = (height / displayDensity + .5f).toInt().coerceAtLeast(1)
            val view = demoViews(context, widthDp, heightDp).apply(context, FrameLayout(context))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                density = Bitmap.DENSITY_NONE
                view.draw(Canvas(this))
            }
        }
    }
}
