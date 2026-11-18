package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Now Playing screen at TV sizes into build/screenshots, to check the layout without a TV.
 * 1920x1080 (xhdpi, 960x540 dp) is the common Android TV UI size; 1280x720 covers small boxes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingTvScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val state = NowPlayingState(
        title = "Tu Tranca - Prod. by El Bandolero x Roberto Ferrante",
        artist = "Charly & Johayron, El Bandolero, Roberto Ferrante",
        album = "Tu Tranca",
        artwork = sampleArtwork(),
        positionMs = 16_000,
        durationMs = 201_000,
        playing = true,
    )

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun playing_1080p() = shot("now_playing_1080p", state, controls = false)

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun controls_1080p() = shot("now_playing_1080p_controls", state.copy(playing = false), controls = true, focusPlay = true)

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun noArtwork_1080p() = shot("now_playing_1080p_no_art", state.copy(artwork = null, album = ""), controls = false)

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun phonePortrait() = shot("now_playing_phone_portrait", state, controls = true)

    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun playing_720p() = shot("now_playing_720p", state, controls = false)

    private fun shot(name: String, s: NowPlayingState, controls: Boolean, focusPlay: Boolean = false) {
        val focus = FocusRequester()
        compose.setContent {
            // the TV remote puts the window in keyboard mode, where a clickable takes focus
            val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.SideEffect { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            NowPlayingTv(s, controlsVisible = controls, onPrevious = {}, onPlayPause = {}, onNext = {}, playPauseFocus = focus)
        }
        if (focusPlay) {
            compose.runOnIdle { focus.requestFocus() }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // invented artwork: diagonal red-to-navy gradient with a pale disc
    private fun sampleArtwork(): Bitmap {
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, 0xFFD62828.toInt(), 0xFF14213D.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 600f, 600f, p)
        c.drawCircle(380f, 240f, 150f, Paint().apply { color = 0xCCF1FAEE.toInt(); isAntiAlias = true })
        return b
    }
}
