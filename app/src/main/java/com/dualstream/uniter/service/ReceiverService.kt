package com.dualstream.uniter.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.dualstream.uniter.audio.AudioOutput
import com.dualstream.uniter.audio.FileLocalSource
import com.dualstream.uniter.audio.FrameAssembler
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.dualstream.uniter.audio.AppAudioLocalSource
import com.dualstream.uniter.audio.InputRoutePolicy
import com.dualstream.uniter.audio.OutputStream
import com.dualstream.uniter.audio.SystemVolume
import com.dualstream.uniter.audio.LocalAudioSource
import com.dualstream.uniter.audio.MicLocalSource
import com.dualstream.uniter.audio.Pcm
import com.dualstream.uniter.audio.PlaybackEngine
import com.dualstream.uniter.audio.RecordingPolicyMonitor
import com.dualstream.uniter.audio.Resampler
import com.dualstream.uniter.audio.SilentSource
import com.dualstream.uniter.audio.ToneSource
import com.dualstream.uniter.codec.AudioDecoder
import com.dualstream.uniter.codec.CodecFactory
import com.dualstream.uniter.codec.CodecUnavailableException
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.HeadsetState
import com.dualstream.uniter.core.LinkState
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.core.MixMode
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.protocol.AudioPayload
import com.dualstream.uniter.protocol.CodecInit
import com.dualstream.uniter.protocol.Frame
import com.dualstream.uniter.protocol.Hello
import com.dualstream.uniter.protocol.Protocol
import com.dualstream.uniter.protocol.StateUpdate
import com.dualstream.uniter.transport.Backoff
import com.dualstream.uniter.transport.BtLink
import com.dualstream.uniter.transport.RfcommAcceptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone A. Owns the headset, runs the RFCOMM server, decodes phone B, mixes it
 * with the app-managed local source and plays the result through A2DP.
 *
 * Threads:
 *  - `dualstream-accept`  accept + handshake + frame read loop
 *  - `dualstream-playback` the audio loop inside [PlaybackEngine]
 *  - `dualstream-keepalive` PING/PONG round-trip measurement
 */
class ReceiverService : Service() {

    private lateinit var prefs: Prefs
    private var engine: PlaybackEngine? = null
    private var acceptor: RfcommAcceptor? = null
    private var linkThread: Thread? = null
    private var keepaliveThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var currentLink: BtLink? = null

    @Volatile
    private var decoder: AudioDecoder? = null

    private val running = AtomicBoolean(false)
    private val backoff = Backoff()

    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null

    /** Non-null while this phone's own microphone is the local mixer source. */
    @Volatile private var micLocal: MicLocalSource? = null

    /** Non-null while this phone's own app audio is the local mixer source. */
    @Volatile private var appAudioLocal: AppAudioLocalSource? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var systemVolume: SystemVolume? = null
    private var policyMonitor: RecordingPolicyMonitor? = null

    /** The mic-as-local-source setting the running engine was built with. */
    private var micLocalActive = false

