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

ADPCM is fixed at 4 bits per sample, so its bitrate setting changes the sample
rate rather than the quantisation. That is why its rows are not comparable with
Opus at the same number.

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
