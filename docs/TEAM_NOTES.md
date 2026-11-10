# Gimlistream: team notes

Product goal: live view (and recording) of DJI FPV Goggles V1/V2 over USB, on macOS, Windows and Android. Current version 0.4.0 (git `a76237e`).

## Stack

| Part | Language / tooling | Key deps |
|---|---|---|
| Desktop (macOS, Windows) | Rust, edition 2021, single crate | `rusb` 0.9 (vendored libusb), `openh264` 0.6 (H.264 decode), `minifb` 0.27 (window + pixel buffer) |
| Android | Java (no Kotlin), Gradle Kotlin DSL, AGP 9.5.1 | Android SDK, `MediaCodec` for H.264 decode, `UsbManager` |
| Packaging | POSIX shell scripts, `build.rs` (Windows icon only) | `lipo`, `codesign`, `ditto` (mac); MinGW `x86_64-w64-mingw32-*` (Windows); Gradle + JDK 21 + Android SDK 37 (Android) |
| CI | GitHub Actions (`.github/workflows/build.yml`) | macos-latest for desktop, ubuntu-latest for Android |

Assets are generated once and committed: `assets/AppIcon.icns`, `assets/icon.ico`, `assets/watermark.rgba` and `assets/menu.rgb` (the last two are raw pixel buffers embedded with `include_bytes!`). `assets/make-icon.swift` and `assets/make-menu.swift` produce them; they are not run by the build. Other files in `assets/`: `icon-source.jpeg` (source image for the icon) and `README-windows.txt` (bundled into the Windows zip by `bundle-windows.sh`).

## Structure

```
src/main.rs                       whole desktop app (873 lines): menu, USB find/stream, decode, record, diagnostics, Windows win:: FFI
build.rs                          Windows only: embeds assets/icon.ico via windres
Cargo.toml / Cargo.lock          single package "gimlistream" 0.4.0
bundle.sh                         macOS universal .app -> dist/Gimlistream.zip
bundle-windows.sh                 cross-build x86_64-pc-windows-gnu -> dist/Gimlistream-windows.zip (bundles pinned zadig.exe + README)
bundle-android.sh                 gradle assembleRelease -> dist/Gimlistream.apk
android/
  build.gradle.kts                applicationId com.gimlistream, minSdk 26, targetSdk/compileSdk 37, versionName/versionCode derived from Cargo.toml (versionCode = major*10000 + minor*100 + patch, 400 for 0.4.0)
  src/main/java/com/gimlistream/
    MainActivity.java             USB worker thread, MediaCodec decode to SurfaceView, recording (446 lines)
    NalSplitter.java              Annex B NAL splitter, plain Java, self-check in main()
  src/main/res/                     layout, icons, device_filter.xml (USB VID/PID filter), cat.jpg/watermark.png
assets/                           icons, menu/watermark buffers, Windows README + generators
docs/TEAM_NOTES.md                this file
```

### How the desktop app works (src/main.rs)

1. `main()` opens a 1280x720 minifb window, then loops `menu()` -> `live()`.
2. Menu: Live view, Record, Open recordings, Diagnostics, Quit. Keys (Enter/Space/L, R, O, D, Esc/Q) or clicks on the baked-in `MENU` image (`MENU_BUTTONS` x positions).
3. `live()` finds the device by VID `0x2ca3` / PID `0x001f`, claims interface 3, and calls `stream()`. It retries every second while waiting, on open errors, or after stream errors.
4. `stream()` writes the `RMVT` magic to endpoint OUT (re-sent on read timeouts, because the goggles stop streaming until poked), reads bulk IN, splits the byte stream at the last Annex B start code, keeps SPS/PPS, optionally writes raw `.h264` (`Gimlistream N.h264` in Movies / Videos / home), and decodes complete NALs with openh264 into the window. Watermark and red record dot are drawn into each frame.
5. Diagnostics (`--diagnostics` subprocess): libusb device list, libusb debug log, and on macOS `ioreg` output. Shown in an osascript dialog on macOS, saved to Desktop and opened in Notepad on Windows.

