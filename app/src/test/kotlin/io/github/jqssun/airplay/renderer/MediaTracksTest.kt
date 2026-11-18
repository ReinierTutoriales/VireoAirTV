package io.github.jqssun.airplay.renderer

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class MediaTracksTest {
    private val untitled: (Int) -> String = { "Track $it" }

    @Test
    fun `labels combine language and a distinct name, keep a lone name, fall back to the track number`() {
        assertEquals("English", MediaTracks.label("en", null, 0, Locale.ENGLISH, untitled))
        assertEquals("English · Commentary", MediaTracks.label("en", "Commentary", 0, Locale.ENGLISH, untitled))
        assertEquals("Director", MediaTracks.label(C.LANGUAGE_UNDETERMINED, "Director", 0, Locale.ENGLISH, untitled))
        assertEquals("Track 2", MediaTracks.label(null, " ", 1, Locale.ENGLISH, untitled))
    }

    @Test
    fun `only undeclared CEA captions are placeholders`() {
        assertTrue(MediaTracks.isPlaceholderCaption(MimeTypes.APPLICATION_CEA608, null))
        assertTrue(MediaTracks.isPlaceholderCaption(MimeTypes.APPLICATION_CEA708, C.LANGUAGE_UNDETERMINED))
        assertFalse(MediaTracks.isPlaceholderCaption(MimeTypes.APPLICATION_CEA608, "en"))
        assertFalse(MediaTracks.isPlaceholderCaption(MimeTypes.TEXT_VTT, null))
    }
}
