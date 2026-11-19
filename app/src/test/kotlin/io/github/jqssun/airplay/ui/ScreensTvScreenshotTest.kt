package io.github.jqssun.airplay.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.viewmodel.MainViewModel
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the app's TV screens at 1920x1080 into build/screenshots. The view model has no service in
 * a test, so the states a session would set (server running, PIN, music) are set on its flows directly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ScreensTvScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val vm = MainViewModel(app)

    // the "television" qualifier does not set UiModeManager's mode type; isTvDevice() also accepts leanback
    @org.junit.Before fun leanback() {
        org.robolectric.Shadows.shadowOf(app.packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> set(field: String, value: T) {
        val f = MainViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }
        (f.get(vm) as MutableStateFlow<T>).value = value
    }

    private fun render(receiverOnly: Boolean = false, before: () -> Unit = {}) {
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            io.github.jqssun.airplay.ui.theme.AirPlayTheme {
                MainScreen(vm, onSurfaceAvailable = {}, onSurfaceDestroyed = {}, receiverOnly = receiverOnly)
            }
        }
        before()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun overviewStopped() { render(); save("tv_overview_stopped") }

    @Test fun overviewReady() { set("_serverState", ServerState.RUNNING); render(); save("tv_overview_ready") }

    @Test fun pin() {
        set("_serverState", ServerState.RUNNING); set("_connectionCount", 1); set("_pinCode", "4821")
        render(); save("tv_pin")
    }

    @Test fun music() {
        set("_serverState", ServerState.RUNNING); set("_connectionCount", 1); set("_audioOnly", true)
        set("_trackInfo", TrackInfo(title = "Midnight Drive", artist = "Sample Artist", album = "Night Album", coverArt = art()))
        render(); save("tv_music")
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun openToolMenu() {
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Menu) }
        compose.waitForIdle()
    }

    @Test fun settings() {
        render {
            openToolMenu()
            compose.onNodeWithText("Settings").performClick()
        }
        save("tv_settings")
    }

    @Test fun settingsPairedDevices() {
        vm.set(io.github.jqssun.airplay.Prefs.REQUIRE_PIN, true)
        set("_trustedDevices", listOf(
            io.github.jqssun.airplay.service.TrustedClients.Entry("a", "iPhone de Reinier"),
            io.github.jqssun.airplay.service.TrustedClients.Entry("b", ""),
        ))
        render {
            openToolMenu()
            compose.onNodeWithText("Settings").performClick()
        }
        compose.onNodeWithText("iPhone de Reinier").performScrollTo().assertExists()
        compose.onNodeWithText("Paired device").assertExists()
        save("tv_settings_paired")
        vm.set(io.github.jqssun.airplay.Prefs.REQUIRE_PIN, false)
    }

    @Test fun logs() {
        vm.addLog("Server started on port 7000"); vm.addLog("Client connected: iPhone")
        render {
            openToolMenu()
            compose.onNodeWithText("Logs").performClick()
        }
        save("tv_logs")
    }

    @Test fun pinOverPicture() {
        set("_serverState", ServerState.RUNNING); set("_connectionCount", 1); set("_pinCode", "4821")
        render(receiverOnly = true); save("tv_pin_receiver_only")
    }

    // an AirPlay video (HLS) screen returned early and never drew the PIN
    @Test fun pinOverVideo() {
        set("_serverState", ServerState.RUNNING); set("_connectionCount", 2)
        set("_videoSessionPending", true); set("_pinCode", "7305")
        render(); save("tv_pin_over_video")
        compose.onNodeWithText("7").assertExists()
    }

    // the screens below the PIN stay composed: arrows must not reach Stop, and OK dismisses the PIN
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun pinHoldsTheRemote() {
        set("_serverState", ServerState.RUNNING); set("_connectionCount", 1); set("_pinCode", "4821")
        render()
        compose.onRoot().performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.DirectionDown)
            pressKey(androidx.compose.ui.input.key.Key.DirectionRight)
            pressKey(androidx.compose.ui.input.key.Key.DirectionCenter)
        }
        compose.waitForIdle()
        org.junit.Assert.assertNull("OK dismisses the PIN", vm.pinCode.value)
        org.junit.Assert.assertEquals("the server keeps running", ServerState.RUNNING, vm.serverState.value)
    }

    // OK on the standby screen opens the menu; a second OK must not land on Stop and end the server
    @Test fun menuDoesNotLandOnStop() {
        set("_serverState", ServerState.RUNNING)
        render()
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Stop").assertExists()
        val focused = compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }
            .map { it.text }
        org.junit.Assert.assertEquals("focus lands on the Overview tab", listOf("Overview"), focused)
    }

    @Test fun receiverWaiting() {
        set("_serverState", ServerState.RUNNING); set("_connectionCount", 1)
        render(receiverOnly = true); save("tv_receiver_only")
    }

    private fun art(): Bitmap {
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, 0xFF3A86FF.toInt(), 0xFF8338EC.toInt(), Shader.TileMode.CLAMP) }
        Canvas(b).drawRect(0f, 0f, 600f, 600f, p)
        return b
    }
}
