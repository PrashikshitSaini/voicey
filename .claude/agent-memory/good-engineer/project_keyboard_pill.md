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
