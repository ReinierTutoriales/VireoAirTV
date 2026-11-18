package io.github.jqssun.airplay.renderer

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import kotlin.io.encoding.Base64

/**
 * YouTube web manifests (Safari's AirPlay button) list every resolution once per audio language and
 * mark the language only in private attributes; the player then takes the highest BANDWIDTH, which is
 * an AI dub by a few bits per second (FDH2/UxPlay#601). Drop the variants tagged as dubbed so the
 * original audio plays, as in the YouTube app. Manifests without those tags pass through unchanged.
 */
object YtDubFilter {
    private const val STREAM_INF = "#EXT-X-STREAM-INF:"
    private const val IFRAME_INF = "#EXT-X-I-FRAME-STREAM-INF:"
    private val XTAGS = Regex("""YT-EXT-XTAGS="([^"]*)"""")
    private val base64 = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** Filtered multivariant playlist, or null to keep the original. */
    fun filter(playlist: String): String? {
        if (!playlist.contains("YT-EXT-XTAGS")) return null
        val lines = playlist.lines()
        val out = ArrayList<String>(lines.size)
        var kept = 0
        var dropped = 0
        var skipUri = false
        for (line in lines) {
            if (skipUri) {
                // the variant's URI is the next line that is not a tag or blank
                if (line.isNotBlank() && !line.startsWith("#")) { skipUri = false; continue }
                if (line.isBlank()) continue
            }
            when {
                line.startsWith(STREAM_INF) && isDubbed(line) -> { dropped++; skipUri = true }
                line.startsWith(IFRAME_INF) && isDubbed(line) -> dropped++
                else -> {
                    if (line.startsWith(STREAM_INF)) kept++
                    out += line
                }
            }
        }
        return if (dropped == 0 || kept == 0) null else out.joinToString("\n")
    }

    // XTAGS is base64 of a small protobuf with "acont" = "original" or "dubbed-auto"
    private fun isDubbed(line: String): Boolean {
        val tags = XTAGS.find(line)?.groupValues?.get(1) ?: return false
        val bytes = runCatching { base64.decode(tags) }.getOrNull() ?: return false
        return String(bytes, Charsets.ISO_8859_1).contains("dubbed")
    }
}

/** HLS parser factory that applies [YtDubFilter] to the first (multivariant) playlist. */
@UnstableApi
class YtDubFilterParserFactory(
    private val base: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        val parser = base.createPlaylistParser()
        return ParsingLoadable.Parser { uri: Uri, input ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            val filtered = YtDubFilter.filter(text) ?: text
            parser.parse(uri, filtered.byteInputStream(Charsets.UTF_8))
        }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = base.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
}
