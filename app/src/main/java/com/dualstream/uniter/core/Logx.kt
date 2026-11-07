package com.dualstream.uniter.core

import android.util.Log

/**
 * Tiny logging facade plus an in-memory ring buffer so the UI can show the last
 * events (useful during Stage 4 field testing, where no USB cable is attached).
 */
object Logx {
    const val TAG = "DualStream"
    private const val RING = 400

    private val ring = ArrayDeque<String>(RING)
    private val lock = Any()

    @Volatile var verbose: Boolean = false

    fun d(where: String, msg: String) {
        if (verbose) Log.d(TAG, "[$where] $msg")
        push("D", where, msg)
    }

    fun i(where: String, msg: String) {
        Log.i(TAG, "[$where] $msg")
        push("I", where, msg)
    }

    fun w(where: String, msg: String, t: Throwable? = null) {
        Log.w(TAG, "[$where] $msg", t)
        push("W", where, msg + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun e(where: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$where] $msg", t)
        push("E", where, msg + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun push(level: String, where: String, msg: String) {
        synchronized(lock) {
            if (ring.size >= RING) ring.removeFirst()
            ring.addLast("${System.currentTimeMillis() % 1_000_000} $level/$where $msg")
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }
}
