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
8. **No continuous integration.** There is no GitHub Actions workflow and no APK
   build in this repository, by instruction, until the specification is complete
   and Stage 4 has been run. `docs/CI.md` records what to set up afterwards.

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
Compose UI) is not covered by these tests and has not been exercised on a
device.

## Building

Standard Android Gradle project, `minSdk` 29 (AudioPlaybackCapture), `targetSdk`
34.

```bash
./gradlew :app:assembleDebug
```

Install on both phones, then on each one pick its role on the main screen:
`A · headset + mixer` or `B · sends audio`.

## Running it

1. Pair the two phones with each other in the Android Bluetooth settings.
   The RFCOMM socket is opened as a secure channel, so a system bond is required.
2. Pair the headset **with phone A only**.
3. Phone A: role A, grant *Nearby devices* and microphone, press **Start receiver**.
4. Phone B: role B, open **Devices**, select phone A, press **Start sending**
   and accept the capture prompt. (Or **Send test tone** to check the link first.)
5. Pick a playback mode on phone A and balance the two volumes.

For the two-microphone setup, choose *Built-in mic* on phone B in step 4 — it
needs no capture consent — and turn on the microphone switch on phone A.
