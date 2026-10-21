package os.deckbridge.android

import os.deckbridge.logic.EngineStats
import os.deckbridge.logic.parseEngineStats

object NativeEngine {
    private var loaded = false

    fun start(deviceId: Int, route: Int): EngineStats {
        return try {
            ensure()
            parseEngineStats(nativeStart(deviceId, route))
        } catch (error: UnsatisfiedLinkError) {
            failed(error.message ?: "biblioteca nativa ausente")
        } catch (error: RuntimeException) {
            failed(error.message ?: "falha ao abrir o motor")
        }
    }

    fun stop() {
        if (!loaded) return
        try {
            nativeStop()
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    fun loadDeck(deck: Int, mono: FloatArray, sampleRate: Int, bpm: Float, cueSeconds: Float, hotCueSeconds: FloatArray) {
        if (!ensureQuiet()) return
        nativeLoadDeck(deck, mono, sampleRate, bpm, cueSeconds, hotCueSeconds)
    }

    fun transport(deck: Int, playing: Boolean) {
        if (!loaded) return
        nativeTransport(deck, playing)
    }

    fun cue(deck: Int) {
        if (!loaded) return
        nativeCue(deck)
    }

    fun hotCue(deck: Int, index: Int) {
        if (!loaded) return
        nativeHotCue(deck, index)
    }

    fun setMix(volA: Float, volB: Float, pitchA: Float, pitchB: Float, cross: Float, pflA: Boolean, pflB: Boolean) {
        if (!loaded) return
        nativeSetMix(volA, volB, pitchA, pitchB, cross, pflA, pflB)
    }

    fun position(deck: Int): Float {
        if (!loaded) return 0f
        return nativePosition(deck)
    }

    private fun ensure() {
        if (!loaded) {
            System.loadLibrary("deckbridge-engine")
            loaded = true
        }
    }

    private fun ensureQuiet(): Boolean {
        return try {
            ensure()
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    private fun failed(message: String): EngineStats {
        return parseEngineStats(
            "opened=false\nrequested_channels=4\nopened_channels=0\nsample_rate=0\n" +
                "device_id=0\nsharing=UNKNOWN\nperformance=UNKNOWN\nfallback_to_two=false\n" +
                "route=0\nphones_sent=false\nmode=tone\n" +
                "xruns_known=false\nxruns=-1\nlatency_known=false\nlatency_ms=-1\nerror=$message\n",
        )
    }

    private external fun nativeStart(deviceId: Int, route: Int): String
    private external fun nativeStop()
    private external fun nativeLoadDeck(
        deck: Int,
        samples: FloatArray,
        rate: Int,
        bpm: Float,
        cueSeconds: Float,
        hot: FloatArray,
    )
    private external fun nativeTransport(deck: Int, playing: Boolean)
    private external fun nativeCue(deck: Int)
    private external fun nativeHotCue(deck: Int, index: Int)
    private external fun nativeSetMix(
        volA: Float,
        volB: Float,
        pitchA: Float,
        pitchB: Float,
        cross: Float,
        pflA: Boolean,
        pflB: Boolean,
    )
    private external fun nativePosition(deck: Int): Float
}
