package com.dualstream.uniter.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single process-wide observable state. Services write, the UI reads.
 * Everything here is cheap and lock-free so the audio threads can publish
 * meters without blocking.
 */
object AppState {

    private val _link = MutableStateFlow(LinkState.IDLE)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _peerName = MutableStateFlow<String?>(null)
    val peerName: StateFlow<String?> = _peerName.asStateFlow()

    private val _capture = MutableStateFlow(CaptureState.IDLE)
    val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    private val _headset = MutableStateFlow(HeadsetState.UNKNOWN)
    val headset: StateFlow<HeadsetState> = _headset.asStateFlow()

    private val _negotiated = MutableStateFlow<StreamConfig?>(null)
    val negotiated: StateFlow<StreamConfig?> = _negotiated.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    /** Last human-readable limitation notice, e.g. coexistence problems. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _transmitting = MutableStateFlow(false)
    val transmitting: StateFlow<Boolean> = _transmitting.asStateFlow()

    private val _receiving = MutableStateFlow(false)
    val receiving: StateFlow<Boolean> = _receiving.asStateFlow()

    fun setLink(s: LinkState) { _link.value = s }
    fun setPeerName(n: String?) { _peerName.value = n }
    fun setCapture(s: CaptureState) { _capture.value = s }
    fun setHeadset(s: HeadsetState) { _headset.value = s }
    fun setNegotiated(c: StreamConfig?) { _negotiated.value = c }
    fun setTransmitting(v: Boolean) { _transmitting.value = v }
    fun setReceiving(v: Boolean) { _receiving.value = v }

    fun notice(msg: String?) {
        _notice.value = msg
        if (msg != null) Logx.i("notice", msg)
    }

    fun updateStats(block: (StreamStats) -> StreamStats) = _stats.update(block)

    fun resetStats() { _stats.value = StreamStats() }

    fun reset() {
        _link.value = LinkState.IDLE
        _capture.value = CaptureState.IDLE
        _negotiated.value = null
        _transmitting.value = false
        _receiving.value = false
        resetStats()
    }
}

/**
 * Runtime counters. [localLevel]/[remoteLevel] are 0f..1f RMS meters that drive
 * the stream indicators of section 8.
 */
data class StreamStats(
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    /** Frames the jitter buffer had to invent because nothing arrived in time. */
    val underruns: Long = 0,
    /** Frames discarded to stop latency from accumulating. */
    val dropped: Long = 0,
    /** Frames lost according to the sequence counter. */
    val lost: Long = 0,
    val jitterFrames: Int = 0,
    val bufferMs: Int = 0,
    val rttMs: Int = -1,
    val localLevel: Float = 0f,
    val remoteLevel: Float = 0f,
    val duckGain: Float = 1f,
    val connectedSinceMs: Long = 0
)
