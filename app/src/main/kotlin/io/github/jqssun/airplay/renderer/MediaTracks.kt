package io.github.jqssun.airplay.renderer

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import java.util.Locale

/** One audio or subtitle choice of an AirPlay video; [group] is null for "Off". */
data class TrackChoice(val label: String, val group: TrackGroup?, val index: Int, val selected: Boolean)

/**
 * The audio tracks and subtitles an AirPlay video offers (an HLS stream's languages) and switching
 * between them. Main thread only, like the player.
 *
 * Ported from CastBay (github.com/weenas/castbay, GPL-3.0), service/MediaTracks.kt; the "Off" and
 * untitled-track labels come from the caller so they are localized.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaTracks {
    /** Supported tracks of [type] ([C.TRACK_TYPE_AUDIO] or [C.TRACK_TYPE_TEXT]); subtitles start with [off]. */
    fun choices(player: Player, type: Int, off: String, untitled: (Int) -> String): List<TrackChoice> {
        val tracks = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).filterNot { index ->
                group.getTrackFormat(index).let { isPlaceholderCaption(it.sampleMimeType, it.language) }
            }.map { index -> Triple(group.mediaTrackGroup, index, group.isTrackSelected(index)) }
        }
        val choices = tracks.mapIndexed { position, (group, index, selected) ->
            group.getFormat(index).let { TrackChoice(label(it.language, it.label, position, untitled = untitled), group, index, selected) }
        }
        if (type != C.TRACK_TYPE_TEXT || choices.isEmpty()) return choices
        return listOf(TrackChoice(off, null, 0, selected = choices.none { it.selected })) + choices
    }

    fun select(player: Player, type: Int, choice: TrackChoice) {
        val builder = player.trackSelectionParameters.buildUpon()
        val group = choice.group
        if (group == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group, choice.index))
        }
        player.trackSelectionParameters = builder.build()
    }

    /**
     * A closed-caption channel inferred from the video stream rather than declared with a language.
     * HLS streams carry these even when empty (their subtitles are burned in), so offering them only confuses.
     */
    fun isPlaceholderCaption(mimeType: String?, language: String?): Boolean =
        (mimeType == MimeTypes.APPLICATION_CEA608 || mimeType == MimeTypes.APPLICATION_CEA708) &&
            (language.isNullOrBlank() || language == C.LANGUAGE_UNDETERMINED)

    /** "English", "English · Commentary", or [untitled] when the stream names nothing. */
    fun label(
        languageTag: String?,
        name: String?,
        position: Int,
        displayLocale: Locale = Locale.getDefault(),
        untitled: (Int) -> String,
    ): String {
        val language = languageTag
            ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayName(displayLocale) }
            ?.takeIf { it.isNotBlank() }
        val label = name?.takeIf { it.isNotBlank() }
        return when {
            language != null && label != null && !label.equals(language, ignoreCase = true) -> "$language · $label"
            label != null -> label
            language != null -> language
            else -> untitled(position + 1)
        }
    }
}
