package com.example.claudewake

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var autoSwitch: Switch
    private lateinit var modeChip: TextView
    private lateinit var nextWake: TextView
    private lateinit var statusLine: TextView
    private lateinit var healthDot: View
    private lateinit var healthLabel: TextView
    private lateinit var pillOff: TextView
    private lateinit var pillPin: TextView
    private lateinit var pillPattern: TextView
    private lateinit var unlockNote: TextView
    private lateinit var captureRow: LinearLayout
    private lateinit var gridRowState: TextView
    private lateinit var messageInput: EditText
    private lateinit var projectValue: TextView
    private lateinit var projectNote: TextView
    private lateinit var a11yState: TextView
    private lateinit var alarmState: TextView
    private lateinit var fullState: TextView
    private lateinit var notifState: TextView
    private lateinit var iconDark: TextView
    private lateinit var iconLight: TextView
    private lateinit var iconMono: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.window(this)
        syncIcon()
        setContentView(build())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun build(): View {
        val scroll = ScrollView(this).apply {
            background = Ui.background()
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20f), Ui.dp(this@MainActivity, 26f),
                Ui.dp(this@MainActivity, 20f), Ui.dp(this@MainActivity, 34f))
        }
        scroll.addView(root)

        root.addView(Ui.caps(this, "automation", Ui.ACCENT))
        root.addView(Ui.text(this, "Claude Wake", 32f, Ui.TEXT, true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8f), 0, 0)
        })
        root.addView(Ui.text(this, "Keeps the conversation going the moment your Claude limit resets.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 6f), 0, Ui.dp(this@MainActivity, 20f))
        })

        root.addView(statusCard())
        root.addView(space(14f))
        root.addView(automationCard())
        root.addView(space(14f))
        root.addView(messageCard())
        root.addView(space(14f))
        root.addView(unlockCard())
        root.addView(space(14f))
        root.addView(iconCard())
        root.addView(space(14f))
        root.addView(permissionsCard())
        root.addView(space(14f))
        root.addView(actionsCard())
        root.addView(Ui.text(this, "PIN and pattern are encrypted with a non-exportable Android Keystore key and never leave this device.", 11.5f, Ui.FAINT).apply {
            setPadding(0, Ui.dp(this@MainActivity, 18f), 0, 0)
        })
        return scroll
    }

    private fun space(h: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, Ui.dp(this@MainActivity, h))
    }

    private fun statusCard(): LinearLayout {
        val card = Ui.card(this)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        healthDot = Ui.dot(this, Ui.OK)
        healthLabel = Ui.caps(this, "checking…", Ui.MUTED)
        head.addView(healthDot)
        head.addView(Ui.spacer(this, 8f))
        head.addView(healthLabel)
        card.addView(head)

        nextWake = Ui.text(this, "—", 25f, Ui.TEXT, true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 12f), 0, 0)
        }
        card.addView(nextWake)

        statusLine = Ui.text(this, "", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 6f), 0, 0)
            maxLines = 3
        }
        card.addView(statusLine)
        return card
    }

    private fun automationCard(): LinearLayout {
        val card = Ui.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Ui.text(this, "Auto-continue", 17f, Ui.TEXT, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        autoSwitch = Ui.switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked == Prefs.enabled(this@MainActivity)) return@setOnCheckedChangeListener
                Prefs.setEnabled(this@MainActivity, checked)
                if (!checked) {
                    Scheduler.cancel(this@MainActivity)
                    Prefs.setStatus(this@MainActivity, "Turned off")
                } else {
                    val t = Prefs.resetAt(this@MainActivity)
                    if (t > System.currentTimeMillis()) Scheduler.schedule(this@MainActivity, t)
                    else Prefs.setStatus(this@MainActivity, "On. Open Claude once while the limit message is showing.")
                }
                render()
            }
        }
        row.addView(autoSwitch)
        card.addView(row)
        card.addView(Ui.text(
            this,
            "Reads the reset time in Claude, then wakes the phone, opens your chat and sends the message below.",
            13f, Ui.MUTED
        ).apply { setPadding(0, Ui.dp(this@MainActivity, 8f), 0, 0) })
        return card
    }

    private fun messageCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.caps(this, "what to send", Ui.ACCENT))
        card.addView(Ui.text(this, "Message", 17f, Ui.TEXT, true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8f), 0, Ui.dp(this@MainActivity, 10f))
        })
        messageInput = Ui.input(this, Prefs.DEFAULT_MESSAGE).apply {
            setText(Prefs.message(this@MainActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Blank falls back to the default inside Prefs.
                    Prefs.setMessage(this@MainActivity, s?.toString().orEmpty())
                }
            })
        }
        card.addView(messageInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { pickProject() }
            setPadding(0, Ui.dp(this@MainActivity, 16f), 0, 0)
        }
        row.addView(Ui.text(this, "Project", 14.5f, Ui.TEXT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        projectValue = Ui.text(this, "", 13f, Ui.ACCENT, true)
        row.addView(projectValue)
        row.addView(Ui.text(this, "›", 18f, Ui.FAINT).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), 0, 0, 0)
        })
        card.addView(row)

        projectNote = Ui.text(this, "", 11.5f, Ui.FAINT).apply {
            setPadding(0, Ui.dp(this@MainActivity, 6f), 0, 0)
        }
        card.addView(projectNote)
        return card
    }

    private fun pickProject() {
        val recorded = Prefs.projects(this)
        val current = Prefs.project(this)
        val names = ArrayList(recorded)
        if (current.isNotEmpty() && names.none { it.equals(current, true) }) names.add(0, current)

        val options = ArrayList<String>()
        options.add("Most recent (default)")
        options.addAll(names)
        options.add("Type a project name…")

        val checked = if (current.isEmpty()) 0 else 1 + names.indexOfFirst { it.equals(current, true) }

        AlertDialog.Builder(this)
            .setTitle("Continue in")
            .setSingleChoiceItems(options.toTypedArray(), checked.coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> {
                        Prefs.setProject(this, "")
                        render()
                    }
                    which == options.size - 1 -> askProjectName()
                    else -> {
                        Prefs.setProject(this, names[which - 1])
                        render()
                    }
                }
            }
            .setNeutralButton("Record from Claude") { _, _ -> recordProjects() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askProjectName() {
        val input = Ui.input(this, "Project name, exactly as in Claude")
        input.setText(Prefs.project(this))
        AlertDialog.Builder(this)
            .setTitle("Project name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setProject(this, input.text.toString().trim())
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recordProjects() {
        val launch = packageManager.getLaunchIntentForPackage(ClaudeAccessibilityService.CLAUDE)
        if (launch == null) {
            Toast.makeText(this, "Claude is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        if (!a11yOn()) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(launch)
        Toast.makeText(
            this,
            "Open Claude's sidebar — project names are recorded automatically",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun unlockCard(): LinearLayout {
        val card = Ui.card(this)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.text(this, "Auto-unlock", 17f, Ui.TEXT, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        modeChip = Ui.caps(this, "off", Ui.FAINT)
        head.addView(modeChip)
        card.addView(head)

        card.addView(Ui.text(
            this,
            "The phone must be unlocked before Claude can be driven. Store the PIN or draw the pattern you already use — it is replayed at most once per wake-up.",
            13f, Ui.MUTED
        ).apply { setPadding(0, Ui.dp(this@MainActivity, 8f), 0, Ui.dp(this@MainActivity, 14f)) })

        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pillOff = pill("Off") { clearUnlock() }
        pillPin = pill("PIN") { onPinTapped() }
        pillPattern = pill("Pattern") {
            startActivity(Intent(this@MainActivity, PatternActivity::class.java))
        }
        pills.addView(pillOff, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pills.addView(Ui.spacer(this, 8f))
        pills.addView(pillPin, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pills.addView(Ui.spacer(this, 8f))
        pills.addView(pillPattern, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(pills)

        unlockNote = Ui.text(this, "", 12f, Ui.FAINT).apply {
            setPadding(0, Ui.dp(this@MainActivity, 12f), 0, 0)
            maxLines = 4
        }
        card.addView(unlockNote)

        captureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setPadding(0, Ui.dp(this@MainActivity, 14f), 0, Ui.dp(this@MainActivity, 2f))
            setOnClickListener { captureGrid() }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(Ui.text(this, "Lock-screen grid", 14f, Ui.TEXT))
        gridRowState = Ui.text(this, "", 11.5f, Ui.ACCENT, true)
        info.addView(gridRowState)
        captureRow.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        captureRow.addView(Ui.text(this, "›", 18f, Ui.FAINT))
        card.addView(captureRow)
        return card
    }

    private fun iconCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.caps(this, "app icon"))
        card.addView(Ui.text(this, "A dead Claude, three ways.", 17f, Ui.TEXT, true).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8f), 0, 0)
        })
        card.addView(Ui.text(
            this,
            "Android can't repaint an icon live, so the app keeps three launcher entries and switches which one is on.",
            13f, Ui.MUTED
        ).apply { setPadding(0, Ui.dp(this@MainActivity, 8f), 0, Ui.dp(this@MainActivity, 14f)) })

        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        iconDark = pill("Dark") { applyIcon(Prefs.ICON_DARK) }
        iconLight = pill("Light") { applyIcon(Prefs.ICON_LIGHT) }
        iconMono = pill("Mono") { applyIcon(Prefs.ICON_MONO) }
        pills.addView(iconDark, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pills.addView(Ui.spacer(this, 8f))
        pills.addView(iconLight, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pills.addView(Ui.spacer(this, 8f))
        pills.addView(iconMono, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(pills)

        card.addView(Ui.text(
            this,
            "The icon changes on the launcher. If the home screen still shows the old one, it updates after the app restarts.",
            11.5f, Ui.FAINT
        ).apply { setPadding(0, Ui.dp(this@MainActivity, 12f), 0, 0) })
        return card
    }

    /**
     * Swaps which launcher alias is enabled. Each alias carries one icon, so turning on exactly
     * one of them is what changes the icon; the app is never reinstalled or killed.
     */
    private fun applyIcon(theme: String) {
        val wanted = aliasFor(theme)
        for (alias in listOf("IconDark", "IconLight", "IconMono")) {
            val component = ComponentName(this, "$packageName.$alias")
            val state = if (alias == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
        Prefs.setIcon(this, theme)
        Toast.makeText(
            this,
            "Icon changed. If the launcher still shows the old one, it refreshes after the app restarts.",
            Toast.LENGTH_LONG
        ).show()
        render()
    }

    /** Keeps the enabled alias in step with the stored choice, e.g. after a restore. */
    private fun syncIcon() {
        val wanted = aliasFor(Prefs.icon(this))
        for (alias in listOf("IconDark", "IconLight", "IconMono")) {
            val component = ComponentName(this, "$packageName.$alias")
            val on = packageManager.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (on != (alias == wanted)) {
                packageManager.setComponentEnabledSetting(
                    component,
                    if (alias == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    private fun aliasFor(theme: String) = when (theme) {
        Prefs.ICON_LIGHT -> "IconLight"
        Prefs.ICON_MONO -> "IconMono"
        else -> "IconDark"
    }

    private fun pill(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 13f, Ui.MUTED, true).apply {
            gravity = Gravity.CENTER
            val p = Ui.dp(this@MainActivity, 12f)
            setPadding(p, Ui.dp(this@MainActivity, 11f), p, Ui.dp(this@MainActivity, 11f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun stylePill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) 0xFF191310.toInt() else Ui.MUTED)
        v.background = Ui.ripple(Ui.rounded(this, 13f, if (on) Ui.ACCENT else 0, if (on) 0 else Ui.STROKE))
    }

    private fun permissionsCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.caps(this, "setup"))
        a11yState = permissionRow(card, "Accessibility service", "Lets the app read Claude and draw gestures", first = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        alarmState = permissionRow(card, "Exact alarms", "Wakes up at the exact reset minute") {
            if (Build.VERSION.SDK_INT >= 31)
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        fullState = permissionRow(card, "Full-screen wake", "Shows the wake screen over the lock screen") {
            if (Build.VERSION.SDK_INT >= 34)
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            else startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        notifState = permissionRow(card, "Notifications", "Reports what happened while you were away") {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        return card
    }

    private fun permissionRow(
        parent: LinearLayout,
        title: String,
        desc: String,
        first: Boolean = false,
        action: () -> Unit
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            setPadding(0, Ui.dp(this@MainActivity, if (first) 14f else 10f), 0, Ui.dp(this@MainActivity, 10f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, title, 14.5f, Ui.TEXT))
        col.addView(Ui.text(this, desc, 11.5f, Ui.FAINT))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val state = Ui.text(this, "", 12f, Ui.ACCENT, true)
        row.addView(state)
        row.addView(Ui.text(this, "›", 18f, Ui.FAINT).apply {
            setPadding(Ui.dp(this@MainActivity, 8f), 0, 0, 0)
        })
        parent.addView(row)
        return state
    }

    private fun actionsCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.button(this, "Run a test now", true) {
            startActivity(Intent(this, WakeActivity::class.java))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(Ui.button(this, "Open Claude") {
            val launch = packageManager.getLaunchIntentForPackage("com.anthropic.claude")
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Claude is not installed", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@MainActivity, 10f)
        })
        return card
    }

    private fun render() {
        autoSwitch.isChecked = Prefs.enabled(this)

        val mode = Prefs.unlockMode(this)
        modeChip.text = when (mode) {
            Prefs.PIN -> "PIN"
            Prefs.PATTERN -> "PATTERN"
            else -> "OFF"
        }
        modeChip.setTextColor(if (mode == Prefs.NONE) Ui.FAINT else Ui.ACCENT)
        stylePill(pillOff, mode == Prefs.NONE)
        stylePill(pillPin, mode == Prefs.PIN)
        stylePill(pillPattern, mode == Prefs.PATTERN)

        val icon = Prefs.icon(this)
        stylePill(iconDark, icon == Prefs.ICON_DARK)
        stylePill(iconLight, icon == Prefs.ICON_LIGHT)
        stylePill(iconMono, icon == Prefs.ICON_MONO)

        unlockNote.text = when (mode) {
            Prefs.PIN ->
                "PIN stored. One attempt per wake-up; a failed PIN switches auto-unlock off to prevent a lockout."
            Prefs.PATTERN -> {
                val dots = SecretStore.loadPattern(this)?.size ?: 0
                "$dots dots stored. The app draws them where your phone puts them."
            }
            else -> "Off. The phone has to be unlocked already when the alarm fires."
        }

        captureRow.visibility = if (mode == Prefs.PATTERN) View.VISIBLE else View.GONE
        if (mode == Prefs.PATTERN) {
            val captured = SecretStore.hasGrid(this)
            gridRowState.text = if (captured)
                "Captured from your lock screen" else "Not captured yet — tap to capture"
            gridRowState.setTextColor(if (captured) Ui.OK else Ui.WARN)
        }

        val a11y = a11yOn()
        healthDot.background = Ui.rounded(this, 4f, if (a11y) Ui.OK else Ui.WARN)
        healthLabel.text = if (a11y) "ACCESSIBILITY CONNECTED" else "ACCESSIBILITY OFF"
        healthLabel.setTextColor(if (a11y) Ui.OK else Ui.WARN)

        val msg = Prefs.message(this)
        if (!messageInput.hasFocus() && messageInput.text.toString() != msg) messageInput.setText(msg)
        val selection = Prefs.project(this)
        projectValue.text = if (selection.isEmpty()) "Most recent" else selection
        val recorded = Prefs.projects(this)
        projectNote.text = if (recorded.isEmpty())
            "No projects recorded yet · tap Project → Record from Claude"
        else
            recorded.size.toString() + " projects recorded · tap Project to change"

        val scheduled = Prefs.enabled(this) && Prefs.resetAt(this) > System.currentTimeMillis()
        nextWake.text =
            if (scheduled) "Next wake " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(Prefs.resetAt(this)))
            else "No wake scheduled"
        nextWake.setTextColor(if (scheduled) Ui.TEXT else Ui.FAINT)
        statusLine.text = Prefs.status(this)

        val alarmOk = Build.VERSION.SDK_INT < 31 ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val fullOk = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        setState(a11yState, a11y, "On", "Off")
        setState(alarmState, alarmOk, "Allowed", "Blocked")
        setState(fullState, fullOk, "Allowed", "Blocked")
        setState(notifState, notifOk, "On", "Off")
    }

    private fun setState(v: TextView, ok: Boolean, yes: String, no: String) {
        v.text = if (ok) yes else no
        v.setTextColor(if (ok) Ui.OK else Ui.ACCENT)
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
                "Only the dot positions are read — your pattern is not drawn, so no unlock attempt is used."
            )
            .setPositiveButton("Start") { _, _ ->
                svc.captureGrid()
                Toast.makeText(this, "Lock the phone, then swipe up", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    private fun clearUnlock() {
        SecretStore.clear(this)
        Prefs.setUnlockMode(this, Prefs.NONE)
        Toast.makeText(this, "Auto-unlock removed", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun onPinTapped() {
        if (Prefs.unlockMode(this) == Prefs.PIN) { askPin(); return }
        AlertDialog.Builder(this)
            .setTitle("Allow auto-unlock?")
            .setMessage(
                "To unlock a PIN-protected phone, this app must store your PIN and type it into the lock " +
                "screen through the Accessibility service.\n\n" +
                "• The PIN is encrypted with a non-exportable Android Keystore key and stays on this phone.\n" +
                "• A stored PIN lowers your phone's security: anyone holding the unlocked phone can use it.\n" +
                "• It enters the PIN once per wake-up; if that fails, auto-unlock turns itself off.\n\n" +
                "Do you consent?"
            )
            .setPositiveButton("I consent") { _, _ -> askPin() }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Your lock-screen PIN (4–16 digits)"
        }
        AlertDialog.Builder(this)
            .setTitle("Enter your lock-screen PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..16 && pin.all { it.isDigit() }) {
                    SecretStore.clear(this)
                    SecretStore.savePin(this, pin)
                    Prefs.setUnlockMode(this, Prefs.PIN)
                    Toast.makeText(this, "PIN saved (encrypted)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be 4–16 digits", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Cancel") { _, _ -> render() }
            .show()
    }

    private fun a11yOn(): Boolean {
        if (ClaudeAccessibilityService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains("$packageName/")
    }
}
