# Pairing runbook (DRAFT for review)

First live pairing between the Portal Mini app and the Muse app.

## (a) Get the SDK token [NEEDS CHARLES]

1. Go to gadgets.muse.ai. [NEEDS CHARLES]
2. Copy the `mgst_...` SDK token. [NEEDS CHARLES]

## (b) Developer mode in the Muse app [NEEDS CHARLES]

1. Turn Developer mode ON in the Muse app, under Settings > Devices. [NEEDS CHARLES]

## (c) Enable ADB on the Portal Mini [NEEDS CHARLES]

1. On the Portal Mini: tap build number 7x to enable developer options, turn on USB debugging. [NEEDS CHARLES]
2. Connect USB-C to the Dell and accept the debugging prompt. [NEEDS CHARLES]

## (d) Build and install from the Dell [HE CAN RUN]

1. `export JAVA_HOME=/home/cromestant/jdk/jdk-17.0.20.1+1`
2. `./gradlew test` (all green), `./gradlew assembleDebug`.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## (e) First launch on the Mini [NEEDS CHARLES]

1. Open the app, paste the token, tap Save. [NEEDS CHARLES]
2. Grant Bluetooth, Location, Microphone when asked. [NEEDS CHARLES]
3. Turn Location ON when prompted (system setting — advertising silently fails without it). [NEEDS CHARLES]

## (f) Advertising [APP]

- The app advertises as `MuseGadgetXXXXXX` automatically while unpaired. [APP]

## (g) Pair in the Muse app [NEEDS CHARLES]

1. In the Muse app: add device, pick `MuseGadgetXXXXXX` when it appears, confirm pairing. [NEEDS CHARLES]

## (h) Expected vs failure modes

- Expected: device appears in the Muse app scan within seconds; pairing completes. [APP]
- Device doesn't appear: check Bluetooth ON, Location ON (system setting, not just permission), and the peripheral-support gate message in the app (some units cannot advertise). [APP]
- Pairing times out: the setup window is 600 s; restart the app and retry. [APP]