Hardening (T57, T58):
- Stream buffer cap: `cap_stream` drops the buffer if it exceeds `MAX_STREAM_BUF` (8 MB) without yielding a start code, like `NalSplitter` does on Android. Unit-tested in `src/main.rs`.
- `live()` reports USB enumeration errors separately from can't-open errors, so "can't list devices" is not shown as "goggles not openable".

### How the Android app works

`MainActivity` opens the device through `UsbManager`, runs a worker thread that reads the same USB protocol, feeds `NalSplitter`, and pushes NALs into `MediaCodec` (AVC decoder) with the output drawn to a `SurfaceView`. The same MAGIC/endpoint layout as the desktop app is used (endpoint 0 = OUT, 1 = IN on interface 3, per the comment in `stream()`).

`onResume` stops a stalled worker thread before starting a new one, so returning to the app does not leave two readers on the device.

## Protocol and hardware facts (keep in sync across desktop and Android)

- USB VID:PID `2ca3:001f`, interface 3, bulk endpoints (OUT to send `RMVT`, IN for video).
- Video is raw H.264 Annex B; chunks arrive at arbitrary boundaries.
- Goggles must be poked with `RMVT` again when reads time out.
- macOS: needs a USB-C to USB-A adapter plus an A-to-C cable; C-to-C does not enumerate.
- Windows: interface 3 needs the WinUSB driver, installed once through Zadig (pinned to 2.9, SHA-256 checked in `bundle-windows.sh`).

## How to run

- Desktop, from source: `cargo run --release` (needs a libusb-capable macOS or Windows machine; `rusb` vendors libusb).
- Android: open `android/` in Android Studio, or run `./bundle-android.sh` (needs Gradle 9.x, JDK 21, Android SDK platform 37.0 and build-tools 37.0.0).

## How to build

```sh
./bundle.sh            # dist/Gimlistream.zip: universal .app (arm64 + x86_64), ad-hoc signed
./bundle-windows.sh    # dist/Gimlistream-windows.zip (brew install mingw-w64; rustup target add x86_64-pc-windows-gnu)
./bundle-android.sh    # dist/Gimlistream.apk (signed with the debug key)
```

`bundle-android.sh` defaults `JAVA_HOME` to `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` and `ANDROID_HOME` to `/opt/homebrew/share/android-commandlinetools` (Homebrew paths); override either via the environment. There is no Gradle wrapper, so the script calls the `gradle` on PATH; CI pins Gradle 9.9.

`bundle.sh` expects rustup targets `aarch64-apple-darwin` and `x86_64-apple-darwin`. CI installs the same three rustup targets and runs `bundle.sh` and `bundle-windows.sh` on macos-latest, and `bundle-android.sh` on ubuntu-latest. Tag pushes (`v*`) publish a GitHub Release with the three artifacts.

## How to test

- Desktop unit tests: `cargo test`. Covers `last_start_code`, `start_codes`, `menu_button` hit-testing and edges (`menu_button_edges`), `read_actions`, the stream buffer cap (`caps_stream_without_start_code`, `capped_stream_stays_bounded_and_recovers`), the REC dot (`rec_dot_is_drawn_in_corner_only`), the watermark blend, and the Annex B handling (`split_handles_chunk_boundaries`, `keeps_latest_sps_pps` exercising `keep_sps_pps`). These run in CI on every push and PR.
- Android: the `NalSplitter` self-check is a plain `main()`, not a JUnit test. Run it with:
  ```sh
  cd android/src/main/java && javac com/gimlistream/NalSplitter.java -d /tmp/nal-out && java -ea -cp /tmp/nal-out com.gimlistream.NalSplitter
  ```
  It prints `NalSplitter ok` on success. This check is CI-only for agents: the agent sandbox denies `javac` and `java`. CI runs it in the Android job of `build.yml`, before `bundle-android.sh`.
