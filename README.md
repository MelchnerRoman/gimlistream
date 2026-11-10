# Gimlistream

Live view (and recording) for DJI FPV Goggles V1/V2 over USB, on Mac, Windows and Android.

Connect the goggles with a USB-C to USB-A adapter; Macs don't see them over a C-to-C cable.

## Download

Latest builds: **Actions** tab → newest run → Artifacts. Tagged versions (`v*`) are on the **Releases** page.

- Mac: unzip, right-click `Gimlistream.app` → Open (ad-hoc signed, not notarized, so Gatekeeper warns on first launch).
- Windows: unzip, run `gimlistream.exe`. SmartScreen may say "Windows protected your PC": click "More info" → "Run anyway". The first time it walks you through installing the WinUSB driver with the bundled Zadig.
- Android 8+: install `Gimlistream.apk`. Android asks to allow installs from this source (turn on "Allow from this source"), and Play Protect may warn about an unknown developer: tap "More details" → "Install anyway".

Recordings are raw H.264 (`.h264`), playable with VLC or IINA.

## Build locally (macOS)

```sh
./bundle.sh           # dist/Gimlistream.zip (universal .app)
./bundle-windows.sh   # dist/Gimlistream-windows.zip (needs: brew install mingw-w64)
./bundle-android.sh   # dist/Gimlistream.apk (needs: Android SDK 37, Gradle, JDK 21)
cargo test
```
