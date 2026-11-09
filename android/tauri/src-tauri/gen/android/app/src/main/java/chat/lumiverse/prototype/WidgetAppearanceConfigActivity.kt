package chat.lumiverse.prototype

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Long-press-only widget appearance settings (background opacity + corner
 * radius). There are no in-app or frontend controls for these; they are
 * reached via the widget's long-press "Configure" entry.
 *
 * Theme colors come from the enrollment palette (read-only here for the
 * preview); saving only persists opacity/radius and re-renders the widgets.
 */
class WidgetAppearanceConfigActivity : AppCompatActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var opacityPct: Int = WidgetAppearance.DEFAULT_OPACITY_PCT
    private var cornerRadiusDp: Int = WidgetAppearance.DEFAULT_CORNER_RADIUS_DP
    private var opacityValid: Boolean = true

    private lateinit var opacitySlider: Slider
    private lateinit var opacityInput: EditText
    private lateinit var radiusSlider: Slider
    private lateinit var opacityLabel: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var preview: FrameLayout
    private lateinit var previewTitle: TextView
    private lateinit var saveButton: MaterialButton

    private var baseBackground: Int = Color.parseColor("#171321")
    private var baseForeground: Int = Color.parseColor("#f5f0ff")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        opacityPct = WidgetAppearance.opacityPct(this)
        cornerRadiusDp = WidgetAppearance.cornerRadiusDp(this)

        // Preview colors mirror the enrollment palette read-only; the palette
        // itself is never written from here.
        val palette = CharacterWidgetStore.current(this)?.optJSONObject("palette")
        baseBackground = parseColor(palette?.optString("background"), baseBackground)
        baseForeground = parseColor(palette?.optString("foreground"), baseForeground)

        buildUi()
        updatePreview()
    }

    private fun parseColor(raw: String?, fallback: Int): Int = try {
        if (raw != null) Color.parseColor(raw) else fallback
    } catch (_: Exception) {
        fallback
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply {
            addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        val title = TextView(this).apply {
            text = getString(R.string.widget_appearance_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(title)

        opacityLabel = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        root.addView(opacityLabel)

        val opacityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        opacitySlider = Slider(this).apply {
            valueFrom = 0f
            valueTo = WidgetAppearance.MAX_OPACITY_PCT.toFloat()
            stepSize = 1f
            value = opacityPct.toFloat()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        opacityInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(opacityPct.toString())
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        opacityRow.addView(opacitySlider)
        opacityRow.addView(opacityInput)
        root.addView(opacityRow)

        radiusLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(radiusLabel)
        radiusSlider = Slider(this).apply {
            valueFrom = 0f
            valueTo = WidgetAppearance.MAX_CORNER_RADIUS_DP.toFloat()
            stepSize = 1f
            value = cornerRadiusDp.toFloat()
        }
        root.addView(radiusSlider)

        val previewHeader = TextView(this).apply {
            text = getString(R.string.widget_appearance_preview)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(previewHeader)
        preview = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110))
        }
        previewTitle = TextView(this).apply {
            text = getString(R.string.widget_recent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        preview.addView(previewTitle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(preview)

        val note = TextView(this).apply {
            text = getString(R.string.widget_appearance_launcher_note)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(note)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(20), 0, 0)
        }
        val cancelButton = MaterialButton(this).apply {
            text = getString(R.string.widget_appearance_cancel)
        }
        cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        saveButton = MaterialButton(this).apply {
            text = getString(R.string.widget_appearance_save)
        }
        saveButton.setOnClickListener { save() }
        buttons.addView(cancelButton)
        buttons.addView(saveButton)
        root.addView(buttons)

        opacitySlider.addOnChangeListener { _, value, _ ->
            opacityPct = value.roundToInt().coerceIn(0, WidgetAppearance.MAX_OPACITY_PCT)
            opacityValid = true
            opacityInput.error = null
            if (opacityInput.text.toString() != opacityPct.toString()) {
                opacityInput.setText(opacityPct.toString())
            }
            updatePreview()
        }
        opacityInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.toIntOrNull()
                if (typed != null && typed in 0..WidgetAppearance.MAX_OPACITY_PCT) {
                    opacityValid = true
                    opacityInput.error = null
                    opacityPct = typed
                    if (opacitySlider.value.roundToInt() != typed) opacitySlider.value = typed.toFloat()
                } else {
                    opacityValid = false
                    opacityInput.error = getString(R.string.widget_appearance_value)
                }
                updatePreview()
            }
        })
        radiusSlider.addOnChangeListener { _, value, _ ->
            cornerRadiusDp = value.roundToInt().coerceIn(0, WidgetAppearance.MAX_CORNER_RADIUS_DP)
            updatePreview()
        }
    }

    private fun updatePreview() {
        opacityLabel.text = getString(R.string.widget_appearance_opacity, opacityPct)
        radiusLabel.text = getString(R.string.widget_appearance_radius, cornerRadiusDp)
        preview.background = GradientDrawable().apply {
            shape = GradientDrawable.Shape.RECTANGLE
            setColor(WidgetAppearance.tintedBackground(baseBackground, opacityPct))
            cornerRadius = WidgetAppearance.effectiveRadiusDp(cornerRadiusDp) * resources.displayMetrics.density
        }
        previewTitle.setTextColor(baseForeground)
        saveButton.isEnabled = opacityValid
    }

    private fun save() {
        if (!opacityValid) return
        WidgetAppearance.set(this, opacityPct, cornerRadiusDp)
        CharacterWidgets.renderAll(this)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, result)
        finish()
    }
}
