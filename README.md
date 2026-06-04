# Voicey

Open-source AI dictation for Android. A mic pill appears above your keyboard whenever you're typing — tap it, speak, and cleaned-up text lands in the focused field, in any app. Bring your own API key. No subscription. No telemetry. No "free for now."

Inspired by [FreeFlow](https://github.com/zachlatta/freeflow) on macOS and the architecture of [Wispr Flow](https://wisprflow.ai)'s Android app — but free forever by construction.

## Download

Grab the latest signed APK and sideload it:

**[➜ Download voicey.apk (latest release)](https://github.com/PrashikshitSaini/voicey/releases/latest/download/voicey.apk)**

On your phone:
1. Open the link above, download the APK.
2. Tap to install. Android will ask you to enable "Install unknown apps" for whichever file manager / browser you used — grant it once, then tap install.
3. Open Voicey, paste your API key (get a free one at [groq.com](https://console.groq.com/keys)), grant the four permissions, tap **Start bubble**.

Don't see a release link yet? Either:
- No version has been tagged yet (build one yourself from source — see below), or
- Use [Obtainium](https://github.com/ImranR98/Obtainium) and point it at `https://github.com/PrashikshitSaini/voicey` for auto-updates from GitHub Releases.

## How it works

```
mic button held / tapped
  → record 16 kHz mono PCM audio
  → POST to your provider's /audio/transcriptions (Groq, OpenAI, Ollama, etc.)
  → POST raw transcript + focused-field context + your custom vocab to /chat/completions
  → clipboard + ACTION_PASTE into the focused text field
```

No keyboard switch. Works alongside Gboard, SwiftKey, whatever you already use.

## Build it yourself

You do **not** need Android Studio. The build runs in GitHub Actions.

1. Fork this repo.
2. Go to the **Actions** tab on your fork → **Build APK** → **Run workflow**.
3. Wait ~3 minutes. Download the APK artifact from the workflow run.
4. Transfer the APK to your phone and install it (enable "Install unknown apps" for your file manager once).

That's it. No SDK install, no Gradle cache, no IDE.

### Building locally (optional)

If you have a JDK 17 + the Android command-line tools installed, you can build locally:

```bash
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Setup on device

1. Launch Voicey. Paste your API key + base URL. Defaults are Groq Cloud.
2. Tap the four permission buttons in order:
   - **Microphone** — to record audio.
   - **Notifications** — required for the foreground bubble service.
   - **Display over other apps** — for the floating bubble.
   - **Accessibility service** — Voicey needs to detect which text field is focused so it knows where to paste. It reads *only* the focused field's text and the app's package name. Nothing else.
3. Tap **Start bubble**.
4. Open any app and tap a text field. The mic pill fades in just above the keyboard (and fades out when the keyboard closes — never mid-dictation). Tap to start, tap again to stop — or enable **Hold to talk** in settings to record while you hold and stop on release. While you speak, the pill shows a live waveform; gentle tones mark recording start/stop (can be turned off in settings).
5. Drag the pill if it's ever in the way — it re-centers above the keyboard the next time one opens. Prefer the old always-visible behavior? Turn off **Show pill only while typing** in settings. The pill never appears over password fields.

## Providers

Voicey works with any OpenAI-compatible API. Tested:

| Provider | API base | Transcription model | Cleanup model |
|---|---|---|---|
| **Groq Cloud** (recommended, free tier) | `https://api.groq.com/openai/v1` | `whisper-large-v3-turbo` | `llama-3.3-70b-versatile` |
| **OpenAI** | `https://api.openai.com/v1` | `whisper-1` | `gpt-4o-mini` |
| **Ollama (local)** | `http://<your-ip>:11434/v1` | not supported — Ollama has no Whisper endpoint | `llama3.2` |

To use Ollama for cleanup only, point Groq at transcription and Ollama at cleanup. Voicey lets you set the two base URLs separately if you fork the `Settings` data class.

## Custom vocabulary and prompt

In settings:

- **Custom vocabulary** — list of names, jargon, project terms. The cleanup model is told to preserve the spelling if you say them.
- **System prompt** — overrides the default cleanup behavior. The default (lifted with light edits from FreeFlow) is conservative: removes fillers, fixes spelling, preserves intent. Replace it if you want a different style.

## Privacy

- Audio leaves your device only to the API endpoint you configure (e.g., Groq).
- The cleanup request sends the raw transcript, the focused field's surrounding text, the foreground app's package name, your custom vocabulary, and your learned corrections to the same endpoint.
- Nothing is sent to anyone else. No telemetry. No analytics. No server owned by this project — there is no server.
- API keys are stored in `EncryptedSharedPreferences` (Android Keystore-backed AES-256).
- Most dictations never touch the clipboard at all: empty fields are written directly. Appending to a field that already has content uses a transient clip that is cleared (or your previous clip restored) about half a second later and flagged sensitive. Caveat: OEM keyboard clipboard panels (Samsung, Gboard) may still capture that brief transit for non-empty-field dictations.
- **Learning from your fixes** ("Learn from my spelling fixes", on by default): if you correct a word right after a dictation lands — say Voicey wrote `VHISPERFLOW` and you fix it to `Wispr Flow` — the pair is learned and applied to future dictations. Learning watches only the field Voicey just dictated into, for at most 45 seconds, never password fields. Pairs are stored on-device in the same encrypted prefs and are erasable anytime via "Clear learned corrections".

## Releasing (maintainer only)

Voicey uses a **shared-signing-key** model. The release keystore is committed to the repo at `app/voicey-release.keystore` and is treated as public infrastructure — same pattern F-Droid and many open-source Android projects use. There is no secret to manage; trust comes from the GitHub Releases URL, not from the signing certificate being private.

Practical consequence: anyone who clones the repo can build an APK signed with the same certificate. For sideloaded open-source distribution this is fine. For Play Store distribution you'd want a private keystore instead.

### One-time: generate the shared keystore (in CI, not on your laptop)

You need **zero local tooling** — no JDK, no Docker, nothing.

1. Open the repo's Actions tab: https://github.com/PrashikshitSaini/voicey/actions
2. Pick the **Generate signing keystore** workflow on the left.
3. Click **Run workflow** → **Run workflow**.
4. Wait ~30 seconds. The workflow generates the keystore and commits it to `main` as `app/voicey-release.keystore`.
5. `git pull` to bring the keystore into your local clone (optional — you don't actually need it locally).

This is a one-time step for the lifetime of the project. Don't re-run it unless you're willing to ask every existing user to uninstall before updating.

### Cutting a release

```bash
git tag v0.1.0
git push origin v0.1.0
```

The `Release APK` workflow runs, signs with the committed keystore, and publishes. The APK lands at `https://github.com/PrashikshitSaini/voicey/releases/latest/download/voicey.apk`.

## License

[MIT](./LICENSE). System prompt borrowed under the spirit of FreeFlow's MIT license.

## Limitations

- Clipboard-based paste fails in a small number of apps that block ACTION_PASTE from accessibility services (some banking apps, some keyboards' own text fields).
- "Hold to talk" requires the bubble to be on screen — there is no equivalent of a global hardware key on Android.
- Battery: the foreground service is lightweight but not free. Stop the bubble when you're done.
