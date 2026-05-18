# Voicey

Open-source AI dictation for Android. Speak into a floating bubble, get cleaned-up text pasted into any text field, in any app. Bring your own API key. No subscription. No telemetry. No "free for now."

Inspired by [FreeFlow](https://github.com/zachlatta/freeflow) on macOS and the architecture of [Wispr Flow](https://wisprflow.ai)'s Android app — but free forever by construction.

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
4. A circle appears on your screen. Tap to start, tap again to stop — or enable **Hold to talk** in settings to record while you hold and stop on release.
5. Drag the bubble anywhere. Long-press the screen edge to dock it.

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
- The cleanup request sends the raw transcript, the focused field's surrounding text, the foreground app's package name, and your custom vocabulary to the same endpoint.
- Nothing is sent to anyone else. No telemetry. No analytics. No server owned by this project — there is no server.
- API keys are stored in `EncryptedSharedPreferences` (Android Keystore-backed AES-256).

## License

[MIT](./LICENSE). System prompt borrowed under the spirit of FreeFlow's MIT license.

## Limitations

- Clipboard-based paste fails in a small number of apps that block ACTION_PASTE from accessibility services (some banking apps, some keyboards' own text fields).
- "Hold to talk" requires the bubble to be on screen — there is no equivalent of a global hardware key on Android.
- Battery: the foreground service is lightweight but not free. Stop the bubble when you're done.
