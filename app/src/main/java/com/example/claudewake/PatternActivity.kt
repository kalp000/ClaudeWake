package com.example.claudewake

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Pattern capture screen.
 *
 * All the user has to provide is the *order* of the dots. Where those dots sit on the lock screen
 * comes from the phone itself: "Capture from lock screen" has the accessibility service read the
 * real pattern view while the phone is locked and store it as screen fractions. Nothing is
 * eyeballed, so the replay cannot drift.
 *
 * Only if a phone hides its pattern view (some OEM skins) does the manual estimate appear.
 */
class PatternActivity : Activity() {

    private lateinit var pattern: PatternView
    private lateinit var hint: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var preview: GridPreview
    private lateinit var gridStatus: TextView
    private lateinit var captureBtn: TextView
    private lateinit var tunePanel: LinearLayout
    private lateinit var offsetBar: SeekBar
    private lateinit var sizeBar: SeekBar

    /** The grid used for replay: left/width, top/height, side/width. */
    private lateinit var grid: Triple<Float, Float, Float>

    private var drawn: List<Int> = emptyList()
    private var seeding = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { setClock(); handler.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.window(this)
        grid = SecretStore.loadGrid(this) ?: defaultGrid()
        setContentView(build())
        Ui.immersive(this)
        setClock()
        syncGrid()
    }

    /** Bars can slide back in after a transient swipe; hide them again while this screen is focused. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        // A capture can happen while this screen is in the background.
        val stored = SecretStore.loadGrid(this)
        if (stored != null && stored != grid) {
            grid = stored
            syncGrid()
        }
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun setClock() {
        val now = Date()
        clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    // ---------- layout ----------

    private fun build(): View {
        val root = FrameLayout(this).apply { background = Ui.background() }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@PatternActivity, 22f)
            setPadding(p, Ui.dp(this@PatternActivity, 40f), p, Ui.dp(this@PatternActivity, 26f))
        }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "Cancel", 14f, Ui.MUTED).apply {
            val p = Ui.dp(this@PatternActivity, 10f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { finish() }
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(Ui.text(this, "Tune grid", 13f, Ui.ACCENT, true).apply {
            val p = Ui.dp(this@PatternActivity, 10f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener {
                val show = tunePanel.visibility != View.VISIBLE
                tunePanel.visibility = if (show) View.VISIBLE else View.GONE
                if (show) seedTuneFromGrid()
            }
        })
        col.addView(bar)

        // Lock-screen-ish preview
        clock = Ui.text(this, "", 52f, Ui.TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        date = Ui.text(this, "", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(clock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@PatternActivity, 10f)
        })
        col.addView(date)

        // Pattern canvas: only the dot order is read from here.
        val (screenW, screenH) = Screen.size(this)
        val canvasSide = min(
            Ui.dp(this, 272f),
            min(screenW - Ui.dp(this, 56f), (screenH * 0.34f).toInt())
        )
        val stage = FrameLayout(this).apply { clipChildren = false }
        col.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        pattern = PatternView(this).apply {
            layoutParams = FrameLayout.LayoutParams(canvasSide, canvasSide, Gravity.CENTER)
            onProgress = { n ->
                hint.text = if (n < 4) "Connect at least 4 dots" else "$n dots — lift to save"
            }
            onComplete = { order -> handle(order) }
        }
        stage.addView(pattern)

        hint = Ui.text(this, "Draw the pattern you use to unlock your phone", 13.5f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@PatternActivity, 8f), 0, Ui.dp(this@PatternActivity, 12f))
        }
        col.addView(hint)

        col.addView(buildGridCard())
        tunePanel = buildTunePanel()
        col.addView(tunePanel)

        col.addView(Ui.button(this, "Save pattern", true) { submit() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@PatternActivity, 12f)
        })

        return root
    }

    private fun buildGridCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@PatternActivity, 20f, Ui.CARD, Ui.STROKE)
            val p = Ui.dp(this@PatternActivity, 14f)
            setPadding(p, p, p, p)
        }

        val (sw, sh) = Screen.size(this)
        preview = GridPreview(this).apply {
            aspect = sw.toFloat() / sh.toFloat()
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@PatternActivity, 58f), Ui.dp(this@PatternActivity, 118f))
        }
        card.addView(preview)
        card.addView(View(this), LinearLayout.LayoutParams(Ui.dp(this@PatternActivity, 14f), 1))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(Ui.caps(this, "Where it lands", Ui.ACCENT))
        gridStatus = Ui.text(this, "", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@PatternActivity, 5f), 0, 0)
        }
        info.addView(gridStatus)
        captureBtn = Ui.text(this, "", 13f, Ui.TEXT, true).apply {
            val p = Ui.dp(this@PatternActivity, 8f)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener { captureGrid() }
        }
        info.addView(captureBtn)
        card.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return card
    }

    private fun buildTunePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val p = Ui.dp(this@PatternActivity, 4f)
            setPadding(p, Ui.dp(this@PatternActivity, 14f), p, 0)
        }
        panel.addView(Ui.text(
            this,
            "Fallback only. Move the mini map until its dots sit where your lock screen puts them.",
            11.5f, Ui.FAINT
        ))
        offsetBar = slider(panel, "Vertical position")
        sizeBar = slider(panel, "Size")
        return panel
    }

    private fun slider(panel: LinearLayout, label: String): SeekBar {
        panel.addView(Ui.caps(this, label, Ui.FAINT))
        val bar = SeekBar(this).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!seeding) tuneChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        panel.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return bar
    }

    // ---------- grid ----------

    /** Centred 72%-wide grid at 55% height: only a placeholder until the real one is captured. */
    private fun defaultGrid(): Triple<Float, Float, Float> {
        val (w, h) = Screen.size(this)
        val side = w * 0.72f
        return Triple(((w - side) / 2f) / w, (h * 0.55f - side / 2f) / h, 0.72f)
    }

