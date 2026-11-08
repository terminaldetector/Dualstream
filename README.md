# DualStream Uniter 2.1

Native Bluetooth audio delegation for Android: two phones share one Bluetooth
headset, and both audio streams are heard in it at the same time.

- **Phone A** owns the headset. It runs an RFCOMM server, decodes phone B,
  mixes it with its own app-managed source and plays the result over A2DP.
- **Phone B** captures its own playback, encodes it and pushes frames to
  phone A over Bluetooth Classic RFCOMM.

No Wi-Fi, no IP sockets, no UDP, no Wi-Fi Direct, no local or cloud server, no
root, no Bluetooth HAL modification. The app declares no `INTERNET`
permission at all, which is what makes that structural rather than a promise.

The headset does **not** need Bluetooth Multipoint: it is only ever paired with
phone A.

```
              PHONE B                                   PHONE A
      ┌───────────────────────┐                 ┌───────────────────────┐
      │ MediaProjection       │                 │  RFCOMM server socket │
      │   + AudioPlayback-    │                 │          │            │
      │     Capture           │                 │     Opus decoder      │
      │   (or direct PCM in)  │                 │          │            │
      │          │            │                 │    jitter buffer      │
      │     downmix to mono   │                 │          │            │
      │          │            │   Bluetooth     │   ┌──────┴──────┐     │
      │     Opus encoder      │ ───RFCOMM────▶  │ local        remote   │
      │          │            │   (secure,      │   └──────┬──────┘     │
      │   framed RFCOMM write │    bonded)      │       mixer           │
      └───────────────────────┘                 │          │            │
                                                │   stereo AudioTrack   │
                                                └──────────┬────────────┘
                                                           │ A2DP
                                                       HEADSET
```

## What is implemented

| Specification | Where |
|---|---|
| §3 RFCOMM transport, discovery, pairing, trusted device, auto reconnect | `transport/` |
| §4 Opus 48 kHz mono, 24/32/48/64 kbit/s, 10–20 ms frames, adaptive buffer | `codec/`, `audio/JitterBuffer.kt` |
| §5 MediaProjection + AudioPlaybackCapture, direct PCM input, silence state | `audio/PlaybackCaptureSource.kt`, `audio/DirectPcmInput.kt` |
| Built-in microphone input on either phone, pinned to the built-in device | `audio/MicCaptureSource.kt`, `audio/MicLocalSource.kt`, `audio/InputRoutePolicy.kt` |
| §6 Hard Split / Mixed Stereo / Remote Only / Priority | `audio/Mixer.kt` |
| §7 Simultaneous RFCOMM + A2DP, device-limit reporting | `service/ReceiverService.kt` |
| §8 Main screen and settings | `ui/` |
| §9 Foreground services, recovery, buffer cleanup, latency control | `service/`, `audio/` |

## Two microphones, one headset

Either phone can contribute its own **built-in** microphone, so both phones can
be heard in the same headset at once. On phone B pick it under *What to send*;
on phone A turn on *Use the built-in microphone as the local source*. In Hard
Split that puts one phone's microphone in each ear.

The important part is that recording is pinned to the built-in microphone with
`setPreferredDevice`, and the route is verified after recording starts rather
than assumed. If Android were left to choose:

- it could route recording to the **headset's** microphone, which switches that
  headset into the hands-free profile and takes the A2DP stream down with it —
  the headset stops being a stereo sink and the bridge collapses;
- and a headset microphone only ever belongs to one phone, so the moment the
  second phone asked for it the system would cut the input on one of them.

Pinning each phone to its own hardware is what lets both record at the same
time. For the same reason the app uses the `VOICE_RECOGNITION` audio source
rather than `VOICE_COMMUNICATION`, and never calls `setMode` or
`startBluetoothSco`: those are the other ways to end up on the hands-free
profile.

Two further details that are easy to get wrong:

- `setPrivacySensitive(false)` is set explicitly. Marking the session privacy
  sensitive would make Android silence *other* recorders — including the
  playback capture running next to it in the same app, which is precisely the
  "app audio + mic" mode.
- A recorder that Android has muted keeps returning frames on schedule; they are
  just zeros. `RecordingPolicyMonitor` watches `isClientSilenced`, so a muted
  microphone is reported as muted instead of looking like a quiet room. In the
  combined mode each half's silence is tracked separately, so a dead microphone
  cannot hide behind the app audio.

Acoustic warning: phone A's microphone sitting next to the headset it is also
feeding is a loop. The platform echo canceller is attached when available, but
it is tuned for handset playback, not for leakage out of an earpiece, so the app
says so rather than pretending it is solved.

## Pairing: why nothing connects until phone A is made visible

This is the step that is easy to miss, and without it the pair never connects.

Opening an RFCOMM server socket makes the *service* available; it does **not**
make the adapter discoverable. Until phone A is visible, a phone B that has
never been paired with it cannot see it in a Bluetooth search at all, however
long it scans — so no pairing happens, and phone A sits on "waiting for phone
B" forever with nothing wrong in its own logs.

Android gives apps no way to become discoverable silently, by design. So:

