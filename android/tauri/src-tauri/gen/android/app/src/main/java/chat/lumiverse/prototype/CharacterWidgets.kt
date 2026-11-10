package chat.lumiverse.prototype

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

open class CharacterWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        CharacterWidgets.renderAll(context)
        CharacterWidgetWorker.request(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        CharacterWidgets.renderAll(context)
    }
    override fun onDeleted(context: Context, ids: IntArray) { CharacterWidgetWorker.schedule(context) }
    override fun onDisabled(context: Context) { CharacterWidgetWorker.schedule(context) }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) { onUpdate(context, AppWidgetManager.getInstance(context), newIds) }
}
class RecentCharactersWidget : CharacterWidgetProvider()
class FavoriteCharactersWidget : CharacterWidgetProvider()

object CharacterWidgets {
    private val providers = listOf(RecentCharactersWidget::class.java, FavoriteCharactersWidget::class.java)
    fun hasWidgets(context: Context) = providers.any { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    fun renderAll(context: Context) = synchronized(CharacterWidgetStore) {
        val manager = AppWidgetManager.getInstance(context)
        val active = CharacterWidgetStore.current(context)
        val snapshot = active?.let { CharacterWidgetStore.snapshot(context, it) }
        providers.forEachIndexed { index, provider ->
            manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                val bitmaps = mutableMapOf<String, Bitmap>()
                fun layout(columns: Int, rows: Int) = layout(context, active, snapshot, index == 1, columns, rows, bitmaps)
                val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                    SizeF(110f, 110f) to layout(2, 2), SizeF(250f, 110f) to layout(4, 2), SizeF(250f, 270f) to layout(4, 4),
                )) else {
                    val options = manager.getAppWidgetOptions(id)
                    val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
                    val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
                    layout(if (width >= 250) 4 else 2, if (width >= 250 && height >= 270) 4 else 2)
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
    private fun tap(context: Context, raw: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(raw ?: "lumiverse://widget/open")
            if (raw != null) putExtra("lumiverse_widget", raw)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    internal fun layout(context: Context, active: org.json.JSONObject?, snapshot: WidgetSnapshot?, favorites: Boolean,
                       columns: Int, rows: Int, bitmaps: MutableMap<String, Bitmap>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.character_widget)
        val palette = active?.optJSONObject("palette")
        fun color(key: String, fallback: String) = try { Color.parseColor(palette?.optString(key, fallback) ?: fallback) } catch (_: Exception) { Color.parseColor(fallback) }
        val background = color("background", "#171321")
        val foreground = color("foreground", "#f5f0ff")
        val accent = color("accent", "#bca6ff")
        // Resource shapes keep their radius in dp at the host's actual size.
        // No background bitmaps enter the shared responsive RemoteViews budget.
        views.setImageViewResource(R.id.widget_background, R.drawable.widget_background_radii)
        views.setInt(R.id.widget_background, "setImageLevel", WidgetAppearance.cornerRadiusDp(context))
        views.setInt(R.id.widget_background, "setColorFilter", background)
        views.setInt(R.id.widget_background, "setImageAlpha", Color.alpha(
            WidgetAppearance.tintedBackground(background, WidgetAppearance.opacityPct(context))))
        views.setTextViewText(R.id.widget_title, context.getString(if (favorites) R.string.widget_favorites else R.string.widget_recent))
        views.setTextColor(R.id.widget_title, foreground)
        views.setImageViewResource(R.id.widget_symbol, if (favorites) R.drawable.widget_ic_favorite else R.drawable.widget_ic_recent)
        views.setInt(R.id.widget_symbol, "setColorFilter", accent)
        views.setOnClickPendingIntent(R.id.widget_header, tap(context))
        views.setOnClickPendingIntent(R.id.widget_empty, tap(context))
        val items = (if (favorites) snapshot?.favorites else snapshot?.recent).orEmpty()
        views.setViewVisibility(R.id.widget_grid, if (items.isEmpty()) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
        views.setTextColor(R.id.widget_empty, foreground)
        views.setTextViewText(R.id.widget_empty, context.getString(when {
            active == null -> R.string.widget_sign_in
            snapshot == null -> R.string.widget_refresh
            favorites -> R.string.widget_no_favorites
            else -> R.string.widget_no_recent
        }))
        views.removeAllViews(R.id.widget_grid)
        if (items.isEmpty() || active == null) return views
        for (row in 0 until rows) {
            val rowViews = RemoteViews(context.packageName, R.layout.character_widget_row)
            for (column in 0 until columns) {
                val cell = RemoteViews(context.packageName, R.layout.character_widget_card)
                val item = items.getOrNull(row * columns + column)
                if (item == null) cell.setViewVisibility(R.id.widget_card, View.INVISIBLE)
                else {
                    // Portraits stay on the opaque palette color: RGB_565 has no
                    // alpha channel, and opacity applies only to the widget background.
                    val bitmap = bitmaps.getOrPut(item.characterId) { portrait(context, item, background, accent) }
                    cell.setImageViewBitmap(R.id.widget_portrait, bitmap)
                    cell.setTextViewText(R.id.widget_name, item.name)
                    cell.setContentDescription(R.id.widget_card, context.getString(R.string.widget_open_character, item.name))
                    val raw = "lumiverse://widget/${active.getString("registrationId")}/${if (favorites) "favorites" else "recent"}/${item.characterId}"
                    cell.setOnClickPendingIntent(R.id.widget_card, tap(context, raw))
                }
                rowViews.addView(R.id.widget_row, cell)
            }
            views.addView(R.id.widget_grid, rowViews)
        }
        return views
    }
    private fun portrait(context: Context, item: WidgetCharacter, background: Int, accent: Int): Bitmap {
        // Sixteen RGB_565 thumbnails fit comfortably below the RemoteViews bitmap budget.
        val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bytes = CharacterWidgetStore.image(context, item.characterId)
        val source = bytes?.takeIf { CharacterWidgetWorker.boundedJPEG(it) }?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }
        if (source != null) {
            val scale = maxOf(96f / source.width, 128f / source.height)
            val matrix = Matrix().apply { setScale(scale, scale); postTranslate((96 - source.width * scale) / 2, (128 - source.height * scale) / 2) }
            paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            canvas.drawRoundRect(RectF(0f, 0f, 96f, 128f), 10f, 10f, paint)
            source.recycle()
        } else {
            paint.color = Color.rgb((Color.red(background) * 4 + Color.red(accent)) / 5,
                (Color.green(background) * 4 + Color.green(accent)) / 5, (Color.blue(background) * 4 + Color.blue(accent)) / 5)
            canvas.drawRoundRect(RectF(0f, 0f, 96f, 128f), 10f, 10f, paint)
            paint.color = accent
            canvas.drawCircle(48f, 45f, 14f, paint)
            canvas.drawRoundRect(RectF(24f, 63f, 72f, 96f), 18f, 18f, paint)
        }
        return bitmap
    }
}
