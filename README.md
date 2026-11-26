# Gimlistream

Live view (and recording) for DJI FPV Goggles V1/V2 over USB, on Mac, Windows and Android.

Connect the goggles with a USB-C to USB-A adapter; Macs don't see them over a C-to-C cable.

## Download

Latest builds: **Actions** tab → newest run → Artifacts. Tagged versions (`v*`) are on the **Releases** page.

- Mac: unzip `Gimlistream-mac.zip`, right-click `Gimlistream.app` → Open (ad-hoc signed, not notarized, so Gatekeeper warns on first launch).
- Windows: unzip, run `gimlistream.exe`. SmartScreen may say "Windows protected your PC": click "More info" → "Run anyway". The first time it walks you through installing the WinUSB driver with the bundled Zadig.
- Android 8+: install `Gimlistream.apk`. Android asks to allow installs from this source (turn on "Allow from this source"), and Play Protect may warn about an unknown developer: tap "More details" → "Install anyway".

Recordings are raw H.264 (`.h264`), playable with VLC or IINA.

## Advanced: show the video in a second pair of goggles (Mac/Windows)

Gimlistream can pass the live video on to a second pair of DJI FPV Goggles V1/V2, unchanged
(no re-encoding). The second pair needs custom firmware, so this is for tinkerers only; there
is no button for it, it switches on by itself. Tested with two FPV Goggles V2.

1. Root the second goggles with [fpv.wtf](https://fpv.wtf) and install WTFOS. On firmware other
   than 01.00.0606, first downgrade with [butter](https://github.com/fpv-wtf/butter) (Windows/Linux only).
2. Install the `dji-moonlight-shim` package from the fpv.wtf package manager.
3. Put the second goggles in **DIY mode** (DJI Digital FPV System): only there does WTFOS add
   **Moonlight** to the menu. They don't need to link to any drone.
4. Replace the package's start script with [`docs/moonlight/dji-moonlight-shim`](docs/moonlight/dji-moonlight-shim)
   (it waits until the goggles UI has released the display; the original 3 s were too short,
   and Moonlight then showed only the DJI logo):
   ```sh
   adb push docs/moonlight/dji-moonlight-shim /tmp/
   adb shell 'cd /opt/bin && cp -p dji-moonlight-shim dji-moonlight-shim.orig && cp /tmp/dji-moonlight-shim dji-moonlight-shim.new && chmod 755 dji-moonlight-shim.new && mv dji-moonlight-shim.new dji-moonlight-shim'
   ```
   A package update undoes this.
5. Choose **Moonlight** on the second goggles, connect both goggles to the computer (each with a
   USB-C to USB-A adapter; on Windows, install WinUSB with Zadig for both) and start **Live view**.

The pair that sends video is shown in the window; every other pair gets the same picture, and
goggles plugged in during Live view are picked up within 2 seconds. The title bar says
"SHARING TO 2ND GOGGLES" once pictures get through.

Good to know:
- The goggles' playback decoder takes at most 60 fps. A 120 fps drone mode still works (the
  goggles drop what they can't show), but a 60 fps mode gives the second pair the lowest delay.
- If you restart Moonlight while Gimlistream keeps streaming, press Esc → Live view in Gimlistream
  (or replug the goggles): the protocol has no way to tell Gimlistream that Moonlight restarted.
- Back on the second goggles leaves Moonlight.

## Build locally (macOS)

```sh
./bundle.sh           # dist/Gimlistream-mac.zip (universal .app)
./bundle-windows.sh   # dist/Gimlistream-windows.zip (needs: brew install mingw-w64)
./bundle-android.sh   # dist/Gimlistream.apk (needs: Android SDK 37, Gradle, JDK 21)
cargo test
```