- Android unit test (JUnit, `android/src/test/java/com/gimlistream/NalSplitterTest.java`): `dropsGarbageOverMaxBufferThenSplitsValidNals` feeds 9 MB without a start code (over the 8 MB cap), expects nothing emitted and no throw, then checks that SPS/PPS still split correctly. **CI-verified only**: CI runs `gradle --no-daemon -p android testDebugUnitTest` in the Android job; agents cannot run it, and it was not run locally.
- Android error handling (T123, not compiled or run here): `record()` catches `IOException | RuntimeException` (MediaStore can throw `SecurityException`/`IllegalArgumentException`), closes `rec`, sets `rec = null` and `recording = false` directly (so the next NAL does not retry `openRecording()`), then resets the UI via `runOnUiThread`. `loop()` has an outer `catch (RuntimeException e)` around the whole iteration: it logs, shows "Error: ... Retrying…", sleeps 1 s and keeps the worker alive (e.g. `SecurityException` from USB calls); it exits instead if `running` is false.
- Android `stream()` (T92): 20 consecutive `bulkTransfer` reads returning `< 0` throw `IOException("repeated USB read errors")` so `loop()` reopens the connection (the counter resets on a successful read; a failed `RMVT` poke write is still ignored on Android). `UsbDeviceConnection.bulkTransfer` returns -1 for timeouts too, so this also fires after ~2 s with no video (e.g. drone off), causing a harmless reconnect cycle. Not unit-tested (needs a USB connection); not compiled here either.
- There is no hardware-free test for USB, decoding, or recording. Verify with real goggles (or Diagnostics output) on each platform.

## Observations (for planning, not yet changed)

1. The version has a single source, `Cargo.toml` (`version = "0.4.0"`). `bundle.sh` reads it for `CFBundleShortVersionString`, and `android/build.gradle.kts` parses it for `versionName` and derives `versionCode` as major*10000 + minor*100 + patch (0.4.0 gives 400, replacing the earlier hard-coded 4). To release a new version, change only `Cargo.toml` (plus the "Current version" header at the top of this file, which is updated by hand); `Cargo.lock` follows on the next cargo build.
2. CI runs `cargo test`, the Android JUnit test (`gradle ... testDebugUnitTest`) and the `NalSplitter` `main()` self-check, all in `build.yml`.
3. Android release builds are signed with the debug key (marked `ponytail:` in `build.gradle.kts`). The macOS build is ad-hoc signed (also marked `ponytail:`). Both verified in T51. Both mean installers show security warnings. The README covers them (T54): Mac Gatekeeper (right-click → Open, described as ad-hoc signed), Android unknown-sources and Play Protect prompts, and Windows SmartScreen (More info → Run anyway).
4. `main.rs` is a single 873-line file mixing UI, USB, decode, recording, diagnostics and Windows FFI. Splitting it is optional; the current layout is easy to scan.
5. Cargo verified in T91 (macOS): `cargo test` ran 10 tests, 10 passed, 0 failed, 0 ignored (`capped_stream_stays_bounded_and_recovers`, `finds_start_codes_and_menu_buttons`, `keeps_latest_sps_pps`, `menu_button_edges`, `read_actions`, `splits_at_last_start_code`, `split_handles_chunk_boundaries`, `rec_dot_is_drawn_in_corner_only`, `caps_stream_without_start_code`, `watermark_blends_into_bottom_right`). `cargo build --release` last verified in T40 (success, about 6s). Neither command printed any warnings or errors. `cargo clippy` (T49): clean, 0 warnings.

### Desktop unplug / replug review (T94)

Read-only review of `live()`, `stream()` and `read_action()` in `src/main.rs`. Not verified on hardware.

- Unplug mid-stream: the next `read_bulk` returns an error other than Timeout/Interrupted (typically `NoDevice`), `read_action` maps it to `Fail`, `stream()` flushes any open recording and returns the error. `live()` shows "stream error ..., retrying...", waits 1 s, then loops. While the goggles are gone, `find_goggles()` returns `None` and the status reads "waiting for goggles". Handled.
- Replug: the next `find_goggles()` finds the device, `open_goggles()` reopens and claims interface 3, and `stream()` re-sends `RMVT` and starts with a fresh decoder, buffer and SPS/PPS. Handled.
- Recording across a replug: `app.recording` stays true, so the new `stream()` opens a new `Gimlistream N.h264` on its first complete NAL. Handled, but the "saved ..." status set before the error is overwritten by "stream error" in `live()`, so the user is not told where the first file went.
- Fixed (after T94): the `RMVT` poke in `stream()` used to ignore its `write_bulk` result. A failed poke write now counts as a stream failure: `stream()` stores the error and breaks, so `live()` shows "stream error ..., retrying..." and reconnects. Not hardware-verified.

