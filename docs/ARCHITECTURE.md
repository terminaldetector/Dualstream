# Architecture

## Packages

```
core/        enums, settings, process-wide observable state, logging
protocol/    RFCOMM framing, handshake and control messages
transport/   Bluetooth discovery, pairing, RFCOMM sockets, reconnect backoff
codec/       encoder/decoder contracts, Opus via MediaCodec, IMA-ADPCM, raw PCM
audio/       capture sources (playback capture, built-in microphone, direct PCM,
             and their mix), resampler, sample ring, jitter buffer, mixer,
             input-route policy, AudioTrack sink
service/     the two foreground services and their notifications
ui/          Compose screens
```

The dependency direction is one way: `ui` and `service` depend on everything
below them, `audio`/`codec`/`transport` depend only on `core` and `protocol`,
and `core`/`protocol` depend on nothing of ours. That is what makes the DSP and
the wire format unit-testable on a plain JVM.

## Wire format

RFCOMM is a reliable, ordered byte stream, so the header only needs message
boundaries, a type tag, a sequence number and a checksum for resynchronisation.

```
offset size field
0      2    magic 0xD5 0x71
2      1    version
3      1    type
4      2    sequence   (uint16, wraps)
6      2    payload length (uint16, max 4096)
8      2    CRC-16/CCITT-FALSE over the payload
10     n    payload
```

Frame types: `HELLO`, `HELLO_ACK`, `AUDIO`, `PING`, `PONG`, `CONFIG`, `FLUSH`,
`BYE`, `STATE`, `CODEC_INIT`.

The sequence number is not used for reordering — RFCOMM does not reorder — only
to count loss. The CRC is not there to correct errors either; it is there so
that a stream cut mid-frame can be resynchronised to the next magic word instead
of feeding garbage to the decoder.

### Session flow

```
B                                     A
│  connect (secure RFCOMM, bonded)    │
│ ───────────────────────────────────▶│
│  HELLO  codec, rate, frame, bitrate │
│ ───────────────────────────────────▶│
│                        HELLO_ACK    │
│ ◀───────────────────────────────────│
│  CODEC_INIT  (Opus csd, or empty)   │
│ ───────────────────────────────────▶│  decoder created here
│  AUDIO, AUDIO, AUDIO …              │
│ ───────────────────────────────────▶│
│  STATE  (capture became silent)     │
│ ───────────────────────────────────▶│
│                     PING / PONG     │  every 2 s, measures round trip
│ ◀──────────────────────────────────▶│
```

`CODEC_INIT` exists because an Android Opus decoder cannot start without the
encoder's codec-specific data. Synthesising a plausible `OpusHead` works, but
carrying the encoder's real one is correct, and it also gives codecs that need
no setup a single well-defined "stream starts now" marker.

An `AUDIO` payload is 5 bytes of header — flags plus a capture timestamp — then
the codec packet. The only flag so far marks a frame the transmitter knows is
digital silence.

## Threads

| Thread | Role | Work |
|---|---|---|
| `dualstream-accept` | A | accept, handshake, read frames, decode, resample |
| `dualstream-playback` | A | jitter buffer → mixer → AudioTrack |
| `dualstream-keepalive` | A | PING every 2 s |
| `dualstream-connect` | B | connect with backoff, then capture → encode → send |
| `dualstream-tx-reader` | B | control frames from A |
| `dualstream-mix-secondary` | B | microphone into the ring, for "app audio + mic" |
| `dualstream-local-mic` | A | this phone's microphone into the ring |

The playback thread is paced by the blocking `AudioTrack.write`, which is the
only clock that matters on the receiving side. Decoding happens on the network
thread so a slow decoder shows up as jitter rather than as a glitch in the
output.

`FrameWriter.write` is synchronised, because the keepalive thread and the audio
thread share one socket.

## Latency control

Delay that accumulates cannot be recovered by playing faster, so the jitter
buffer removes it instead:

- it primes to a target depth before the first frame is played;
- an underrun deepens the target by one frame, because the producer is behind;
- a long clean run shrinks the target again, so one bad minute does not leave
  permanent delay;
- above a hard ceiling the oldest frames are dropped outright.

On an underrun the last played frame is returned once more, faded to zero, which
is cheap concealment and avoids the click a hard gap produces.

After any link break both the jitter buffer and the AudioTrack are flushed, so a
reconnect never replays stale audio.

## Codec selection

Opus at 48 kHz mono is the primary codec, as specified. The factory tries it and
falls back to IMA-ADPCM if the device has no Opus *encoder* — only required
from Android 10, and absent on some vendor builds.

ADPCM is fixed at 4 bits per sample, so it cannot honour a bitrate budget by
changing quantisation; it changes the sample rate instead (8/12/16 kHz for
32/48/64 kbit/s). The pipeline resamples around whatever rate the codec reports,
and the receiver resamples back to 48 kHz before mixing.

## Microphone input and why it is pinned

Both phones can record their own built-in microphone: on B as the encoder's
input, on A as the mixer's local source. The whole design of this path is about
not losing the input or the headset.

`InputRoutePolicy` holds the rule — record the built-in microphone, nothing
else — as plain integer logic with no Android dependency, so it is unit-tested.
`MicCaptureSource` asserts at runtime that its constants still match
`AudioDeviceInfo`, which is the only thing the duplication could get wrong.

The reasoning:

- Asking for "the microphone" with a Bluetooth headset connected lets Android
  route the input to the headset's microphone. That switches the headset to the
  hands-free profile and the A2DP stream dies with it.
- A headset microphone belongs to one phone at a time. Two phones asking for it
  means one of them gets silenced.
- `setPreferredDevice` is a request, not a guarantee, so `routedDevice` is
  checked after `startRecording`, and `RecordingPolicyMonitor` keeps watching
  because the platform can move a recording later.

Consequently this code never calls `setMode`, never calls `startBluetoothSco`,
and uses `VOICE_RECOGNITION` rather than `VOICE_COMMUNICATION` — each of those
is a route to the hands-free profile. `setPrivacySensitive(false)` is explicit
so this session does not silence the playback capture beside it.

### Clock separation

A recorder is paced by the capture clock, a player by the output clock, and the
two drift. `PcmRing` sits between them: non-blocking reads, and writes that drop
the oldest samples when the reader falls behind. Aged live microphone audio is
worthless, so discarding it is the right failure mode.

- `MicLocalSource` uses it so a late microphone frame becomes silence in the
  local channel instead of stalling playback of *both* sources.
- `MixCaptureSource` uses it for the secondary source, so the primary's blocking
  read stays the clock and neither recorder's jitter becomes the other's problem.

`MixCaptureSource` tracks each side's silence separately. The sum of a live
source and a muted one is still audible, so a single check would hide the
muting — the exact thing section 5 forbids reporting as a working stream.

## What the app deliberately does not do

- No `INTERNET` permission, so there is no IP path even by accident.
- No reflection-based `createRfcommSocket`: an unpaired channel is not
  acceptable, so pairing is left to the system's own bonding flow.
- No hidden APIs to force Bluetooth profile switching.
- No attempt to re-acquire MediaProjection without a user gesture.
- No claim that an uncapturable app is being transmitted.
- No recording from anything but the built-in microphone, and no SCO or call
  audio mode, so the headset is never pulled off A2DP.
- No claim that a microphone muted by the audio policy is sending audio.
- No GitHub Actions workflow and no APK build in the repository, by instruction,
  until the specification is complete and Stage 4 has been run (`docs/CI.md`).
