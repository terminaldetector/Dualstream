package com.dualstream.uniter.transport

import kotlin.random.Random

/**
 * Exponential backoff with jitter for automatic reconnection (section 3,
 * item 4, and section 9).
 *
 * The cap is intentionally short: a dropped RFCOMM link during playback should
 * come back within seconds, not minutes, and Bluetooth reconnects are cheap
 * compared with a network dial-up.
 */
class Backoff(
    private val firstDelayMs: Long = 500,
    private val maxDelayMs: Long = 8_000,
    private val jitterRatio: Double = 0.25
) {
    var attempts: Int = 0
        private set

    fun nextDelayMs(): Long {
        val base = (firstDelayMs shl minOf(attempts, 5)).coerceAtMost(maxDelayMs)
        attempts++
        val jitter = (base * jitterRatio * (Random.nextDouble() * 2 - 1)).toLong()
        return (base + jitter).coerceAtLeast(100L)
    }

    fun reset() {
        attempts = 0
    }
}
