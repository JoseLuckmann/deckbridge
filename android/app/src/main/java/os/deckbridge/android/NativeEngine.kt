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

    fun loadDeck(
        deck: Int,
        pcm: os.deckbridge.logic.DecodedPcm,
        bpm: Float,
        cueSeconds: Float,
        hotCueSeconds: FloatArray,
        loopStartSeconds: Float,
        loopEndSeconds: Float,
    ) {
        if (!ensureQuiet()) return
        val frames = minOf(pcm.left.size, pcm.right.size)
        val interleaved = FloatArray(frames * 2)
        for (index in 0 until frames) {
            interleaved[index * 2] = pcm.left[index]
            interleaved[index * 2 + 1] = pcm.right[index]
        }
        nativeLoadDeck(deck, interleaved, 2, pcm.sampleRate, bpm, cueSeconds, hotCueSeconds, loopStartSeconds, loopEndSeconds)
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

    fun running(): Boolean = loaded

    fun seek(deck: Int, seconds: Float) {
        if (!loaded) return
        nativeSeek(deck, seconds)
    }

    fun nudge(deck: Int, seconds: Float) {
        if (!loaded) return
        nativeNudge(deck, seconds)
    }

    fun setBeatFx(effect: Int, on: Boolean, level: Int, channel: Int, bpm: Float, beats: Float) {
        if (!loaded) return
        nativeSetBeatFx(effect, on, level, channel, bpm, beats)
    }

    fun setFilter(deck: Int, norm: Float) {
        if (!loaded) return
        nativeSetFilter(deck, norm)
    }

    fun setKeyLock(deck: Int, enabled: Boolean) {
        if (!loaded) return
        nativeSetKeyLock(deck, enabled)
    }

    fun setKeyShift(deck: Int, semitones: Int) {
        if (!loaded) return
        nativeSetKeyShift(deck, semitones)
    }

    fun setSlip(deck: Int, enabled: Boolean) {
        if (!loaded) return
        nativeSetSlip(deck, enabled)
    }

    fun hotCueRelease(deck: Int) {
        if (!loaded) return
        nativeHotCueRelease(deck)
    }

    fun setTrim(deck: Int, linear: Float) {
        if (!loaded) return
        nativeSetTrim(deck, linear)
    }

    fun setLoop(deck: Int, startSec: Float, endSec: Float) {
        if (!loaded) return
        nativeSetLoop(deck, startSec, endSec)
    }

    fun setReverse(deck: Int, reversed: Boolean) {
        if (!loaded) return
        nativeSetReverse(deck, reversed)
    }

    fun setBuffer(frames: Int) {
        if (!loaded) return
        nativeSetBuffer(frames)
    }

    fun setRmx(values: FloatArray) {
        if (!loaded) return
        nativeSetRmx(values)
    }

    fun recordStart(path: String, aac: Boolean): Boolean {
        if (!ensureQuiet()) return false
        return nativeRecordStart(path, aac)
    }

    fun recordStop() {
        if (!loaded) return
        nativeRecordStop()
    }

    fun recordPoll(): String {
        if (!loaded) return "0,0,0,48000"
        return nativeRecordPoll()
    }

    fun recordPull(dest: FloatArray): Int {
        if (!loaded) return 0
        return nativeRecordPull(dest)
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
        channels: Int,
        rate: Int,
        bpm: Float,
        cueSeconds: Float,
        hot: FloatArray,
        loopStart: Float,
        loopEnd: Float,
    )
    private external fun nativeTransport(deck: Int, playing: Boolean)
    private external fun nativeCue(deck: Int)
    private external fun nativeHotCue(deck: Int, index: Int)
    private external fun nativeHotCueRelease(deck: Int)
    private external fun nativeSetTrim(deck: Int, linear: Float)
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
    private external fun nativeSeek(deck: Int, seconds: Float)
    private external fun nativeNudge(deck: Int, seconds: Float)
    private external fun nativeSetBeatFx(effect: Int, on: Boolean, level: Int, channel: Int, bpm: Float, beats: Float)
    private external fun nativeSetFilter(deck: Int, norm: Float)
    private external fun nativeSetKeyLock(deck: Int, enabled: Boolean)
    private external fun nativeSetKeyShift(deck: Int, semitones: Int)
    private external fun nativeSetSlip(deck: Int, enabled: Boolean)
    private external fun nativeSetLoop(deck: Int, startSec: Float, endSec: Float)
    private external fun nativeSetReverse(deck: Int, reversed: Boolean)
    private external fun nativeSetBuffer(frames: Int)
    private external fun nativeSetRmx(values: FloatArray)
    private external fun nativeRecordStart(path: String, side: Boolean): Boolean
    private external fun nativeRecordStop()
    private external fun nativeRecordPoll(): String
    private external fun nativeRecordPull(dest: FloatArray): Int
}
