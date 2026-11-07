package com.dualstream.uniter.core

/**
 * Negotiated stream parameters: what the two phones agreed to use for the
 * current stream. Travels on the wire inside the handshake.
 */
data class StreamConfig(
    val codec: CodecId,
    val bitrate: Int,
    val frameMs: Int
)
