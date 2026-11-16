package com.example.claudewake

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Hand-rolled design system: the app deliberately has no AndroidX/Material dependency. */
object Ui {
    val BG_TOP = 0xFF0F0D0C.toInt()
    val BG_BOTTOM = 0xFF191614.toInt()
    val CARD = 0xFF1D1A18.toInt()
    val CARD_HI = 0xFF262120.toInt()
    val STROKE = 0x1FFFFFFF
    val TEXT = 0xFFF5F1ED.toInt()
    val MUTED = 0xFFA9A199.toInt()
    val FAINT = 0xFF736B65.toInt()
    val ACCENT = 0xFFD97757.toInt()
    val OK = 0xFF7BC49B.toInt()
    val WARN = 0xFFE5B36A.toInt()
    val DANGER = 0xFFE06C6C.toInt()

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun background() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM)
    )

    fun rounded(c: Context, radiusDp: Float, fill: Int, stroke: Int = 0, strokeDp: Float = 1f) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(dp(c, strokeDp).coerceAtLeast(1), stroke)
        }

    fun ripple(content: Drawable, color: Int = 0x24FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(color), content, content)

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    fun window(a: Activity) {
        val w = a.window
        // Touching decorView first: PhoneWindow#getInsetsController() NPEs before the decor exists.
        val decor = w.decorView
        w.statusBarColor = BG_TOP
        w.navigationBarColor = BG_BOTTOM
        if (Build.VERSION.SDK_INT >= 29) {
            w.isStatusBarContrastEnforced = false
            w.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            decor.windowInsetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    /**
     * Full-bleed mode: hides both system bars.
     * Used by the pattern preview so the real status-bar clock does not sit next to the mock one.
     */
    fun immersive(a: Activity) {
        val decor = a.window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            decor.windowInsetsController?.let { c ->
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    fun text(c: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun caps(c: Context, s: String, color: Int = MUTED) =
        text(c, s.uppercase(), 10.5f, color, true).apply { letterSpacing = 0.16f }

    fun card(c: Context, padDp: Float = 18f): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, 24f, CARD, STROKE)
        setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp))
    }

    fun input(c: Context, hint: String): android.widget.EditText =
        android.widget.EditText(c).apply {
            background = rounded(c, 14f, CARD_HI, STROKE)
            setTextColor(TEXT)
            setHintTextColor(FAINT)
            textSize = 15f
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            val px = dp(c, 14f)
            val py = dp(c, 12f)
            setPadding(px, py, px, py)
        }

    fun switch(c: Context) = Switch(c).apply {
        val on = intArrayOf(android.R.attr.state_checked)
        val off = intArrayOf()
        trackTintList = ColorStateList(arrayOf(on, off), intArrayOf(ACCENT, 0xFF544B46.toInt()))
        thumbTintList = ColorStateList(arrayOf(on, off), intArrayOf(TEXT, 0xFFA9A199.toInt()))
    }

    fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit) =
        TextView(c).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) 0xFF191310.toInt() else TEXT)
            background = ripple(rounded(c, 16f, if (primary) ACCENT else CARD_HI, if (primary) 0 else STROKE))
            val px = dp(c, 18f)
            val py = dp(c, 15f)
            setPadding(px, py, px, py)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun dot(c: Context, color: Int): View {
        val s = dp(c, 8f)
        return View(c).apply {
            background = rounded(c, 4f, color)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
    }

    fun spacer(c: Context, dp: Float, vertical: Boolean = false): View =
        if (vertical) View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, dp)) }
        else View(c).apply { layoutParams = LinearLayout.LayoutParams(dp(c, dp), 1) }
}

/** Real screen size (independent of the current window's insets). */
object Screen {
    fun size(c: Context): Pair<Int, Int> {
        val wm = c.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }
}
