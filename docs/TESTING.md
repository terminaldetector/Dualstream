# Stage 4 — stability testing on physical devices

Section 11 of the specification says the app counts as working only after a test
on real hardware. This file is the procedure for that test. **It has not been
run.** Everything below is the plan plus the sheet to fill in, not results.

## What you need

- Two Android phones, API 29 or newer, paired with each other.
- One Bluetooth headset, paired with phone A only.
- A third device able to record audio, for the latency measurement.
- Wi-Fi and mobile data switched **off** on both phones for the whole run
  (criterion 5 of section 11).

## Preparation

1. Phone A: role `A`, headset connected, **Start receiver**.
2. Phone B: role `B`, phone A selected under **Devices**.
3. Phone B: **Send test tone** first. If the tone is not heard in the headset,
   stop here — nothing further is meaningful. This isolates the RFCOMM link and
   the A2DP output from the capture path.
4. Phone B: **Stop**, then **Start sending** with a player that allows playback
   capture, and confirm the status reads *Capturing audio* rather than
   *Running, but the app in the foreground forbids capture*.

## Measurements

### 1. End-to-end latency

The app cannot measure this by itself: its own clock stops at the AudioTrack,
and the A2DP sink adds its own buffering on top.

Method: play a click track on phone B. Record, on the third device,
simultaneously, the direct output of phone B's speaker and the headset output
(hold the headset's driver against the recorder's microphone). Measure the
offset between the two click onsets in any waveform editor.

Repeat for each configuration in the sheet. Report the median of 10 clicks, not
a single reading.

Note that `Round trip` on the Diagnostics screen is the RFCOMM PING/PONG time,
not audio latency. It is useful as a radio health indicator and as a lower
bound, nothing more.

### 2. RFCOMM stability

Run for **two hours** of continuous playback. Record from the Diagnostics
screen, every 15 minutes:

- `Frames sent` and `Frames received` — the difference is what the link lost or
  the receiver dropped.
- `Lost per sequence` — gaps in the sequence counter.
- Number of reconnects (visible as `Reconnecting` in the status line, and in the
  log ring).

### 3. Audio dropouts

- `Audio gaps (underruns)` — frames the jitter buffer had to invent.
- `Dropped to cut latency` — frames discarded to stop delay from accumulating.

A rising `Dropped` with a stable `Audio gaps` means the sender is faster than
the player, which is normal and self-correcting. A rising `Audio gaps` means the
radio is not keeping up: lower the bitrate.

### 4. Power consumption

Battery percentage on both phones at the start and end of the two hours, screens
off, with nothing else running. Note the Android battery-usage figure for the
app afterwards as a cross-check.

### 5. Behaviour with the screens locked

Lock both screens five minutes into the run and leave them locked. Criterion 6
of section 11 is that transmission does not stop immediately after locking.
Record how long it survives, and whether it survives the full two hours.

If it does not, check the battery optimisation setting for the app on the phone
that stopped; several vendor builds kill foreground services aggressively
regardless of the service type.

### 6. Recovery

Each of these should recover without restarting the app. Record the time to
recover, or the failure.

| Event | Expected |
|---|---|
| Walk phone B out of range and back | reconnects, no stale audio burst |
| Turn Bluetooth off and on on phone B | status shows `Bluetooth is off`, then reconnects |
| Turn the headset off and on | audio routes to phone A's speaker, then back to the headset |
| Stop the capture from the Android notification | status shows `Consent required`, no silent restart |
| Kill phone B's app | phone A returns to `Waiting for phone B` |

## Configurations to cover

Run the latency and a 15-minute stability sample for each, then pick one for the
full two hours.

| Codec | Bitrate | Frame | Latency (median) | Gaps / 15 min | Notes |
|---|---|---|---|---|---|
| Opus | 24 kbit/s | 20 ms | | | |
| Opus | 32 kbit/s | 20 ms | | | |
| Opus | 32 kbit/s | 10 ms | | | |
| Opus | 48 kbit/s | 20 ms | | | |
| Opus | 64 kbit/s | 20 ms | | | |
| ADPCM | 32 kbit/s | 20 ms | | | 8 kHz, speech only |
| ADPCM | 64 kbit/s | 20 ms | | | 16 kHz |

Run the latency row once more with phone B on its **built-in microphone** rather
than app audio. The capture path is different and may add its own buffering, so
the app-audio figure cannot be assumed to carry over.

ADPCM is fixed at 4 bits per sample, so its bitrate setting changes the sample
rate rather than the quantisation. That is why its rows are not comparable with
Opus at the same number.

## Two-microphone check

This is the case where the platform is most likely to take something away, so it
gets its own pass. Phone A's headset must be connected throughout.

### Each phone on its own microphone

1. Phone B: *What to send* → **Built-in mic**, **Start sending**. No capture
   consent is needed, so this also isolates the microphone path from
   MediaProjection.
2. Phone A: turn on **Use the built-in microphone as the local source**.
3. Mode **Hard Split**: one phone's microphone should arrive in each ear. Speak
   into each phone in turn and confirm which ear it lands in.
4. Mode **Mixed Stereo**: both in both ears.

Record for each phone:

| Check | Where to look |
|---|---|
| Status is `Built-in microphone`, not `Recording from the wrong input` | main screen |
| The headset is still `Headset connected (A2DP)` and still sounds like a stereo sink | main screen, and by ear |
| No notice about the hands-free profile appeared | main screen |
| Both microphones work **at the same time** | by ear |

The fourth row is the point of the exercise. If one phone's microphone goes
silent the moment the other starts, the input was not pinned to the built-in
device on one of them — check the log ring for the route it reported.

A headset that visibly switches to hands-free (the characteristic drop to narrow
band, or the headset announcing a call) means the pinning failed. Record the
phone model: that is a platform behaviour worth knowing about.

### Combined app audio and microphone

On phone B select **App audio + mic** and grant the capture consent.

- Both halves should be audible in the headset simultaneously.
- Mute or stop the player on phone B: the microphone must keep going, and the
  status must not claim more than is true.
- Start a voice recorder or a call-screening app on phone B, which takes the
  input: the status should turn to *Muted by Android* within a second or two.
  This is the case the policy monitor exists for — the recorder keeps delivering
  frames, they are simply zeros, so without the monitor the app would show a
  healthy stream.

### Microphone contention and recovery

| Event | Expected |
|---|---|
| Another app records on phone B | status `Muted by Android`, recovers when that app stops |
| Another app records on phone A | notice about the muted microphone |
| Plug in a wired headset on either phone | route warning, or recording stays on the built-in mic |
| Phone call on either phone | capture stops; it must not come back silently |
| Headset disconnected and reconnected while both mics run | audio follows the route, no stale burst |

### Feedback

With phone A's microphone mixed into the headset it is also feeding, howl-round
is expected at close range. Record the distance at which it starts, with echo
processing on and off (Settings → Microphone), and whether the automatic gain
control makes it worse. This is acoustics, not a bug, but the usable distance is
worth writing down.

## Coexistence check (section 7)

Some phones cannot run an RFCOMM link and an A2DP stream at once without
audible damage. The receiver already watches for this: more than 25 audio gaps
in a minute while an A2DP sink is active flips the headset status to
`Headset connected, link degraded` and shows a notice.

Record for each phone-A candidate and each headset whether that notice appears,
at which bitrate it stops appearing, and what the audio actually sounds like.
This is a hardware property; it cannot be fixed in the app.

## Reporting

Fill in the tables above and append the Diagnostics log ring for any run that
failed. A run is only meaningful with Wi-Fi and mobile data confirmed off on
both phones.
