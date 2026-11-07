# Continuous integration

`.github/workflows/android.yml` runs on every push, on pull requests and on
demand. Four jobs, in parallel, so one failure does not hide the other signals.

| Job | What it proves |
|---|---|
| **Unit tests** | `:app:testDebugUnitTest` — the wire protocol, codecs, resampler, mixer, jitter buffer, sample ring, input-route policy and capture mixing. No device needed; this is what catches logic regressions. |
| **Lint** | `:app:lintDebug` — mainly for the permission and foreground-service-type checks, which are easy to get wrong and otherwise only surface on a device. |
| **Build debug APK** | `:app:assembleDebug` compiles the Android half — services, MediaCodec wrappers, capture paths, Compose UI — which the unit tests do not touch at all. Then `scripts/check_apk.py` checks the built package. The APK is uploaded as an artifact. |
| **Startup smoke test** | Boots an API 34 emulator and runs `:app:connectedDebugAndroidTest`: the app must start, survive a recreation, and persist its settings, all with no permissions granted. |

Reports and the APK are uploaded as artifacts on every run, including failures.

## What the package check verifies

`scripts/check_apk.py` inspects the assembled APK with `aapt2`. A build that
compiles says nothing about whether the package still obeys the product rules,
and each of these would quietly break it:

- **No IP networking permission at all.** Sections 2 and 12 require a
  Bluetooth-only transport, and the absence of `INTERNET` is what makes that
  structural rather than a promise. A dependency pulling the permission in
  through manifest merging is exactly the kind of thing nobody notices by hand.
  `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and the Wi-Fi change permissions
  are refused too.
- **The permissions the app cannot work without**, including
  `FOREGROUND_SERVICE_MICROPHONE`.
- **`minSdk` is 29**, which is what AudioPlaybackCapture needs.
- **Both services declare the foreground service types they claim at runtime.**
  Android refuses a claim the manifest does not cover, so a missing bit means a
  service that cannot start — on a device, in the field, not at build time.
- **Neither service is exported.**

The script parses `aapt2` text output, which differs between versions. If that
parsing silently stopped matching, every assertion above would turn into a
pass, so the parser has its own test against recorded output shapes from both
`aapt2` and the older `aapt`, and the script runs that test before trusting
itself. Run it directly with:

```bash
python3 scripts/check_apk.py --self-test
```

That test has already earned its place: it caught the parser dropping the first
of two consecutive `<service>` elements.

## What CI cannot tell you

Section 11 of the specification is explicit that the app counts as working only
after a test on two physical phones, and that is not pedantry — the parts most
likely to fail are the parts no runner has:

- two Bluetooth links sharing one radio;
- A2DP coexisting with RFCOMM;
- the audio policy deciding which app gets the microphone;
- whether a built-in microphone stays pinned when a headset is connected;
- vendor-specific foreground service and battery limits;
- end-to-end audio latency.

So a green pipeline means "it builds, the logic is intact and it starts". It
does not mean the bridge works. `docs/TESTING.md` is the procedure that decides
that, and it still has to be run by hand.

## Notes

- The Gradle wrapper is committed, including the JAR, and the pipeline runs
  `gradle/actions/wrapper-validation` to prove that binary is a genuine Gradle
  release before executing it.
- Gradle 8.9, AGP 8.5.2, Kotlin 1.9.24, JDK 17, `compileSdk` 34.
- Dependencies come from `google()` and `mavenCentral()` only.
- The unit tests set `unitTests.isReturnDefaultValues = true`, because a few
  classes under test touch `android.util.Log`.
- Two unit tests are deliberately concurrent (`PcmRingTest`,
  `MixCaptureSourceTest`). They are bounded and assert invariants rather than
  timings, but a heavily loaded shared runner is where a latent flake would show
  up first.
- The emulator job is the slowest and the most likely to be flaky for reasons
  that have nothing to do with the app. It is kept separate so the other three
  signals stay readable.
