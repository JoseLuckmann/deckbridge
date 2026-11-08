package os.deckbridge.android

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.WaveZoom

/**
 * O modo DJ em celular (Galaxy S25 Ultra: 891×412 dp deitado no padrão, 832×384 com "zoom de tela" maior; 412×891 em
 * pé) e no Tab S6 Lite de verdade (2000×1200 px a 240 dpi = 1333×800 dp). Os prints de `ScreenShots.kt` são em mdpi
 * (px = dp) e nunca mostravam a altura de 412 dp de um celular deitado.
 */
private val PHONE = System.getProperty("phone.prints")?.takeIf { it.isNotBlank() } ?: "build/prints-phone"

internal fun phoneSet(tag: String) {
    val catalog = simulatedCatalogForTests()
    shot("$PHONE/dj-$tag-player.png") { a -> a.setContentView(playerForTests(a, catalog, WaveZoom.BARS_8, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f)) }
    val playlist = catalog.playlists.first { it.name == "Todas" }.id
    shot("$PHONE/dj-$tag-browse.png") { a -> a.setContentView(playerForTests(a, catalog, WaveZoom.BARS_8, BeatFxState(), BrowsePlace(inList = true, playlistId = playlist, index = 4))) }
    shot("$PHONE/dj-$tag-mixer.png") { a -> a.setContentView(playerForTests(a, catalog, WaveZoom.BARS_4, BeatFxState(), overlay = "mixer", nav = "MIXER")) }
    shot("$PHONE/dj-$tag-menu.png") { a -> a.setContentView(playerForTests(a, catalog, WaveZoom.BARS_4, BeatFxState(), overlay = "menu", nav = "MENU")) }
    shot("$PHONE/dj-$tag-rmx.png") { a -> a.setContentView(playerForTests(a, catalog, WaveZoom.BARS_8, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f, rmx = RmxState(panel = true, full = true, scene = 1, intensity = 0.55f, bars = 8))) }
    shot("$PHONE/dj-$tag-popup.png") { a -> a.setContentView(playerForTests(a, catalog, WaveZoom.BARS_8, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f, startPopup = "LOOP")) }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w891dp-h412dp-land-560dpi")
class ScreenShotsPhoneLand { @Test fun screens() = phoneSet("s25-891x412") }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w832dp-h384dp-land-560dpi")
class ScreenShotsPhoneLandZoom { @Test fun screens() = phoneSet("s25-832x384") }

/** Em pé não há layout de dois decks que caiba em 412 dp: o modo DJ gira para deitado (sensorLandscape). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DjModeOrientation {
    @Test fun theDjModeIsAlwaysLandscape() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = ctx.packageManager.getActivityInfo(android.content.ComponentName(ctx, MainActivity::class.java), 0)
        org.junit.Assert.assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, info.screenOrientation)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1333dp-h800dp-land-hdpi")
class ScreenShotsTabS6 { @Test fun screens() = phoneSet("tab-1333x800") }
