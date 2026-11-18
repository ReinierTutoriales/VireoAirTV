package io.github.jqssun.airplay.renderer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YtDubFilterTest {
    // XTAGS values from the manifest in FDH2/UxPlay#601: acont=dubbed-auto lang=fr-FR, acont=original lang=en-US
    private val dubFr = "ChQKBWFjb250EgtkdWJiZWQtYXV0bwoNCgRsYW5nEgVmci1GUg"
    private val origEn = "ChEKBWFjb250EghvcmlnaW5hbAoNCgRsYW5nEgVlbi1VUw"

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=5966703,CODECS="mp4a.40.2,avc1.64002A",RESOLUTION=1440x1080,YT-EXT-XTAGS="$dubFr",YT-EXT-AUDIO-CONTENT-ID="fr-FR.10"
        https://manifest.googlevideo.com/fr/1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5966523,CODECS="mp4a.40.2,avc1.64002A",RESOLUTION=1440x1080,YT-EXT-XTAGS="$origEn",YT-EXT-AUDIO-CONTENT-ID="en-US.4"
        https://manifest.googlevideo.com/en/1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5966521,CODECS="mp4a.40.2,avc1.64002A",RESOLUTION=1440x1080
        https://manifest.googlevideo.com/orig/1080.m3u8
    """.trimIndent()

    @Test
    fun `dubbed variants are dropped, original and untagged ones stay`() {
        val out = YtDubFilter.filter(master)!!
        assertFalse(out.contains("/fr/"))
        assertFalse(out.contains(dubFr))
        assertTrue(out.contains("/en/1080.m3u8"))
        assertTrue(out.contains("/orig/1080.m3u8"))
        assertEquals(2, out.lines().count { it.startsWith("#EXT-X-STREAM-INF:") })
    }

    @Test
    fun `manifests without YouTube tags, or with only dubs, are left alone`() {
        assertNull(YtDubFilter.filter("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n"))
        val onlyDub = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,YT-EXT-XTAGS=\"$dubFr\"\nfr.m3u8\n"
        assertNull(YtDubFilter.filter(onlyDub))
    }
}
