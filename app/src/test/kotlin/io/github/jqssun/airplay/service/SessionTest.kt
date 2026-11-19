package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.service.SessionEvent.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionTest {
    private fun run(vararg events: SessionEvent) = events.fold(Session()) { s, e -> s.on(e) }

    @Test
    fun `a connection alone shows the connected screen`() {
        assertEquals(Screen.CONNECTED, run(Connected).screen)
    }

    @Test
    fun `mirroring that stops without music returns to connected, not to an empty music screen`() {
        // the onn 4K log: screen audio, picture, then teardown and "mirror running: 0"
        val s = run(Connected, AudioStarted(screen = true), MirrorStarted, AudioEnded, MirrorStopped)
        assertEquals(Screen.CONNECTED, s.screen)
    }

    @Test
    fun `music keeps playing after mirroring stops`() {
        val s = run(Connected, AudioStarted(screen = false), MirrorStarted, MirrorStopped)
        assertEquals(Screen.MUSIC, s.screen)
    }

    @Test
    fun `a paused music stream keeps the music screen`() {
        assertEquals(Screen.MUSIC, run(Connected, AudioStarted(screen = false), AudioEnded).screen)
    }

    @Test
    fun `a video takes the screen and mirroring returns when it ends`() {
        val s = run(Connected, MirrorStarted, VideoStarted)
        assertEquals(Screen.VIDEO, s.screen)
        assertEquals(Screen.MIRRORING, s.on(VideoEnded).screen)
    }

    @Test
    fun `music resumes after a video only if its stream is still open`() {
        val open = run(Connected, AudioStarted(screen = false), VideoStarted)
        assertEquals(Screen.MUSIC, open.on(VideoEnded).screen)
        assertEquals(Screen.CONNECTED, open.on(AudioEnded).on(VideoEnded).screen)
    }

    @Test
    fun `music during a video does not cover the video`() {
        assertEquals(Screen.VIDEO, run(Connected, VideoStarted, AudioStarted(screen = false)).screen)
    }

    @Test
    fun `the last disconnect and a server stop clear everything`() {
        val busy = run(Connected, AudioStarted(screen = false), MirrorStarted, VideoStarted)
        assertEquals(Session(), busy.on(Disconnected))
        assertEquals(Session(), busy.on(ServerStopped))
    }
}
