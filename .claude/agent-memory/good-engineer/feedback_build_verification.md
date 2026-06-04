---
name: build-verification-via-ci
description: Never install a local JDK/Android SDK — GitHub Actions "Build APK" workflow is the only compiler for this repo
metadata:
  type: feedback
---

Verify Kotlin/Android changes by pushing a feature branch and dispatching the **Build APK** workflow (`gh workflow run "Build APK" --ref <branch>`); never install a JDK or Android SDK locally.

**Why:** User was emphatic ("I don't want to install any fucking SDK on my Mac") — the repo is deliberately designed so all builds happen in CI (README documents this; no gradlew wrapper committed). The machine has no JDK, no SDK, no gradle.

**How to apply:** Any change touching `app/` needs a branch push + CI dispatch before claiming it compiles. Iterate until green. On-device behavior (overlay positioning, keyboard detection timing on Samsung OneUI) can only be verified by the user sideloading the APK artifact — say so explicitly instead of claiming verified. See [[user-profile]].
