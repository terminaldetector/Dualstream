package com.dualstream.uniter.audio

import com.dualstream.uniter.core.MixMode

/**
 * Which of the app's controls actually affect what the listener hears.
 *
 * Phone A's mixer only sees two things: the stream decoded from phone B, and a
 * source this app owns — a file it decodes, or its own microphone. Audio that
 * any other app on phone A is playing goes straight to the A2DP headset
 * without passing through here, and no public API lets an app intercept,
 * re-route or attenuate another app's output.
 *
 * That limit is in section 6 of the specification, but it was not in the
 * interface: the local volume slider and the channel modes looked live while
 * doing nothing, which reads as "the app is broken" rather than "the platform
 * does not allow this". This decides what to enable and what to say, as plain
 * logic so it can be tested.
 */
object MixerControls {

    /** True when the app owns a source it can actually mix and attenuate. */
    fun hasLocalSource(
        localSourceUri: String?,
        micAsLocalSource: Boolean,
        appAudioAsLocalSource: Boolean = false
    ): Boolean =
        appAudioAsLocalSource || micAsLocalSource || !localSourceUri.isNullOrBlank()

    /** Modes that are only meaningful with an app-owned local source. */
    fun modeNeedsLocalSource(mode: MixMode): Boolean = when (mode) {
        MixMode.HARD_SPLIT, MixMode.PRIORITY -> true
        MixMode.MIXED_STEREO -> true
        // Strict Split works without one; that ear is simply silent, which is
        // the defined behaviour rather than a fault to warn about.
        MixMode.STRICT_HARD_SPLIT -> false
        MixMode.REMOTE_ONLY -> false
    }

    /** Whether the local volume slider changes anything right now. */
    fun localVolumeEffective(hasLocalSource: Boolean, mode: MixMode): Boolean =
        hasLocalSource && mode != MixMode.REMOTE_ONLY

    /**
     * The mode actually used for mixing.
     *
     * Hard Split with no local source puts silence in one ear and phone B in
     * the other: half the headset dead, and if the link is also down, nothing
     * at all. That reads as "the app muted everything". Falling back to Remote
     * Only keeps both ears working and is strictly better than half silence —
     * but the interface has to say it is happening, or the fallback becomes
     * the next thing that looks broken.
     */
    fun effectiveMode(mode: MixMode, hasLocalSource: Boolean): MixMode = when {
        // Strict Split means what it says. Silence on one side is the correct
        // result when that side has no source, and quietly turning it into
        // Remote Only would put phone B in both ears — the opposite of the
        // contract the mode exists to keep.
        mode == MixMode.STRICT_HARD_SPLIT -> mode
        !hasLocalSource && mode != MixMode.REMOTE_ONLY -> MixMode.REMOTE_ONLY
        else -> mode
    }

    /** True when [effectiveMode] is quietly overriding what the user picked. */
    fun isFallingBack(mode: MixMode, hasLocalSource: Boolean): Boolean =
        effectiveMode(mode, hasLocalSource) != mode

    /** The remote slider works whenever a stream is arriving. */
    fun remoteVolumeEffective(): Boolean = true

    /**
     * What to tell the user about the gap between what they expect this
     * control to do and what it can do. Null when there is nothing to explain.
     */
    fun explain(mode: MixMode, hasLocalSource: Boolean): String? = when {
        hasLocalSource -> null

        mode == MixMode.STRICT_HARD_SPLIT ->
            "No source for this phone, so the left ear is silent and only phone B is heard, " +
                "on the right. Strict Split never copies one phone into the other's ear."

        mode == MixMode.REMOTE_ONLY ->
            "Only phone B is played, so no local source is needed."

        mode == MixMode.HARD_SPLIT ->
            "No local source is selected, so the left ear is silent and this is effectively " +
                "Remote Only. Hard Split places a source this app owns in one ear — pick a " +
                "track or turn on this phone's microphone in Settings. Audio that other apps " +
                "on this phone are playing cannot be used: it goes straight to the headset and " +
                "Android gives no app a way to capture and re-route it."

        else ->
            "No local source is selected, so the local volume slider does nothing and only " +
                "phone B is heard. This app can mix a track it decodes itself or this phone's " +
                "microphone. Audio that other apps are playing reaches the headset directly, " +
                "outside this app's mixer, and Android gives no app a way to intercept it."
    }

    /**
     * The only control that does reach everything in the headset, including
     * other apps: the system media volume.
     */
    const val SYSTEM_VOLUME_NOTE: String =
        "This is Android's media volume. It is the one control that affects everything going " +
            "to the headset, including audio from other apps on this phone — but for the same " +
            "reason it moves this app's own output with it. The sliders above set the balance " +
            "between the two sources inside the mix; this sets how loud the result is."
}
