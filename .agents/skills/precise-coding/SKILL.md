---
name: precise-coding
description: Use when writing or modifying any code (Kotlin, C/C++ JNI, CMake, Gradle). Forces grounded output by verifying against the existing codebase and official documentation. Reduces hallucinations and token waste.
---

# Precise Coding (Anti-Hallucination)

1. Read the relevant existing Kotlin, C/C++ (JNI bridge, UxPlay callbacks), CMake and Gradle files before writing anything.
2. Never invent Android APIs, MediaCodec/AudioTrack/Oboe behaviors, NsdManager behaviors, UxPlay functions or AirPlay protocol features not already present or clearly documented.
3. If unclear, state the assumption explicitly instead of inventing.
4. Prefer the smallest change that matches existing patterns and preserves D-pad UX, playback latency and low-memory behavior.
5. Do not claim correctness or test passage unless reasoned from the actual project files or an executed run.
6. Write only the precise code needed. Avoid speculative refactors or unused code.
7. Follow agent-governance rules: no AI-named branches, no authorship other than the repository owner.

Every line must be traceable to existing code or a clearly labeled minimal assumption.

## Before proposing a replacement
- State the expected user behavior and trace the existing consumers before adding a library or replacing a component. API availability alone does not establish fit.
- Distinguish the AirPlay paths: mirroring (raw H.264/HEVC to MediaCodec), audio (RAOP: AAC/ALAC), and video (HLS through ExoPlayer and the local playlist proxy). A fix for one is not a fix for the others.
- Verify current versions in primary release documentation (Google Maven and Maven Central `maven-metadata.xml`, Gradle `versions/current`, UxPlay releases) and read a library's sources before using an API not seen in this repository.

## JNI and native boundary
- Every Java/Kotlin method called from native code must keep its exact name and signature; check `native_bridge.cpp` and `android_raop_callbacks.c` when renaming or changing a callback. R8 keep rules live in `app/proguard-rules.pro`.
- Native resources (sockets, codecs, threads, JNI global refs) must have a single owner and a release path on session teardown and server stop.
- Native engine changes go as patches under `app/src/main/cpp/patches/UxPlay/`, applied by the `applyUxplayPatches` Gradle task.

## Delivery efficiency
- Work toward the owner's acceptance criterion, not a sequence of preparation-only commits. Map the minimum complete vertical slice and implement related changes together.
- One cause per commit, with all the files that cause needs. To split a working tree that mixes causes, save the full files, restore the first cause's version, build, commit, then restore the full files; each commit must compile on its own.
- After changes, report implemented behavior, validation and remaining gaps precisely. Do not call a component integrated merely because it compiles.

## Single-pass edits
- Before editing, list every consumer of what changes: grep the symbol, its members, its resource IDs, pref keys (`Prefs.kt`) and intent actions in `app/src`, `app/src/main/cpp` and `.github/`.
- Scripted edits must anchor on unique surrounding context and assert one match. Read the resulting hunk before committing.
- Chain shell steps with `&&` or `set -e`. Keep a file's trailing newline when rewriting it.
- Run every check the session can run before pushing (`git diff --check`, build if available); leave what cannot run to CI and say so.
- Query the remote (branches, PRs, releases) before stating repository facts; the previous turn is not evidence.

## Verified platform and tooling facts
- `NewStringUTF` aborts the process on bytes that are not Modified UTF-8; network strings (DACP ids, URLs) are percent-encoded first (`_new_ascii_string`).
- `KEYCODE_HOME` is never delivered to apps; only Back and media keys can be handled.
- `SYSTEM_ALERT_WINDOW` still exempts activity starts from the background; Android 15 removed its exemption for starting foreground services. The service runs as `connectedDevice` (allowed from `BOOT_COMPLETED`; its prerequisite is `CHANGE_WIFI_MULTICAST_STATE`).
- Moving code out of a function annotated `@OptIn(UnstableApi::class)` needs the annotation on the new function, or lint fails with `UnsafeOptInUsageError`.
- `adb shell` joins its arguments into one remote command line: single-quote values. `adb connect` exits 0 when it fails: check its message. Windows PowerShell 5.1 turns stderr of a native command into a terminating error under `ErrorActionPreference = Stop`.
- In PowerShell, `curl` is an alias of `Invoke-WebRequest`; use `curl.exe` in copy-paste commands.

## Commit messages state only what was checked
- "The APK is unchanged" needs the merged manifest or APK diff to prove it; a `debugImplementation` line changes the debug APK. Describe what was measured or inspected, nothing beyond it.

## Professional code, not filler
- Match the surrounding naming, structure and comment density. Comments state why (contract, platform fact, measured cost), never what the next line does.
- No speculative helpers, wrappers, logging or defensive branches the callers do not need; delete code made dead by the change in the same commit.
- Put measured numbers and the method in the commit or PR, not in the code.
