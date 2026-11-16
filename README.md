# Claude Wake

> **Unofficial.** Not affiliated with, endorsed by, or sponsored by Anthropic. "Claude" is a trademark of Anthropic, PBC. This project only interacts with the official Claude Android app installed on your own device, using the app's own user interface.

An Android app that watches the Claude usage-limit banner, waits for the limit to reset, wakes your phone, and sends a follow-up message (default: `continue`) in your most recent chat.

The app is installed as **Claude Wake** (`com.example.claudewake`), version 1.4.

## How it works

1. While the Claude app is open and showing a limit banner, the accessibility service reads the on-screen text and looks for a message such as "Your limit will reset at 3 PM" or "resets in 2 hours".
2. It schedules an exact alarm one minute after the stated reset time. If exact alarms are not permitted, it falls back to an inexact alarm, which may fire a few minutes late.
3. At that time it wakes the screen and, if you opted in, unlocks the phone with your stored PIN or pattern.
4. It opens the Claude app and opens your most recent chat, or a project you chose in the app, types your message, and presses Send.
5. If the limit is still active, it reads the new reset time and reschedules instead of sending.

## Requirements

- Android 9 or newer (the app targets Android 14).
- The Claude app installed and signed in on the same phone.
- The Claude app set to **English**. The reset-time detector matches English phrases only.
- The accessibility service enabled (see Setup).
- The **Auto-continue** switch turned on. Nothing is read or scheduled while it is off.
- The phone unlocked when the alarm fires, or auto-unlock enabled.
- For pattern auto-unlock: the real dot grid captured once from your lock screen.

## Read this before using it

### Terms of service

Anthropic's Consumer Terms of Service restrict accessing Claude through automated or non-human means (bots, scripts) except via the API or where Anthropic explicitly permits it. This app automates the Claude app's UI, so using it may violate those terms and could put your account at risk. The terms are updated from time to time, so read the current version at <https://www.anthropic.com/legal/consumer-terms> and decide for yourself. The API with your own key is the compliant alternative. Nothing here is legal advice.

### Accessibility service

The app needs an accessibility service, one of Android's most powerful capabilities. It is configured to receive events from exactly two packages: `com.anthropic.claude` and `com.android.systemui` (the lock screen, used only for the optional auto-unlock and for capturing the pattern grid). The service's code only reads and acts on those screens. Like any accessibility service it can dispatch touch gestures, so it is a permission you should grant deliberately; review the source before installing.

### Optional PIN / pattern auto-unlock

- Off by default. If enabled, your PIN or pattern is stored encrypted (AES-256-GCM, 256-bit) with a non-exportable Android Keystore key, in app-private storage. Backups are disabled.
- The key is not tied to your biometrics or your lock screen and is not itself protected by a device credential, so on a rooted or otherwise compromised device the stored value could be recovered. If that concerns you, do not enable this.
- The app makes **one** unlock attempt. If it actually entered the PIN or drew the pattern and the phone is still locked, it turns auto-unlock off and notifies you, to avoid Android's wrong-attempt lockout. If it cannot reach the lock-screen keypad or dots, it reports that and leaves the setting alone.
- Do not install a **debug** build if you use this feature. Debug builds are debuggable, so anything with adb access to the phone can read the app's private data.

### Privacy

- The app declares **no `INTERNET` permission** and has **no third-party dependencies**, so it cannot make network connections and contains no analytics, telemetry, ads or tracking SDKs.
- It does not use contacts, SMS, location, camera, microphone, or the clipboard.
- It reads the lock screen's window layout only when you capture the grid or when auto-unlock is actually unlocking. It never reads the lock screen for any other purpose.
- Project names seen in Claude's sidebar are recorded locally so you can pick a target.
- Everything the app stores stays in its private storage; there is no account, no sync, and no cloud component.

### What is stored on the device

| Data | Where | Protection |
|---|---|---|
| Message, chosen project, recorded project names, status line, last reset time, icon choice | app-private SharedPreferences | app sandbox only |
| PIN, pattern, pattern grid | app-private SharedPreferences | AES-256-GCM with a non-exportable Keystore key |

## Known limitations

