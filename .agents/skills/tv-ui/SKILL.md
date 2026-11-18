---
name: tv-ui
description: Use for UI, focus, navigation, playback overlay or settings layout work. Ensures a polished Android TV remote (D-pad) experience and low-memory friendliness.
---

# TV UI & Remote

- Optimize for the Android TV remote: proper focus handling, D-pad navigation, clear focus indicators and accessibility. Existing focus helpers live in `ui/TvFocus.kt` and `ui/Tv.kt`.
- The UI is Jetpack Compose. Keep layouts simple; avoid heavy animations or memory-intensive views during playback.
- The playback surface (`VideoSurfaceView`, `VideoPipeline`) must not be recreated by recomposition or overlay toggles; overlays change visibility only.
- Trace focus in the actual composable and callback; cover adjacent controls when behavior changes. Record what still needs a real remote/device check.

## Receiver presentation (Apple TV model)
The app is not the protagonist; the sender's picture is.
- The service opens the activity (intent extra `EXTRA_AUTO_LAUNCH`) only for a mirroring picture, an AirPlay `/play` or a pairing PIN. Audio plays in the background with media keys and the notification.
- An activity opened that way is receiver-only: mirroring fullscreen with no chrome, AirPlay video with its own controls, black between pictures, never the tabs. 1.5 s after the last picture it moves its task back. Back moves it back at once. `onStop` leaves receiver-only mode so recents show the app.
- Auto picture-in-picture only while there is a picture.
- During mirroring the remote sends DACP commands (play/pause, next, previous; center when nothing is on screen). Show feedback only for commands actually sent.

## Motion
- One curve and three durations in `ui/Motion.kt`; animations run on events (start, track change, rotation, command), never continuously during playback.
- Fades are a separate black layer (`FadeFromBlack`) with the same size and transform as the surface, never a wrapper that changes the `SurfaceView` modifiers.

## Recheck the interaction that actually failed
- Distinguish D-pad focus, the remote's virtual pointer, touch/mouse input and media keys (play/pause, seek). A focus-only check does not prove pointer or media-key handling.
- Separate system Back, closing the overlay, leaving fullscreen/PiP and stopping the session. Test each transition and its return path.
- Dynamic list refreshes (logs, settings) must preserve the focused item when it remains available.
- Check horizontal commands in both LTR and RTL layouts.

## Compose for TV
- Consult current official Android Developers guidance for Compose for TV, focus and Back handling before adopting a pattern; record the consulted date in the PR.
- Use the newest compatible stable dependency versions after checking release notes and AGP/Kotlin/minSdk compatibility.
- User-visible text and content descriptions come from `stringResource`; a literal string in a composable is a localization regression.
- A long-lived `pointerInput` handler must read changing callbacks/state through `rememberUpdatedState` or restart with the relevant keys.
- `Modifier.border(0.dp, ...)` draws a one-pixel hairline; omit the modifier when no border is intended.
