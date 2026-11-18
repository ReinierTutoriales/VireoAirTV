package io.github.jqssun.airplay.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class DmapParserTest {

    private fun item(tag: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        out.write(payload)
        return out.toByteArray()
    }

    private fun str(tag: String, value: String) = item(tag, value.toByteArray(Charsets.UTF_8))

    @Test
    fun `parses string and integer tags`() {
        val data = str("minm", "Song ñ") + item("astm", byteArrayOf(0, 0, 0x0F, 0xA0.toByte()))
        val map = DmapParser.parse(data)
        assertEquals("Song ñ", map["minm"])
        assertEquals(4000L, map["astm"])
    }

    @Test
    fun `parses nested containers`() {
        val data = item("mlit", str("asar", "Artist") + str("asal", "Album"))
        val inner = DmapParser.parse(data)["mlit"] as Map<*, *>
        assertEquals("Artist", inner["asar"])
        assertEquals("Album", inner["asal"])
    }

    @Test
    fun `stops at a length past the end of the buffer`() {
        val truncated = str("minm", "ok") + "asar".toByteArray() + ByteBuffer.allocate(4).putInt(1000).array()
        assertEquals(mapOf("minm" to "ok"), DmapParser.parse(truncated))
    }

    @Test
    fun `stops at a negative length`() {
        val bad = "asar".toByteArray() + ByteBuffer.allocate(4).putInt(-1).array() + ByteArray(16)
        assertTrue(DmapParser.parse(bad).isEmpty())
    }

    @Test
    fun `ignores a trailing fragment shorter than a header`() {
        assertEquals(mapOf("minm" to "x"), DmapParser.parse(str("minm", "x") + byteArrayOf(1, 2, 3)))
    }

    // hostile sender: deeply nested containers must not overflow the stack
    @Test
    fun `bounds container depth`() {
        var data = str("minm", "deep")
        repeat(2_000) { data = item("mlit", data) }
        var level: Any? = DmapParser.parse(data)
        var depth = 0
        while (level is Map<*, *> && level.containsKey("mlit")) {
            level = level["mlit"]
            depth++
        }
        assertTrue(depth <= 9, "depth $depth")
    }
}
