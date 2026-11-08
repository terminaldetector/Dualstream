package com.dualstream.uniter.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.media.AudioManager
import com.dualstream.uniter.audio.CaptureSource
import com.dualstream.uniter.audio.DirectPcmInput
import com.dualstream.uniter.audio.InputRoutePolicy
import com.dualstream.uniter.audio.MicCaptureSource
import com.dualstream.uniter.audio.MixCaptureSource
import com.dualstream.uniter.audio.Pcm
import com.dualstream.uniter.audio.PlaybackCaptureSource
import com.dualstream.uniter.audio.RateMeter
import com.dualstream.uniter.audio.RecordingPolicyMonitor
import com.dualstream.uniter.audio.Resampler
import com.dualstream.uniter.audio.ToneSource
import com.dualstream.uniter.codec.AudioEncoder
import com.dualstream.uniter.codec.BitrateController
import com.dualstream.uniter.codec.CodecFactory
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.CaptureState
import com.dualstream.uniter.core.InputSource
import com.dualstream.uniter.core.LinkState
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.core.Role
import com.dualstream.uniter.protocol.AudioPayload
import com.dualstream.uniter.protocol.CodecInit
import com.dualstream.uniter.protocol.ConfigUpdate
import com.dualstream.uniter.protocol.Hello
import com.dualstream.uniter.protocol.Protocol
import com.dualstream.uniter.protocol.StateUpdate
import com.dualstream.uniter.transport.Backoff
import com.dualstream.uniter.transport.BtDiscovery
import com.dualstream.uniter.transport.BtLink
import com.dualstream.uniter.transport.RfcommConnector
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone B. Captures its own playback, encodes it and pushes frames to phone A
 * over RFCOMM.
 *
 * MediaProjection consent is never re-acquired silently: if the system revokes
 * the projection (or the user stops it), the service reports
 * [CaptureState.NEEDS_CONSENT] and waits for the user, as required by
 * section 9.
 */
class TransmitterService : Service() {

    private lateinit var prefs: Prefs
    private var discovery: BtDiscovery? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var connectThread: Thread? = null
    private var readerThread: Thread? = null

    @Volatile private var currentLink: BtLink? = null
    @Volatile private var capture: CaptureSource? = null
    @Volatile private var encoder: AudioEncoder? = null
    @Volatile private var peerAddress: String? = null
    @Volatile private var inputSource: InputSource = InputSource.PLAYBACK_CAPTURE

    private var audioManager: AudioManager? = null
    private var policyMonitor: RecordingPolicyMonitor? = null

    /** Set by the policy monitor when Android mutes our recorder. */
    @Volatile private var recorderSilencedByPolicy = false
    @Volatile private var projectionStartedAt = 0L

    /** Media volume before we silenced it, so it can be put back. */
    private var volumeBeforeMute: Int? = null

    /** Non-null only while the "app audio + microphone" source is running. */
    @Volatile private var mixSource: MixCaptureSource? = null

    private val rateMeter = RateMeter()
    private var bitrate: BitrateController? = null

    /** Frames thrown away rather than pushed into an already overloaded link. */
    @Volatile private var droppedToBudget = 0L

    /** Set by the receiver through a CONFIG frame; applied on the next restart. */
    @Volatile private var requestedBitrate: Int? = null