### Next small improvements (T117, read-only review; items 1 and 4 since applied in T123 and T125)

Scope: `MainActivity.java` worker/`stream()`/`record()`/`drain()`, and `src/main.rs` outside the areas T94 covered (`live()`, `stream()`, `read_action()`). Nothing compiled or run on hardware.

1. **Android `record()`: a failed open retries on every NAL.** The `IOException` branch sets `rec = null` and calls `setRecording(false)` via `runOnUiThread`, but `recording` (volatile, worker-readable) stays true until the UI thread runs. Meanwhile the next NAL sees `recording && rec == null` and calls `openRecording()` again, which can toast "Recording failed" several times. Fix: set `recording = false;` directly in the catch, before the `runOnUiThread`. One line.
2. **Android `fitVideo()` goes stale on in-place resize.** The manifest has `sensorLandscape` plus `configChanges` for `screenSize`, so a split-screen or freeform resize does not recreate the activity. `fitVideo()` only runs on `INFO_OUTPUT_FORMAT_CHANGED`, so the letterbox keeps the old size until the next format change. Fix: keep the last `w`/`h` in fields and re-run `fitVideo` from a layout-change listener on `R.id.root`. Needs a device check (split-screen), since the 180° rotation case does not change screen size.
3. **Android `stream()`: `boolean[] showing = {false}` is an array only for no reason.** No lambda captures it, so a plain local `boolean showing` works. Trivial cleanup, same behaviour.
4. **Desktop `find_goggles()`: one unreadable device aborts the whole scan.** `dev.device_descriptor()?` returns the error to `live()`, which then shows "couldn't list USB devices" and never reaches the goggles. `diagnostics()` already skips such devices (`let Ok(d) = ... else { continue }`). Fix: use the same `continue` pattern here. Rare on macOS, but it is one line and matches the existing code.

Ruled out (checked, no change proposed): the `stream()` poke/retry paths and the desktop `write_bulk` poke error (covered by T94 and T106); the Android `onPause` join (bounded at 1 s); the Android `queue()` drop when the decoder is busy (documented as a deliberate trade-off in the code); the Android `NalSplitter` 3-byte start-code assumption in `record()`/`queue()` (matches `NalSplitter`'s output, checked in the source).

## Needs human verification

Agent shells can't run gradle or adb, so these have never been run by agents:

- `gradle --no-daemon -p android testDebugUnitTest` (NalSplitterTest, including the T121 overflow test once re-added in T139) and `gradle :android:assembleDebug`.
- On-device check of the T124 in-place resize re-fit (split-screen/freeform resize should re-letterbox the video).

The CI Android unit-test step is the only automated check.

## Verification status of these notes

- Read every source, script, workflow and build file listed above.
- Ran `cargo test` in T91 (10 passed, 0 failed) and `cargo build --release` in T40 (success), with no warnings or errors. See Observation 5.
- Ran `cargo clippy` in T49: clean, 0 warnings.
- Bundle scripts (T52): still never executed. They are `sh` scripts needing lipo, codesign, ditto, mingw, curl, zip and gradle, and agents may only run npm/node/cargo. Checked by reading only: every file they reference exists (`assets/AppIcon.icns`, `assets/README-windows.txt`, `android/build.gradle.kts`); the README commands and output paths match the scripts; `build.yml` runs all three scripts with the same rustup targets and mingw install the scripts list; the version regex in `bundle.sh` matches `version = "0.4.0"` in `Cargo.toml`; `build.gradle.kts` parses that version for `versionName`/`versionCode`. Unverified: that the scripts actually succeed, the Zadig SHA-256 and URL, the static-link DLL check, the APK output filename `gimlistream-release.apk`, and the codesign/zip output. CI is the only place they run.
- Did not run the Android build; it remains unverified. The Android `NalSplitter` self-check and `NalSplitterTest` cannot be run by agents (no javac/java/gradle in their shell); the Android job in `build.yml` runs them and is the source of truth. The commands above are taken from the README, the scripts and CI.
