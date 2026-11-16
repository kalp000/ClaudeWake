package com.example.claudewake

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.min

class ClaudeAccessibilityService : AccessibilityService() {

    companion object {
        const val CLAUDE = "com.anthropic.claude"
        var instance: ClaudeAccessibilityService? = null
        private const val MAX_ATTEMPTS = 30
        /** Fallback lock-screen grid, only used when the phone refuses to show its real one. */
        private const val DEFAULT_SIDE = 0.72f       // of screen width
        private const val DEFAULT_CENTER_Y = 0.55f   // of screen height
        private val SKIP_LABELS = listOf("new chat", "search", "projects", "starred", "artifacts",
            "settings", "recents", "recent", "chats", "recent chats", "upgrade", "profile")
        /**
         * Top-level navigation entries, matched EXACTLY (so a chat called "Code review" is still
         * fine). Tapping these switches section (e.g. into Code) and never opens a conversation.
         * Controls that live on the chat screen are listed too, now that their content
         * descriptions are readable: they must never be tapped as if they were a chat row.
         */
        private val NAV_LABELS = setOf("code", "claude code", "chat", "cowork", "home", "customize",
            "new session", "sessions", "history", "menu", "close", "back", "open sidebar", "close sidebar",
            "open menu", "close menu", "more options", "send", "reply to claude", "add files and more",
            "start speech input", "stop speech input", "copy message", "share", "narrate", "retry message",
            "good response", "bad response", "view artifact", "scroll to bottom")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var attempts = 0
    private var navigated = false
    private var menuTries = 0
    private var inProject = false
    private var projectMisses = 0
    private var lastScan = 0L
    private var clicks = 0                 // conversations/rows opened this run
    private var lastClickAttempt = 0       // attempt number of the last such click
    private val clickedRows = mutableSetOf<String>()

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    // ---------- 1) Read the reset time whenever the Claude app shows it ----------
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (running || !Prefs.enabled(this)) return
        if (e.packageName?.toString() != CLAUDE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 2000) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        recordProjects(root)
        val resetAt = LimitParser.parse(collectText(root))
        if (resetAt != null && resetAt > System.currentTimeMillis() &&
            abs(resetAt - Prefs.resetAt(this)) > 60_000
        ) {
            Scheduler.schedule(this, resetAt)
        }
    }

    // ---------- Record the project names Claude shows in its sidebar ----------
    private var lastProjectRecord = 0L

