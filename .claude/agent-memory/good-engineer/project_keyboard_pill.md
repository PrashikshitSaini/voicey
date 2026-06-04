---
name: keyboard-pill-feature
description: Change log + sensitive areas for the keyboard-aware pill rework (branch feature/keyboard-aware-pill, 2026-06-04)
metadata:
  type: project
---

2026-06-04, branch `feature/keyboard-aware-pill`: replaced the always-on 56dp circle bubble with a 160×48dp pill that appears centered above the keyboard (accessibility `TYPE_WINDOWS_CHANGED` → IME window scan), FreeFlow-style spectrum animation while recording, gentle synthesized start/stop tones, model dropdowns, and a defensive `<think>` strip in PostProcessor.

**Why:** bubble complaints (always visible, overlay conflicts, miss-clicks) + user originally wanted IME integration, which Samsung/Gboard make impossible — keyboard-aware overlay is Wispr Flow's actual Android architecture.

**Sensitive areas — read fully before touching:**
- `FloatingBubbleService.applyBubbleVisibility()`: must cancel in-flight fades or the pill strands at alpha-0 while swallowing touches (bug already fixed once — don't reintroduce).
- `FocusAccessibilityService` companion: static listener contract; all state main-thread; `onDestroy` reports keyboard-gone BEFORE nulling would break fallback ordering (`instance = null` must precede `updateKeyboardState`).
- `Recorder` capture loop: reads in 1600-byte chunks for ~20Hz level updates; the AudioRecord internal buffer must stay `bufferSize` — don't "simplify" reads back to full-buffer or the spectrum dies.
- Overlay windows: inflate(null) ignores root XML dimensions — window size MUST be explicit pixels in LayoutParams (the old 28dp-touch-target bug).
- `desiredVisible()` pins the pill during RECORDING/PROCESSING; falls back to always-visible when accessibility is off. Keyboard detection on Samsung OneUI unverified on-device as of writing.

**How to apply:** before modifying bubble visibility, positioning, or recorder internals, re-read these spots and preserve the invariants above.

**2026-06-04 on-device debugging lessons (do not relearn these):**
- `FocusAccessibilityService.currentPackageName()` is event-stream noise — the last event before any action is usually Voicey's own pill re-render or the keyboard package. For "which app is the user in", ALWAYS use `focusedNode.packageName`. This bug silently killed correction learning v1.
- Never use TYPE_WINDOW_STATE_CHANGED as an app-switch/session boundary — Samsung keyboard and our own windows fire it mid-editing. Keyboard-close + timeout are the reliable boundaries.
- Samsung keyboard's clipboard panel snapshots every primaryClip change and ignores EXTRA_IS_SENSITIVE for its own UI — the only real fix is not transiting the clipboard (tier-0 SET_TEXT for empty fields). Background apps can't READ the clipboard on Android 10+ (restore path is dead code on modern devices; clear is the live path).
- Tier-0 direct SET_TEXT MUST keep the `isEditable` gate: WebView virtual nodes leave it false and may hide real content behind empty-looking node.text — without the gate, SET_TEXT destroys user data.

**2026-06-04 addendum (same branch):** clipboard now restore-or-clear after paste (TextInjector — never walk back to SET_TEXT splicing, placeholder bug); CorrectionLearner learns spelling fixes from post-dictation edits (LCS token diff scoped to inserted tokens, precision-first thresholds, 45s session). Invariants: `wantsTextFrom()` gate must stay BEFORE `event.source` fetch in TYPE_VIEW_TEXT_CHANGED (fires every keystroke system-wide); learned terms must stay OUT of the Whisper prompt (echo/hallucination vector — cleanup KNOWN_CORRECTIONS only); the clear-corrections button is the escape hatch for bad learns — never remove it. Learner expects on-device threshold tuning; won't fire in WebViews (no reliable text-change events).
