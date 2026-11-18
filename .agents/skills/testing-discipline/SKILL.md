---
name: testing-discipline
description: Use when changing product logic, adding features, or fixing regressions in VireoAirTV. Selects relevant checks and records what CI, emulator and device runs actually covered.
---

# Testing Discipline

- Identify the changed behavior and run its focused check first; for functional fixes require a check that detects the old defect.
- JVM unit tests (JUnit 6) live in `app/src/test/`; run `./gradlew testDebugUnitTest`. Code that touches Android APIs needs a device or emulator check; do not report tests that do not exist.
- Do not weaken existing checks. Record any unavailable check rather than treating it as passed.
- For mirroring, audio, HLS video, latency, memory, boot start or remote input, name the physical TV check still needed; emulator runs and doubles do not validate hardware decoding or real network behavior.

## Validation matrix (report each separately)
- Discovery: receiver appears/disappears on enable/disable and survives Wi-Fi/Ethernet/IP change.
- Session: PIN pairing, connect, disconnect and reconnect are repeatable.
- Mirroring: image and audio in sync, no persistent freeze; rotation and aspect correct.
- Audio (RAOP): AAC/ALAC playback, metadata, volume.
- Video (HLS): play, pause, seek, stop.
- Lifecycle: cold boot with `boot_auto_start`, process killed by the system, app in background while another TV app is open.
- Resources: memory, CPU and native resources released after long sessions (use the `sanitize` build type: HWASan + UBSan on arm64).

## Convert failures into regression coverage
- Turn a reproducible error into the smallest fixture or scripted reproduction, then check the correction and adjacent paths. State when a before/after comparison was not run.
- Classify a failed check before acting: product logic, test fixture, build, native patch application or environment. Read its evidence; do not change unrelated code to make a run green.

## Checks that exist for this repo
- Native: `scripts/native-regress.sh` builds host ASan/UBSan tests from `app/src/test/native/`; a new native fix adds a test that fails on the unpatched source.
- Provisioning scripts: run `provision.sh` with a fake `adb` first on `PATH` that logs its arguments; check the command sequence, a refused connection, a missing APK and a name with spaces and an apostrophe. `provision.ps1` cannot run here: say so.
- Manifest: after permission or component edits, read the merged manifest under `app/build/intermediates/merged_manifest*/`; libraries can add permissions back.
- Upstream changes: compile all three ABIs right after exporting patches; a patch can reference members our base lacks.

## Security and release gates
- Classify each security report as proven defect, exposure with stated preconditions, or unverified suspicion. The receiver is exposed to the LAN: pair fixes with adversarial cases (unauthenticated session, malformed packets, oversized payloads, concurrent connections).
- Distinguish a merged PR, successful CI, triggered release workflow and published signed APK. Confirm tag, workflow conclusion and artifacts before saying a version shipped.
- Release CI does not establish lower latency, lower RAM usage or success on a physical TV; require before/after device measurements for those claims.
