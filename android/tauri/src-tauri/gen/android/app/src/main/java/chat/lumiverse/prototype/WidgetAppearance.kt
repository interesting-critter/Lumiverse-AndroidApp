package chat.lumiverse.test

import android.content.Context
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Long-press-only widget background appearance: opacity + corner radius.
 *
 * There are no in-app/frontend controls for these; they are configured from
 * [WidgetAppearanceConfigActivity] via the widget's long-press "Configure"
 * entry (android.appwidget.action.APPWIDGET_CONFIGURE).
 *
 * Theme safety: colors still come from the encrypted enrollment palette
 * (see NativeCharacterWidgets.configure). Opacity is applied only as the
 * alpha channel at render time; the palette RGB channels, foreground, and
 * accent are never modified.
 */
object WidgetAppearance {
    const val PREFS = "lumiverse-widget-appearance"
    const val KEY_OPACITY_PCT = "widget_opacity_pct"
    const val KEY_CORNER_RADIUS_DP = "widget_corner_radius_dp"

    const val DEFAULT_OPACITY_PCT = 100
    const val DEFAULT_CORNER_RADIUS_DP = 24
    const val MAX_OPACITY_PCT = 100
    const val MAX_CORNER_RADIUS_DP = 64

    /**
     * The smallest widget bucket is 110dp wide, so a corner radius above
     * half of that would be clamped visually anyway. Clamp rendering to
     * 55dp while still persisting the user's full 0..64 choice.
     */
    const val MAX_EFFECTIVE_RADIUS_DP = 55

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun opacityPct(context: Context): Int =
        prefs(context).getInt(KEY_OPACITY_PCT, DEFAULT_OPACITY_PCT).coerceIn(0, MAX_OPACITY_PCT)

    fun cornerRadiusDp(context: Context): Int =
        prefs(context).getInt(KEY_CORNER_RADIUS_DP, DEFAULT_CORNER_RADIUS_DP)
            .coerceIn(0, MAX_CORNER_RADIUS_DP)

    fun set(context: Context, opacityPct: Int, cornerRadiusDp: Int) {
        prefs(context).edit()
            .putInt(KEY_OPACITY_PCT, opacityPct.coerceIn(0, MAX_OPACITY_PCT))
            .putInt(KEY_CORNER_RADIUS_DP, cornerRadiusDp.coerceIn(0, MAX_CORNER_RADIUS_DP))
            .apply()
    }

    /**
     * Returns [opaqueBackground] with only its alpha replaced by [opacityPct].
     * RGB channels are preserved exactly.
     */
    fun tintedBackground(opaqueBackground: Int, opacityPct: Int): Int {
        val alpha = (opacityPct.coerceIn(0, MAX_OPACITY_PCT) * 255f / 100f).roundToInt()
        return Color.argb(alpha, Color.red(opaqueBackground), Color.green(opaqueBackground), Color.blue(opaqueBackground))
    }

    /** Radius actually used for rendering, clamped to the smallest widget bucket. */
    fun effectiveRadiusDp(radiusDp: Int): Int =
        radiusDp.coerceIn(0, MAX_CORNER_RADIUS_DP).coerceAtMost(MAX_EFFECTIVE_RADIUS_DP)
}
