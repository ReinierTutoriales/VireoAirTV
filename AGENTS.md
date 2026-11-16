# VireoAirTV — Agent Instructions

## Project
AirPlay receiver for Android TV, independent of the VireoLumaTV browser. Derived from `jqssun/android-airplay-server` (GPL-3.0), which uses UxPlay (C) through JNI. See `docs/ARQUITECTURA.md` (target), `docs/AUDITORIA.md` (verified state and gaps) and `docs/UPSTREAM.md` (sync procedure).

## Stack (verified at import, upstream a46cca0)
- Kotlin + Jetpack Compose, Hilt, Media3/ExoPlayer (HLS), Oboe, MediaCodec.
- Native: UxPlay, libplist, OpenSSL, FFmpeg (ALAC only) as Git submodules; CMake + NDK 27; JNI bridge in `app/src/main/cpp/`.
- compileSdk/targetSdk 37, minSdk 23. ABIs: arm64-v8a, armeabi-v7a, x86_64. Module: `:app`.

## Build
```bash
git submodule update --init --recursive
./gradlew assembleDebug        # sanitize build: ./gradlew assembleSanitize
```
The `applyUxplayPatches` task applies `app/src/main/cpp/patches/UxPlay/*.patch` before CMake configure. There is no unit-test source set yet.

## Priorities
- Stable, recoverable receiver on Android TV/Google TV: discovery, sessions, mirroring, audio and HLS video.
- Low memory and low latency on TV hardware; select behavior by API level and reported codec capabilities, never by model.
- Do not invent APIs, protocol features or device behavior not present in the code or official documentation.
- Keep GPL-3.0 obligations and upstream credits.

## Skills
Read only the relevant `.agents/skills/` instructions: `agent-governance` before Git mutations, `precise-coding` for implementation, `testing-discipline` for functional changes, `code-quality` for refactors and reviews, `tv-ui` for focus/layout/overlay work. Use `no-ai-slop` (third-party, MIT; see NOTICE.md) for prose only: documentation, commit and PR text; it never overrides the verification rules here. Claude Code loads `no-ai-slop` through the `.claude/skills/no-ai-slop` symlink.

## Method
1. Record base SHA, reproducible failure, expected behavior and consumers of the code being changed.
2. One cause per commit. Separate logic, UI, dependencies and native patches unless technically inseparable.
3. Report only what was run. Never describe a change as faster or lighter without a measurement, or as validated on TV without a device run; say "pendiente de validación física".
4. Do not change `applicationId`, signing or version, and do not publish releases, as a side effect.
