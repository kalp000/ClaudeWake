package com.example.claudewake

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle

/**
 * Shows over the lock screen and dismisses the keyguard.
 *  - swipe / no lock: handled by Android.
 *  - PIN lock: only if the user opted in (PIN stored encrypted on-device).
 *  - Pattern lock: replays the pattern the user drew during setup.
 */
class WakeActivity : Activity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { go(); return }

        val mode = Prefs.unlockMode(this)
        val secure = mode != Prefs.NONE

        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = go()
            override fun onDismissCancelled() { if (!secure) blocked() }
            override fun onDismissError() { if (!secure) blocked() }
        })

        if (!secure) return
        val svc = ClaudeAccessibilityService.instance
        if (svc == null) {
            Prefs.setStatus(this, "Accessibility service not running, can't auto-unlock.")
            return
        }

        when (mode) {
            Prefs.PIN -> {
                val pin = SecretStore.loadPin(this)
                if (pin == null) { blocked(); return }
                Prefs.setStatus(this, "Unlocking with PIN…")
                svc.unlockWithPin(pin) { ok -> if (ok) go() }
            }
            Prefs.PATTERN -> {
                val order = SecretStore.loadPattern(this)
                if (order == null) { blocked(); return }
                Prefs.setStatus(this, "Drawing the unlock pattern…")
                svc.unlockWithPattern(order) { ok -> if (ok) go() }
            }
        }
    }

    private fun blocked() {
        Prefs.setStatus(this, "Phone has a secure lock. Set up PIN or pattern auto-unlock in the app, or unlock manually.")
        Notify.post(this, 11, "Claude Wake can't unlock", "Unlock your phone, or set up auto-unlock.")
        go() // still try: works if the user unlocks within the retry window
    }

    private fun go() {
        if (launched) return
        launched = true
        val launch = packageManager.getLaunchIntentForPackage("com.anthropic.claude")
        if (launch == null) { Prefs.setStatus(this, "Claude app not installed"); finish(); return }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ClaudeAccessibilityService.instance?.beginContinue()
            ?: Prefs.setStatus(this, "Accessibility service is not running. Enable it in settings.")
        finish()
    }
}
