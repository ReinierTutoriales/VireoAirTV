# Notices

VireoAirTV is derived from [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server), licensed under GPL-3.0 (see `LICENSE`). Native components keep their own licenses inside their submodules: UxPlay, libplist, OpenSSL and FFmpeg.

## FairPlay v2

UxPlay patch 0046 adds `lib/fairplay_v2/` by Omar Roth (LGPL-2.1-or-later), merged upstream in UxPlay commit 6ab8bf6. It lets macOS Music stream to the receiver.

## Code from CastBay

`renderer/HevcSelfTest.kt` with `app/src/main/assets/hevc_selftest.h265`, and `renderer/MediaTracks.kt`, are ported from [CastBay](https://github.com/weenas/castbay) by Weenas (GPL-3.0): `service/HevcSelfTest.kt` with its asset and `service/MediaTracks.kt`.

`discovery/own/MdnsZone.kt` and `discovery/own/MdnsResponder.kt` (with `MdnsZoneTest.kt`) are ported from CastBay's `service/MdnsZone.kt` and `service/MdnsResponder.kt` (commit `df132a3`). `service/AudioFocus.kt`, the screen-saver wake in `AirPlayService`, and the live-stream position in `AirPlayVideoPlayer` follow CastBay's `AudioFocus.kt`, `ScreenLauncher.kt` and commit `8edbc8a`.

## Agent tooling

`.agents/skills/no-ai-slop/` is an unmodified copy of the no-ai-slop skill by Peter Yang, used by contributors' agents to edit prose. It is not part of the app.

- Source: https://github.com/petergyang/no-ai-slop/tree/000650b156983f5159695b441477f4e63b25dc85/skills/no-ai-slop
- Snapshot commit: `000650b156983f5159695b441477f4e63b25dc85`
- License: MIT, in `.agents/skills/no-ai-slop/LICENSE`
