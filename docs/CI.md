# Continuous integration — deferred plan, not wired up

**Nothing in this repository runs on GitHub Actions.** There is no
`.github/` directory and no workflow file, by instruction: CI and APK builds
are deferred until the specification is fully implemented and Stage 4 has been
run on physical devices. This file only records what to set up later, so the
decision does not have to be rediscovered.

Do not add a workflow until that instruction is lifted.

## Why it is deferred rather than forgotten

A green build would prove very little here. Section 11 of the specification is
explicit that the app counts as working only after a test on two real phones,
and the part that can fail in interesting ways — two Bluetooth links on one
radio, the audio policy deciding who gets the microphone, vendor foreground
service limits — is exactly the part no CI runner can exercise. Producing an
APK now would mostly create the impression of verification.

What CI *can* usefully do is keep the platform-independent half honest. That is
worth having, later.

## What to run when it is enabled

In order of value:

1. **`:app:testDebugUnitTest`** — the JVM tests. These cover the wire format,
   the codecs, the resampler, the mixer, the jitter buffer, the sample ring, the
   input-route policy and the capture mixing. They need no emulator and no
   device, and they are what would actually catch a regression.
2. **`:app:lintDebug`** — worth it mainly for the permission and
   foreground-service-type checks, which are easy to get wrong and hard to
   notice without a device.
3. **`:app:assembleDebug`** — compiles the Android half, which the JVM tests do
   not touch. This is the only way the services and the Compose UI get type-
   checked at all today.

An instrumented test run (`connectedDebugAndroidTest`) is not worth setting up:
the behaviour that matters needs two phones and a headset, which no hosted
runner has.

## Notes for whoever sets it up

- The Gradle wrapper properties are committed but the wrapper JAR and scripts
  are not, so either commit them or use `gradle/actions/setup-gradle`.
- `minSdk` is 29 and `compileSdk` is 34; the Android SDK platform and build
  tools have to be provisioned on the runner.
- Dependencies come from `google()` and `mavenCentral()` only.
- The unit tests use `testOptions { unitTests.isReturnDefaultValues = true }`,
  because a few classes under test touch `android.util.Log`. Nothing else in the
  tested set needs the framework.
- The tests include two deliberately concurrent cases (`PcmRingTest`,
  `MixCaptureSourceTest`). They are bounded and assert invariants rather than
  timings, but a heavily loaded shared runner is the place where a latent
  flake would show up first.
