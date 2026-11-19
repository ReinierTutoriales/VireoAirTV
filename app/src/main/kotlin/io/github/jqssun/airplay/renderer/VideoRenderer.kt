package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    private val lock = Any()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    // Frames waiting for a decoder input buffer, oldest first. Guarded by [inputLock], which the feed
    // side and the drain thread both take for input work; [lock] is never taken under it.
    private val pending = ArrayDeque<Pair<ByteArray, Long>>()
    private val inputLock = Any()
    // after a backlog is dropped, P-frames until the next key frame would decode to corruption
    private var awaitingKeyFrame = false
    // output side runs on its own thread so a decoded frame reaches the screen as soon as it is ready,
    // not when the next input frame arrives
    private var _drainThread: Thread? = null
    @Volatile private var _draining = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    @Volatile var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    @Volatile private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
        stopCodec()
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    // hevcWorks: verdict of HevcSelfTest for a decoder (null = not tested); ignored when h265 is forced on
    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean?, hevcWorks: (String) -> Boolean? = { null }): Boolean =
        synchronized(lock) {
            avcDecoder = selector.avc()
            hevcDecoder = if (h265 != false) selector.hevc(avcDecoder, w, h, fps, force = h265 == true) else null
            if (h265 == null && hevcDecoder?.let { hevcWorks(it.name) } == false) {
                Log.i(TAG, "hevc decoder ${hevcDecoder?.name} failed the self-test; h264 only")
                hevcDecoder = null
            }
            maxFps = fps
            Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
            hevcDecoder != null
        }

    /** Selected H.265 decoder name, for the self-test. */
    fun hevcDecoderName(): String? = synchronized(lock) { hevcDecoder?.name }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() = synchronized(lock) { stopCodec() }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, ntpTimeNs)
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    /**
     * Queues the frame and hands the decoder as many queued frames as it has input buffers for, without
     * waiting. This runs on the network thread: it used to wait up to 200 ms for a buffer and then drop the
     * frame, which stalled the stream and corrupted the picture until the next key frame (the sender sends
     * one only at session start and on format changes). Now a slow decoder only builds a backlog, which the
     * drain thread keeps feeding; if the backlog grows past [MAX_PENDING_FRAMES] it is dropped whole and
     * decoding resumes at the next key frame, so the picture never shows broken references (CastBay
     * VideoRenderer does the same).
     */
    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // without the drain thread no frame leaves the decoder; reset it (feedFrame restarts at a keyframe)
        if (_drainThread?.isAlive == false) throw IllegalStateException("decoder output thread stopped")
        synchronized(inputLock) {
            if (pending.size >= MAX_PENDING_FRAMES) {
                droppedFrames += pending.size
                Log.w(TAG, "Decoder backlog of ${pending.size} frames dropped; resuming at the next key frame")
                pending.clear()
                awaitingKeyFrame = true
            }
            if (awaitingKeyFrame) {
                if (!_isKeyframe(data, currentH265)) { droppedFrames++; return }
                awaitingKeyFrame = false
            }
            pending.addLast(data to ntpTimeNs / 1000)
            _feedPending(c, if (firstFrameQueued) 0L else FIRST_FEED_WAIT_US)
        }
    }

    /** Under [inputLock]: copies queued frames into free input buffers, waiting at most [waitUs] for the first. */
    private fun _feedPending(c: MediaCodec, waitUs: Long) {
        var wait = waitUs
        while (pending.isNotEmpty()) {
            val idx = c.dequeueInputBuffer(wait)
            if (idx < 0) return
            wait = 0L
            val (data, ptsUs) = pending.removeFirst()
            val buf = c.getInputBuffer(idx) ?: return
            if (data.size > buf.capacity()) {
                // a frame that cannot be decoded breaks every later reference: skip to the next key frame
                Log.w(TAG, "Video frame of ${data.size} bytes exceeds the input buffer; resuming at the next key frame")
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                droppedFrames += 1 + pending.size
                pending.clear()
                awaitingKeyFrame = true
                return
            }
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
            firstFrameQueued = true
        }
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        synchronized(inputLock) { pending.clear(); awaitingKeyFrame = false }
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // honoured since Q; the public constant only arrived in API 31
            setInteger("allow-frame-drop", if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        _draining = true
        _drainThread = Thread({ _drainLoop(c) }, "VideoDrain").apply { start() }
    }

    private fun stopCodec() {
        // the drain thread must leave dequeueOutputBuffer before the codec stops
        _draining = false
        _drainThread?.let {
            it.interrupt()
            try { it.join(DRAIN_JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        _drainThread = null
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    private fun _drainLoop(c: MediaCodec) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        val info = MediaCodec.BufferInfo()
        try {
            while (_draining) {
                // a decoder that freed input buffers takes the frames still queued, even if no new one arrives
                synchronized(inputLock) { if (pending.isNotEmpty() && _draining) _feedPending(c, 0L) }
                val idx = c.dequeueOutputBuffer(info, DRAIN_WAIT_US)
                if (idx < 0) continue
                if (!_draining) { c.releaseOutputBuffer(idx, false); break }
                _release(c, idx, info)
            }
        } catch (e: IllegalStateException) {
            // codec error or released; the feed side resets it on its next frame
            if (_draining) Log.w(TAG, "Decoder output failed", e)
        }
    }

    private fun _release(c: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
        _recordOutputFrameTime()
        if (!scheduledOutputBufferRelease) {
            c.releaseOutputBuffer(idx, true)
            return
        }
        // schedule the frame at the VSYNC matching its NTP presentation time
        val ptsUs = info.presentationTimeUs
        if (_ptsBaseUs == Long.MIN_VALUE) {
            _ptsBaseUs = ptsUs
            _wallBaseNs = System.nanoTime()
        }
        c.releaseOutputBuffer(idx, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        _resetStats()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        // the decoder's first input buffers can take a moment after start(); later frames never wait
        private const val FIRST_FEED_WAIT_US = 50_000L
        // about 2 s at 60 fps: past this the decoder is not keeping up, and the backlog is dropped
        private const val MAX_PENDING_FRAMES = 120
        private const val DRAIN_WAIT_US = 10_000L
        private const val DRAIN_JOIN_MS = 500L
    }
}
