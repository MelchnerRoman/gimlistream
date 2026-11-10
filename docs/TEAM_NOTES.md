# Gimlistream: team notes

Product goal: live view (and recording) of DJI FPV Goggles V1/V2 over USB, on macOS, Windows and Android. Current version 0.5.1 (`Cargo.toml`).

## Stack

| Part | Language / tooling | Key deps |
|---|---|---|
| Desktop (macOS, Windows) | Rust, edition 2021, single crate | `rusb` 0.9 (vendored libusb), `openh264` 0.6 (H.264 decode), `minifb` 0.27 (window + pixel buffer) |
| Android | Java (no Kotlin), Gradle Kotlin DSL, AGP 9.5.1 | Android SDK, `MediaCodec` for H.264 decode, `UsbManager` |
| Packaging | POSIX shell scripts, `build.rs` (Windows icon only) | `lipo`, `codesign`, `ditto` (mac); MinGW `x86_64-w64-mingw32-*` (Windows); Gradle + JDK 21 + Android SDK 37 (Android) |
| CI | GitHub Actions (`.github/workflows/build.yml`) | macos-latest for desktop, ubuntu-latest for Android, windows-latest for the Ctrl-C smoke job (T268); `cargo clippy --all-targets -- -D warnings` runs in the desktop job (T305) |

Assets are generated once and committed: `assets/AppIcon.icns`, `assets/icon.ico`, `assets/watermark.rgba` and `assets/menu.rgb` (the last two are raw pixel buffers embedded with `include_bytes!`). `assets/make-icon.swift` and `assets/make-menu.swift` produce them; they are not run by the build. Other files in `assets/`: `icon-source.jpeg` (source image for the icon) and `README-windows.txt` (bundled into the Windows zip by `bundle-windows.sh`).

## Structure