- It works by reading Claude's UI, so a Claude app update can break it.
- It may tap the wrong chat if the layout changes. Check the status line on the main screen after a wake-up.
- **English only.** If Claude's interface is in another language, no reset time will be detected.
- The limit banner must be visible in Claude while the service is running, so open Claude when you want the reset time read. It cannot detect a limit that appeared while Claude was closed.
- **A scheduled wake-up does not survive a reboot.** There is no boot receiver, so after restarting your phone, open Claude again to re-arm it.
- Aggressive battery management (ColorOS, MIUI, One UI and similar) can delay or kill the alarm. The app does not request a battery-optimisation exemption; allow it to run in the background in your phone's settings if wake-ups are late.
- The automation gives up after about 30 UI steps and holds a 90-second wake lock, so a very slow phone or a stuck screen can end the attempt without sending.
- On a phone with a secure lock and auto-unlock off, the app cannot unlock it, and the message will not be sent.
- On Android 13+, sideloaded apps may need "Allow restricted settings" in App info before the accessibility service can be enabled.
- Play Protect may warn about sideloaded apps that use accessibility services.
- The package name is the `com.example.claudewake` placeholder. Change it before publishing anywhere that rejects placeholder IDs.

## Permissions

| Permission | Why |
|---|---|
| `WAKE_LOCK` | Turn the screen on at reset time |
| `SCHEDULE_EXACT_ALARM` | Fire at the reset minute; falls back to an inexact alarm if denied |
| `USE_FULL_SCREEN_INTENT` | Show the wake screen from the background |
| `DISABLE_KEYGUARD` | Dismiss the lock screen when allowed |
| `POST_NOTIFICATIONS` | Status and failure notifications |
| Accessibility service | Read the reset time and operate the Claude app and lock screen |

## Build

Requirements: Android Studio, or JDK 17 plus Android SDK 34. The Gradle wrapper (Gradle 9.6.0) is included, so no local Gradle install is needed.

```bash
./gradlew assembleDebug      # debug build (do not use with PIN or pattern unlock)
./gradlew assembleRelease    # unsigned until you add your own signing config
```

The APK is written to `app/build/outputs/apk/debug/` or `app/build/outputs/apk/release/`. Release builds are named `app-release-unsigned.apk` until a signing config is added.

`local.properties` is not committed, because it holds a machine-specific SDK path. Android Studio creates it when you open the project; otherwise create it yourself:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Setup

1. Install the app and open it.
2. Allow notifications when prompted.
3. Under **Permissions**, tap **Accessibility service** and enable **Claude Wake**. On Android 13+, you may first need **App info → Allow restricted settings**.
4. Under **Permissions**, tap **Exact alarms** and **Full-screen wake** and allow both.
5. Turn on **Auto-continue**. Nothing is recorded or scheduled while it is off.
6. Optional:
   - Edit the **message** (default `continue`).
   - Choose a **project** under "Continue in", or leave it on the most recent chat. "Record from Claude" reads the project names from Claude's sidebar.
   - Choose an unlock method under **Auto-unlock**: **Off**, **PIN**, or **Pattern**.
7. For **Pattern**, the replay needs your phone's real dot grid:
   - Tap **Pattern**, draw your pattern, and tap **Save pattern**.
   - On the same screen, tap **Capture from lock screen ›**, then **Start**.
   - Lock the phone, swipe up so the dots appear, and unlock normally. The app only reads where the dots are; it does not draw your pattern, so no unlock attempt is used.
   - When the card reads "Exactly as your phone places it", pattern unlock is ready.
8. Open Claude while the limit message is showing so the reset time is read. The status line then shows when the app will wake.

### Changing the app icon

Under **App icon**, choose **Dark**, **Light**, or **Mono**. Android cannot repaint a launcher icon at runtime, so the app ships three launcher entries and enables the one you pick; the other two are disabled, so only one icon appears in the app drawer. The launcher applies the new icon immediately in most cases; if your home screen still shows the old one, it refreshes after the app restarts.

The three icons are built from the artwork in `AppIcons/`, including Android 13 themed-icon support. If you use a third-party launcher icon pack, it may restyle the icon on your home screen; the app drawer icon is the one this setting controls.

## Uninstall / revoke

Set **Auto-unlock** to **Off**: that erases the stored PIN, pattern and grid. Then disable the accessibility service in Android settings and uninstall. Uninstalling removes all stored data, including the encrypted secret.

## License

MIT. See [`LICENSE`](LICENSE).

The icon artwork is unofficial fan art derived from Claude's visual identity and is not part of, or endorsed by, Anthropic.

## Disclaimer

Provided as is, with no warranty. Automating the Claude app may break the terms you agreed to and could affect your account. Use at your own risk.

This app is not distributed through Google Play. If you did not build an APK from this repository yourself, you cannot know what it contains; only install builds you trust.
