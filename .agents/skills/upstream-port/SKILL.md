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
4. If a fix needs a missing feature, list it as "not ported" with the reason.
5. Export with `git format-patch --start-number <next>`; check `git apply --unidiff-zero` in order on a fresh worktree of the pin and compare the tree with the branch head.
6. One patch per commit in VireoAirTV: `uxplay: <upstream subject>`, body names the patch number and the upstream hash.

## Security fixes of our own
- Reproduce first on the host (gcc + the library sources) and keep the reproduction as a test in `app/src/test/native/`, run by `scripts/native-regress.sh`. The test must fail on the unpatched source.
- The receiver is reachable from the whole LAN before pairing: bound allocations driven by input size, and never hand network bytes to `NewStringUTF` unchecked.
- Downloads at build time must be pinned by hash.

## Verification before commit
`./gradlew assembleDebug testDebugUnitTest lintDebug` (3 ABIs), `assembleSanitize`, `scripts/native-regress.sh`. Device behavior stays "pendiente de validación física". Record ported, rejected and pending items in `docs/AUDITORIA-REFERENCIAS.md`.
