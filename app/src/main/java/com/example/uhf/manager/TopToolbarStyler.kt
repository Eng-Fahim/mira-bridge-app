package com.example.uhf.manager

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import com.example.uhf.R
import com.google.android.material.appbar.MaterialToolbar

object TopToolbarStyler {

    private const val TAG = "TopToolbarStyler"

    @JvmStatic
    fun apply(activity: Activity) {
        try {
            val isDark = (activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            val density = activity.resources.displayMetrics.density
            fun dp(v: Float): Int = (v * density).toInt()

            val headerBg = if (isDark) Color.parseColor("#0A192F") else Color.parseColor("#FFFFFF")
            val subBarBg = if (isDark) Color.parseColor("#11223D") else Color.parseColor("#FAF7F0")
            val titleColor = if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#1E293B")
            val pillBg = if (isDark) Color.parseColor("#182B49") else Color.parseColor("#FDF8EC")
            val pillText = if (isDark) Color.parseColor("#F7DF8B") else Color.parseColor("#8C6200")
            val goldLineColor = if (isDark) Color.parseColor("#4DD4AF37") else Color.parseColor("#55D4AF37")
            val iconTint = if (isDark) Color.parseColor("#F7DF8B") else Color.parseColor("#8C6200")

            // 1. مطابقة شريط الحالة العلوي (Status Bar) مع الثيم الحالي
            activity.window?.let { window ->
                window.statusBarColor = headerBg
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    WindowInsetsControllerCompat(window, window.decorView)
                        .isAppearanceLightStatusBars = !isDark
                }
            }

            // 2. تنسيق الشريط العلوي الرئيسي
            val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
            if (toolbar != null) {
                toolbar.background = createBottomLineBackground(headerBg, goldLineColor, dp(1f))
                toolbar.elevation = dp(2f).toFloat()
                toolbar.setContentInsetsRelative(dp(12f), dp(8f))

                toolbar.overflowIcon?.mutate()?.colorFilter =
                    PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)

                tintMenuIcons(toolbar.menu, isDark)
            }

            // 3. عنوان المنشأة
            activity.findViewById<TextView>(R.id.tvTitle)?.apply {
                setTextColor(titleColor)
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
            }

            // 4. شارة الفرع النشط
            activity.findViewById<TextView>(R.id.tvSubtitle)?.apply {
                setTextColor(pillText)
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(9f), dp(2f), dp(9f), dp(2f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10f).toFloat()
                    setColor(pillBg)
                    setStroke(dp(1f), goldLineColor)
                }
            }

            // 5. الشريط الثانوي
            val secondaryToolbar = activity.findViewById<MaterialToolbar>(R.id.secondaryToolbar)
            if (secondaryToolbar != null) {
                secondaryToolbar.background = createBottomLineBackground(subBarBg, goldLineColor, dp(1f))
                tintMenuIcons(secondaryToolbar.menu, isDark)
            }

        } catch (e: Exception) {
            Log.e(TAG, "apply error: ${e.message}", e)
        }
    }

    @JvmStatic
    fun tintMenuIcons(menu: Menu?) {
        tintMenuIcons(menu, false)
    }

    @JvmStatic
    fun tintMenuIcons(menu: Menu?, isDark: Boolean) {
        if (menu == null) return
        try {
            val tint = if (isDark) Color.parseColor("#F7DF8B") else Color.parseColor("#8C6200")
            val filter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                item.icon?.mutate()?.colorFilter = filter
                // تلوين أيقونة الجرس داخل actionLayout المخصص حتى لا تختفي
                item.actionView?.let { actionView ->
                    tintImageViewsRecursively(actionView, tint)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tintMenuIcons error: ${e.message}")
        }
    }

    private fun tintImageViewsRecursively(view: View, color: Int) {
        if (view is ImageView) {
            view.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintImageViewsRecursively(view.getChildAt(i), color)
            }
        }
    }

    private fun createBottomLineBackground(bgColor: Int, lineColor: Int, strokePx: Int): LayerDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
        }
        val line = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(lineColor)
        }
        val layers = LayerDrawable(arrayOf(bg, line))
        layers.setLayerHeight(1, strokePx)
        layers.setLayerGravity(1, Gravity.BOTTOM)
        return layers
    }
}
