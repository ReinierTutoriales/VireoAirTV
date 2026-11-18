---
name: code-quality
description: Use when optimizing, cleaning, or reviewing code. Prioritizes correctness, service lifecycle robustness, low-memory behavior, playback latency, maintainability and TV remote usability.
---

# Code Quality

- Analyze for real improvements in clarity, stability, latency, memory use and remote navigation.
- Prefer the simplest correct solution that matches existing style.
- Keep changes focused and measurable against low-memory TV constraints. Do not write for the sake of writing.
- Verify against existing checks and code before considering the change complete; note any remaining need for real Android TV validation.
- Follow agent-governance rules on branches, authorship and upstream patches.

## Replacing older code
Replace an implementation only when the alternative improves security, correctness, performance, maintainability or compatibility from minSdk to targetSdk, and say which. Check the current official Android/AndroidX reference first, then keep the replacement inside the project's dependencies:

- Foreground services: `ServiceCompat.startForeground` with the declared type, before the work starts; handle refusal (Android 12+ background start, Android 14+ type permission, Android 15+ `BOOT_COMPLETED` restrictions for dataSync, camera, mediaPlayback, phoneCall, mediaProjection and microphone).
- Local network: API 37 requires `ACCESS_LOCAL_NETWORK`; keep the `hasLocalNetwork()` check before starting discovery or sockets.
- Network changes: react through `ConnectivityManager.NetworkCallback`, debounced, instead of polling.
- Notifications: monochrome small icon, `NotificationCompat`, a category.
- Main-thread work: no blocking I/O, codec configuration or JNI calls that can stall on the main thread.

Renaming, reformatting or moving code to change `git blame` is not a replacement.

## Quality first
- A change that lowers picture or sound quality is a regression, whatever it saves. The 0.0.2 latency changes degraded mirroring and were reverted; the 720 px cover-art cap was visibly blurry.
- Prefer a reference implementation over invention: moonlight-android for decoder setup, surface sizing and `Surface.setFrameRate`; UxPlay's README for codec choices (audio-only mode is ALAC, so ALAC is advertised).
- The mirroring GL blit draws into a buffer the size of the stream (`setFixedSize`), so the display hardware scales once.
- When a change removes or narrows an old behavior, list every path that relied on it before committing (opening the app only for pictures broke PIN pairing until the PIN was made a reason to open).

## Avoid repeating failed optimization choices
- Evaluate codec capabilities independently through `MediaCodecList`/`DecoderSelector`. Do not infer HEVC support from H.264, or efficiency from hardware availability alone.
- Separate a correctness/resource fix from a measured performance improvement. Name the affected resource and tradeoff.
- Smaller APK size, newer APIs and fewer lines alone do not prove lower latency or better playback.

## Security-sensitive reviews
- The receiver accepts connections from the LAN. Treat RAOP/HTTP handlers, pairing, HLS proxy, `SettingsReceiver` (protected by `DUMP`), exported components and FileProvider as separate trust boundaries.
- Validate sizes and lengths before native buffer use; prefer bounded parsing. Check fixes against the existing UxPlay patches before adding new ones.
- Treat a security finding as confirmed only when supported by code and a reproduction; otherwise state the preconditions and remaining validation.
