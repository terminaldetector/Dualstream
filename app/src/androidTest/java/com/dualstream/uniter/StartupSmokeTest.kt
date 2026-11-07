package com.dualstream.uniter

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dualstream.uniter.core.CodecId
import com.dualstream.uniter.core.MixMode
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.core.Role
import com.dualstream.uniter.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke tests.
 *
 * These are deliberately modest. The behaviour that actually matters — two
 * Bluetooth links on one radio, playback capture, who wins the microphone —
 * needs two physical phones and a headset, which no emulator provides, and
 * section 11 of the specification says so outright. What an emulator *can*
 * prove is that the app starts at all: a Compose crash, a missing resource, a
 * bad theme or a broken Activity would otherwise only surface when someone
 * installs the APK by hand.
 *
 * The tests run with no permissions granted on purpose. That is the state a
 * first launch is in, and the UI has to cope with it rather than crash.
 */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {

    @Test
    fun mainActivityReachesResumedWithoutPermissions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // onActivity rethrows anything the activity threw on the main thread.
            scenario.onActivity { activity -> assertNotNull(activity) }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun theActivitySurvivesARecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity -> assertNotNull(activity) }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun settingsRoundTripThroughRealSharedPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs(context)

        val originalRole = prefs.role
        val originalMode = prefs.mixMode
        val originalBitrate = prefs.bitrate
        val originalCodec = prefs.preferredCodec
        val originalBalance = prefs.balance
        try {
            prefs.role = Role.TRANSMITTER
            prefs.mixMode = MixMode.HARD_SPLIT
            prefs.bitrate = 48_000
            prefs.preferredCodec = CodecId.ADPCM
            prefs.balance = -0.5f

            // A fresh instance, so this reads what was actually persisted.
            val reread = Prefs(context)
            assertEquals(Role.TRANSMITTER, reread.role)
            assertEquals(MixMode.HARD_SPLIT, reread.mixMode)
            assertEquals(48_000, reread.bitrate)
            assertEquals(CodecId.ADPCM, reread.preferredCodec)
            assertEquals(-0.5f, reread.balance, 1e-6f)
        } finally {
            prefs.role = originalRole
            prefs.mixMode = originalMode
            prefs.bitrate = originalBitrate
            prefs.preferredCodec = originalCodec
            prefs.balance = originalBalance
        }
    }

    @Test
    fun outOfRangeSettingsAreClampedNotStored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs(context)
        val original = prefs.localVolume
        try {
            prefs.localVolume = 5f
            assertEquals(1f, Prefs(context).localVolume, 1e-6f)
            prefs.localVolume = -3f
            assertEquals(0f, Prefs(context).localVolume, 1e-6f)
        } finally {
            prefs.localVolume = original
        }
    }
}
