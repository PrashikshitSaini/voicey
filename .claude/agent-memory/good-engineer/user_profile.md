---
name: user-profile
description: Maintainer of voicey; Samsung/Gboard user who won't switch keyboards; product instincts referenced to Wispr Flow and FreeFlow
metadata:
  type: user
---

Prashikshit is the sole maintainer of voicey (open-source Android AI dictation, BYO API key). Daily driver is Samsung/Gboard and he **won't switch keyboards** — this killed the voice-IME-subtype integration path (Gboard/Samsung hardcode their voice input; only HeliBoard/FlorisBoard-class keyboards honor third-party voice IMEs).

He benchmarks UX against **Wispr Flow** (Android: keyboard-aware floating bubble, NOT an IME) and **FreeFlow** (macOS, zachlatta/freeflow — source of the spectrum-bars animation and audio-level normalizer we ported). When he says "make it like X," fetch X's actual implementation rather than guessing.

Working style: fast conversational iterations, approves designs quickly, skips formal docs ("don't worry about making a doc, just start coding"), expects work done right the first time so he doesn't have to revisit. Asks "what did the hook say?" — relay hook findings honestly, triaged into real vs stale/mid-batch noise. See [[build-verification-via-ci]].
