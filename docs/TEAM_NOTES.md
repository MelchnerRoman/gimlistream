# Gimlistream: team notes

Product goal: live view (and recording) of DJI FPV Goggles V1/V2 over USB, on macOS, Windows and Android. Current version 0.4.0 (release commit `a76237e`; this tree adds T182, T189, T193, T195, T205, T209 and T224 on top of that release).

## Stack

| Part | Language / tooling | Key deps |
|---|---|---|
| Desktop (macOS, Windows) | Rust, edition 2021, single crate | `rusb` 0.9 (vendored libusb), `openh264` 0.6 (H.264 decode), `minifb` 0.27 (window + pixel buffer) |
| Android | Java (no Kotlin), Gradle Kotlin DSL, AGP 9.5.1 | Android SDK, `MediaCodec` for H.264 decode, `UsbManager` |
| Packaging | POSIX shell scripts, `build.rs` (Windows icon only) | `lipo`, `codesign`, `ditto` (mac); MinGW `x86_64-w64-mingw32-*` (Windows); Gradle + JDK 21 + Android SDK 37 (Android) |
| CI | GitHub Actions (`.github/workflows/build.yml`) | macos-latest for desktop, ubuntu-latest for Android, windows-latest for the Ctrl-C smoke job (T268) |

Assets are generated once and committed: `assets/AppIcon.icns`, `assets/icon.ico`, `assets/watermark.rgba` and `assets/menu.rgb` (the last two are raw pixel buffers embedded with `include_bytes!`). `assets/make-icon.swift` and `assets/make-menu.swift` produce them; they are not run by the build. Other files in `assets/`: `icon-source.jpeg` (source image for the icon) and `README-windows.txt` (bundled into the Windows zip by `bundle-windows.sh`).

## Structure

```
src/main.rs                       whole desktop app (1013 lines): menu, USB find/stream, decode, record, diagnostics, Windows win:: FFI
build.rs                          Windows only: embeds assets/icon.ico via windres
Cargo.toml / Cargo.lock          single package "gimlistream" 0.4.0
bundle.sh                         macOS universal .app -> dist/Gimlistream.zip
bundle-windows.sh                 cross-build x86_64-pc-windows-gnu -> dist/Gimlistream-windows.zip (bundles pinned zadig.exe + README)
bundle-android.sh                 gradle assembleRelease -> dist/Gimlistream.apk
android/
  build.gradle.kts                applicationId com.gimlistream, minSdk 26, targetSdk/compileSdk 37, versionName/versionCode derived from Cargo.toml (versionCode = major*10000 + minor*100 + patch, 400 for 0.4.0)
  src/main/java/com/gimlistream/
    MainActivity.java             USB worker thread, MediaCodec decode to SurfaceView, recording (476 lines)
    NalSplitter.java              Annex B NAL splitter, plain Java, self-check in main()
    RecordingUtil.java            recording file naming/collision + close-after-failure helpers, plain Java
  src/test/java/com/gimlistream/
    NalSplitterTest.java          JUnit test for NalSplitter (CI-only for agents)
    RecordingUtilTest.java        JUnit tests for RecordingUtil (CI-only for agents)
  src/main/res/                     layout, icons, device_filter.xml (USB VID/PID filter), cat.jpg/watermark.png
assets/                           icons, menu/watermark buffers, Windows README + generators
docs/TEAM_NOTES.md                this file
```

### How the desktop app works (src/main.rs)

1. `main()` opens a 1280x720 minifb window, then loops `menu()` -> `live()`.
2. Menu: Live view, Record, Open recordings, Diagnostics, Quit. Keys (Enter/Space/L, R, O, D, Esc/Q) or clicks on the baked-in `MENU` image (`MENU_BUTTONS` x positions).
3. `live()` finds the device by VID `0x2ca3` / PID `0x001f`, claims interface 3, and calls `stream()`. It retries every second while waiting, on open errors, or after stream errors.
4. `stream()` writes the `RMVT` magic to endpoint OUT (re-sent on read timeouts, because the goggles stop streaming until poked), reads bulk IN, splits the byte stream at the last Annex B start code, keeps SPS/PPS, optionally writes raw `.h264` (`Gimlistream N.h264` in Movies / Videos / home), and decodes complete NALs with openh264 into the window. A recording opened by the R toggle first drops complete NALs until `first_idr()` finds a type-5 slice, then writes the latest SPS/PPS plus the rest of that NAL (T182), so every file starts at a keyframe. Watermark and red record dot are drawn into each frame.
5. Diagnostics (`--diagnostics` subprocess): libusb device list, libusb debug log, and on macOS `ioreg` output. Shown in an osascript dialog on macOS, saved to Desktop and opened in Notepad on Windows. The subprocess and the report run on a background thread (T195), so the stream and any open recording keep going while the report is produced.

