Gimlistream for Windows
=======================

Live video from DJI FPV Goggles V1/V2 on your PC.

One-time setup (USB driver)
---------------------------
Windows needs the WinUSB driver on the goggles' video interface. Gimlistream
shows these steps too, and offers to open Zadig, when it finds the goggles
but can't open them.

1. Power on the goggles and connect them to the PC.
   Use a USB-A to USB-C cable (on a laptop with only USB-C: a USB-C to USB-A
   adapter + A-to-C cable). C-to-C cables often don't work with the goggles.
2. Run zadig.exe (included) and allow the admin prompt.
3. Options > List All Devices.
4. Select the entry whose USB ID is 2CA3 001F 03 (interface 3 of the goggles).
   Double-check the USB ID: don't replace the driver of anything else.
5. Driver: WinUSB, then click "Install Driver" (or "Replace Driver").
6. Unplug and replug the goggles.

Using Gimlistream
-----------------
- Start gimlistream.exe. Windows may warn ("Windows protected your PC")
  because the app isn't signed: click "More info" > "Run anyway".
- Turn on the drone so the goggles have video.
- D: diagnostics report (saved to your Desktop and opened in Notepad).
- Esc: quit.

Zadig
-----
zadig.exe is Zadig 2.9 by Pete Batard / Akeo Consulting, unmodified, licensed
under the GNU GPL v3. Source code: https://github.com/pbatard/libwdi
Website: https://zadig.akeo.ie