1. On **phone A**, press **Make visible**. The system asks the user to confirm.
2. On **phone B**, press **Scan**, then **Pair** with phone A.
3. On **phone B**, press **Use this phone**.

After that the pair is bonded and visibility is no longer needed: phone B
connects straight to the stored address. The app tracks this and stops asking
for visibility once a peer is paired, rather than nagging about a step that has
already done its job.

To take the guesswork out of step 2, the device list can query each device's
service records (`fetchUuidsWithSdp`) and marks the ones that advertise the
DualStream service, so phone A is identifiable by what it runs rather than by
its name.

### Any app on either phone

Phone B sends whatever any app on it is playing; phone A does the same for the
local ear. Both ask for capture consent when you press Start, and that is the
only step: there are no switches for it, because the two settings it depends
on — playing the mix on the accessibility stream and taking the media volume
to zero — are not choices, they are what makes it work at all.

Why those two: capture taps the audio, it does not take it away, so the
captured apps keep reaching the headset by their own path and would be heard
twice. Silencing that path means the media volume, and the media volume would
silence this app too if the mix were on the same stream.

One thing is unverified and decides whether this works on a given phone:
**does the capture still carry audio once the media volume is zero?** That
depends on where the platform puts the capture tap relative to the volume, it
is not reliably documented, and it has to be measured per device. If it turns
out to be silent, that phone cannot do this and the app has to say so rather
than present a dead feature. See `docs/TESTING.md`.

Apps that forbid playback capture are out of reach on both phones whatever
happens; that is a platform policy, not a setting.

### Getting "phone A in the left ear, phone B in the right"

That is Hard Split, and it works — with one condition: the left ear plays a
source **this app owns**, not whatever another app on phone A happens to be
playing. Pick a track in Settings, or turn on phone A's microphone, and Hard
Split puts it in one ear with phone B in the other.

Without a local source the left channel has nothing in it. The app used to
play silence there, so half the headset went dead and, if the link to phone B
was also down, all of it did — which looks exactly like the app muting
everything. It now falls back to playing phone B in both ears and says that it
is doing so, with one-tap buttons to pick a track or switch the microphone on.

### What phone A's controls can and cannot reach

Phone A's mixer sees exactly two things: the stream decoded from phone B, and
a source this app owns — a track it decodes itself, or its own microphone.

Audio that **any other app on phone A is playing** goes straight to the A2DP
headset without passing through this process, and no public API lets an app
intercept, re-route or attenuate another app's output. So the local volume
slider and the channel modes have nothing to apply to unless a local source is
selected, and the app now says so instead of presenting live-looking controls
that do nothing.

The one control that does reach everything in the headset is Android's media
volume, so the main screen offers it directly. For the same reason it moves
this app's own output along with everything else: the per-source sliders set
the balance inside the mix, the media volume sets how loud the result is.

Capturing phone A's own app audio and re-mixing it does not solve this. The
capture is a tap, not a redirect, so the original keeps playing to the headset
and the listener hears it twice; silencing it means turning the media volume
down, which silences this app's output too, because both use the music stream.

### Why phone A cannot appear as actual headphones

A reasonable thing to want: phone B should just see phone A in its Bluetooth
menu as a pair of headphones and send audio there with no app involved, the way
an app can make a phone appear as a Bluetooth keyboard.

The keyboard case works because Android exposes the HID *device* role publicly,
through `BluetoothHidDevice`. There is no equivalent for audio. Appearing as
headphones means implementing the A2DP **sink** role, and on Android that is
platform territory: the profile is disabled in most stock builds, and what API
exists is hidden and gated behind system permissions. A normal app cannot
register itself as a Bluetooth audio sink, and section 12 of the specification
rules out the alternatives — no root, no Bluetooth Audio HAL modification.

So the app does the nearest thing that is actually possible: it advertises its
own service record over RFCOMM, which is what lets phone B recognise phone A as
"the phone running DualStream" and connect to it. The audio then travels over
that channel instead of over A2DP. Functionally this is what was wanted — phone
B's audio comes out of phone A's headset — but it needs the app on both phones,
and it cannot show up in the system Bluetooth menu as a headset.

## Honest limitations

These are platform limits, not shortcuts. The specification asks for them to be
surfaced rather than hidden.

1. **Playback capture is not universal.** Android only hands over audio from
   players whose capture policy allows it. Many streaming apps opt out, and
   phone calls and DRM-protected audio can never be captured. When the capture
   is running but every frame is digital silence, the app reports
   *"Running, but the app in the foreground forbids capture"* and never shows
   that as a working stream.
2. **Hard Split needs an app-owned local source.** Without system privileges an
   app cannot intercept arbitrary audio from other apps on phone A and force it
   into one ear. So the left channel plays a track this app decodes itself
   (chosen in Settings, or the built-in test tone). Anything else would be a
   promise the platform does not keep.
3. **Opus encoding depends on the device.** An Opus *encoder* is only a required
   MediaCodec component from Android 10, and some vendor builds ship a decoder
   but no encoder. The app detects this, says so, and falls back to IMA-ADPCM at
   a reduced sample rate, which is pure Kotlin and always available.
