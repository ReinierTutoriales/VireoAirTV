package io.github.jqssun.airplay.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// media3 session view of whatever the receiver is playing: RAOP audio (DACP) or AirPlay video
@androidx.annotation.OptIn(UnstableApi::class)
class SessionPlayer(
    looper: Looper,
    private val seekStepMs: Long,
    private val snapshot: () -> Snapshot,
    private val controls: Controls,
) : SimpleBasePlayer(looper) {

    enum class Mode { IDLE, AUDIO, VIDEO }

    data class Snapshot(
        val mode: Mode,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artworkData: ByteArray? = null,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        val playing: Boolean = false,
        val speed: Float = 1f,
    )

    interface Controls {
        fun setPlaying(playing: Boolean)
        fun stop()
        fun seekTo(positionMs: Long)
        fun next()
        fun previous()
    }

    // must be called on the constructor looper
    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = snapshot()
        if (s.mode == Mode.IDLE) {
            return State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
        }
        val video = s.mode == Mode.VIDEO
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .apply {
                if (video) {
                    addAll(
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_SEEK_BACK,
                    )
                } else {
                    addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
                }
            }
            .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .apply { s.artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val current = MediaItemData.Builder(UID_CURRENT)
            .setMediaMetadata(metadata)
            .setDurationUs(if (s.durationMs > 0) s.durationMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(video)
            .build()
        // audio keeps neighbours so next/previous stay enabled for the sender's queue
        val playlist = if (video) listOf(current) else listOf(
            MediaItemData.Builder(UID_PREVIOUS).build(),
            current,
            MediaItemData.Builder(UID_NEXT).build(),
        )
        val speed = if (s.playing) s.speed.coerceAtLeast(MIN_SPEED) else 1f
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (video) 0 else 1)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setContentPositionMs(
                if (s.playing) PositionSupplier.getExtrapolating(s.positionMs, speed)
                else PositionSupplier.getConstant(s.positionMs)
            )
            .setSeekForwardIncrementMs(seekStepMs)
            .setSeekBackIncrementMs(seekStepMs)
            // seekToPrevious must always mean "previous item", never "restart current"
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        controls.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controls.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controls.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controls.previous()
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_BACK ->
                controls.seekTo(positionMs.coerceAtLeast(0))
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val UID_PREVIOUS = "previous"
        const val UID_CURRENT = "current"
        const val UID_NEXT = "next"
        const val MIN_SPEED = 0.1f
    }
}
