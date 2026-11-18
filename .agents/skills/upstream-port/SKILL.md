---
name: upstream-port
description: Use when auditing UxPlay, libplist, OpenSSL, FFmpeg, Media3 or jqssun for fixes, vulnerabilities or improvements, and when porting them into VireoAirTV as patches or pin bumps.
---

# Upstream Port

Never move a pin wholesale to a moving branch. Port individual fixes onto our base and keep what works.

## Sources to read (git and official pages, not summaries)
- UxPlay `FDH2/UxPlay`: `git log <pin>..origin/master -- lib/`, open PRs (`git fetch origin pull/N/head:prN`), active branches.
- jqssun/android-airplay-server: new commits since the last audit.
- libplist: `origin/master` after our pin.
- FFmpeg: the `release/X.Y` branch after our tag; filter with `-- libavcodec/aac* libavcodec/alac* libavutil/`.
- OpenSSL: the security advisories for our major.minor; check which primitives we call (`grep -o "EVP_[a-z0-9_]*()"`) before calling a CVE relevant.
- CVE databases for "UxPlay" and "libplist"; check the fixed version against our pin.
- Maven metadata (dl.google.com, repo1.maven.org) for Gradle dependencies; stable releases only.

## Porting
1. Work in a clone of UxPlay at the pin, on a branch whose base is the pin plus `patches/UxPlay/0001..N`. Confirm that base equals what `applyUxplayPatches` produces (`git diff --cached --stat` must be empty).
2. `git cherry-pick -x` one upstream commit at a time. On conflict, resolve against our code; never pull in code for features the base lacks (e.g. fields or callbacks that do not exist).
3. Read every ported diff. Upstream commits can carry defects (wrong printf types, freed pointers in logs, leaks on new error paths, partial loops). Fix them in the port and say so in the commit body.
4. If a fix needs a missing feature, list it as "not ported" with the reason. Before porting a commit, check `origin/master` for later commits that fix it (`dc5ce10` repaired a regression of the NTP rewrite `4bfa594`); port them together or not at all.
5. Export with `git format-patch --start-number <next>`; check `git apply --unidiff-zero` in order on a fresh worktree of the pin and compare the tree with the branch head.
6. One patch per commit in VireoAirTV: `uxplay: <upstream subject>`, body names the patch number and the upstream hash.

## Security fixes of our own
- Reproduce first on the host (gcc + the library sources) and keep the reproduction as a test in `app/src/test/native/`, run by `scripts/native-regress.sh`. The test must fail on the unpatched source.
- The receiver is reachable from the whole LAN before pairing: bound allocations driven by input size, and never hand network bytes to `NewStringUTF` unchecked.
- Downloads at build time must be pinned by hash.

## Improve, don't only copy
A port is the floor. Before closing a pass, look at our own code with the same tools that found the upstream bugs:
- Read every upstream diff for the bug class it fixes and look for the same class elsewhere: the FairPlay v2 merge fixed `req[14]`; the same unchecked mode byte was also in the handshake (`req[12]`, patch 0045), unreported upstream.
- Run `scripts/fuzz.sh` (libFuzzer, ASan/UBSan) after any change to code that reads sender data, and add a target when a new parser appears. Code the fuzzer cannot reach (parsers inside socket loops, such as `raop_rtp_mirror.c`) gets a manual pass: every length, offset and count from the network checked against the received size before use.
- Run the native code under ASan/UBSan with random input and fix what it reports. A refactor of crypto or codec code needs a differential test: identical output before and after on many random inputs, and a known-answer value kept in the test (patch 0047).
- Trace the hot path end to end (network -> decoder -> display) and question each wait: decoder output drained only when the next input arrived added one frame of latency (`VideoRenderer` drain thread).
- Do not report a performance problem without proof: the playfair `printf` calls looked costly but compile to nothing (`#define printf(...)`).

## Verification before commit
`./gradlew assembleDebug testDebugUnitTest lintDebug` (3 ABIs), `assembleSanitize`, `scripts/native-regress.sh`. Device behavior stays "pendiente de validación física". Record ported, rejected and pending items in `docs/AUDITORIA-REFERENCIAS.md`.
