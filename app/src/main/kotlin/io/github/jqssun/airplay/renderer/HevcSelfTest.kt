package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit

/**
 * Decodes one small H.265 picture (assets/hevc_selftest.h265: Main profile, 256x144, an IDR with
 * its parameter sets) with the decoder mirroring would use. Some TVs list a hardware H.265 decoder
 * that then fails to start or never gives a picture; advertising H.265 there leaves mirroring black.
 *
 * Ported from CastBay (github.com/weenas/castbay, GPL-3.0), service/HevcSelfTest.kt and its asset.
 * Here the result is kept per decoder and system build, so the test runs once, off the main thread.
 */
object HevcSelfTest {
    private const val TAG = "HevcSelfTest"
    private const val TIMEOUT_MS = 3000L
    private const val PREFS = "hevc_selftest"

    /** Stored verdict for [codecName] on this system build; null when not tested yet. */
    fun verdict(context: Context, codecName: String): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = _key(codecName)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    /** Runs the test and stores the verdict. Takes up to [TIMEOUT_MS]; never call on the main thread. */
    fun run(context: Context, codecName: String): Boolean {
        val started = SystemClock.elapsedRealtime()
        var codec: MediaCodec? = null
        val result = try {
            val unit = context.assets.open("hevc_selftest.h265").use { it.readBytes() }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 256, 144)
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, 0)
            codec.start()
            _decodes(codec, unit, started + TIMEOUT_MS)
        } catch (error: Exception) {
            Log.w(TAG, "$codecName: ${error.javaClass.simpleName}: ${error.message}")
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
        Log.i(TAG, "$codecName: ${if (result) "picture" else "no picture"} after ${SystemClock.elapsedRealtime() - started} ms")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(_key(codecName), result) }
        return result
    }

    // a firmware update can fix or break the decoder
    private fun _key(codecName: String) = "${Build.FINGERPRINT}|$codecName"

    // queues the unit, then end of stream (so a decoder that holds pictures back lets it go), and waits for a picture
    private fun _decodes(codec: MediaCodec, unit: ByteArray, deadline: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        var unitQueued = false
        var endQueued = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!unitQueued) {
                val index = codec.dequeueInputBuffer(20_000)
                if (index >= 0) {
                    codec.getInputBuffer(index)?.apply { clear(); put(unit) }
                    codec.queueInputBuffer(index, 0, unit.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    unitQueued = true
                }
            } else if (!endQueued) {
                val index = codec.dequeueInputBuffer(20_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, 33_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    endQueued = true
                }
            }
            val output = codec.dequeueOutputBuffer(info, 20_000)
            if (output >= 0) {
                val picture = info.size > 0
                codec.releaseOutputBuffer(output, false)
                if (picture) return true
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
        }
        return false
    }
}