```
src/main.rs                       whole desktop app (1085 lines): menu, USB find/stream, decode, record, diagnostics, Windows win:: FFI
build.rs                          Windows only: embeds assets/icon.ico via windres
Cargo.toml / Cargo.lock          single package "gimlistream" 0.4.0
bundle.sh                         macOS universal .app -> dist/Gimlistream.zip
bundle-windows.sh                 cross-build x86_64-pc-windows-gnu -> dist/Gimlistream-windows.zip (bundles pinned zadig.exe + README)
bundle-android.sh                 gradle assembleRelease -> dist/Gimlistream.apk
android/
  build.gradle.kts                applicationId com.gimlistream, minSdk 26, targetSdk/compileSdk 37, versionName/versionCode derived from Cargo.toml (versionCode = major*10000 + minor*100 + patch, 501 for 0.5.1)
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
- Stopping before the first IDR does not claim "saved" (T214): `finish_recording()` flushes, then checks the file's length; a zero-byte file is removed (after the `BufWriter` is dropped, so Windows can delete it) and the status says `no keyframe captured, nothing saved: <path>` instead of `saved <path>`. This covers both pressing R again and a reconnect/stream end while still waiting for an IDR. Unit-tested (`empty_recording_is_not_reported_as_saved`). T251: a metadata/readback failure after the flush now reports `saving <path> failed: could not verify the file: <e>` instead of claiming `saved`; unit-tested (`unverifiable_recording_is_not_reported_as_saved`). Not hardware-verified; how often a real goggles stream has no IDR in a short window is unmeasured.
- Waiting for the keyframe is visible (T238): while `rec_wait_idr` is true the status reads `recording to <path> (waiting for keyframe)`; it switches to `recording to <path>` when the first IDR has been written. This explains why an open recording is still 0 bytes and makes the T214 message (`no keyframe captured, nothing saved`) predictable. Not hardware-verified: the T238 agent environment had no goggles attached (the app's own `--diagnostics` reported `USB devices seen by libusb: none`), so the IDR cadence and how often a short REC press lands in the no-keyframe case remain unmeasured. Measurement procedure is under "Needs human verification".
- Diagnostics do not stall the stream (T195): `diagnose()` spawns the `--diagnostics` subprocess and the report/dialog on a thread (handle kept in `App.diagnosing`) instead of running inline in `poll_ui()`. `stream()` keeps reading USB and writing the recording while the report is generated and shown. Only one report runs at a time: pressing D while the previous thread is still running is ignored; a finished handle is replaced on the next press (never joined). Not hardware-verified beyond reading the code.
- Ctrl-C / SIGTERM flushes the recording (T194, landed as T205; T194 was closed as a duplicate): a `ctrlc` handler sets only the `QUIT` atomic, and the loops check it, so `stream()` unwinds through its normal tail and `finish_recording()` flushes the `BufWriter` instead of the process dying with a truncated file. The `saved <path>` (or `no keyframe captured, nothing saved`) result goes to the title and, since T227, is also printed to stderr so it survives the window closing. See "T194/T205/T209/T224/T227" below. Not hardware-verified; the human check is under "Needs human verification".
- Ctrl-C reaches the app on Windows (T224): `main()` calls `win::attach_parent_console()` (`kernel32` `AttachConsole(ATTACH_PARENT_PROCESS = 0xFFFF_FFFF)`) before `ctrlc::set_handler`, because a `windows_subsystem = "windows"` process started from cmd/PowerShell has no console and would never receive `CTRL_C_EVENT`. The call simply fails when there is no parent console (Explorer launch), so those launches are unaffected. No new crate: the declaration lives in the existing `mod win`. Compiles clean for `x86_64-pc-windows-gnu`; not verified on Windows hardware. If stdout/stderr are missing after attaching (a GUI-subsystem process starts without them), it points them at `CONOUT$` so the `saved <path>` report reaches the console; redirected handles are kept.

### How the Android app works

`MainActivity` opens the device through `UsbManager`, runs a worker thread that reads the same USB protocol, feeds `NalSplitter`, and pushes NALs into `MediaCodec` (AVC decoder) with the output drawn to a `SurfaceView`. The same MAGIC/endpoint layout as the desktop app is used (endpoint 0 = OUT, 1 = IN on interface 3, per the comment in `stream()`).

`onResume` stops a stalled worker thread before starting a new one, so returning to the app does not leave two readers on the device.

USB attach/detach and permission (T189, read-only review): `loop()` keeps a local `permissionRequested`/`lastRequestMs`. When a stream throws (unplug or any connection error) it clears `permissionRequested` before retrying, so a detach followed by a quick replug asks for USB permission immediately instead of staying silent until the 15 s re-ask window: the replugged device is a new `UsbDevice` that has no permission, and the stale flag/last-request time would otherwise suppress the prompt. When no goggles are found the flag is also cleared, and `requestPermission` is only called when `!permissionRequested || now - lastRequestMs > 15000`. Unplug is still detected by polling (a `bulkTransfer` read returns `<= 0` and `findGoggles() == null` throws `"goggles disconnected"`), not by a `USB_DEVICE_DETACHED` receiver. No receiver exists at all; the permission result is polled through `hasPermission()`, so there is nothing to register or unregister. Not compiled or run here.

Recording helpers (T193): the file-naming/collision logic moved out of `MainActivity.openRecording()` into `RecordingUtil.pickUnusedFile(dir, name)`: returns `dir/name`, else `base (1).ext`, `base (2).ext`, …; throws `IOException` when `dir` is null (unmounted storage) or cannot be created; the extension is split on the last dot, so a name without one still works. `RecordingUtil.closeAfterFailure(rec)` closes a failed stream without throwing and returns `false` (the new "recording" flag), which `record()` now assigns. `MainActivity` uses both. `RecordingUtilTest` (JUnit 4 `TemporaryFolder`) covers no-collision, numbered suffixes, null dir, a regular file passed as the dir, creation of a missing dir (`missingDirIsCreated`), and `closeAfterFailure` for a stream whose `close()` throws and for null (`closeAfterFailureNullReturnsFalse`; the last two cases added in T218). Same caveat as `NalSplitterTest`: **CI-only for agents** (`gradle --no-daemon -p android testDebugUnitTest`), not run locally.

Recording starts at the first IDR (T302; desktop parity with T182/T238): `record()` no longer opens the file on the first NAL after REC. While `recording` is true and `rec` is null it keeps updating the latest `sps`/`pps` and writes nothing; the file is only created when a type-5 NAL arrives, and it opens with the latest `sps`, then `pps`, then that IDR. Before this, a recording started mid-GOP began with a P-slice, so players showed nothing until the next IDR. Consequence: stopping REC (or the stream ending) before any IDR creates no file at all, so there is no 0-byte leftover; `stopRecording()` already no-ops when `rec == null`. While waiting, the status reads `Recording… waiting for keyframe`; it is cleared when the file opens (`toast("Recording to " + recName)` stays), when REC is stopped, and when `stream()` ends. The same change replaced `boolean[] showing = {false}` in `stream()` with a plain local `boolean` (T117 item 3). The failure path (`RecordingUtil.closeAfterFailure`, `recording = false`, `rec = null`, `setRecording(false)`) is unchanged. Not compiled and not hardware-verified: only CI (`gradle --no-daemon -p android testDebugUnitTest` and the APK build) checks it, and the IDR cadence on Android remains unmeasured.

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

- Desktop unit tests: `cargo test` (15 tests). Covers `last_start_code` (`splits_at_last_start_code`), `start_codes`, `menu_button` hit-testing and edges (`menu_button_edges`), `read_actions`, the stream buffer cap (`caps_stream_without_start_code`, `capped_stream_stays_bounded_and_recovers`), the REC dot (`rec_dot_is_drawn_in_corner_only`), the watermark blend, the first-IDR search (`finds_idr_offset`, added in T182), the recording gate that drops pre-IDR NALs and prepends SPS/PPS (`recording_starts_at_first_idr`, added in T301), the Ctrl-C flush helper (`finish_recording_flushes_buffered_tail`, added in T209), the empty-recording status (`empty_recording_is_not_reported_as_saved`, added in T214), the unverifiable-readback status (`unverifiable_recording_is_not_reported_as_saved`, added in T251) and the Annex B handling (`split_handles_chunk_boundaries`, `keeps_latest_sps_pps` exercising `keep_sps_pps`). These run in CI on every push and PR.
- Android: the `NalSplitter` self-check is a plain `main()`, not a JUnit test. Run it with:
  ```sh
  cd android/src/main/java && javac com/gimlistream/NalSplitter.java -d /tmp/nal-out && java -ea -cp /tmp/nal-out com.gimlistream.NalSplitter
  ```
  It prints `NalSplitter ok` on success. This check is CI-only for agents: the agent sandbox denies `javac` and `java`. CI runs it in the Android job of `build.yml`, before `bundle-android.sh`.
- Android unit test (JUnit, `android/src/test/java/com/gimlistream/NalSplitterTest.java`): `dropsGarbageOverMaxBufferThenSplitsValidNals` feeds 9 MB without a start code (over the 8 MB cap), expects nothing emitted and no throw, then checks that SPS/PPS still split correctly. `skipsStartCodeOnlyUnits` (added in T303) feeds a start-code-only unit before an SPS and between two real NALs; neither 3-byte empty unit is emitted, so `record()`/`queue()` never read the NAL type from the next start code's first byte. **CI-verified only**: CI runs `gradle --no-daemon -p android testDebugUnitTest` in the Android job; agents cannot run it, and it was not run locally.
- Android unit test (JUnit, `android/src/test/java/com/gimlistream/RecordingUtilTest.java`, extended in T193 and T218): `RecordingUtil.pickUnusedFile` keeps the name when free, returns `a (1).h264` / `a (2).h264` on collisions, creates a missing directory (`missingDirIsCreated`, added in T218), and throws `IOException` for a null dir or a regular file used as the dir (`TemporaryFolder` rule); `closeAfterFailure` covers the runtime-exception-on-close case and returns false for a null stream (`closeAfterFailureNullReturnsFalse`, added in T218). **CI-only for agents** (same `testDebugUnitTest` step); not run locally.
- Android error handling (T123, not compiled or run here): `record()` catches `IOException | RuntimeException` (MediaStore can throw `SecurityException`/`IllegalArgumentException`), closes `rec`, sets `rec = null` and `recording = false` directly (so the next NAL does not retry `openRecording()`), then resets the UI via `runOnUiThread`. `loop()` has an outer `catch (RuntimeException e)` around the whole iteration: it logs, shows "Error: ... Retrying…", sleeps 1 s and keeps the worker alive (e.g. `SecurityException` from USB calls); it exits instead if `running` is false.
- Android recording close hardening (T137, T151; not compiled or run here): `record()`/`stopRecording()` catch `Exception` (not just `IOException`) when closing `rec`, so a `RuntimeException` cannot escape; `rec = null` moved into a `finally`, and toasts fall back to the exception class name when `getMessage()` is null. T151: when `stream()` ends (error, disconnect, stop) while `recording` is true, it sets `recording = false` and resets the REC button via `runOnUiThread(() -> setRecording(false))`, so the UI matches the closed file. Both landed.
- Android `stream()` (T92): 20 consecutive `bulkTransfer` reads returning `< 0` throw `IOException("repeated USB read errors")` so `loop()` reopens the connection (the counter resets on a successful read; a failed `RMVT` poke write is still ignored on Android). `UsbDeviceConnection.bulkTransfer` returns -1 for timeouts too, so this also fires after ~2 s with no video (e.g. drone off), causing a harmless reconnect cycle. Not unit-tested (needs a USB connection); not compiled here either.
- There is no hardware-free test for USB, decoding, or recording. Verify with real goggles (or Diagnostics output) on each platform.

## Needs human verification

Agents run without goggles, Windows or an Android device, so these have only been checked by reading the code. Task history lives in `git log agentherd`.

Desktop (goggles attached):
- Press R a second or more into the stream: the title shows `(waiting for keyframe)` briefly, and the `.h264` plays from its first frame. Note how long the wait is; if keyframes are seconds apart, very short recordings save nothing.
- Press D while streaming and recording: the video keeps updating while the report is open.
- With REC on, press Ctrl-C in the launching terminal (and on macOS `kill <pid>`): exit code 0, `saved <path>` on stderr, the file plays to the end.
- Windows: the same Ctrl-C check from cmd and PowerShell (`saved <path>` must appear in that console), and a double-click launch from Explorer opens no console window.

Android (device + goggles):
- Pause during REC, then resume: one playable file, closed on pause.
- Unplug during REC: file saved, screen shows "Waiting for goggles". Quick replug: the permission dialog appears right away.
- Split-screen/freeform resize re-letterboxes the video.