    private val running = AtomicBoolean(false)
    private val backoff = Backoff()
    private var seq = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Logx.verbose = prefs.verboseLog
        NotificationHelper.ensureChannels(this)
        discovery = BtDiscovery(this)
        audioManager = ContextCompat.getSystemService(this, AudioManager::class.java)
        // A field report of "it drops after a few minutes" is impossible to tell
        // from "the system restarted the service" without saying so in the log.
        starts++
        Logx.i("tx", "service created (start #$starts)")
        // A previous run may have been killed while the speaker was silenced.
        if (prefs.savedMediaVolume > 0) {
            Logx.w("tx", "restoring a media volume left silenced by an earlier run")
            restoreLocalSpeaker()
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
                backoff.reset()
                dropLink("manual reconnect")
                return START_STICKY
            }
            ServiceContract.ACTION_APPLY_SETTINGS -> {
                // Encoder parameters only change between streams, so note the request
                // and restart the stream on the existing link.
                requestedBitrate = prefs.snapshotStreamConfig().bitrate
                dropLink("settings changed")
                return START_STICKY
            }
        }

        // Claim the minimum type first: a service started with
        // startForegroundService has to go foreground promptly, but the full set
        // of types is only known once the input source has been resolved.
        promote("Starting…", ForegroundTypes.connectedDevice())

        inputSource = intent?.getStringExtra(ServiceContract.EXTRA_INPUT_SOURCE)
            ?.let { name -> runCatching { InputSource.valueOf(name) }.getOrNull() }
            ?: prefs.inputSource

        peerAddress = intent?.getStringExtra(ServiceContract.EXTRA_PEER_ADDRESS)
            ?: prefs.trustedPeerAddress

        if (peerAddress == null) {
            AppState.notice("Select phone A first: it is the phone with the headset.")
            AppState.setLink(LinkState.IDLE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (inputSource.needsMic && !hasRecordAudio()) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            AppState.notice("Microphone permission is required to send this phone's microphone.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Now claim exactly what this session uses. Claiming mediaProjection
        // without a projection is refused on Android 14, so a microphone-only or
        // test-tone session must not ask for it.
        var types = ForegroundTypes.connectedDevice()
        if (inputSource.needsProjection) types = types or ForegroundTypes.mediaProjection()
        if (inputSource.needsMic) types = types or ForegroundTypes.microphone()
        if (!promote("Starting…", types)) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            AppState.notice(
                "Android refused this foreground service. Capture consent may have expired; " +
                    "start sending again."
            )
            stopSelf()
            return START_NOT_STICKY
        }

        if (inputSource.needsProjection) {
            val code = intent?.getIntExtra(ServiceContract.EXTRA_RESULT_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra(ServiceContract.EXTRA_RESULT_DATA)
            if (code == 0 || data == null) {
                if (projection == null) {
                    AppState.setCapture(CaptureState.NEEDS_CONSENT)
                    AppState.notice("Screen capture consent is required to capture this phone's audio.")
                    stopSelf()
                    return START_NOT_STICKY
                }
            } else if (projection == null && !acquireProjection(code, data)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startEverything()
        return START_STICKY
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- lifecycle

    private fun acquireProjection(resultCode: Int, data: Intent): Boolean {
        if (!hasRecordAudio()) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            AppState.notice("Microphone permission is required: Android gates playback capture behind it.")
            return false
        }
        val mpm = ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
        if (mpm == null) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            return false
        }
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Logx.e("tx", "getMediaProjection failed", t)
            null
        }
        if (mp == null) {
            AppState.setCapture(CaptureState.NEEDS_CONSENT)
            AppState.notice("Screen capture consent was not granted.")
            return false
        }
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Logx.w(
                    "tx",
                    "MediaProjection stopped by the system or the user after " +
                        "${(System.currentTimeMillis() - projectionStartedAt) / 1000}s"
                )
                AppState.setCapture(CaptureState.NEEDS_CONSENT)
                AppState.notice("Capture stopped. Android requires fresh consent before it can resume.")
                // Explicitly do NOT try to re-acquire: that needs a user gesture.
                stopEverything()
                stopSelf()
            }
        }
        mp.registerCallback(cb, Handler(Looper.getMainLooper()))
        projection = mp
        projectionStartedAt = System.currentTimeMillis()
        projectionCallback = cb
        return true
    }

    private fun startEverything() {
        if (running.getAndSet(true)) return
        AppState.resetStats()
        if (prefs.muteLocalSpeaker) muteLocalSpeaker()
        acquireWakeLock()
        connectThread = Thread(::connectLoop, "dualstream-connect").apply { start() }
    }

    private fun stopEverything() {
        if (!running.getAndSet(false)) return
        Logx.i("tx", "stopping")
        dropLink("service stop")
        connectThread?.join(1_500)
        connectThread = null
        readerThread?.interrupt()
        readerThread = null
        stopCaptureAndEncoder()
        policyMonitor = null
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
        restoreLocalSpeaker()
        discovery?.release()
        releaseWakeLock()
        AppState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connect

    private fun connectLoop() {
        val disc = discovery ?: return
        val address = peerAddress ?: return

        while (running.get()) {
            if (!disc.isBluetoothOn) {
                AppState.setLink(LinkState.BLUETOOTH_OFF)
                updateNotification("Bluetooth is off")
                sleepQuietly(1_500)
                continue
            }
            if (!disc.hasPermissions()) {
                AppState.setLink(LinkState.NO_PERMISSION)
                AppState.notice("Grant the Nearby devices permission to connect to phone A.")
                return
            }
            val device = disc.deviceFor(address)
            if (device == null) {
                AppState.setLink(LinkState.FAILED)
                AppState.notice("Phone A ($address) is not a known device any more.")
                return
            }
            if (!disc.isBonded(address)) {
                AppState.setLink(LinkState.FAILED)
                AppState.notice("Pair with phone A in the Android Bluetooth settings first.")
                return
            }

            AppState.setLink(LinkState.CONNECTING)
            updateNotification("Connecting to phone A…")

            val link = try {
                RfcommConnector(device).connect()
            } catch (se: SecurityException) {
                Logx.e("tx", "missing BLUETOOTH_CONNECT", se)
                AppState.setLink(LinkState.NO_PERMISSION)
                return
            } catch (e: IOException) {
                if (!running.get()) return
                val delay = backoff.nextDelayMs()
                Logx.w("tx", "connect failed (attempt ${backoff.attempts}), retry in ${delay}ms: ${e.message}")
                AppState.setLink(LinkState.RECOVERING)
                if (!prefs.autoReconnect && backoff.attempts > 1) {
                    AppState.setLink(LinkState.FAILED)
                    return
                }
                sleepQuietly(delay)
                continue
            }

            backoff.reset()
            currentLink = link
            prefs.trustedPeerAddress = link.remoteAddress
            prefs.trustedPeerName = link.remoteName
            AppState.setPeerName(link.remoteName)

            try {
                runStream(link)
            } catch (t: Throwable) {
                Logx.w("tx", "stream ended: ${t.javaClass.simpleName}: ${t.message}")
                bitrate?.let { ladder ->
                    if (ladder.update(0, 0, -1, linkFailed = true)) {
                        Logx.i("tx", "after the drop, bitrate -> ${ladder.current}")
                        requestedBitrate = ladder.current
                    }
                }
            } finally {
                stopCaptureAndEncoder()
                currentLink?.close()
                currentLink = null
                AppState.setTransmitting(false)
                AppState.setNegotiated(null)
                if (!prefs.autoReconnect) {
                    AppState.setLink(LinkState.IDLE)
                    running.set(false)
                    updateNotification("Disconnected")
                    return
                }
                AppState.setLink(LinkState.RECOVERING)
                updateNotification("Reconnecting…")
                sleepQuietly(500)
            }
        }
    }

    /** Handshake, then capture-encode-send until something breaks. */
    private fun runStream(link: BtLink) {
        AppState.setLink(LinkState.HANDSHAKING)

        val base = prefs.snapshotStreamConfig()
        val ladder = bitrate ?: BitrateController(ceilingBps = base.bitrate).also { bitrate = it }
        if (prefs.powerSave) ladder.forceLowest()
        val config = base.copy(bitrate = requestedBitrate ?: ladder.current)
        rateMeter.reset()
        droppedToBudget = 0

        val enc = CodecFactory.createEncoder(config)
        encoder = enc
        val effective = config.copy(codec = enc.id)
        if (enc.id != config.codec) {
            AppState.notice("${config.codec.label} is not available on this phone; using ${enc.id.label}.")
        }

        link.writer.write(
            Protocol.Type.HELLO, 0,
            Hello(
                role = Role.TRANSMITTER,
                codec = enc.id,
                sampleRate = enc.sampleRate,
                channels = 1,
                frameMs = effective.frameMs,
                bitrate = effective.bitrate,
                deviceName = android.os.Build.MODEL ?: "phone B"
            ).encode()
        )

        val ack = link.reader.read()
        if (ack.type != Protocol.Type.HELLO_ACK) {
            throw IOException("expected HELLO_ACK, got ${ack.type}")
        }
        AppState.setNegotiated(effective)
        AppState.setLink(LinkState.CONNECTED)
        Logx.i("tx", "handshake complete: ${enc.id.label} ${enc.sampleRate}Hz ${effective.bitrate}bps frame=${effective.frameMs}ms")

        // Start the capture before CODEC_INIT so Opus has produced its csd by the
        // time we announce it.
        val src = startCapture(effective.frameMs) ?: throw IOException("capture unavailable")

        readerThread = Thread({ readerLoop(link) }, "dualstream-tx-reader").apply {
            isDaemon = true
            start()
        }

        pumpAudio(link, enc, src)
    }

    private fun startCapture(frameMs: Int): CaptureSource? {
        val frameSamples = Pcm.OUT_RATE * frameMs / 1000
        val src: CaptureSource? = when (inputSource) {
            InputSource.TEST_TONE -> startTone(frameMs, frameSamples)
            InputSource.BUILTIN_MIC -> startMic(frameSamples)
            InputSource.PLAYBACK_CAPTURE -> startPlaybackCapture(frameSamples)
            InputSource.PLAYBACK_AND_MIC -> startPlaybackAndMic(frameSamples)
        }
        capture = src
        return src
    }

    private fun startTone(frameMs: Int, frameSamples: Int): CaptureSource {
        // Stage 1 of the plan: prove the RFCOMM path with a synthetic signal
        // through the direct PCM input, no MediaProjection and no microphone.
        val input = DirectPcmInput(Pcm.OUT_RATE)
        input.start()
        val tone = ToneSource(frequency = 660.0)
        Thread({
            val buf = ShortArray(frameSamples)
            while (running.get()) {
                tone.read(buf)
                input.offer(buf.copyOf())
                sleepQuietly(frameMs.toLong())
            }
        }, "dualstream-tone").apply { isDaemon = true }.start()
        AppState.setCapture(CaptureState.DIRECT_PCM)
        return input
    }

    private fun newMic(frameSamples: Int): MicCaptureSource = MicCaptureSource(
        audioManager = audioManager,
        sampleRate = Pcm.OUT_RATE,
        frameSamples = frameSamples,
        enableProcessing = prefs.micProcessing,
        gain = prefs.micGain
    )

    private fun startMic(frameSamples: Int): CaptureSource? {
        val mic = newMic(frameSamples)
        if (!mic.start()) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            AppState.notice("The microphone could not be opened. Another app may be holding it.")
            return null
        }
        watchRecordingPolicy(mic.sessionId)
        // reportMicRoute may raise WRONG_INPUT_ROUTE; announcing MIC on top of
        // it would hide the failure behind a healthy-looking status.
        reportMicRoute(mic)
        if (mic.routeProblem == null) AppState.setCapture(CaptureState.MIC)
        return mic
    }

    private fun startPlaybackCapture(frameSamples: Int): CaptureSource? {
        val mp = projection
        if (mp == null) {
            AppState.setCapture(CaptureState.NEEDS_CONSENT)
            return null
        }
        val src = PlaybackCaptureSource(mp, Pcm.OUT_RATE, frameSamples)
        if (!src.start()) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            AppState.notice("Playback capture could not be started on this device.")
            return null
        }
        AppState.setCapture(CaptureState.CAPTURING_SILENCE)   // until proven otherwise
        return src
    }

    private fun startPlaybackAndMic(frameSamples: Int): CaptureSource? {
        val mp = projection
        if (mp == null) {
            AppState.setCapture(CaptureState.NEEDS_CONSENT)
            return null
        }
        val playback = PlaybackCaptureSource(mp, Pcm.OUT_RATE, frameSamples)
        val mic = newMic(frameSamples)
        val mix = MixCaptureSource(
            primary = playback,
            secondary = mic,
            frameSamples = frameSamples,
            secondaryGain = 1f
        )
        if (!mix.start()) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            AppState.notice("Neither playback capture nor the microphone could be started.")
            return null
        }
        if (mix.secondaryFailed) {
            AppState.notice(
                "The microphone could not be added, so only app audio is being sent. " +
                    "Another app may be holding the microphone."
            )
        } else {
            watchRecordingPolicy(mic.sessionId)
            reportMicRoute(mic)
        }
        mixSource = mix
        AppState.setCapture(CaptureState.CAPTURING_SILENCE)
        return mix
    }

    private fun reportMicRoute(mic: MicCaptureSource) {
        val problem = mic.routeProblem
        if (problem != null) {
            AppState.setCapture(CaptureState.WRONG_INPUT_ROUTE)
            AppState.notice(problem)
        } else {
            val type = mic.routedType
            Logx.i(
                "tx",
                "microphone on " + (type?.let { InputRoutePolicy.describe(it) } ?: "an unknown input")
            )
        }
    }

    /**
     * Android can mute a recorder without failing a single read, so the policy
     * callback is the only way to tell a silenced microphone from a quiet room.
     */
    private fun watchRecordingPolicy(sessionId: Int) {
        recorderSilencedByPolicy = false
        val monitor = policyMonitor ?: RecordingPolicyMonitor(audioManager) { report ->
            recorderSilencedByPolicy = report.silenced
            if (report.silenced) {
                AppState.setCapture(CaptureState.SILENCED_BY_POLICY)
                AppState.notice(
                    "Android has muted this phone's microphone for us: another app took the " +
                        "input, or this app lost its microphone claim. Nothing audible is being sent."
                )
            } else {
                val type = report.routedType
                if (type != null) {
                    InputRoutePolicy.rejectionReason(type)?.let { reason ->
                        AppState.setCapture(CaptureState.WRONG_INPUT_ROUTE)
                        AppState.notice(reason)
                    }
                }
            }
        }.also { policyMonitor = it }
        monitor.start(sessionId)
    }

    /**
     * The capture thread. Reads mono frames, resamples to the codec rate,
     * encodes and writes AUDIO frames.
     */
    private fun pumpAudio(link: BtLink, enc: AudioEncoder, src: CaptureSource) {
        val captureFrameSamples = src.sampleRate * (AppState.negotiated.value?.frameMs ?: prefs.frameMs) / 1000
        val inBuf = ShortArray(captureFrameSamples)
        val resampler = Resampler(src.sampleRate, enc.sampleRate)
        var codecBuf = ShortArray(maxOf(enc.frameSamples * 2, resampler.maxOut(captureFrameSamples) + enc.frameSamples))
        var codecFilled = 0

        var sentInit = false
        var silentRun = 0
        var audibleRun = 0
        var lastStateSent = CaptureState.IDLE
        var framesSinceMeter = 0
        var micSilentRun = 0
        var idleReads = 0

        AppState.setTransmitting(true)
        updateNotification("Sending audio to ${link.remoteName}")

        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (t: Throwable) {
            Logx.w("tx", "could not raise thread priority", t)
        }

        while (running.get() && !link.closed) {
            val n = src.read(inBuf)
            if (n < 0) {
                throw IOException("capture source failed")
            }
            if (n == 0) {
                // A blocking source paces this loop. One that returns nothing
                // without blocking would spin a core instead.
                idleReads++
                if (idleReads > IDLE_READS_BEFORE_BACKOFF) sleepQuietly(5)
                continue
            }
            idleReads = 0

            // Silence bookkeeping. Android hands back digital silence both when
            // the playing app forbids capture and when the audio policy mutes our
            // recorder, and the specification forbids showing either as a working
            // stream.
            val silent = Pcm.isSilent(inBuf, 0, n)
            if (silent) {
                silentRun++
                audibleRun = 0
            } else {
                audibleRun++
                silentRun = 0
            }
            val state = classify(src, silentRun, audibleRun, lastStateSent)
            if (state != lastStateSent && state != CaptureState.IDLE) {
                lastStateSent = state
                AppState.setCapture(state)
                runCatching {
                    link.writer.write(Protocol.Type.STATE, 0, StateUpdate(state).encode())
                }
            }

            // Resample into the codec accumulator.
            if (resampler.bypass) {
                if (codecBuf.size < codecFilled + n) codecBuf = codecBuf.copyOf(codecFilled + n)
                inBuf.copyInto(codecBuf, codecFilled, 0, n)
                codecFilled += n
            } else {
                val need = codecFilled + resampler.maxOut(n)
                if (codecBuf.size < need) codecBuf = codecBuf.copyOf(need)
                val scratch = ShortArray(resampler.maxOut(n))
                val m = resampler.process(inBuf, 0, n, scratch)
                scratch.copyInto(codecBuf, codecFilled, 0, m)
                codecFilled += m
            }

            // Encode whole codec frames.
            var consumed = 0
            while (codecFilled - consumed >= enc.frameSamples) {
                val packets = enc.encode(codecBuf, consumed, enc.frameSamples)
                consumed += enc.frameSamples

                if (!sentInit) {
                    link.writer.write(
                        Protocol.Type.CODEC_INIT, 0, CodecInit(enc.initData).encode()
                    )
                    sentInit = true
                    Logx.i("tx", "CODEC_INIT sent (${enc.initData.size} buffers)")
                }
                for (p in packets) {
                    val wire = p.size + AudioPayload.HEADER + Protocol.HEADER_SIZE
                    // Each packet covers the frame that produced it; when the
                    // encoder emits several per frame they share that audio.
                    val samplesPerPacket = enc.frameSamples / packets.size.coerceAtLeast(1)
                    if (policeRate(wire, samplesPerPacket, enc.sampleRate)) {
                        sendAudio(link, p, silent)
                    }
                }
            }
            if (consumed > 0) {
                codecBuf.copyInto(codecBuf, 0, consumed, codecFilled)
                codecFilled -= consumed
            }

            if (++framesSinceMeter >= METER_EVERY) {
                framesSinceMeter = 0
                val level = Pcm.rms(inBuf, 0, n)
                AppState.updateStats { it.copy(localLevel = level) }
            }

            // In the combined source the sum can be audible while the microphone
            // half is dead, so each side is checked on its own.
            mixSource?.let { mix ->
                if (mix.secondarySilent) micSilentRun++ else micSilentRun = 0
                if (micSilentRun == MIC_SILENT_WARN_FRAMES) {
                    AppState.notice(
                        if (mix.secondaryFailed)
                            "The microphone half of the stream has stopped; only app audio is going out."
                        else
                            "App audio is going out, but the microphone has been silent for a while. " +
                                "Check that nothing else holds it."
                    )
                }
            }
        }
    }

    /**
     * Maps the capture situation onto a reportable state. The order matters: a
     * recorder muted by the audio policy keeps delivering frames on schedule,
     * so that case has to win over any "we received audio" reasoning.
     */
    private fun classify(
        src: CaptureSource,
        silentRun: Int,
        audibleRun: Int,
        last: CaptureState
    ): CaptureState = when {
        recorderSilencedByPolicy -> CaptureState.SILENCED_BY_POLICY
        // A bad route stays reported until it is actually fixed; a frame of
        // non-silent PCM is not evidence that it was.
        last == CaptureState.WRONG_INPUT_ROUTE -> CaptureState.WRONG_INPUT_ROUTE
        src is DirectPcmInput -> CaptureState.DIRECT_PCM
        audibleRun >= 2 -> when (inputSource) {
            InputSource.BUILTIN_MIC -> CaptureState.MIC
            InputSource.PLAYBACK_AND_MIC -> CaptureState.MIC_AND_PLAYBACK
            else -> CaptureState.CAPTURING
        }
        silentRun > SILENCE_FRAMES -> CaptureState.CAPTURING_SILENCE
        else -> last
    }

    /**
     * Measures the stream and keeps it inside its budget.
     *
     * The first field run sent ~200 kbit/s from a 32 kbit/s request and the
     * link died; nothing in the app noticed, because nothing was measuring. So
     * every packet is now accounted for, the ladder reacts to what is actually
     * produced, and when the encoder still will not comply the frames are
     * dropped here rather than pushed into a socket that cannot carry them.
     * Dropping audio is bad; burying the link until it resets is worse, and it
     * takes the latency with it.
     *
     * @return true when this packet should be sent
     */
    private fun policeRate(packetBytes: Int, audioSamples: Int, sampleRate: Int): Boolean {
        val ladder = bitrate ?: return true
        val now = System.currentTimeMillis()
        if (rateMeter.record(now, packetBytes, audioSamples, sampleRate)) {
            val before = ladder.current
            val changed = ladder.update(
                measuredBps = rateMeter.bitsPerSecond,
                underrunsDelta = 0,
                rttMs = AppState.stats.value.rttMs,
                linkFailed = false
            )
            AppState.updateStats {
                it.copy(
                    sendBitsPerSecond = rateMeter.bitsPerSecond,
                    packetsPerSecond = rateMeter.packetsPerSecond,
                    realtimeRatio = rateMeter.realtimeRatio,
                    targetBitrate = ladder.current,
                    bitrateReason = ladder.lastReason,
                    droppedToBudget = droppedToBudget,
                    activePlayers = countActivePlayers()
                )
            }
            if (changed) {
                Logx.i(
                    "tx",
                    "bitrate ${before} -> ${ladder.current} (${ladder.lastReason}); " +
                        "measured ${rateMeter.bitsPerSecond / 1000}kbit/s, " +
                        "${rateMeter.packetsPerSecond}pkt/s, " +
                        "realtime x${"%.2f".format(rateMeter.realtimeRatio)}"
                )
                // Ask the encoder for the new rate; it restarts the stream if it
                // cannot change live.
                requestedBitrate = ladder.current
                if (encoder?.setBitrate(ladder.current) != true) {
                    dropLink("bitrate change to ${ladder.current}")
                }
            }
        }

        // Hard ceiling, independent of the ladder: never exceed the budget by
        // more than this, whatever the encoder is doing.
        val ceiling = (ladder.current * HARD_BUDGET_MARGIN).toInt()
        if (rateMeter.windowsCompleted > 0 && rateMeter.bitsPerSecond > ceiling) {
            droppedToBudget++
            // Drop one in two while over budget, so the stream degrades rather
            // than stops.
            return droppedToBudget % 2 == 0L
        }
        return true
    }

    /** How many players Android currently reports on this phone. */
    private fun countActivePlayers(): Int = try {
        audioManager?.activePlaybackConfigurations?.size ?: 0
    } catch (t: Throwable) {
        0
    }

    private fun sendAudio(link: BtLink, packet: ByteArray, silent: Boolean) {
        val payload = ByteArray(AudioPayload.HEADER + packet.size)
        AudioPayload.writeHeader(
            payload,
            if (silent) AudioPayload.FLAG_SILENCE else 0,
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        )
        packet.copyInto(payload, AudioPayload.HEADER)
        seq = (seq + 1) and 0xFFFF
        link.writer.write(Protocol.Type.AUDIO, seq, payload)
        AppState.updateStats {
            it.copy(
                framesSent = it.framesSent + 1,
                bytesSent = it.bytesSent + payload.size + Protocol.HEADER_SIZE
            )
        }
    }

    /** Handles control frames coming back from phone A. */
    private fun readerLoop(link: BtLink) {
        try {
            while (running.get() && !link.closed) {
                val frame = link.reader.read()
                when (frame.type) {
                    Protocol.Type.PING ->
                        link.writer.write(Protocol.Type.PONG, frame.seq, frame.payload)

                    Protocol.Type.CONFIG -> {
                        ConfigUpdate.decode(frame.payload)?.let { cfg ->
                            Logx.i("tx", "receiver asked for ${cfg.bitrate}bps / ${cfg.frameMs}ms")
                            requestedBitrate = cfg.bitrate
                            if (encoder?.setBitrate(cfg.bitrate) != true) {
                                // Not all encoders accept a live change; restart the stream.
                                dropLink("bitrate change")
                            }
                        }
                    }

                    Protocol.Type.FLUSH -> {
                        Logx.i("tx", "receiver asked for a clean restart")
                        dropLink("flush requested")
                    }

                    Protocol.Type.BYE -> {
                        Logx.i("tx", "receiver said goodbye")
                        link.close()
                    }

                    else -> Logx.d("tx", "ignoring frame type ${frame.type}")
                }
            }
        } catch (e: Throwable) {
            if (running.get()) Logx.d("tx", "reader loop ended: ${e.message}")
            link.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun stopCaptureAndEncoder() {
        policyMonitor?.stop()
        recorderSilencedByPolicy = false
        mixSource = null
        capture?.let { runCatching { it.stop() } }
        capture = null
        encoder?.let { runCatching { it.release() } }
        encoder = null
        seq = 0
    }

    private fun dropLink(why: String) {
        val link = currentLink ?: return
        Logx.i("tx", "dropping link: $why")
        runCatching { link.writer.write(Protocol.Type.BYE, 0, ByteArray(0)) }
        link.close()
    }

    /**
     * Playback capture taps the mix; it does not take the audio away from this
     * phone, so whatever is being captured still comes out of its speaker.
     * Android offers an app no way to redirect or mute another app's output,
     * so the only thing in reach is this phone's own media volume.
     *
     * Whether the remote side keeps hearing the audio afterwards depends on
     * where the capture tap sits relative to this attenuation, and that varies
     * between devices. It has to be checked on the actual hardware rather than
     * assumed, which is why this is a setting and not silent behaviour.
     */
    private fun muteLocalSpeaker() {
        val am = audioManager ?: return
        runCatching {
            val stream = AudioManager.STREAM_MUSIC
            val now = am.getStreamVolume(stream)
            if (now > 0) {
                volumeBeforeMute = now
                prefs.savedMediaVolume = now
                am.setStreamVolume(stream, 0, 0)
                Logx.i("tx", "media volume silenced (was $now)")
            }
        }.onFailure {
            Logx.w("tx", "could not silence the media volume", it)
            AppState.notice(
                "This phone's media volume could not be silenced automatically. Turn it down " +
                    "by hand if the sound should not come out of this phone as well."
            )
        }
    }

    private fun restoreLocalSpeaker() {
        val am = audioManager ?: return
        // Prefer the persisted value: if the process was killed while muted,
        // the in-memory one is gone and the phone would stay silent for good.
        val previous = volumeBeforeMute ?: prefs.savedMediaVolume.takeIf { it > 0 } ?: return
        volumeBeforeMute = null
        prefs.savedMediaVolume = -1
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0)
            Logx.i("tx", "media volume restored to $previous")
        }.onFailure { Logx.w("tx", "could not restore the media volume", it) }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dualstream:transmitter").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun promote(text: String, types: Int): Boolean = ForegroundTypes.promote(
        this,
        NotificationHelper.ID_TRANSMITTER,
        NotificationHelper.build(
            this, NotificationHelper.CHANNEL_TRANSMITTER,
            "DualStream transmitter", text, TransmitterService::class.java
        ),
        types
    )

    private fun updateNotification(text: String) {
        NotificationHelper.update(
            this, NotificationHelper.ID_TRANSMITTER,
            NotificationHelper.build(
                this, NotificationHelper.CHANNEL_TRANSMITTER,
                "DualStream transmitter", text, TransmitterService::class.java
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
        /** How many times this process has created the service. */
        @Volatile private var starts = 0

        /** ~1 s of silence at 20 ms frames before we call the stream silent. */
        private const val SILENCE_FRAMES = 50
        private const val METER_EVERY = 5

        /** ~4 s at 20 ms frames before we point out a dead microphone half. */
        private const val MIC_SILENT_WARN_FRAMES = 200

        /**
         * How far over the chosen bitrate the stream may run before frames are
         * dropped outright. Some headroom is normal for a variable-rate codec;
         * three times over is the failure the field run hit.
         */
        private const val HARD_BUDGET_MARGIN = 1.8f

        /** Tolerate a short gap before assuming the source is not blocking. */
        private const val IDLE_READS_BEFORE_BACKOFF = 3
        private const val WAKELOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        /** Starts a source that needs MediaProjection consent. */
        fun startWithProjection(
            context: Context,
            peerAddress: String,
            source: InputSource,
            resultCode: Int,
            resultData: Intent?
        ) {
            val i = Intent(context, TransmitterService::class.java)
                .setAction(ServiceContract.ACTION_START)
                .putExtra(ServiceContract.EXTRA_PEER_ADDRESS, peerAddress)
                .putExtra(ServiceContract.EXTRA_INPUT_SOURCE, source.name)
                .putExtra(ServiceContract.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ServiceContract.EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, i)
        }

        /**
         * Starts a source that needs no projection: the built-in microphone, or
         * the Stage 1 test tone.
         */
        fun startWithoutProjection(context: Context, peerAddress: String, source: InputSource) {
            require(!source.needsProjection) { "$source needs MediaProjection consent" }
            val i = Intent(context, TransmitterService::class.java)
                .setAction(ServiceContract.ACTION_START)
                .putExtra(ServiceContract.EXTRA_PEER_ADDRESS, peerAddress)
                .putExtra(ServiceContract.EXTRA_INPUT_SOURCE, source.name)
            ContextCompat.startForegroundService(context, i)
        }

        fun send(context: Context, action: String) {
            val i = Intent(context, TransmitterService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
