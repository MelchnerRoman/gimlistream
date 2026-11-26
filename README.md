# Gimlistream

Live view (and recording) for DJI FPV Goggles V1/V2 over USB, on Mac, Windows and Android.

Connect the goggles with a USB-C to USB-A adapter; Macs don't see them over a C-to-C cable.

## Download

Latest builds: **Actions** tab → newest run → Artifacts. Tagged versions (`v*`) are on the **Releases** page.

- Mac: unzip, right-click `Gimlistream.app` → Open (ad-hoc signed, not notarized, so Gatekeeper warns on first launch).
- Windows: unzip, run `gimlistream.exe`. SmartScreen may say "Windows protected your PC": click "More info" → "Run anyway". The first time it walks you through installing the WinUSB driver with the bundled Zadig.
- Android 8+: install `Gimlistream.apk`. Android asks to allow installs from this source (turn on "Allow from this source"), and Play Protect may warn about an unknown developer: tap "More details" → "Install anyway".

Recordings are raw H.264 (`.h264`), playable with VLC or IINA.

## Advanced: show the video in a second pair of goggles (Mac/Windows)

Gimlistream can pass the live video on to a second pair of DJI FPV Goggles V1/V2, unchanged
(no re-encoding, about 1 ms extra delay). The second pair needs custom firmware, so this is
for tinkerers only; there is no button for it, it switches on by itself:

1. Root the second goggles with [fpv.wtf](https://fpv.wtf) and install WTFOS. On firmware other
   than 01.00.0606, first downgrade with [butter](https://github.com/fpv-wtf/butter) (Windows/Linux only).
2. Install the `dji-moonlight-shim` package from the fpv.wtf package manager.
3. On the second goggles, choose **Moonlight** in the menu.
4. Connect both goggles to the computer (each with a USB-C to USB-A adapter; on Windows, install
   WinUSB with Zadig for both) and start **Live view**.

The pair that sends video is shown in the window; every other pair gets the same picture.
The title bar says "SHARING TO 2ND GOGGLES" once pictures get through. Back on the second
goggles leaves Moonlight.

## Build locally (macOS)

```sh
./bundle.sh           # dist/Gimlistream.zip (universal .app)
./bundle-windows.sh   # dist/Gimlistream-windows.zip (needs: brew install mingw-w64)
./bundle-android.sh   # dist/Gimlistream.apk (needs: Android SDK 37, Gradle, JDK 21)
cargo test
```
