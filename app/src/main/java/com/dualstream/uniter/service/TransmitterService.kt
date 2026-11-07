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
import com.dualstream.uniter.audio.CaptureSource
import com.dualstream.uniter.audio.DirectPcmInput
import com.dualstream.uniter.audio.Pcm
import com.dualstream.uniter.audio.PlaybackCaptureSource
import com.dualstream.uniter.audio.Resampler
import com.dualstream.uniter.audio.ToneSource
import com.dualstream.uniter.codec.AudioEncoder
import com.dualstream.uniter.codec.CodecFactory
import com.dualstream.uniter.core.AppState
import com.dualstream.uniter.core.CaptureState
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
    @Volatile private var useTestTone = false

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

        // Foreground promotion must happen before MediaProjection is obtained,
        // otherwise Android 14+ rejects getMediaProjection().
        promoteToForeground("Starting…")

        useTestTone = intent?.getBooleanExtra(ServiceContract.EXTRA_USE_TEST_TONE, false) == true
        peerAddress = intent?.getStringExtra(ServiceContract.EXTRA_PEER_ADDRESS)
            ?: prefs.trustedPeerAddress

        if (peerAddress == null) {
            AppState.notice("Select phone A first: it is the phone with the headset.")
            AppState.setLink(LinkState.IDLE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!useTestTone) {
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

    // ---------------------------------------------------------------- lifecycle

    private fun acquireProjection(resultCode: Int, data: Intent): Boolean {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
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
                Logx.i("tx", "MediaProjection stopped by the system or the user")
                AppState.setCapture(CaptureState.NEEDS_CONSENT)
                AppState.notice("Capture stopped. Android requires fresh consent before it can resume.")
                // Explicitly do NOT try to re-acquire: that needs a user gesture.
                stopEverything()
                stopSelf()
            }
        }
        mp.registerCallback(cb, Handler(Looper.getMainLooper()))
        projection = mp
        projectionCallback = cb
        return true
    }

    private fun startEverything() {
        if (running.getAndSet(true)) return
        AppState.resetStats()
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
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
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
        val config = requestedBitrate?.let { base.copy(bitrate = it) } ?: base

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
        if (useTestTone) {
            // Stage 1 of the plan: prove the RFCOMM path with a synthetic signal
            // through the direct PCM input, no MediaProjection involved.
            val input = DirectPcmInput(Pcm.OUT_RATE)
            input.start()
            val tone = ToneSource(frequency = 660.0)
            val frameSamples = Pcm.OUT_RATE * frameMs / 1000
            Thread({
                val buf = ShortArray(frameSamples)
                while (running.get()) {
                    tone.read(buf)
                    input.offer(buf.copyOf())
                    sleepQuietly(frameMs.toLong())
                }
            }, "dualstream-tone").apply { isDaemon = true }.start()
            capture = input
            AppState.setCapture(CaptureState.DIRECT_PCM)
            return input
        }

        val mp = projection
        if (mp == null) {
            AppState.setCapture(CaptureState.NEEDS_CONSENT)
            return null
        }
        val frameSamples = 48_000 * frameMs / 1000
        val src = PlaybackCaptureSource(mp, 48_000, frameSamples)
        if (!src.start()) {
            AppState.setCapture(CaptureState.UNAVAILABLE)
            AppState.notice("Playback capture could not be started on this device.")
            return null
        }
        capture = src
        AppState.setCapture(CaptureState.CAPTURING_SILENCE)   // until proven otherwise
        return src
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
            if (n == 0) continue

            // Silence bookkeeping. Android hands back digital silence when the
            // playing app forbids capture, and the specification forbids showing
            // that as a working stream.
            val silent = com.dualstream.uniter.audio.Pcm.isSilent(inBuf, 0, n)
            if (silent) {
                silentRun++
                audibleRun = 0
            } else {
                audibleRun++
                silentRun = 0
            }
            val state = when {
                src is DirectPcmInput -> CaptureState.DIRECT_PCM
                audibleRun >= 2 -> CaptureState.CAPTURING
                silentRun > SILENCE_FRAMES -> CaptureState.CAPTURING_SILENCE
                else -> lastStateSent
            }
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
                    sendAudio(link, p, silent)
                }
            }
            if (consumed > 0) {
                codecBuf.copyInto(codecBuf, 0, consumed, codecFilled)
                codecFilled -= consumed
            }

            if (++framesSinceMeter >= METER_EVERY) {
                framesSinceMeter = 0
                val level = com.dualstream.uniter.audio.Pcm.rms(inBuf, 0, n)
                AppState.updateStats { it.copy(localLevel = level) }
            }
        }
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

    private fun promoteToForeground(text: String) {
        startForeground(
            NotificationHelper.ID_TRANSMITTER,
            NotificationHelper.build(
                this, NotificationHelper.CHANNEL_TRANSMITTER,
                "DualStream transmitter", text, TransmitterService::class.java
            )
        )
    }

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
        /** ~1 s of silence at 20 ms frames before we call the stream silent. */
        private const val SILENCE_FRAMES = 50
        private const val METER_EVERY = 5
        private const val WAKELOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        fun start(
            context: Context,
            peerAddress: String,
            resultCode: Int,
            resultData: Intent?
        ) {
            val i = Intent(context, TransmitterService::class.java)
                .setAction(ServiceContract.ACTION_START)
                .putExtra(ServiceContract.EXTRA_PEER_ADDRESS, peerAddress)
                .putExtra(ServiceContract.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ServiceContract.EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, i)
        }

        /** Stage 1 bring-up: send a test tone, no MediaProjection consent needed. */
        fun startTestTone(context: Context, peerAddress: String) {
            val i = Intent(context, TransmitterService::class.java)
                .setAction(ServiceContract.ACTION_START)
                .putExtra(ServiceContract.EXTRA_PEER_ADDRESS, peerAddress)
                .putExtra(ServiceContract.EXTRA_USE_TEST_TONE, true)
            ContextCompat.startForegroundService(context, i)
        }

        fun send(context: Context, action: String) {
            val i = Intent(context, TransmitterService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