Hardening (T57, T58, T140, T177, T178, T182, T194/T205, T195, T214, T224, T238):
- Responsive window (T140): `stream()` calls `app.window.update()` when a NAL yields no frame (`Ok(None)`) or fails to decode, so the window keeps pumping events (close, Esc, clicks) even when nothing decodes. A failing `update_with_buffer` no longer returns via `?`: it is stored in `err` and the loop breaks, so an open recording is still flushed and reported. `err` is now `Option<Box<dyn Error>>`. Not hardware-verified.
- Stream buffer cap: `cap_stream` drops the buffer if it exceeds `MAX_STREAM_BUF` (8 MB) without yielding a start code, like `NalSplitter` does on Android. Unit-tested in `src/main.rs`.
- `live()` reports USB enumeration errors separately from can't-open errors, so "can't list devices" is not shown as "goggles not openable". Open errors also get a hint from the `rusb::Error` kind: `Access` adds "(permission denied)", `Busy` adds "(in use by another app?)" (T177). Windows shows the Zadig prompt once per run.
- `find_goggles()` skips devices whose descriptor cannot be read instead of aborting the scan (T125).
- Decode errors (T178): a `decode()` error is printed to stderr and the NAL is skipped; `Ok(None)` and a zero-size frame (`w == 0 || h == 0`, from a corrupt SPS) draw nothing but still pump the window, so the stream keeps going and the window stays responsive. The log lock in the libusb callback uses `unwrap_or_else(into_inner)` so it cannot panic inside an `extern` callback (T177). Not hardware-verified.
- Recording starts at a keyframe (T182): opening a file sets `rec_wait_idr = true`; complete NALs are dropped until `first_idr()` finds a NAL type 5, then the latest SPS/PPS and that NAL from its start code onward are written, and everything after is written normally. Before this, the file started wherever the user pressed R, so players showed black/garbage until the next IDR. Two consequences: an open recording that never sees an IDR stays 0 bytes (the REC dot is shown, nothing is written), and the SPS/PPS written are the latest ones seen before that IDR, not the ones captured at file open. `first_idr` is unit-tested (`finds_idr_offset`). The same commit also made file creation race-safe: `create_new` instead of `File::create`, retrying once under a new `recording_path()` name on `AlreadyExists` (previously a name taken between the `exists()` check and the create was truncated). Not hardware-verified.
- Stopping before the first IDR does not claim "saved" (T214): `finish_recording()` flushes, then checks the file's length; a zero-byte file is removed (after the `BufWriter` is dropped, so Windows can delete it) and the status says `no keyframe captured, nothing saved: <path>` instead of `saved <path>`. This covers both pressing R again and a reconnect/stream end while still waiting for an IDR. Unit-tested (`empty_recording_is_not_reported_as_saved`). Not hardware-verified; how often a real goggles stream has no IDR in a short window is unmeasured.
- Waiting for the keyframe is visible (T238): while `rec_wait_idr` is true the status reads `recording to <path> (waiting for keyframe)`; it switches to `recording to <path>` when the first IDR has been written. This explains why an open recording is still 0 bytes and makes the T214 message (`no keyframe captured, nothing saved`) predictable. Not hardware-verified: the T238 agent environment had no goggles attached (the app's own `--diagnostics` reported `USB devices seen by libusb: none`), so the IDR cadence and how often a short REC press lands in the no-keyframe case remain unmeasured. Measurement procedure is under "Needs human verification".
- Diagnostics do not stall the stream (T195): `diagnose()` spawns the `--diagnostics` subprocess and the report/dialog on a thread (handle kept in `App.diagnosing`) instead of running inline in `poll_ui()`. `stream()` keeps reading USB and writing the recording while the report is generated and shown. Only one report runs at a time: pressing D while the previous thread is still running is ignored; a finished handle is replaced on the next press (never joined). Not hardware-verified beyond reading the code.
- Ctrl-C / SIGTERM flushes the recording (T194, landed as T205; T194 was closed as a duplicate): a `ctrlc` handler sets only the `QUIT` atomic, and the loops check it, so `stream()` unwinds through its normal tail and `finish_recording()` flushes the `BufWriter` instead of the process dying with a truncated file. The `saved <path>` (or `no keyframe captured, nothing saved`) result goes to the title and, since T227, is also printed to stderr so it survives the window closing. See "T194/T205/T209/T224/T227" below. Not hardware-verified; the human check is under "Needs human verification".
- Ctrl-C reaches the app on Windows (T224): `main()` calls `win::attach_parent_console()` (`kernel32` `AttachConsole(ATTACH_PARENT_PROCESS = 0xFFFF_FFFF)`) before `ctrlc::set_handler`, because a `windows_subsystem = "windows"` process started from cmd/PowerShell has no console and would never receive `CTRL_C_EVENT`. The call simply fails when there is no parent console (Explorer launch), so those launches are unaffected. No new crate: the declaration lives in the existing `mod win`. Compiles clean for `x86_64-pc-windows-gnu`; not verified on Windows hardware.

### How the Android app works

`MainActivity` opens the device through `UsbManager`, runs a worker thread that reads the same USB protocol, feeds `NalSplitter`, and pushes NALs into `MediaCodec` (AVC decoder) with the output drawn to a `SurfaceView`. The same MAGIC/endpoint layout as the desktop app is used (endpoint 0 = OUT, 1 = IN on interface 3, per the comment in `stream()`).

`onResume` stops a stalled worker thread before starting a new one, so returning to the app does not leave two readers on the device.

USB attach/detach and permission (T189, read-only review): `loop()` keeps a local `permissionRequested`/`lastRequestMs`. When a stream throws (unplug or any connection error) it clears `permissionRequested` before retrying, so a detach followed by a quick replug asks for USB permission immediately instead of staying silent until the 15 s re-ask window: the replugged device is a new `UsbDevice` that has no permission, and the stale flag/last-request time would otherwise suppress the prompt. When no goggles are found the flag is also cleared, and `requestPermission` is only called when `!permissionRequested || now - lastRequestMs > 15000`. Unplug is still detected by polling (a `bulkTransfer` read returns `<= 0` and `findGoggles() == null` throws `"goggles disconnected"`), not by a `USB_DEVICE_DETACHED` receiver. No receiver exists at all; the permission result is polled through `hasPermission()`, so there is nothing to register or unregister. Not compiled or run here.

Recording helpers (T193): the file-naming/collision logic moved out of `MainActivity.openRecording()` into `RecordingUtil.pickUnusedFile(dir, name)`: returns `dir/name`, else `base (1).ext`, `base (2).ext`, …; throws `IOException` when `dir` is null (unmounted storage) or cannot be created; the extension is split on the last dot, so a name without one still works. `RecordingUtil.closeAfterFailure(rec)` closes a failed stream without throwing and returns `false` (the new "recording" flag), which `record()` now assigns. `MainActivity` uses both. `RecordingUtilTest` (JUnit 4 `TemporaryFolder`) covers no-collision, numbered suffixes, null dir, a regular file passed as the dir, creation of a missing dir (`missingDirIsCreated`), and `closeAfterFailure` for a stream whose `close()` throws and for null (`closeAfterFailureNullReturnsFalse`; the last two cases added in T218). Same caveat as `NalSplitterTest`: **CI-only for agents** (`gradle --no-daemon -p android testDebugUnitTest`), not run locally.

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

- Desktop unit tests: `cargo test`. Covers `last_start_code`, `start_codes`, `menu_button` hit-testing and edges (`menu_button_edges`), `read_actions`, the stream buffer cap (`caps_stream_without_start_code`, `capped_stream_stays_bounded_and_recovers`), the REC dot (`rec_dot_is_drawn_in_corner_only`), the watermark blend, the first-IDR search (`finds_idr_offset`, added in T182), the Ctrl-C flush helper (`finish_recording_flushes_buffered_tail`, added in T209), the empty-recording status (`empty_recording_is_not_reported_as_saved`, added in T214) and the Annex B handling (`split_handles_chunk_boundaries`, `keeps_latest_sps_pps` exercising `keep_sps_pps`). These run in CI on every push and PR.
- Android: the `NalSplitter` self-check is a plain `main()`, not a JUnit test. Run it with:
  ```sh
  cd android/src/main/java && javac com/gimlistream/NalSplitter.java -d /tmp/nal-out && java -ea -cp /tmp/nal-out com.gimlistream.NalSplitter
  ```
  It prints `NalSplitter ok` on success. This check is CI-only for agents: the agent sandbox denies `javac` and `java`. CI runs it in the Android job of `build.yml`, before `bundle-android.sh`.
- Android unit test (JUnit, `android/src/test/java/com/gimlistream/NalSplitterTest.java`): `dropsGarbageOverMaxBufferThenSplitsValidNals` feeds 9 MB without a start code (over the 8 MB cap), expects nothing emitted and no throw, then checks that SPS/PPS still split correctly. **CI-verified only**: CI runs `gradle --no-daemon -p android testDebugUnitTest` in the Android job; agents cannot run it, and it was not run locally.
- Android unit test (JUnit, `android/src/test/java/com/gimlistream/RecordingUtilTest.java`, extended in T193 and T218): `RecordingUtil.pickUnusedFile` keeps the name when free, returns `a (1).h264` / `a (2).h264` on collisions, creates a missing directory (`missingDirIsCreated`, added in T218), and throws `IOException` for a null dir or a regular file used as the dir (`TemporaryFolder` rule); `closeAfterFailure` covers the runtime-exception-on-close case and returns false for a null stream (`closeAfterFailureNullReturnsFalse`, added in T218). **CI-only for agents** (same `testDebugUnitTest` step); not run locally.
- Android error handling (T123, not compiled or run here): `record()` catches `IOException | RuntimeException` (MediaStore can throw `SecurityException`/`IllegalArgumentException`), closes `rec`, sets `rec = null` and `recording = false` directly (so the next NAL does not retry `openRecording()`), then resets the UI via `runOnUiThread`. `loop()` has an outer `catch (RuntimeException e)` around the whole iteration: it logs, shows "Error: ... Retrying…", sleeps 1 s and keeps the worker alive (e.g. `SecurityException` from USB calls); it exits instead if `running` is false.
- Android recording close hardening (T137, T151; not compiled or run here): `record()`/`stopRecording()` catch `Exception` (not just `IOException`) when closing `rec`, so a `RuntimeException` cannot escape; `rec = null` moved into a `finally`, and toasts fall back to the exception class name when `getMessage()` is null. T151: when `stream()` ends (error, disconnect, stop) while `recording` is true, it sets `recording = false` and resets the REC button via `runOnUiThread(() -> setRecording(false))`, so the UI matches the closed file. Both landed.
- Android `stream()` (T92): 20 consecutive `bulkTransfer` reads returning `< 0` throw `IOException("repeated USB read errors")` so `loop()` reopens the connection (the counter resets on a successful read; a failed `RMVT` poke write is still ignored on Android). `UsbDeviceConnection.bulkTransfer` returns -1 for timeouts too, so this also fires after ~2 s with no video (e.g. drone off), causing a harmless reconnect cycle. Not unit-tested (needs a USB connection); not compiled here either.
- There is no hardware-free test for USB, decoding, or recording. Verify with real goggles (or Diagnostics output) on each platform.

## Observations (for planning, not yet changed)

1. The version has a single source, `Cargo.toml` (`version = "0.4.0"`). `bundle.sh` reads it for `CFBundleShortVersionString`, and `android/build.gradle.kts` parses it for `versionName` and derives `versionCode` as major*10000 + minor*100 + patch (0.4.0 gives 400, replacing the earlier hard-coded 4). To release a new version, change only `Cargo.toml` (plus the "Current version" header at the top of this file, which is updated by hand); `Cargo.lock` follows on the next cargo build.
2. CI runs `cargo test`, the Android JUnit test (`gradle ... testDebugUnitTest`), the `NalSplitter` `main()` self-check, and the Windows Ctrl-C smoke job (T268; see its own section below), all in `build.yml`.
3. Android release builds are signed with the debug key (marked `ponytail:` in `build.gradle.kts`). The macOS build is ad-hoc signed (also marked `ponytail:`). Both verified in T51. Both mean installers show security warnings. The README covers them (T54): Mac Gatekeeper (right-click → Open, described as ad-hoc signed), Android unknown-sources and Play Protect prompts, and Windows SmartScreen (More info → Run anyway).
4. `main.rs` is a single 972-line file mixing UI, USB, decode, recording, diagnostics and Windows FFI. Splitting it is optional; the current layout is easy to scan.
5. Cargo verified in T214 (macOS): `cargo test` ran 13 tests, 13 passed, 0 failed; `cargo clippy` is clean. The set is the T91 ten plus `finds_idr_offset` (T182), `finish_recording_flushes_buffered_tail` (T209) and `empty_recording_is_not_reported_as_saved` (T214). Before T214 the same set ran clean (12/12) in T209; `cargo test` was last clean before T209 in T204 (11/11), and `cargo clippy` was last run before T209 in T186 with no warnings or errors. T91: `cargo test` ran 10 tests, 10 passed, 0 failed, 0 ignored (`capped_stream_stays_bounded_and_recovers`, `finds_start_codes_and_menu_buttons`, `keeps_latest_sps_pps`, `menu_button_edges`, `read_actions`, `splits_at_last_start_code`, `split_handles_chunk_boundaries`, `rec_dot_is_drawn_in_corner_only`, `caps_stream_without_start_code`, `watermark_blends_into_bottom_right`). `cargo build --release` re-verified in T209 (success, about 6s; first in T40). Neither command printed any warnings or errors. `cargo clippy` (T49): clean, 0 warnings.

### Desktop unplug / replug review (T94)

Read-only review of `live()`, `stream()` and `read_action()` in `src/main.rs`. Not verified on hardware.

- Unplug mid-stream: the next `read_bulk` returns an error other than Timeout/Interrupted (typically `NoDevice`), `read_action` maps it to `Fail`, `stream()` flushes any open recording and returns the error. `live()` shows "stream error ..., retrying...", waits 1 s, then loops. While the goggles are gone, `find_goggles()` returns `None` and the status reads "waiting for goggles". Handled.
- Replug: the next `find_goggles()` finds the device, `open_goggles()` reopens and claims interface 3, and `stream()` re-sends `RMVT` and starts with a fresh decoder, buffer and SPS/PPS. Handled.
- Recording across a replug: `app.recording` stays true, so the new `stream()` opens a new `Gimlistream N.h264` on its first complete NAL. Handled, but the "saved ..." status set before the error is overwritten by "stream error" in `live()`, so the user is not told where the first file went. Fixed in T173: when a recording is open at error time, `stream()` appends "(saved <file>)" to the returned error, so the "stream error: ..., retrying..." status shows the filename. Not hardware-verified.
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

- `gradle --no-daemon -p android testDebugUnitTest` (`NalSplitterTest` plus `RecordingUtilTest` from T193) and `gradle :android:assembleDebug`.
- On-device check of the T124 in-place resize re-fit (split-screen/freeform resize should re-letterbox the video).
- Desktop, T182 (first-IDR recording start): start a recording mid-GOP (press R a second or more into the stream, not right at connect) and confirm with a real player that the `.h264` plays from its first frame (from the keyframe), not black/garbage. Expected: the file is shorter than the time R was on (pre-IDR NALs are dropped), and the player shows video immediately on open. Also check that if R is pressed and the goggles stop sending before any IDR arrives, no 0-byte file is left behind and stopping the recording reports `no keyframe captured, nothing saved: <path>` (T214).
- Desktop, T238 (IDR cadence): with the goggles streaming, press R and time how long the title shows `recording to <path> (waiting for keyframe)` before it switches to `recording to <path>`, over about five runs, and once more right after a reconnect (a new `stream()` starts with `rec_wait_idr` again). Note the times here. If the wait is normally well under a second, a short REC press rarely lands in the T214 no-keyframe case and the hint is just a brief explanation of the 0-byte file; if it is many seconds, the T238 hint is doing real work and a short REC press will commonly be discarded.
- Desktop, T195 (diagnostics while streaming): press D while the stream is running and recording; the window should keep updating and the REC file keep growing while the report subprocess runs and the dialog is up. Pressing D again while a report is still open should do nothing (one report at a time). Confirm the report still contains the correct status line.
- Desktop, T194/T205/T209/T224 (Ctrl-C/SIGTERM flush): with the goggles streaming and REC on, press Ctrl-C in the launching terminal (macOS, and on Windows from cmd/PowerShell) and, on macOS, send SIGTERM (`kill <pid>`). Expected: the process exits with code 0 and prints the `saved <path>` (or `no keyframe captured, nothing saved`) report to stderr (T227), so it survives the window closing; the file is a complete Annex B stream through the last fully buffered NAL (no truncated tail) and plays in VLC/IINA. T224 adds `AttachConsole(ATTACH_PARENT_PROCESS)` before `ctrlc::set_handler`, so the Windows build should now receive Ctrl-C; this has **not** been tried on real Windows hardware. Launching from Explorer (or closing the app window) must still flush as before. The signal plumbing without goggles was verified in T209 on macOS (SIGINT and SIGTERM both exit 0); only the recording flush under a live stream is unverified.
- On-device, T189 (USB detach/replug permission): with the goggles streaming, unplug and replug quickly (within 15 s). Expected: the permission dialog appears immediately on replug instead of streaming staying silent until the 15 s re-ask; after allowing, the stream resumes. Also confirm a plain unplug still shows "Connection lost … Retrying…" then "Waiting for goggles…".
- On-device lifecycle check (T159, from T156). Expected results, with the goggles connected:
  - Pause during REC, then resume: one playable file per session, closed on pause.
  - Unplug during REC: file saved, and the screen shows "Waiting for goggles".
  - Permission dialog during REC: the first file ends and a second one starts on resume (current behaviour, see T156 finding 4; confirm the owner wants this).

The CI Android unit-test step is the only automated check.

## Verification status of these notes

- Read every source, script, workflow and build file listed above.
- Ran `cargo test` in T214 (13 passed, 0 failed) and `cargo build --release` in T40 (success), with no warnings or errors. See Observation 5.
- T238: ran `cargo test` after the waiting-for-keyframe status change (13 passed, 0 failed), and ran `cargo run -- --diagnostics` to look for goggles; it reported `USB devices seen by libusb: none`, so the IDR cadence measurement could not be taken in this environment.
- Ran `cargo clippy` in T186: no warnings or errors (also clean in T49).
- Bundle scripts (T52): still never executed. They are `sh` scripts needing lipo, codesign, ditto, mingw, curl, zip and gradle, and agents may only run npm/node/cargo. Checked by reading only: every file they reference exists (`assets/AppIcon.icns`, `assets/README-windows.txt`, `android/build.gradle.kts`); the README commands and output paths match the scripts; `build.yml` runs all three scripts with the same rustup targets and mingw install the scripts list; the version regex in `bundle.sh` matches `version = "0.4.0"` in `Cargo.toml`; `build.gradle.kts` parses that version for `versionName`/`versionCode`. Unverified: that the scripts actually succeed, the Zadig SHA-256 and URL, the static-link DLL check, the APK output filename `gimlistream-release.apk`, and the codesign/zip output. CI is the only place they run.
- Did not run the Android build; it remains unverified. The Android `NalSplitter` self-check and `NalSplitterTest` cannot be run by agents (no javac/java/gradle in their shell); the Android job in `build.yml` runs them and is the source of truth. The commands above are taken from the README, the scripts and CI.

## T156: Android lifecycle review (read-only)

Scope: `MainActivity.java` (`onCreate`, `onResume`, `onPause`, `startWorker`, `loop`, `stream`, `record`, `stopRecording`) and `AndroidManifest.xml`. Nothing compiled or run on a device.

Facts from the code:
- There is no USB-detached broadcast receiver, and no `BroadcastReceiver` of any kind. The USB permission result is polled, not received (comment at line 229). `grep` for `Receiver`, `registerReceiver`, `DETACHED`, `onDestroy`, `onStop`, `onNewIntent` finds nothing. So there is nothing to unregister.
- Unplug is detected by polling in `stream()`: a `bulkTransfer` read returns `<= 0` and `findGoggles() == null` throws `IOException("goggles disconnected")` (about 100 ms, one read timeout). The `finally` then calls `stopRecording()`, `codec.release()` and `conn.close()`. `loop()` then shows "Waiting for goggles". The file is closed and the USB connection released.
- `onPause` sets `running = false`, removes the pending `startWorker` post and joins the worker for up to 1 s. The worker's `stream()` `finally` is the only place that closes `rec`, so a recording is closed on every pause (the worker exits within one read timeout, 100 ms, when streaming).
- `rec` is only non-null inside `stream()`, so the `finally` covers every path that opens a file.
- No `onDestroy` is needed: the back-invoked callback is owned by the window, and the layout listener and the `startWorker` post are owned by views and removed in `onPause`.

Findings (none is a crash or a leak):
1. **Join can time out, so the worker can outlive `onPause` by about 1–2 s.** The idle branch `sleep(1000)` (no goggles found) does not check `running`, and the stream-start and no-data `bulkTransfer(out, MAGIC, 2000)` pokes can block up to 2 s. `join(1000)` then returns early. The worker still exits (`running` is false) and closes the file, and `onResume` waits on `startWorker` if it is still alive. Low. Risk: if Android kills the process in that window, the 1 MB `BufferedOutputStream` may not be flushed and the file is truncated.
2. **`sps`/`pps` were never cleared between streams.** `record()` wrote the last SPS/PPS from the previous connection at the start of the new file. Applied in T157: `stream()` resets `sps` and `pps` to null before it opens the device. See "T157" below.
3. **Errors other than `Exception` kill the worker.** `loop()` catches `Exception` and `RuntimeException` only. An `Error` (e.g. `OutOfMemoryError`) ends the thread with an uncaught exception, which crashes the app. The `finally` still closes the file and the connection. Very low. Not worth changing without a reproduction.
4. **Resolved:** `stream()`'s `finally` now clears `recording` (and the REC button) after closing the file. Pause or unplug during REC ends the recording; it does not auto-start a second file on resume.

Needs device checks (agents cannot run them): pause during REC then resume (expect one playable file per session, closed on pause); unplug during REC (expect file saved and "Waiting for goggles"); permission dialog during REC.

Follow-ups: finding 2 (sps/pps reset) was applied in T157; the on-device lifecycle check is T159 (listed under "Needs human verification"). Finding 3 is not applied. Finding 1 is applied in T167 (idle "no goggles" sleep is now 10 x 100 ms and stops when `running` is false; the 2 s `bulkTransfer` pokes are unchanged). Not compiled here (no Android build available).

## T157: SPS/PPS reset at the start of each stream (Android)

`MainActivity.stream()` now clears the parameter sets for every new connection, so a new recording never starts with the previous goggles' SPS/PPS. Not compiled or run here.

The duplicate reset from the parallel T157/T160 merges was removed in T169; `stream()` now has a single `sps = pps = null;` (MainActivity.java:265).

## T191: MediaCodec error handling review (Android)

Reviewed `stream()`/`queue()`/`drain()`/`loop()`. Already fine: `createDecoderByType` `IOException`, `configure`/`start` `CodecException` and dequeue `IllegalStateException` all propagate out of `stream()`, whose `finally` closes the file, releases the codec and the connection; `loop()` shows "Connection lost: ... Retrying…" and restarts. Fixed (not compiled or run here): `surfaceDestroyed` and the decode step (`nals.add` + `drain`) now share `surfaceLock`, so the surface is not torn down while a frame is being rendered; `stream()` then sees `surface == null` and exits. The 1 s retry sleeps in `loop()` are now 10 x 100 ms and stop when `running` is false.

## T167/T168: responsive idle sleep (Android)

The idle "no goggles" sleep in `MainActivity.loop()` now exits promptly on pause (10 x 100 ms, checks `running`). T168 verifies this. The 2 s `bulkTransfer` pokes can still delay exit.

## T194/T205/T209/T224/T227: Ctrl-C/SIGTERM flushes a recording (desktop)

T194 was closed as a duplicate of T205; both tickets describe this change, and T205 (plus the T209 QA pass) is what landed. T205 adds `ctrlc` (feature `termination`) and sets an `AtomicBool` (`QUIT`) in its handler (main.rs:167, the only `ctrlc::set_handler` call in the tree); `menu()` and `watching()` check it, so `stream()` unwinds through the existing exit path: the loop condition fails within one 100 ms USB read (or up to 2 s while a poke write is in flight), `finish_recording()` flushes the `BufWriter`, the file is closed, and `main` returns `Ok(())` so the exit code is 0. Before T227, `finish_recording()`'s result went through `set_status()` to the window title only, and the window closes with the process, so the terminal showed nothing; T227 now also prints it to stderr and keeps the title on quit (see below). The handler only sets a flag, so nothing async-signal-unsafe runs in the signal context. The flush keeps the last fully drained NAL; the partial NAL still in the stream buffer is not written (expected). If recording was still waiting for its first IDR, the empty file is removed and reported as `no keyframe captured, nothing saved` (T214), not as `saved`.

T227 makes the final report durable on quit (commit `d441e19`, merged as `51e86da`). `finish_recording()` now `eprintln!`s the message it returns (`saved <path>`, `saving <path> failed: <e>`, and after T214's merge also `no keyframe captured, nothing saved: <path>`) before returning it, so the result reaches stderr instead of only the window title, which disappears with the window. `menu()` only resets the title to `Gimlistream` when `!quit_requested()`: after a quit makes `live()` return, `main()` still calls `menu()` once more, and that reset would overwrite the final status line. On the quit path `stream()` clears `app.recording` before `set_status(&saved)`, so the last title does not append ` - RECORDING` for a file that has just been closed. Caveat: stderr is only visible for a terminal launch; launched from Finder (macOS) or Explorer (Windows) there is no attached console, so the message is not shown there and the title remains the only (transient) report. Hardware verification of the flush and the printed report under a live goggle stream is still pending (see "Needs human verification").

Static review found no defect in the macOS path. T209 verified without goggles on macOS: launching the built binary (debug and release) and sending SIGINT and SIGTERM each produced exit code 0 within the 1.2 s check window. T209 also added `finish_recording_flushes_buffered_tail` (buffered bytes really reach the file before "saved" is reported). Not verified: the flush under a live goggle stream (needs hardware; see "Needs human verification").

T224 (Windows console): `windows_subsystem = "windows"` (main.rs:2) makes the process a GUI-subsystem binary, which is not attached to the cmd/PowerShell console that launched it, so `ctrlc`'s `SetConsoleCtrlHandler` never sees `CTRL_C_EVENT` and Ctrl-C cannot flush a recording. `main()` now calls `win::attach_parent_console()`, which declares and calls `kernel32!AttachConsole(ATTACH_PARENT_PROCESS)` (0xFFFF_FFFF) before installing the handler. `AttachConsole` returns 0 with no parent console (Explorer launch, or `--diagnostics`, which returns before this point), so GUI launches are unchanged; if the process is already attached it also fails harmlessly. Closing the app window still flushes through the existing path. Windows behaviour is reasoned from the Win32 API, not run on hardware: host `cargo test` stays 13/13 and `cargo check`/`clippy --target x86_64-pc-windows-gnu` are clean, but the real Ctrl-C/flush check on Windows with goggles is still owed (see "Needs human verification").

T226 is a duplicate of T224, not a new change: the task was cut after T224 had already landed on `agentherd`, so the fix (`main.rs:164-165`, before `ctrlc::set_handler` at `main.rs:167`) is present in its tree. Re-verified in T226: `cargo test` is 13/13 and `cargo check --target x86_64-pc-windows-gnu` is clean; no code change was needed, and the Windows-host Ctrl-C/console-close check remains owed (see "Needs human verification"). The earlier `task-226-conflict-1`/`-2` branches only had to be redone because they were built on pre-T224 trees.

T194 is closed as a duplicate of T205; do not merge `task-194` (`fe60da9`) or `task-194-conflict-1` (`c805fbd`). Both add the same handler under a different name (`INTERRUPTED`/`STOP`) and were written on older trees (`e83f2ff`, pre-T195; `81a1cf9`, pre-T182/T193/T195), so merging them would revert those landed changes as well as duplicate the handler (only one `ctrlc::set_handler` can be installed). Their only additive behaviour over T205 is `c805fbd`'s final `eprintln!("{}", app.status)` after the main loop; `fe60da9` additionally installs the handler before the `--diagnostics` early return, which would swallow Ctrl-C in that mode. Verified in T221 against the current tree: one `static QUIT`, one `ctrlc::set_handler` call, one `quit_requested()`, and `cargo test` passes (12 tests).

## T268: Windows Ctrl-C smoke job (CI)

`.github/workflows/build.yml` gains a `windows-ctrl-c` job (`needs: desktop`, `windows-latest`, 5 min timeout). It downloads the `Gimlistream-windows` artifact built by the macos desktop job instead of adding a second Windows build path, expands it, and runs `.github/ctrl-c-smoke.ps1`. The script allocates a private console (`FreeConsole` + `AllocConsole`, so the event cannot reach the runner), starts `gimlistream.exe` there with `UseShellExecute = $false` (so its parent is the script, making `AttachConsole(ATTACH_PARENT_PROCESS)` attach to that console), then calls `GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)` (with the script itself ignoring Ctrl-C) every 250 ms until the process exits. It asserts exit code 0 and no `panicked` on stderr; a process that never exits means the event was never delivered, so a regression such as a dropped `AttachConsole` call fails the job rather than hanging it (the job has a timeout and the script kills the app in `finally`).

This is the automatable half of T233. It cannot verify a playable `.h264`: with no goggles attached there is no stream and no recording, so the flush under a live stream stays under "Needs human verification". **Not yet executed**: the workflow and script were written and reviewed, but agents have no Windows runner and CI has not run them yet. The job depends on the existing desktop job, so a failure to cross-build `gimlistream.exe` also stops the smoke job.
