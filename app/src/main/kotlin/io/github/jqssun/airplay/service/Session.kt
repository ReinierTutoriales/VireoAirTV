package io.github.jqssun.airplay.service

/** What the receiver is showing, from most to least specific. The UI draws this and nothing else. */
enum class Screen { IDLE, CONNECTED, VIDEO, MIRRORING, MUSIC }

/** Protocol events that change the session. Every change goes through [Session.on]. */
sealed interface SessionEvent {
    data object Connected : SessionEvent
    /** The last connection closed: the sender is gone. */
    data object Disconnected : SessionEvent
    data object MirrorStarted : SessionEvent
    data object MirrorStopped : SessionEvent
    data object VideoStarted : SessionEvent
    data object VideoEnded : SessionEvent
    /** An audio stream opened; [screen] is mirroring sound, otherwise music. */
    data class AudioStarted(val screen: Boolean) : SessionEvent
    data object AudioEnded : SessionEvent
    data object ServerStopped : SessionEvent
}

/**
 * The receiver's session as one value. Mirroring and an AirPlay video can coexist (a video sent while
 * the screen is mirrored, then mirroring resumes), so they are separate facts; music is a mode of its own
 * and ends when either starts.
 */
data class Session(
    val connected: Boolean = false,
    val mirroring: Boolean = false,
    val video: Boolean = false,
    /** Music streaming (not mirroring sound) is the sender's current content. */
    val music: Boolean = false,
    /** A music stream is open, whatever is on screen: mirroring can stop while music keeps playing. */
    val musicStream: Boolean = false,
) {
    val screen: Screen get() = when {
        video -> Screen.VIDEO
        mirroring -> Screen.MIRRORING
        music -> Screen.MUSIC
        connected -> Screen.CONNECTED
        else -> Screen.IDLE
    }

    fun on(e: SessionEvent): Session = when (e) {
        SessionEvent.Connected -> copy(connected = true)
        SessionEvent.Disconnected, SessionEvent.ServerStopped -> Session()
        SessionEvent.MirrorStarted -> copy(connected = true, mirroring = true, music = false)
        // back to music only if a music stream is still open; mirroring sound ends with mirroring
        SessionEvent.MirrorStopped -> copy(mirroring = false, music = musicStream && !video)
        SessionEvent.VideoStarted -> copy(connected = true, video = true, music = false)
        SessionEvent.VideoEnded -> copy(video = false, music = musicStream && !mirroring)
        is SessionEvent.AudioStarted ->
            // mirroring sound replaces the music stream; the picture follows within milliseconds
            if (e.screen) copy(connected = true, music = false, musicStream = false)
            else copy(connected = true, musicStream = true, music = !mirroring && !video)
        SessionEvent.AudioEnded -> copy(musicStream = false)
    }
}
