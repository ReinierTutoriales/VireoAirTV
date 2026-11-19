package io.github.jqssun.airplay.service

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Holds Android's audio focus while something is cast, as any media player does (and as CastBay's
 * AudioFocus does): the TV's own apps then pause instead of playing over the cast, and the system
 * routes media keys to our media session. Losing it to another app is only logged; the sender decides
 * what plays.
 */
class AudioFocus(private val audioManager: AudioManager) {
    @Volatile private var held = false
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) held = false
        Log.i(TAG, "Audio focus change: $change")
    }
    private val request: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
    } else null

    @Synchronized
    fun acquire() {
        if (held) return
        val result = if (Build.VERSION.SDK_INT >= 26) audioManager.requestAudioFocus(request!!)
        else @Suppress("DEPRECATION") audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, if (held) "Audio focus gained" else "Audio focus refused")
    }

    @Synchronized
    fun release() {
        if (!held) return
        if (Build.VERSION.SDK_INT >= 26) audioManager.abandonAudioFocusRequest(request!!)
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(listener)
        held = false
        Log.i(TAG, "Audio focus released")
    }

    private companion object { const val TAG = "AudioFocus" }
}
