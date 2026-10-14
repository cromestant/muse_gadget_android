# muse_gadget_android

An unofficial Android port of the [Muse Gadgets device protocol](https://github.com/facebookincubator/muse-gadget-sdk)
(Apache 2.0), built to turn a Meta Portal Mini (DT90GB) into a voice gadget for Muse: tap to talk, Muse replies
on screen and out loud.

## Disclaimer

This is an independent, unofficial project. It is **not** affiliated with, endorsed by, or sponsored by
Meta Platforms, Inc. The author has no relationship with Meta. "Meta", "Portal", and "Muse" are trademarks of
their respective owners.

## What it does

- BLE GATT pairing (pairing v5) with the Muse mobile app
- Wi-Fi provisioning driven from the app
- Encrypted link to Muse's cloud (Noise XX over WebSocket), registering as a `linux`/`homehub` gadget
- Push-to-talk voice notes (16 kHz PCM WAV) with replies streaming back as text
- Spoken replies via Muse's TTS endpoint, played on the device speaker
- No Google Play Services and no Meta apps required; minSdk 29

## Build

- JDK 17 and the Android SDK (platform 29)
- `export JAVA_HOME=<path to JDK 17>`
- `./gradlew assembleDebug` &rarr; `app/build/outputs/apk/debug/app-debug.apk`
- `./gradlew test` runs the JVM unit tests

## Status

Works end-to-end on a Portal Mini: pair in the Muse app, tap to talk, get text + spoken replies.
Expect rough edges &mdash; this is a personal project.
