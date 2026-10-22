package os.deckbridge.android

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import os.deckbridge.logic.PlayerSettings

private val Context.playerStore by preferencesDataStore("deckbridge-player")

object SettingsStore {
    private val range = doublePreferencesKey("tempo_range")
    private val keyLock = booleanPreferencesKey("key_lock")
    private val quantize = booleanPreferencesKey("quantize")
    private val quantizeBeats = doublePreferencesKey("quantize_beats")
    private val autoCue = booleanPreferencesKey("auto_cue")
    private val hotCue = booleanPreferencesKey("hot_cue_auto_load")
    private val jogVinyl = booleanPreferencesKey("jog_vinyl")
    private val wave = stringPreferencesKey("wave_color")
    private val zoom = intPreferencesKey("zoom_bars")
    private val playhead = floatPreferencesKey("playhead")
    private val buffer = intPreferencesKey("buffer_frames")
    private val encoder = booleanPreferencesKey("encoder_flip")
    private val jog = floatPreferencesKey("jog_sensitivity")
    private val historySeconds = intPreferencesKey("history_seconds")
    private val recordAac = booleanPreferencesKey("record_aac")
    private val followUsb = booleanPreferencesKey("follow_usb_wave")

    fun flow(context: Context): Flow<PlayerSettings> = context.playerStore.data.map { prefs ->
        PlayerSettings(
            tempoRange = prefs[range] ?: PlayerSettings().tempoRange,
            keyLock = prefs[keyLock] ?: false,
            quantize = prefs[quantize] ?: true,
            quantizeBeats = prefs[quantizeBeats] ?: 1.0,
            autoCue = prefs[autoCue] ?: true,
            hotCueAutoLoad = prefs[hotCue] ?: true,
            jogVinyl = prefs[jogVinyl] ?: true,
            waveColor = prefs[wave] ?: "RGB",
            followUsb = prefs[followUsb] ?: true,
            zoomBars = prefs[zoom] ?: 8,
            playhead = prefs[playhead] ?: 0.30f,
            bufferFrames = prefs[buffer] ?: 256,
            encoderFlip = prefs[encoder] ?: false,
            jogSensitivity = prefs[jog] ?: 1f,
            historySeconds = prefs[historySeconds] ?: 10,
            recordAac = prefs[recordAac] ?: false,
        )
    }

    suspend fun save(context: Context, settings: PlayerSettings) {
        context.playerStore.edit { prefs ->
            prefs[range] = settings.tempoRange
            prefs[keyLock] = settings.keyLock
            prefs[quantize] = settings.quantize
            prefs[quantizeBeats] = settings.quantizeBeats
            prefs[autoCue] = settings.autoCue
            prefs[hotCue] = settings.hotCueAutoLoad
            prefs[jogVinyl] = settings.jogVinyl
            prefs[wave] = settings.waveColor
            prefs[zoom] = settings.zoomBars
            prefs[playhead] = settings.playhead
            prefs[buffer] = settings.bufferFrames
            prefs[encoder] = settings.encoderFlip
            prefs[jog] = settings.jogSensitivity
            prefs[historySeconds] = settings.historySeconds
            prefs[recordAac] = settings.recordAac
            prefs[followUsb] = settings.followUsb
        }
    }
}