4. **No latency figure is claimed.** Section 4 forbids promising a fixed delay
   before measurement. The app measures and displays the RFCOMM round trip and
   the buffer depth instead; end-to-end delay has to be measured on the actual
   pair of phones, per `docs/TESTING.md`.
5. **Two Bluetooth links on one radio can conflict.** If RFCOMM and A2DP do not
   coexist well on a given phone, the receiver counts the audio gaps and reports
   a device limitation rather than just sounding broken. No hidden API is used
   to force Bluetooth profiles.
6. **MediaProjection is never restarted silently.** If the system or the user
   stops the projection, the transmitter stops and asks for fresh consent,
   because that requires a user gesture.
7. **The microphone can be taken away at any time.** Android grants the input to
   one client and may mute ours without any error. The app detects and reports
   that, but it cannot prevent it; only the built-in-device pinning stops the
   two phones from causing it to each other.
8. **A green pipeline is not a working bridge.** CI builds the app, runs the
   logic tests and checks that it starts on an emulator. It cannot test two
   Bluetooth links on one radio, A2DP coexistence, microphone contention or
   latency, because those need two phones and a headset. See `docs/CI.md`.

## Development stages

The order of work follows section 10 of the specification.

- **Stage 1 — Bluetooth Bridge.** Done, and reachable from the UI: *Send test
  tone* on phone B streams a generated signal through the direct PCM input, so
  the RFCOMM link and the headset output can be verified before any capture
  consent exists.
- **Stage 2 — Audio Capture.** Done: `PlaybackCaptureSource`, plus built-in
  microphone capture on both phones and a combined "app audio + mic" source.
- **Stage 3 — Dual Audio.** Done: `Mixer`, independent volumes, four modes,
  channel balance.
- **Stage 4 — Stability.** This is field work on two physical phones. The
  procedure and the measurement sheet are in `docs/TESTING.md`; the app ships
  the counters that stage needs on the Diagnostics screen.

**Section 11 of the specification is explicit: the app counts as working only
after a test on physical devices, not after an APK is produced.** Stage 4 has
not been run — nobody has run this on hardware yet.

## Verification done so far

Everything that does not need Android APIs is covered by JVM unit tests: the
RFCOMM framing and its resynchronisation and checksum behaviour, the handshake
messages, the ADPCM codec's signal-to-noise ratio and its recovery after a lost
packet, the resampler, the mixer in all four modes including ducking and
saturation, the jitter buffer's priming, latency trimming and concealment, the
frame assembler, the reconnect backoff, the sample ring that decouples the
microphone from the mixer, the input-route policy and the two-source capture
mixing including its failure and silence paths.

```bash
./gradlew :app:testDebugUnitTest
```

85 tests, all passing at the time of writing. They have found two real bugs so
far: the concealment fade never reached zero, so it still produced the click it
existed to prevent; and a capture feeder would spin at full CPU against a source
that returns without blocking.

The Android-dependent half (services, MediaCodec wrappers, capture, AudioTrack,
Compose UI) is not covered by these tests. CI compiles it, checks the assembled
package against the product's hard constraints, and boots an emulator to confirm
the app starts — but no part of the actual bridge has been exercised on real
hardware.

## Описание для Telegram

`docs/TELEGRAM.md` содержит то же самое, но так, как это читают в канале: без
таблиц и заголовков, с учётом лимитов Telegram. Четыре варианта — описание
канала, строка для bio, полный пост-анонс и короткий. Ограничения проекта и
то, что мост ещё не проверен на физических телефонах, в них сказано прямо;
при правке это нужно сохранить.

## Continuous integration

`.github/workflows/android.yml` runs on every push: unit tests, lint, a debug
APK build with a package check, and an emulator startup smoke test. The APK and
all reports are uploaded as artifacts. Details and, more importantly, the list
of what CI *cannot* verify are in `docs/CI.md`.

## Building

Standard Android Gradle project, `minSdk` 29 (AudioPlaybackCapture), `targetSdk`
34. The Gradle wrapper is committed, so no local Gradle install is needed.

```bash
./gradlew :app:assembleDebug
```

Install on both phones, then on each one pick its role on the main screen:
`A · headset + mixer` or `B · sends audio`.

## Running it

1. On phone A press **Make visible**, then from phone B scan and pair with it.
   The RFCOMM socket is opened as a secure channel, so a system bond is
   required, and phone A has to be discoverable for that first pairing. See
   "Pairing" above — this is the step whose absence makes nothing connect.
2. Pair the headset **with phone A only**.
3. Phone A: role A, grant *Nearby devices* and microphone, press **Start receiver**.
4. Phone B: role B, open **Devices**, select phone A, press **Start sending**
   and accept the capture prompt. (Or **Send test tone** to check the link first.)
5. Pick a playback mode on phone A and balance the two volumes.

For the two-microphone setup, choose *Built-in mic* on phone B in step 4 — it
needs no capture consent — and turn on the microphone switch on phone A.