    /**
     * Claude's sidebar lists sections ("Projects", "Recents", …). Everything between the
     * "Projects" header and the next section header is a project name, so it is remembered and
     * offered as a target in the app. Runs passively: no extra navigation.
     */
    private fun recordProjects(root: AccessibilityNodeInfo) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProjectRecord < 3000) return
        lastProjectRecord = now

        val all = mutableListOf<AccessibilityNodeInfo>()
        flatten(root, all)
        val start = all.indexOfFirst { label(it).trim().equals("projects", true) }
        if (start < 0) return

        val names = ArrayList<String>()
        for (i in start + 1 until all.size) {
            val t = label(all[i]).trim()
            if (t.isEmpty() || t.length > 80) continue
            val lower = t.lowercase()
            if (lower in SKIP_LABELS) break
            if (lower.startsWith("new ") || lower.startsWith("create ") ||
                lower.startsWith("add ") || lower.startsWith("see all") || lower.startsWith("show all")
            ) continue
            if (names.none { it.equals(t, ignoreCase = true) }) names.add(t)
            if (names.size >= 40) break
        }
        if (names.isEmpty()) return

        Prefs.setProjects(this, names)
        Prefs.setProjectScanAt(this, System.currentTimeMillis())
        Prefs.setStatus(this, "Recorded ${names.size} project${if (names.size == 1) "" else "s"} from Claude.")
    }

    // ---------- 2) Called after the phone is awake and Claude is launched ----------
    fun beginContinue() {
        running = true; attempts = 0; navigated = false; menuTries = 0
        inProject = false; projectMisses = 0
        clicks = 0; lastClickAttempt = 0; clickedRows.clear()
        Prefs.setStatus(this, "Working in Claude…")
        handler.postDelayed({ tick() }, 3000)
    }

    private fun again() = handler.postDelayed({ tick() }, 1500)

    private fun tick() {
        if (!running) return
        if (++attempts > MAX_ATTEMPTS) {
            if (navigated || menuTries > 0) noChat() else finish("Couldn't reach the Claude screen. Is the phone unlocked? Screen: " + screenDump(rootInActiveWindow))
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != CLAUDE) { again(); return }

        // Still rate-limited? Reschedule instead of sending.
        LimitParser.parse(collectText(root))?.let {
            if (it > System.currentTimeMillis() + 120_000) {
                Scheduler.schedule(this, it)
                finish("Still limited. Rescheduled."); return
            }
        }

        // In an existing chat the input says "Reply to Claude…"; on a new chat it doesn't.
        // After we have opened a conversation (navigated), accept any text box that isn't a search
        // field: Claude Code sessions don't always say "Reply to Claude…" in their input.
        val input = findNode(root) { isTextInput(it) }
        val hint = ((input?.hintText ?: "").toString() + " " + (input?.text ?: "")).lowercase()
        if (input != null && !hint.contains("search") && (hint.contains("reply") || navigated)) {
            sendMessage(input); return
        }

        // A project was chosen in the app: open it before looking for a chat.
        val target = Prefs.project(this)
        if (target.isNotEmpty() && !inProject && projectMisses < 8) {
            val item = findNode(root) {
                val t = label(it).trim()
                (t.equals(target, true) || t.lowercase().startsWith(target.lowercase() + " ")) &&
                    clickableAncestor(it) != null
            }
            if (item != null) {
                inProject = true
                click(item)
                again(); return
            }
            projectMisses++
        }

        // Otherwise open the sidebar and tap the newest chat under "Recents".
        val header = findNode(root) { label(it).lowercase().trim() in listOf("recents", "recent", "chats", "recent chats") }
        if (header != null) {
            // Click once; only try another row if the list is still showing a few ticks later.
            val mayClick = clicks == 0 || (clicks < 3 && attempts - lastClickAttempt >= 3)
            if (mayClick) {
                val item = firstChatAfter(root, header)
                if (item != null) {
                    click(item); navigated = true; clicks++; lastClickAttempt = attempts
                    again(); return
                }
            }
            if (attempts >= 12 && clicks == 0) { noChat(); return }
        } else if (inProject && !navigated && attempts > 6) {
            // Inside a project the chat list has no "Recents" header: open its first row.
            val row = firstChatRow(root)
            if (row != null) { click(row); navigated = true; again(); return }
        } else if (!navigated && menuTries < 4) {
            val menu = findNode(root) {
                val l = label(it).lowercase()
                it.isClickable && (l.contains("menu") || l.contains("navigation") || l.contains("sidebar") || l.contains("drawer"))
            }
            if (menu != null) { click(menu); menuTries++ }
        }
        again()
    }

    private fun sendMessage(input: AccessibilityNodeInfo) {
        val text = Prefs.message(this)
        val composer = Rect()
        input.getBoundsInScreen(composer)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        handler.postDelayed({
            // Confirm the text really landed before pressing Send, otherwise a silent typing
            // failure would be reported as a successful send.
            val typed = rootInActiveWindow
                ?.let { r -> findNode(r) { isTextInput(it) } }
                ?.text?.toString()?.trim()
            if (typed.isNullOrEmpty()) finish("Couldn't type " + quoted(text) + " into Claude's message box")
            else pressSend(text, composer, 0)
        }, 900)
    }

    /**
     * Presses Send, and proves it happened by watching the composer.
     *
     * Claude's composer is drawn by a toolkit that often exposes the button with no label at all,
     * so four strategies are tried in turn until the text leaves the box: the Send node, a real
     * finger tap on it, a tap on the rightmost small control in the composer row, and finally the
     * keyboard's Enter action.
     */
    private fun pressSend(text: String, composer: Rect, attempt: Int) {
        val root = rootInActiveWindow
        if (root == null) { finish("Typed " + quoted(text) + " but Claude went away"); return }
        if (composerCleared(root, text)) { sent(text); return }
        if (attempt > 3) {
            finish("Typed " + quoted(text) + " but Send didn't respond. Screen: " + screenDump(root))
            return
        }
        val button = findSend(root, composer)
        val acted = when (attempt) {
            0 -> button != null && click(button)
            1 -> button != null && tap(button)
            2 -> tapAt(composerSendX(composer), composerSendY(composer))
            else -> enterKey()
        }
        handler.postDelayed({ pressSend(text, composer, attempt + 1) }, if (acted) 900 else 250)
    }

    private fun sent(text: String) {
        finish("Sent " + quoted(text) + " at " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
    }

    /** True once our text has left the composer - either the box is empty or it disappeared. */
    private fun composerCleared(root: AccessibilityNodeInfo, text: String): Boolean {
        val input = findNode(root) { isTextInput(it) }
        if (input == null) return true
        return (input.text?.toString() ?: "").trim() != text.trim()
    }

    /**
     * The Send control. Prefers a node that names itself, then any small control on the right of
     * the composer row (that is where Send always sits), which survives unlabelled buttons.
     */
    private fun findSend(root: AccessibilityNodeInfo, composer: Rect): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        flatten(root, all)
        val band = composerBand(composer)
        val named = all.firstOrNull { n ->
            if (!n.isVisibleToUser || !inBand(n, band)) return@firstOrNull false
            val l = label(n).lowercase().trim()
            val id = n.viewIdResourceName?.lowercase() ?: ""
            val sendish = l == "send" || l == "send message" || l == "submit" || l == "send prompt" ||
                l == "send message button" || (l.startsWith("send") && l.length <= 24 && !l.contains("feedback"))
            sendish || id.contains("send")
        }
        if (named != null) return named
        val (w, _) = Screen.size(this)
        return all.filter { n ->
            if (!n.isVisibleToUser || !inBand(n, band)) return@filter false
            val b = Rect(); n.getBoundsInScreen(b)
            b.width() > 0 && b.width() < w * 0.3f && b.centerX() > composer.centerX()
        }.maxByOrNull { val b = Rect(); it.getBoundsInScreen(b); b.centerX() }
    }

    /** Vertical strip around the composer, so buttons elsewhere on screen are never tapped. */
    private fun composerBand(composer: Rect) =
        (composer.top - composer.height())..(composer.bottom + composer.height())

    private fun inBand(n: AccessibilityNodeInfo, band: IntRange): Boolean {
        val b = Rect(); n.getBoundsInScreen(b)
        return b.centerY() in band
    }

    private fun composerSendX(composer: Rect): Float {
        val (w, _) = Screen.size(this)
        val x = if (composer.width() > 0) composer.right - composer.height() * 0.1f else w * 0.9f
        return x.coerceIn(w * 0.55f, w * 0.97f)
    }

    private fun composerSendY(composer: Rect): Float {
        val (_, h) = Screen.size(this)
        val y = if (composer.height() > 0) composer.centerY().toFloat() else h * 0.95f
        return y.coerceIn(h * 0.2f, h * 0.99f)
    }

    /** A finger tap on a real screen coordinate - what a hand-made button needs. */
    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        val b = Rect(); n.getBoundsInScreen(b)
        return b.width() > 0 && tapAt(b.centerX().toFloat(), b.centerY().toFloat())
    }

    /** Last resort: the Enter key of the keyboard, which Claude treats as Send. */
    private fun enterKey(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val input = rootInActiveWindow?.let { r -> findNode(r) { isTextInput(it) } } ?: return false
        return input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    private fun quoted(t: String): String {
        val short = if (t.length > 40) t.take(37) + "…" else t
        return "\"" + short + "\""
    }

    private fun noChat() {
        Prefs.setEnabled(this, false)
        Scheduler.cancel(this)
        finish("No chat to continue. Auto-continue turned OFF. Screen: " + screenDump(rootInActiveWindow))
        Notify.post(this, 12, "Claude Wake turned off", "No chat found to continue.")
    }

    private fun finish(msg: String) {
        running = false
        Prefs.setStatus(this, msg)
    }

    // ---------- Optional: enter the user's PIN on the lock screen (opt-in) ----------
    private var unlocking = false

    /** Enters the PIN at most ONCE per call, to avoid triggering Android's wrong-PIN lockout. */
    fun unlockWithPin(pin: String, onResult: (Boolean) -> Unit) {
        if (unlocking) return
        unlocking = true
        val km = getSystemService(KeyguardManager::class.java)
        var tries = 0
        var verify = 0
        var pinEntered = false
        lateinit var step: Runnable
        fun end(ok: Boolean) {
            unlocking = false
            if (!ok && pinEntered) {
                // PIN was typed but the phone is still locked: stop to avoid lockout.
                Prefs.setUnlockMode(this, Prefs.NONE)
                Prefs.setStatus(this, "PIN entry did not unlock. Auto-unlock turned OFF to avoid lockout. Re-enable it after checking your PIN.")
                Notify.post(this, 13, "Auto-unlock turned off", "PIN entry failed. Check the PIN and re-enable.")
            } else if (!ok) Prefs.setStatus(this, "Couldn't find the lock-screen keypad.")
            onResult(ok)
        }
        step = Runnable {
            if (!km.isKeyguardLocked) { end(true); return@Runnable }
            if (pinEntered) {
                if (++verify > 6) end(false) else handler.postDelayed(step, 700)
                return@Runnable
            }
            if (++tries > 25) { end(false); return@Runnable }
            val root = rootInActiveWindow
            val keypadShown = root?.let { r -> findNode(r) { label(it).trim() == "5" } } != null
            if (!keypadShown) { swipeUp(); handler.postDelayed(step, 1200); return@Runnable }
            pinEntered = true
            enterDigits(pin, 0) { pressEnter(); handler.postDelayed(step, 1000) }
        }
        handler.postDelayed(step, 1500)
    }

    private fun enterDigits(pin: String, i: Int, done: () -> Unit) {
        if (i >= pin.length) { done(); return }
        val d = pin[i].toString()
        val node = rootInActiveWindow?.let { r -> findNode(r) { label(it).trim() == d } }
        if (node == null) { done(); return }
        click(node)
        handler.postDelayed({ enterDigits(pin, i + 1, done) }, 200)
    }

    private fun pressEnter() {
        val root = rootInActiveWindow ?: return
        findNode(root) {
            val l = label(it).trim().lowercase()
            l == "enter" || l == "ok" || l == "done"
        }?.let { click(it) }
    }

    private fun swipeUp() {
        val m = resources.displayMetrics
        val path = Path().apply {
            moveTo(m.widthPixels / 2f, m.heightPixels * 0.85f)
            lineTo(m.widthPixels / 2f, m.heightPixels * 0.25f)
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(),
            null, null
        )
    }

    // ---------- Optional: replay the user's lock-screen pattern (opt-in) ----------
    private var unlockingPattern = false

    /**
     * Replays the pattern once.
     *
     * Where the dots sit comes from the phone itself: the live lock-screen pattern view when
     * SystemUI exposes it (and it is remembered for next time), otherwise the grid captured from
     * the real lock screen, otherwise a centred estimate.
     */
    fun unlockWithPattern(order: List<Int>, onResult: (Boolean) -> Unit) {
        if (unlocking || unlockingPattern) return
        unlockingPattern = true
        val km = getSystemService(KeyguardManager::class.java)
        var stage = 0 // 0 = reveal keyguard, 1 = draw, 2 = verify
        var checks = 0
        lateinit var step: Runnable

        fun end(ok: Boolean, drawn: Boolean) {
            unlockingPattern = false
            if (!ok && drawn) {
                Prefs.setUnlockMode(this, Prefs.NONE)
                Prefs.setStatus(this, "The pattern didn't unlock the phone. Auto-unlock turned OFF to avoid lockout.")
                Notify.post(this, 14, "Auto-unlock turned off", "Pattern entry failed. Check the pattern, then re-enable.")
            } else if (!ok) {
                Prefs.setStatus(this, "Couldn't reach the lock-screen pattern. Unlock the phone manually.")
            }
            onResult(ok)
        }

        step = Runnable {
            if (!km.isKeyguardLocked) { end(true, stage > 0); return@Runnable }
            when (stage) {
                0 -> {
                    val visible = patternBounds() != null
                    if (!visible) swipeUpEdge()
                    stage = 1
                    handler.postDelayed(step, if (visible) 300 else 1500)
                }
                1 -> {
                    val live = patternBounds()
                    // Remember the phone's own grid the moment we can see it.
                    if (live != null) saveGridFrom(live)
                    val pts = if (live != null) gridPoints(order, live) else storedPoints(order)
                    if (pts.size != order.size || pts.size < 2) { end(false, false); return@Runnable }
                    Prefs.setStatus(this, "Drawing the unlock pattern…")
                    stage = 2
                    dispatchPattern(pts) { handler.postDelayed(step, 1300) }
                }
                else -> {
                    if (++checks > 7) end(false, true) else handler.postDelayed(step, 700)
                }
            }
        }
        handler.postDelayed(step, 1200)
    }

    private fun gridPoints(order: List<Int>, b: Rect): List<PointF> {
        val side = min(b.width(), b.height()).toFloat()
        return gridPoints(
            order,
            b.left + (b.width() - side) / 2f,
            b.top + (b.height() - side) / 2f,
            side
        )
    }

    private fun gridPoints(order: List<Int>, left: Float, top: Float, side: Float): List<PointF> {
        val cell = side / 3f
        return order.map { i -> PointF(left + cell * (i % 3 + 0.5f), top + cell * (i / 3 + 0.5f)) }
    }

    /**
     * The grid the phone itself uses.
     *
     * Order of truth: the grid captured from the real lock screen, then per-dot coordinates an
     * older install stored, then a centred estimate (72% of the width at 55% of the height).
     */
    private fun storedPoints(order: List<Int>): List<PointF> {
        val (w, h) = Screen.size(this)
        val grid = SecretStore.loadGrid(this)
        if (grid != null) {
            return gridPoints(order, grid.first * w, grid.second * h, grid.third * w)
        }
        val legacy = SecretStore.loadPatternPoints(this)
        if (legacy.size == order.size && legacy.isNotEmpty()) {
            return legacy.map { PointF(it.first * w, it.second * h) }
        }
        val side = w * DEFAULT_SIDE
        return gridPoints(order, (w - side) / 2f, h * DEFAULT_CENTER_Y - side / 2f, side)
    }

    // ---------- Learn the real grid from the lock screen ----------

    private var capturingGrid = false
    private var captureTries = 0

    /**
     * Waits for the user to lock the phone and swipe up, then remembers where the phone itself
     * drew the 3x3 dots. After that the replay lands exactly on the real dots - no eyeballing.
     */
    fun captureGrid() {
        if (capturingGrid) return
        capturingGrid = true
        captureTries = 0
        Prefs.setStatus(this, "Waiting for the lock screen — swipe up so the dots show.")
        handler.postDelayed(captureTick, 500)
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (!capturingGrid) return
            val km = getSystemService(KeyguardManager::class.java)
            val b = if (km.isKeyguardLocked) patternBounds() else null
            if (b != null) {
                capturingGrid = false
                saveGridFrom(b)
                return
            }
            if (++captureTries > 600) { // ~5 minutes
                capturingGrid = false
                Prefs.setStatus(this@ClaudeAccessibilityService,
                    "Couldn't see the lock-screen dots. Tune the grid by hand instead.")
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    /** Stores the phone's own dot grid as screen fractions, so it survives rotation/resolution changes. */
    private fun saveGridFrom(b: Rect) {
        val (w, h) = Screen.size(this)
        val side = min(b.width(), b.height()).toFloat()
        val left = b.left + (b.width() - side) / 2f
        val top = b.top + (b.height() - side) / 2f
        SecretStore.saveGrid(this, left / w, top / h, side / w)
        Prefs.setStatus(this, "Lock-screen grid captured. Replay now matches your phone exactly.")
    }

    private fun patternBounds(): Rect? {
        val (sw, sh) = Screen.size(this)
        val roots = (windows.mapNotNull { it.root } + rootInActiveWindow).filterNotNull()
        for (r in roots) {
            val all = mutableListOf<AccessibilityNodeInfo>()
            flatten(r, all)
            for (n in all) {
                val id = n.viewIdResourceName?.lowercase() ?: ""
                val cls = n.className?.toString() ?: ""
                val looksLikePattern = id.contains("lockpattern") || id.contains("patternview") ||
                    cls.contains("LockPatternView") || cls.contains("PatternView")
                if (!looksLikePattern || !n.isVisibleToUser) continue
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.width() <= 0 || b.height() <= 0) continue
                val ratio = b.width().toFloat() / b.height().toFloat()
                // Guard against unrelated "pattern" nodes: must be a large, roughly square view.
                if (ratio in 0.7f..1.4f && b.width() > sw * 0.35f && b.top > sh * 0.15f) return b
            }
        }
        return null
    }

    private fun dispatchPattern(points: List<PointF>, done: () -> Unit) {
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        val duration = (180L + 90L * points.size).coerceIn(300L, 1400L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val sent = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = done()
            override fun onCancelled(g: GestureDescription?) = done()
        }, handler)
        if (!sent) done()
    }

    /** Swipe up along the edge so the stroke never crosses the pattern dots (which would count as an attempt). */
    private fun swipeUpEdge() {
        val (w, h) = Screen.size(this)
        val x = w * 0.06f
        val path = Path().apply {
            moveTo(x, h * 0.88f)
            lineTo(x, h * 0.30f)
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 280)).build(),
            null, null
        )
    }

    // ---------- helpers ----------
    /**
     * A node's readable name. Buttons keep their label in the content description while their
     * text is an empty string, so text alone is not enough - that is exactly why "Send" used to
     * look like an unlabelled button.
     */
    private fun label(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) return t
        return n.contentDescription?.toString()?.trim() ?: ""
    }

    private fun flatten(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) flatten(n.getChild(i), out)
    }

    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>(); flatten(root, all)
        return all.firstOrNull(pred)
    }

    private fun collectText(root: AccessibilityNodeInfo): String {
        val all = mutableListOf<AccessibilityNodeInfo>(); flatten(root, all)
        return all.joinToString(" ") { label(it) }
    }

    private fun firstChatAfter(root: AccessibilityNodeInfo, header: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>(); flatten(root, all)
        val idx = all.indexOfFirst { it == header }
        if (idx < 0) return null
        for (i in idx + 1 until all.size) {
            val t = label(all[i]).trim()
            val lower = t.lowercase()
            if (t.isEmpty() || SKIP_LABELS.any { lower.startsWith(it) } || isNavLabel(lower)) continue
            if (lower in clickedRows) continue
            var p: AccessibilityNodeInfo? = all[i]
            while (p != null && !p.isClickable) p = p.parent
            if (p != null) { clickedRows.add(lower); return p }
        }
        return null
    }

    private fun isNavLabel(lower: String) = lower in NAV_LABELS ||
        lower.startsWith("new ") || lower.startsWith("create ") || lower.startsWith("add ") ||
        lower.startsWith("see all") || lower.startsWith("show all")

    /** A visible text box: either flagged editable or one that accepts ACTION_SET_TEXT. */
    private fun isTextInput(n: AccessibilityNodeInfo) = n.isVisibleToUser &&
        (n.isEditable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })

    /** Short list of the labels on screen, shown in the status line when a run fails. */
    private fun screenDump(root: AccessibilityNodeInfo?): String {
        if (root == null) return "(no window)"
        val all = mutableListOf<AccessibilityNodeInfo>(); flatten(root, all)
        return all.map { label(it).trim() }.filter { it.isNotEmpty() }.distinct().take(25)
            .joinToString(" | ") { if (it.length > 30) it.take(30) + "…" else it }
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = n
        while (p != null && !p.isClickable) p = p.parent
        return p
    }

    private fun click(n: AccessibilityNodeInfo): Boolean =
        clickableAncestor(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

    /** First row that looks like a conversation, used inside a project where there is no header. */
    private fun firstChatRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val target = Prefs.project(this)
        val all = mutableListOf<AccessibilityNodeInfo>()
        flatten(root, all)
        for (n in all) {
            val t = label(n).trim()
            if (t.isEmpty() || t.length > 80) continue
            if (target.isNotEmpty() && t.equals(target, true)) continue
            val lower = t.lowercase()
            if (lower in SKIP_LABELS || isNavLabel(lower)) continue
            if (lower in clickedRows) continue
            val p = clickableAncestor(n) ?: continue
            clickedRows.add(lower)
            return p
        }
        return null
    }
}
