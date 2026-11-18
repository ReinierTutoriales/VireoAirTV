package io.github.jqssun.airplay.ui

import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// shared surface for mirroring (VideoRenderer) and airplay video (AirPlayVideoPlayer)
@Composable
fun VideoSurfaceView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    // false = caller sizes the surface (content-scale modes)
    applyAspectRatio: Boolean = true,
    // buffer size for producers that draw at window size (the mirroring GL blit): the compositor
    // then scales source pixels once to the panel instead of after a GL resample to the view size
    bufferSize: Pair<Int, Int>? = null,
    // content frame rate hint for the display mode (Surface.setFrameRate); 0 = no preference
    frameRate: Float = 0f,
) {
    val state = remember { SurfaceState() }
    val callbacks = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                state.applyFrameRate(holder)
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed(holder.surface)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also {
                it.holder.addCallback(callbacks)
            }
        },
        update = { view ->
            if (state.bufferSize != bufferSize) {
                state.bufferSize = bufferSize
                if (bufferSize != null) view.holder.setFixedSize(bufferSize.first, bufferSize.second)
                else view.holder.setSizeFromLayout()
            }
            if (state.frameRate != frameRate) {
                state.frameRate = frameRate
                state.applyFrameRate(view.holder)
            }
        },
        modifier = if (applyAspectRatio) {
            modifier
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f)
                .fillMaxSize()
        } else {
            modifier
        }
    )
}

private class SurfaceState {
    var bufferSize: Pair<Int, Int>? = null
    var frameRate = 0f

    // same call as moonlight-android (Game.surfaceChanged): fixed-source content, switch even if not seamless
    fun applyFrameRate(holder: SurfaceHolder) {
        val surface = holder.surface
        if (surface == null || !surface.isValid) return
        when {
            Build.VERSION.SDK_INT >= 31 -> surface.setFrameRate(
                frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS
            )
            Build.VERSION.SDK_INT >= 30 -> surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }
}