    private fun syncGrid() {
        if (!::preview.isInitialized) return
        val captured = SecretStore.hasGrid(this)
        preview.set(grid.first, grid.second, grid.third)
        gridStatus.text = if (captured)
            "Exactly as your phone places it — no matching needed."
        else
            "Estimated. Capture it from your lock screen for an exact fit."
        gridStatus.setTextColor(if (captured) Ui.MUTED else Ui.WARN)
        captureBtn.text = if (captured) "Capture again ›" else "Capture from lock screen ›"
        captureBtn.setTextColor(Ui.ACCENT)
    }

    private fun seedTuneFromGrid() {
        if (!::offsetBar.isInitialized) return
        val (w, h) = Screen.size(this)
        seeding = true
        sizeBar.progress = (((grid.third - 0.45f) / 0.45f) * 100f).toInt().coerceIn(0, 100)
        val centerY = grid.second + (grid.third * w) / (2f * h)
        offsetBar.progress = (((centerY - 0.55f) / 0.16f) * 50f + 50f).toInt().coerceIn(0, 100)
        seeding = false
    }

    private fun tuneChanged() {
        if (!::offsetBar.isInitialized || !::sizeBar.isInitialized) return
        val (w, h) = Screen.size(this)
        val side = (0.45f + 0.45f * sizeBar.progress / 100f) * w
        val centerY = 0.55f + (offsetBar.progress - 50) / 50f * 0.16f
        grid = Triple(((w - side) / 2f) / w, centerY - (side / 2f) / h, side / w)
        syncGrid()
    }

    private fun captureGrid() {
        val svc = ClaudeAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "Enable the Claude Wake accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Capture your real grid")
            .setMessage(
                "1. Tap Start, then lock your phone.\n" +
                "2. Swipe up so the dots appear.\n" +
                "3. Unlock normally.\n\n" +
                "Claude Wake only reads where the phone put the dots — it never draws your pattern " +
                "during capture, so no unlock attempt is used."
            )
            .setPositiveButton("Start") { _, _ ->
                svc.captureGrid()
                Toast.makeText(this, "Lock the phone, then swipe up", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    // ---------- pattern ----------

    private fun handle(order: List<Int>) {
        drawn = order
        if (order.size < 4) {
            pattern.flashError()
            hint.text = "Connect at least 4 dots"
            return
        }
        pattern.flashOk()
        hint.text = order.size.toString() + " dots — tap Save pattern"
    }

    private fun submit() {
        val order = drawn
        if (order.size < 4) {
            pattern.flashError()
            hint.text = "Draw at least 4 dots first"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save this pattern?")
            .setMessage(
                "Make sure it is exactly the pattern you draw on your lock screen.\n\n" +
                "Claude Wake draws it once per wake-up. If it is wrong, auto-unlock switches itself " +
                "off after that single attempt so you are not locked out."
            )
            .setPositiveButton("Save") { _, _ -> savePattern(order) }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    private fun savePattern(order: List<Int>) {
        SecretStore.clear(this)
        SecretStore.savePattern(this, order)
        SecretStore.saveGrid(this, grid.first, grid.second, grid.third)
        Prefs.setUnlockMode(this, Prefs.PATTERN)
        pattern.flashOk()
        hint.text = "Saved"
        Toast.makeText(this, "Pattern saved", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ finish() }, 420)
    }
}
