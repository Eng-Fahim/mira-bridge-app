package com.example.uhf.manager

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import com.example.uhf.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * TopToolbarStyler
 * يضفي الهوية الملكية لـ MIRA ID™ (الكحلي العميق والذهب عيار 24)
 * على الشريط العلوي الرئيسي والثانوي وشريط حالة النظام دون تعديل IDs.
 */
object TopToolbarStyler {

    private const val TAG = "TopToolbarStyler"

    private val NAVY_OBSIDIAN = Color.parseColor("#071224")
    private val NAVY_DEEP = Color.parseColor("#0A192F")
    private val NAVY_SUB_BAR = Color.parseColor("#0F213E")
    private val NAVY_PILL = Color.parseColor("#182B49")

    private val GOLD_BRIGHT = Color.parseColor("#F7DF8B")
    private val GOLD_PRIMARY = Color.parseColor("#D4AF37")
    private val GOLD_BORDER = Color.parseColor("#66D4AF37")
    private val TEXT_CHAMPAGNE = Color.parseColor("#F8FAFC")

    @JvmStatic
    fun apply(activity: Activity) {
        try {
            val density = activity.resources.displayMetrics.density
            fun dp(v: Float): Int = (v * density).toInt()

            // 1. توحيد لون شريط الحالة العلوي (Status Bar) مع الكحلي الملكي
            activity.window?.let { window ->
                window.statusBarColor = NAVY_OBSIDIAN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    WindowInsetsControllerCompat(window, window.decorView)
                        .isAppearanceLightStatusBars = false
                }
            }

            // 2. تنسيق الشريط العلوي الرئيسي (@id/toolbar)
            val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
            if (toolbar != null) {
                toolbar.background = createRoyalHeaderBackground(dp(1f))
                toolbar.elevation = dp(8f).toFloat()
                toolbar.setContentInsetsRelative(dp(14f), dp(8f))

                // تلوين زر الثلاث نقاط (Overflow Icon) بالذهبي
                toolbar.overflowIcon?.mutate()?.colorFilter =
                    PorterDuffColorFilter(GOLD_BRIGHT, PorterDuff.Mode.SRC_IN)

                tintMenuIcons(toolbar.menu)
            }

            // 3. تنسيق اسم المنشأة (@id/tvTitle)
            activity.findViewById<TextView>(R.id.tvTitle)?.apply {
                setTextColor(TEXT_CHAMPAGNE)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.02f
            }

            // 4. تحويل اسم الفرع (@id/tvSubtitle) إلى شارة كبسولية ذهبية أنيقة
            activity.findViewById<TextView>(R.id.tvSubtitle)?.apply {
                setTextColor(GOLD_BRIGHT)
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12f).toFloat()
                    setColor(NAVY_PILL)
                    setStroke(dp(1f), GOLD_BORDER)
                }
            }

            // 5. تنسيق الشريط الثانوي (@id/secondaryToolbar) ليتكامل مع العلوي
            val secondaryToolbar = activity.findViewById<MaterialToolbar>(R.id.secondaryToolbar)
            if (secondaryToolbar != null) {
                secondaryToolbar.background = createSubToolbarBackground(dp(1f))
                secondaryToolbar.elevation = dp(4f).toFloat()
                tintMenuIcons(secondaryToolbar.menu)
            }

        } catch (e: Exception) {
            Log.e(TAG, "apply error: ${e.message}", e)
        }
    }

    @JvmStatic
    fun tintMenuIcons(menu: Menu?) {
        if (menu == null) return
        try {
            val filter = PorterDuffColorFilter(GOLD_BRIGHT, PorterDuff.Mode.SRC_IN)
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.actionView == null) {
                    item.icon?.mutate()?.colorFilter = filter
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tintMenuIcons error: ${e.message}")
        }
    }

    private fun createRoyalHeaderBackground(strokePx: Int): LayerDrawable {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(NAVY_OBSIDIAN, NAVY_DEEP)
        )
        val goldLine = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(GOLD_PRIMARY)
        }
        val layers = LayerDrawable(arrayOf(gradient, goldLine))
        layers.setLayerInset(1, 0, 0, 0, 0)
        layers.setLayerHeight(1, strokePx)
        layers.setLayerGravity(1, android.view.Gravity.BOTTOM)
        return layers
    }

    private fun createSubToolbarBackground(strokePx: Int): LayerDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(NAVY_SUB_BAR)
        }
        val subtleBorder = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(GOLD_BORDER)
        }
        val layers = LayerDrawable(arrayOf(bg, subtleBorder))
        layers.setLayerHeight(1, strokePx)
        layers.setLayerGravity(1, android.view.Gravity.BOTTOM)
        return layers
    }
}