    /** Underruns at the start of the current coexistence observation window. */
    private var windowUnderruns = 0L
    private var windowStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Logx.verbose = prefs.verboseLog
        NotificationHelper.ensureChannels(this)
        audioManager = ContextCompat.getSystemService(this, AudioManager::class.java)
        systemVolume = SystemVolume(audioManager)
        if (prefs.savedMediaVolume > 0) {
            Logx.w("receiver", "restoring a media volume left silenced by an earlier run")
            restoreMediaVolume()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceContract.ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ServiceContract.ACTION_RECONNECT -> {
                Logx.i("receiver", "manual reconnect requested")
                backoff.reset()
                dropLink("manual reconnect")
                if (!running.get()) startEverything()
                return START_STICKY
            }
            ServiceContract.ACTION_APPLY_SETTINGS -> {
                applySettings()
                return START_STICKY
            }
            else -> {
                // Android 14 requires the service to be foreground with the
                // mediaProjection type BEFORE getMediaProjection(), so the claim
                // has to happen here rather than inside startEverything().
                val hasConsent = (intent?.getIntExtra(ServiceContract.EXTRA_RESULT_CODE, 0) ?: 0) != 0
                promote("Starting…", includeProjection = hasConsent)
                adoptProjection(intent)
                startEverything()
            }
        }
        return START_STICKY
    }

    /**
     * Phone A needs its own MediaProjection consent to use this phone's app
     * audio as the local channel. Without it that option simply is not
     * available, and the rest of the receiver works as before.
     */
    private fun adoptProjection(intent: Intent?) {
        if (projection != null) return
        val code = intent?.getIntExtra(ServiceContract.EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(ServiceContract.EXTRA_RESULT_DATA)
        if (code == 0 || data == null) return

        val mpm = ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
            ?: return
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (mp == null) {
            Logx.w("receiver", "capture consent was not usable")
            return
        }
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Logx.w("receiver", "this phone's capture was stopped")
                appAudioLocal = null
                projection = null
                exclusiveLocalPossible = false
                // The silencing only existed to serve this capture. Leaving it
                // on strands the phone mute for reasons the user cannot see.
                restoreMediaVolume()
                AppState.notice(
                    "Capture of this phone's audio stopped. Android needs fresh consent " +
                        "before the local channel can use it again."
                )
                engine?.setLocalSource(buildLocalSource())
            }
        }
        mp.registerCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
        projection = mp
        projectionCallback = cb
        Logx.i("receiver", "capture consent accepted for this phone's app audio")
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * True when this phone can both capture its own apps and stop them
     * reaching the headset directly. Without the second half the first is
     * worse than useless: the audio is heard twice and the channel split is
     * drowned out.
     */
    @Volatile private var exclusiveLocalPossible = false

    private fun startEverything() {
        promote("Waiting for phone B")
        if (running.getAndSet(true)) {
            applySettings()
            return
        }
        AppState.resetStats()
        acquireWakeLock()
        registerHeadsetCallback()

        exclusiveLocalPossible = projection != null && canSilenceMediaStream()
        if (projection != null && !exclusiveLocalPossible) {
            AppState.notice(
                if (!SystemVolume.canMute(this))
                    "To keep this phone's other apps out of the headset their volume has to be " +
                        "muted, and Android counts a mute as a Do Not Disturb change. Grant " +
                        "Do Not Disturb access to this app, then start the receiver again. " +
                        "Until then the local ear uses a track or the microphone."
                else
                    "This phone does not let the app change its media volume even with Do Not " +
                        "Disturb access, so its other apps cannot be kept out of the headset. " +
                        "The local ear uses a track or the microphone instead. Phone B is " +
                        "unaffected."
            )
        }
        Logx.i("receiver", "exclusive local channel possible: $exclusiveLocalPossible")

        val eng = PlaybackEngine(
            frameMs = prefs.frameMs,
            // Capturing this phone means its apps must stop reaching the
            // headset directly, which means silencing the media volume, which
            // only works if the mix is somewhere else.
            // Only leave the media stream when the other apps on it are
            // actually being silenced. Otherwise this just moves our own output
            // somewhere that may not reach the headset at all.
            outputStream = if (exclusiveLocalPossible) {
                OutputStream.ACCESSIBILITY
            } else {
                OutputStream.MEDIA
            },
            localSourceProvider = ::buildLocalSource
        )
        if (!eng.start()) {
            AppState.notice("AudioTrack could not be opened. Playback is unavailable on this device.")
            AppState.setLink(LinkState.FAILED)
            running.set(false)
            return
        }
        engine = eng
        applySettings()

        val adapter = ContextCompat.getSystemService(this, android.bluetooth.BluetoothManager::class.java)
            ?.adapter
        if (adapter == null) {
            AppState.setLink(LinkState.FAILED)
            AppState.notice("This device has no Bluetooth adapter.")
            return
        }
        if (!adapter.isEnabled) {
            AppState.setLink(LinkState.BLUETOOTH_OFF)
            AppState.notice("Bluetooth is off. Turn it on to accept phone B.")
        }

        linkThread = Thread({ acceptLoop(adapter) }, "dualstream-accept").apply { start() }
        keepaliveThread = Thread(::keepaliveLoop, "dualstream-keepalive").apply { start() }
    }

    private fun stopEverything() {
        if (!running.getAndSet(false)) return
        Logx.i("receiver", "stopping")
        dropLink("service stop")
        runCatching { acceptor?.close() }
        acceptor = null
        linkThread?.join(1_500)
        linkThread = null
        keepaliveThread?.interrupt()
        keepaliveThread = null
        engine?.stop()
        engine = null
        restoreMediaVolume()
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
        appAudioLocal = null
        policyMonitor?.stop()
        policyMonitor = null
        micLocal = null
        micLocalActive = false
        unregisterHeadsetCallback()
        releaseWakeLock()
        AppState.reset()
        AppState.setHeadset(HeadsetState.UNKNOWN)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ settings

    private fun applySettings() {
        val eng = engine ?: return
        eng.mode = prefs.mixMode
        eng.localVolume = prefs.localVolume
        eng.remoteVolume = prefs.remoteVolume
        eng.balance = prefs.balance
        eng.duckDepth = prefs.duckDepth

        micLocal?.gain = prefs.micGain

        // Switching the local source between a file and the microphone changes
        // which foreground types this service needs, so rebuild both together.
        if (prefs.micAsLocalSource != micLocalActive) {
            policyMonitor?.stop()
            micLocal = null
            promote(if (prefs.micAsLocalSource) "Microphone + phone B" else "Receiver running")
            eng.setLocalSource(buildLocalSource())
        } else {
            warnAboutFeedbackIfNeeded()
        }
        Logx.d("receiver", "settings applied: mode=${prefs.mixMode} local=${prefs.localVolume} remote=${prefs.remoteVolume}")
    }

    /**
     * The local source for HARD_SPLIT and PRIORITY. Section 6 is explicit that
     * it has to be a source the app itself controls, so this is either the user
     * selected file or the built-in test tone.
     */
    private fun buildLocalSource(): LocalAudioSource {
        micLocal = null
        appAudioLocal = null
        micLocalActive = prefs.micAsLocalSource

        // Capturing this phone's apps is the fallback, not the override: if the
        // user has explicitly chosen the microphone or a track, that choice
        // wins. Deciding the source from whether a projection token happens to
        // exist made the microphone setting look ignored.
        val explicitChoice = prefs.micAsLocalSource || !prefs.localSourceUri.isNullOrBlank()
        run {
            val mp = if (exclusiveLocalPossible && !explicitChoice) projection else null
            if (mp != null) {
                val frameSamples = Pcm.OUT_RATE * prefs.frameMs / 1000
                val src = AppAudioLocalSource(mp, frameSamples)
                if (src.start()) {
                    appAudioLocal = src
                    applyOtherAppSilencing()
                    Logx.i("receiver", "local source is this phone's app audio")
                    return src
                }
                Logx.w("receiver", "this phone's app audio could not be captured")
                AppState.notice("This phone's app audio could not be captured.")
            }
        }

        if (prefs.micAsLocalSource) {
            if (!hasRecordAudio()) {
                AppState.notice("Microphone permission is required to use this phone's microphone.")
                micLocalActive = false
                return SilentSource
            }
            val frameSamples = Pcm.OUT_RATE * prefs.frameMs / 1000
            val mic = MicLocalSource(
                audioManager = audioManager,
                frameSamples = frameSamples,
                gain = prefs.micGain,
                processing = prefs.micProcessing
            )
            if (mic.start()) {
                micLocal = mic
                watchRecordingPolicy(mic.sessionId)
                mic.routeProblem?.let { AppState.notice(it) }
                warnAboutFeedbackIfNeeded()
                Logx.i("receiver", "local source is this phone's microphone")
                return mic
            }
            Logx.w("receiver", "local microphone could not start")
            AppState.notice("This phone's microphone could not be opened. Another app may hold it.")
            micLocalActive = false
            return SilentSource
        }

        val uri = prefs.localSourceUri
        if (uri.isNullOrBlank()) return SilentSource
        if (uri == LOCAL_TONE) return ToneSource()
        return try {
            FileLocalSource(this, Uri.parse(uri))
        } catch (t: Throwable) {
            Logx.w("receiver", "local source $uri unusable", t)
            AppState.notice("The selected local track could not be decoded.")
            SilentSource
        }
    }

    /**
     * This phone's microphone sitting next to a headset playing the same mix is
     * an acoustic loop. The platform echo canceller helps, but it is tuned for
     * handset playback, not for leakage out of someone else's earpiece, so the
     * honest thing is to say it out loud.
     */
    private fun warnAboutFeedbackIfNeeded() {
        if (!micLocalActive) return
        if (prefs.mixMode == MixMode.REMOTE_ONLY) return
        val a2dp = audioManager?.let { AudioOutput.isA2dpActive(it) } == true
        if (!a2dp) return
        AppState.notice(
            "This phone's microphone is mixed into the headset it is also feeding. Keep the " +
                "headset away from the phone, or howl-round is likely."
        )
    }

    /**
     * Captured apps keep playing to the headset by their own path, so without
     * silencing them the listener hears them twice: once directly in both
     * ears, once through the mix. The only lever is the media volume, which is
     * why the mix has to be on another stream for this to be any use.
     */
    /**
     * Can this phone's media stream actually be silenced by this app?
     *
     * Everything about using phone A's own app audio rests on it. Those apps
     * reach the headset by their own path, and if that path cannot be silenced
     * they are heard at full volume in both ears no matter what the mixer
     * does — the routing is simply bypassed, which is exactly how it sounded
     * in the field: the local side blasting into both channels whatever the
     * settings said.
     *
     * Several builds refuse a background volume change outright, so asking is
     * not enough: the value is set, read back, and put straight back. A
     * refusal here is a fact about the device, and the app has to act on it
     * rather than carry on and leave both the routing and the volume broken.
     */
    private fun canSilenceMediaStream(): Boolean {
        val vol = systemVolume ?: return false
        val max = vol.max
        if (max <= 0) return false
        val before = vol.current
        if (before == 0) return true

        if (!vol.setFraction(0f)) {
            Logx.w(
                "receiver",
                "media volume change refused; Do Not Disturb access granted=" +
                    SystemVolume.canMute(this)
            )
            return false
        }
        val achieved = vol.current == 0
        // Never leave it silenced by a probe.
        vol.setFraction(before.toFloat() / max)
        if (!achieved) {
            Logx.w("receiver", "media volume did not reach zero; it is not ours to control")
        }
        return achieved
    }

    private fun applyOtherAppSilencing() {
        val vol = systemVolume ?: return
        val now = vol.current
        if (now > 0) {
            prefs.savedMediaVolume = now
            if (vol.setFraction(0f)) {
                Logx.i("receiver", "media volume silenced (was $now) so only the mix is heard")
            } else {
                prefs.savedMediaVolume = -1
            }
        }
    }

    private fun restoreMediaVolume() {
        val vol = systemVolume ?: return
        val previous = prefs.savedMediaVolume
        if (previous <= 0) return
        prefs.savedMediaVolume = -1
        val max = vol.max
        if (max > 0 && vol.setFraction(previous.toFloat() / max)) {
            Logx.i("receiver", "media volume restored to $previous")
        }
    }

    private fun watchRecordingPolicy(sessionId: Int) {
        val monitor = policyMonitor ?: RecordingPolicyMonitor(audioManager) { report ->
            if (report.silenced) {
                AppState.notice(
                    "Android has muted this phone's microphone for us: another app took the " +
                        "input, or this app lost its microphone claim."
                )
            } else {
                report.routedType?.let { type ->
                    InputRoutePolicy.rejectionReason(type)?.let { AppState.notice(it) }
                }
            }
        }.also { policyMonitor = it }
        monitor.start(sessionId)
    }

    // ------------------------------------------------------------------- accept

    private fun acceptLoop(adapter: BluetoothAdapter) {
        while (running.get()) {
            if (!adapter.isEnabled) {
                AppState.setLink(LinkState.BLUETOOTH_OFF)
                sleepQuietly(1_500)
                continue
            }
            val acc = RfcommAcceptor(adapter)
            acceptor = acc
            AppState.setLink(LinkState.LISTENING)
            updateNotification("Waiting for phone B")
            warnIfInvisible(adapter)

            val link = try {
                acc.accept()
            } catch (se: SecurityException) {
                Logx.e("receiver", "missing BLUETOOTH_CONNECT", se)
                AppState.setLink(LinkState.NO_PERMISSION)
                AppState.notice("Grant the Nearby devices permission, then press Reconnect.")
                return
            } catch (e: IOException) {
                if (!running.get()) return
                Logx.w("receiver", "accept failed", e)
                AppState.setLink(LinkState.RECOVERING)
                sleepQuietly(backoff.nextDelayMs())
                continue
            } finally {
                runCatching { acc.close() }
                acceptor = null
            }
            if (link == null) continue

            backoff.reset()
            currentLink = link
            AppState.setPeerName(link.remoteName)
            AppState.setLink(LinkState.HANDSHAKING)

            try {
                serveLink(link)
            } catch (t: Throwable) {
                Logx.w("receiver", "link ended: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                cleanupAfterLink()
                if (!prefs.autoReconnect) {
                    AppState.setLink(LinkState.IDLE)
                    running.set(false)
                    updateNotification("Disconnected")
                    return
                }
                AppState.setLink(LinkState.RECOVERING)
                updateNotification("Reconnecting…")
            }
        }
    }

    /** Handshake then read frames until the peer disappears. */
    private fun serveLink(link: BtLink) {
        // RFCOMM sockets have no read timeout, so a peer that connects and then
        // says nothing would block this thread forever. A watchdog closes the
        // socket, which makes the pending read fail.
        val handshakeDone = AtomicBoolean(false)
        Thread({
            sleepQuietly(HANDSHAKE_TIMEOUT_MS)
            if (!handshakeDone.get() && !link.closed) {
                Logx.w("receiver", "handshake timed out, closing link")
                link.close()
            }
        }, "dualstream-handshake-watchdog").apply { isDaemon = true }.start()

        val hello = waitForHello(link) ?: throw IOException("no HELLO from peer")
        handshakeDone.set(true)
        Logx.i("receiver", "HELLO from ${hello.deviceName}: ${hello.codec} ${hello.sampleRate}Hz frame=${hello.frameMs}ms ${hello.bitrate}bps")

        link.writer.write(
            Protocol.Type.HELLO_ACK, 0,
            Hello(
                role = com.dualstream.uniter.core.Role.RECEIVER,
                codec = hello.codec,
                sampleRate = hello.sampleRate,
                channels = 1,
                frameMs = hello.frameMs,
                bitrate = hello.bitrate,
                deviceName = android.os.Build.MODEL ?: "phone A"
            ).encode()
        )
        AppState.setNegotiated(hello.config)

        // Decoder is created from CODEC_INIT, which the transmitter always sends
        // before its first audio frame (empty for codecs that need no setup).
        var assembler: FrameAssembler? = null
        var resampler: Resampler? = null
        var scratch = ShortArray(0)
        var lastSeq = -1

        while (running.get() && !link.closed) {
            val frame = link.reader.read()
            when (frame.type) {
                Protocol.Type.CODEC_INIT -> {
                    val init = CodecInit.decode(frame.payload) ?: CodecInit(emptyList())
                    releaseDecoder()
                    val dec = try {
                        CodecFactory.createDecoder(
                            hello.codec, hello.sampleRate, hello.frameMs, init.buffers
                        )
                    } catch (e: CodecUnavailableException) {
                        Logx.e("receiver", "decoder unavailable", e)
                        AppState.notice("This device cannot decode ${hello.codec.label}. Ask phone B to switch codec.")
                        link.writer.write(Protocol.Type.BYE, 0, ByteArray(0))
                        throw IOException("no decoder for ${hello.codec}")
                    }
                    decoder = dec
                    resampler = Resampler(dec.sampleRate, Pcm.OUT_RATE)
                    assembler = FrameAssembler(engine?.frameSamples ?: (Pcm.OUT_RATE * 20 / 1000))
                    scratch = ShortArray(maxOf(4096, dec.frameSamples * 8))
                    engine?.flushAudio()
                    val connectedAt = System.currentTimeMillis()
                    windowUnderruns = engine?.jitter?.underruns ?: 0
                    windowStartMs = connectedAt
                    AppState.setLink(LinkState.CONNECTED)
                    AppState.updateStats { it.copy(connectedSinceMs = connectedAt) }
                    updateNotification("Connected to ${link.remoteName}")
                    Logx.i("receiver", "stream ready: ${dec.id.label} ${dec.sampleRate}Hz")
                }

                Protocol.Type.AUDIO -> {
                    val dec = decoder
                    val asm = assembler
                    val rs = resampler
                    if (dec == null || asm == null || rs == null) {
                        Logx.d("receiver", "audio before CODEC_INIT, dropped")
                        continue
                    }
                    if (lastSeq >= 0) {
                        val expected = (lastSeq + 1) and 0xFFFF
                        if (frame.seq != expected) {
                            val gap = ((frame.seq - expected) and 0xFFFF)
                            if (gap in 1..512) {
                                AppState.updateStats { it.copy(lost = it.lost + gap) }
                            }
                        }
                    }
                    lastSeq = frame.seq

                    AppState.updateStats {
                        it.copy(
                            framesReceived = it.framesReceived + 1,
                            bytesReceived = it.bytesReceived + frame.payload.size + Protocol.HEADER_SIZE
                        )
                    }
                    if (frame.payload.size <= AudioPayload.HEADER) continue

                    val pcm = dec.decode(
                        frame.payload, AudioPayload.HEADER,
                        frame.payload.size - AudioPayload.HEADER
                    ) ?: continue

                    if (rs.bypass) {
                        asm.submit(pcm, 0, pcm.size) { engine?.submitRemote(it) }
                    } else {
                        val need = rs.maxOut(pcm.size)
                        if (scratch.size < need) scratch = ShortArray(need)
                        val n = rs.process(pcm, 0, pcm.size, scratch)
                        asm.submit(scratch, 0, n) { engine?.submitRemote(it) }
                    }
                    checkCoexistence()
                }

                Protocol.Type.PING ->
                    link.writer.write(Protocol.Type.PONG, frame.seq, frame.payload)

                Protocol.Type.PONG -> notePong(frame)

                Protocol.Type.STATE -> {
                    StateUpdate.decode(frame.payload)?.let {
                        AppState.setCapture(it.capture)
                        if (it.capture == CaptureState.CAPTURING_SILENCE) {
                            AppState.notice(
                                "Phone B is connected but the app it is playing does not allow playback capture."
                            )
                        }
                    }
                }

                Protocol.Type.BYE -> {
                    Logx.i("receiver", "peer said goodbye")
                    throw IOException("peer closed the session")
                }

                else -> Logx.d("receiver", "ignoring frame type ${frame.type}")
            }
        }
    }

    private fun waitForHello(link: BtLink): Hello? {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = link.reader.read()
            if (frame.type == Protocol.Type.HELLO) {
                return Hello.decode(frame.payload)
            }
            Logx.d("receiver", "pre-handshake frame ${frame.type} ignored")
        }
        return null
    }

    private fun notePong(frame: Frame) {
        if (frame.payload.size < 8) return
        var sent = 0L
        for (i in 0 until 8) sent = (sent shl 8) or (frame.payload[i].toLong() and 0xFF)
        val rtt = (System.currentTimeMillis() - sent).toInt()
        if (rtt in 0..10_000) AppState.updateStats { it.copy(rttMs = rtt) }
    }

    /**
     * Section 7: if the two Bluetooth links do not coexist well on this device,
     * say so instead of silently sounding broken.
     */
    private fun checkCoexistence() {
        val eng = engine ?: return
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) {
            windowStartMs = now
            windowUnderruns = eng.jitter.underruns
            return
        }
        if (now - windowStartMs < COEXIST_WINDOW_MS) return
        val delta = eng.jitter.underruns - windowUnderruns
        windowStartMs = now
        windowUnderruns = eng.jitter.underruns

        val a2dp = audioManager?.let { AudioOutput.isA2dpActive(it) } == true
        if (!a2dp) return
        if (delta > COEXIST_UNDERRUN_LIMIT) {
            AppState.setHeadset(HeadsetState.CONNECTED_DEGRADED)
            AppState.notice(
                "This phone is struggling to run the RFCOMM link and the A2DP headset at the same " +
                    "time ($delta audio gaps in the last minute). Lower the transmission quality or " +
                    "try another headset."
            )
        } else {
            AppState.setHeadset(HeadsetState.CONNECTED_A2DP)
        }
    }

    // ---------------------------------------------------------------- keepalive

    private fun keepaliveLoop() {
        while (running.get()) {
            try {
                Thread.sleep(KEEPALIVE_MS)
            } catch (e: InterruptedException) {
                return
            }
            val link = currentLink ?: continue
            if (link.closed) continue
            val now = System.currentTimeMillis()
            val payload = ByteArray(8)
            for (i in 0 until 8) payload[i] = ((now shr ((7 - i) * 8)) and 0xFF).toByte()
            try {
                link.writer.write(Protocol.Type.PING, 0, payload)
            } catch (e: IOException) {
                Logx.w("receiver", "keepalive failed, dropping link", e)
                dropLink("keepalive failure")
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Listening is not the same as being findable.
     *
     * An RFCOMM server socket accepts connections but does nothing to the
     * adapter's scan mode, so a phone B that has never been paired with this
     * one cannot see it at all and the user is left watching "waiting for phone
     * B" forever. Say so instead, but only while there is no paired peer to
     * connect from: once bonded, phone B connects straight to the address and
     * visibility is irrelevant.
     */
    private fun warnIfInvisible(adapter: BluetoothAdapter) {
        val visible = try {
            adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        } catch (t: SecurityException) {
            return
        }
        if (visible) return

        val peer = prefs.trustedPeerAddress
        val bonded = peer != null && try {
            adapter.bondedDevices.orEmpty().any { it.address == peer }
        } catch (t: SecurityException) {
            false
        }
        if (bonded) return

        AppState.notice(
            "This phone is listening, but it is not visible to other devices and no phone is " +
                "paired with it yet, so phone B cannot find it. Press Make visible, then pair " +
                "from phone B."
        )
    }

    private fun dropLink(why: String) {
        val link = currentLink ?: return
        Logx.i("receiver", "dropping link: $why")
        currentLink = null
        runCatching { link.writer.write(Protocol.Type.BYE, 0, ByteArray(0)) }
        link.close()
    }

    /** Section 9: clear buffers so a reconnect does not replay stale audio. */
    private fun cleanupAfterLink() {
        currentLink?.close()
        currentLink = null
        releaseDecoder()
        engine?.flushAudio()
        AppState.setCapture(CaptureState.IDLE)
        AppState.setNegotiated(null)
        AppState.setPeerName(null)
        AppState.updateStats { it.copy(rttMs = -1, connectedSinceMs = 0) }
    }

    private fun releaseDecoder() {
        decoder?.let {
            it.reset()
            it.release()
        }
        decoder = null
    }

    private fun registerHeadsetCallback() {
        val am = audioManager ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()

            private fun refresh() {
                val a2dp = AudioOutput.isA2dpActive(am)
                AppState.setHeadset(
                    if (a2dp) HeadsetState.CONNECTED_A2DP else HeadsetState.DISCONNECTED
                )
                if (!a2dp) {
                    // Section 9: the headset went away. Drop queued audio so it does
                    // not burst out of the speaker when it comes back.
                    engine?.flushAudio()
                    AppState.notice("Bluetooth headset disconnected. Audio is routed to this phone.")
                } else {
                    AppState.notice(null)
                }
            }
        }
        am.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb
        AppState.setHeadset(
            if (AudioOutput.isA2dpActive(am)) HeadsetState.CONNECTED_A2DP
            else HeadsetState.DISCONNECTED
        )
    }

    private fun unregisterHeadsetCallback() {
        val am = audioManager ?: return
        deviceCallback?.let { runCatching { am.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dualstream:receiver").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /**
     * Claims exactly the foreground types in use. The microphone type is only
     * claimed while this phone's own microphone feeds the mixer; claiming types
     * that are not in use is refused outright on recent Android versions.
     */
    private fun promote(text: String, includeProjection: Boolean = projection != null): Boolean {
        var types = ForegroundTypes.mediaPlayback() or ForegroundTypes.connectedDevice()
        if (prefs.micAsLocalSource && hasRecordAudio()) {
            types = types or ForegroundTypes.microphone()
        }
        if (includeProjection) {
            types = types or ForegroundTypes.mediaProjection()
        }
        return ForegroundTypes.promote(
            this,
            NotificationHelper.ID_RECEIVER,
            NotificationHelper.build(
                this, NotificationHelper.CHANNEL_RECEIVER,
                "DualStream receiver", text, ReceiverService::class.java
            ),
            types
        )
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun updateNotification(text: String) {
        NotificationHelper.update(
            this, NotificationHelper.ID_RECEIVER,
            NotificationHelper.build(
                this, NotificationHelper.CHANNEL_RECEIVER,
                "DualStream receiver", text, ReceiverService::class.java
            )
        )
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** Sentinel URI meaning "use the built-in test tone as the local source". */
        const val LOCAL_TONE = "dualstream://tone"

        private const val HANDSHAKE_TIMEOUT_MS = 8_000L
        private const val KEEPALIVE_MS = 2_000L
        private const val COEXIST_WINDOW_MS = 60_000L
        private const val COEXIST_UNDERRUN_LIMIT = 25
        private const val WAKELOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        /**
         * @param resultCode/[resultData] MediaProjection consent, so this phone
         * can put its own app audio in the local ear. Pass 0/null to run
         * without it; everything else still works.
         */
        fun start(context: android.content.Context, resultCode: Int = 0, resultData: Intent? = null) {
            val i = Intent(context, ReceiverService::class.java)
                .setAction(ServiceContract.ACTION_START)
                .putExtra(ServiceContract.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ServiceContract.EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, i)
        }

        fun send(context: android.content.Context, action: String) {
            val i = Intent(context, ReceiverService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
