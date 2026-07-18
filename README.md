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
  → POST raw transcript + focused-field context + your dictionary to /chat/completions
  → direct write for empty fields, transient paste when appending
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
| **Groq Cloud** (recommended, free tier) | `https://api.groq.com/openai/v1` | `whisper-large-v3-turbo` | `openai/gpt-oss-20b` |
| **OpenAI** | `https://api.openai.com/v1` | `whisper-1` | `gpt-4o-mini` |
| **Ollama (local)** | `http://<your-ip>:11434/v1` | not supported — Ollama has no Whisper endpoint | `llama3.2` |

To use Ollama for cleanup only, point Groq at transcription and Ollama at cleanup. Voicey lets you set the two base URLs separately if you fork the `Settings` data class.

## Custom vocabulary and prompt

In settings:

- **My dictionary** — add names, jargon, and project terms; inspect or remove every correction Voicey has learned; or teach a `wrong → right` correction manually.
- **Language** — choose multilingual auto-detection or pin a supported language for better accuracy and latency on short dictations. Groq's current Whisper models support 99+ languages.
- **System prompt** — overrides the default cleanup behavior. The default (lifted with light edits from FreeFlow) is conservative: removes fillers, fixes spelling, preserves intent. Replace it if you want a different style.

Groq model suggestions are kept intentionally conservative. As of July 2026 the supported speech models are `whisper-large-v3-turbo` (fastest/best value) and `whisper-large-v3` (highest accuracy). The app recommends `openai/gpt-oss-20b` or `openai/gpt-oss-120b` for cleanup and warns when a saved Groq model has a published shutdown.

## Privacy

- Audio leaves your device only to the API endpoint you configure (e.g., Groq).
- The cleanup request sends the raw transcript, the focused field's surrounding text and hint, the foreground app's package name, your custom vocabulary, and your learned corrections to the same endpoint.
- Nothing is sent to anyone else. No telemetry. No analytics. No server owned by this project — there is no server.
- API keys are stored in `EncryptedSharedPreferences` (Android Keystore-backed AES-256).
- Most dictations never touch the clipboard at all: empty fields are written directly. Appending to a field that already has content uses a transient clip that is cleared (or your previous clip restored) about half a second later and flagged sensitive. Caveat: OEM keyboard clipboard panels (Samsung, Gboard) may still capture that brief transit for non-empty-field dictations.
- **Learning from your fixes** ("Learn from my spelling fixes", on by default): if you correct a word right after a dictation lands — say Voicey wrote `VHISPERFLOW` and you fix it to `Wispr Flow` — the pair is learned and applied to future dictations. Learning watches only the field Voicey just dictated into, for at most 45 seconds, never password fields. Pairs are stored on-device in the same encrypted prefs and are erasable anytime via "Clear learned corrections".
- **Never use the clipboard** (optional): strict mode reconstructs accessible field text around the cursor and writes it directly. Editors that hide their text or reject accessibility writes fail visibly instead of falling back to a temporary clipboard paste. Leave it off for maximum WebView/rich-editor compatibility.
- **Smart app-aware formatting** (on by default): Voicey maps common email, messaging, notes, and document apps to conservative formatting profiles. It uses paragraphs for longer prose, bullets for genuine groups of points, and numbered lists for ordered steps—without forcing every dictation into a list. Focused-field hints keep subject, recipient, and search fields concise.
- Cleanup responses are checked before insertion. If a model echoes Voicey's internal context labels, vocabulary, corrections, or request metadata, the response is discarded and the original Whisper transcript is inserted instead.

## Releasing (maintainer only)

Voicey uses a deliberately **shared-signing-key** model. The release keystore is committed at `app/voicey-release.keystore`, so GitHub Actions can produce the same signing identity on every run without maintainer secrets or a local Android setup.

Practical consequence: anyone who clones the repo can build an APK signed with this certificate. Install APKs only from this repository's Actions or Releases pages. This convenience tradeoff is intended for Voicey's current direct, sideloaded distribution; a Play Store or security-sensitive distribution should move to a private keystore before its first public build.

Certificate SHA-256: `54:27:65:5E:E3:6A:BC:BC:10:93:FB:56:D4:25:61:72:01:51:1B:6B:E7:83:E5:D0:E4:E6:84:76:CF:F1:45:17`

Both debug artifacts and release APKs use this certificate. Once a stable-signed Voicey build is installed, later GitHub Actions APKs update it in place and retain settings and Android permissions. Builds older than v0.1.9 used temporary CI certificates, so moving from one of those builds requires one final uninstall/reinstall.

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
