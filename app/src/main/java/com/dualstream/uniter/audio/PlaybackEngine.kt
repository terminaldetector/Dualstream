package com.dualstream.uniter.audio

import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.core.MixMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone-A playback half: jitter buffer, mixer, local source and AudioTrack
 * on one dedicated thread.
 *
 * The thread runs on a fixed frame grid and blocks on the AudioTrack write,
 * which is what paces the whole loop. Remote frames arrive asynchronously from
 * the RFCOMM reader thread through [submitRemote].
 */
class PlaybackEngine(
    private val frameMs: Int = 20,
    outputStream: OutputStream = OutputStream.MEDIA,
    localSourceProvider: () -> LocalAudioSource = { SilentSource }
) {
    val frameSamples: Int = Pcm.OUT_RATE * frameMs / 1000

    private val output = AudioOutput(Pcm.OUT_RATE, frameSamples, outputStream)
    private val mixer = Mixer(frameSamples)
    val jitter = JitterBuffer(frameSamples, frameMs)

    private val localBuf = ShortArray(frameSamples)
    private var localSource: LocalAudioSource = SilentSource
    private val provider = localSourceProvider

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    var mode: MixMode = MixMode.MIXED_STEREO

    var localVolume: Float
        get() = mixer.localVolume
        set(v) { mixer.localVolume = v.coerceIn(0f, 1f) }

    var remoteVolume: Float
        get() = mixer.remoteVolume
        set(v) { mixer.remoteVolume = v.coerceIn(0f, 1f) }

    var balance: Float
        get() = mixer.balance
        set(v) { mixer.balance = v.coerceIn(-1f, 1f) }

    var duckDepth: Float
        get() = mixer.duckDepth
        set(v) { mixer.duckDepth = v.coerceIn(0f, 1f) }

    /** False when nothing is feeding the local channel, so the mixer can adapt. */
    @Volatile
    var hasLocalSource: Boolean = false
        private set

    /** Sources retired by a swap, released by the audio thread between frames. */
    private val retired = java.util.concurrent.ConcurrentLinkedQueue<LocalAudioSource>()

    fun setLocalSource(src: LocalAudioSource) {
        val old = localSource
        localSource = src
        hasLocalSource = src !== SilentSource
        AppState.setLocalSource(if (hasLocalSource) src.description else null)
        if (old !== src && old !== SilentSource) {
            // Never release from the caller's thread: the audio loop may be
            // inside old.read() right now, and releasing under it is a
            // use-after-free that surfaces as a crash or a burst of noise.
            if (running.get()) retired.add(old) else old.release()
        }
        Logx.i("engine", "local source = ${src.description} (usable=$hasLocalSource)")
    }

    fun start(): Boolean {
        if (running.get()) return true
        if (!output.start()) return false
        setLocalSource(provider())
        running.set(true)
        thread = Thread({ loop() }, "dualstream-playback").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        AppState.setReceiving(true)
        return true
    }

    /** Hands a decoded mono frame at [Pcm.OUT_RATE] to the jitter buffer. */
    fun submitRemote(frame: ShortArray) {
        jitter.push(frame)
    }

    /** Clears buffered audio so a reconnect does not replay stale sound. */
    fun flushAudio() {
        jitter.flush()
        output.flush()
        mixer.reset()
        Logx.i("engine", "audio buffers flushed")
    }

    private fun loop() {
        Logx.i("engine", "playback loop started, frame=${frameMs}ms/$frameSamples samples")
        var cleanFrames = 0
        var meterCounter = 0
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (t: Throwable) {
            Logx.w("engine", "could not raise thread priority", t)
        }

        var announcedFallback = false
        while (running.get()) {
            // Safe point: nothing is reading a retired source here.
            while (true) {
                val old = retired.poll() ?: break
                runCatching { old.release() }.onFailure {
                    Logx.w("engine", "releasing a retired source failed", it)
                }
            }

            // Hard Split with nothing in the local channel would leave one ear
            // silent; with the link also down, both. Degrade rather than mute.
            val requested = mode
            val effective = MixerControls.effectiveMode(requested, hasLocalSource)
            if (effective != requested && !announcedFallback) {
                announcedFallback = true
                Logx.i(
                    "engine",
                    "no local source, playing ${requested.label} as ${effective.label}"
                )
                AppState.notice(
                    "No local source is selected, so ${requested.label} would leave one ear " +
                        "silent. Playing phone B in both ears instead. Pick a track or turn on " +
                        "this phone's microphone to use ${requested.label}."
                )
            } else if (effective == requested && announcedFallback) {
                announcedFallback = false
            }

            val remote = jitter.pop() ?: jitter.lastGoodTail()
            if (remote == null) cleanFrames = 0 else cleanFrames++
            jitter.maybeRelax(cleanFrames)

            val localCount = if (effective == MixMode.REMOTE_ONLY) {
                0
            } else {
                runCatching { localSource.read(localBuf) }.getOrElse {
                    Logx.w("engine", "local source read failed", it)
                    0
                }
            }
            if (localCount < localBuf.size) {
                java.util.Arrays.fill(localBuf, localCount.coerceAtLeast(0), localBuf.size, 0)
            }
            val local = if (localCount > 0) localBuf else null

            val stereo = mixer.mix(local, remote, effective)
            val written = output.write(stereo, stereo.size)
            if (written > 0) output.noteWritten(written)
            if (written < 0) {
                Logx.e("engine", "AudioTrack write failed, stopping loop")
                break
            }

            if (++meterCounter >= METER_EVERY) {
                meterCounter = 0
                val localLevel = if (local != null) Pcm.rms(localBuf) else 0f
                val remoteLevel = if (remote != null) Pcm.rms(remote) else 0f
                AppState.updateStats {
                    it.copy(
                        localLevel = localLevel,
                        remoteLevel = remoteLevel,
                        duckGain = mixer.duckGain,
                        underruns = jitter.underruns,
                        dropped = jitter.dropped,
                        jitterFrames = jitter.size,
                        bufferMs = jitter.bufferedMs + output.queuedMs()
                    )
                }
            }
        }
        Logx.i("engine", "playback loop finished")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(1_000)
        thread = null
        while (true) {
            val old = retired.poll() ?: break
            runCatching { old.release() }
        }
        output.stop()
        jitter.flush()
        if (localSource !== SilentSource) localSource.release()
        localSource = SilentSource
        AppState.setReceiving(false)
    }

    private companion object {
        /** Publish meters every ~100 ms at 20 ms frames. */
        const val METER_EVERY = 5
    }
}
