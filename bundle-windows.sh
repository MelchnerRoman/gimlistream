#!/bin/sh
# Builds dist/Gimlistream-windows.zip (gimlistream.exe + zadig.exe + README.txt) from macOS.
# Needs: brew install mingw-w64; rustup target add x86_64-pc-windows-gnu
set -e
cd "$(dirname "$0")"
T=x86_64-pc-windows-gnu

# Link MinGW's C++ runtime (used by openh264) and libgcc/winpthread statically: no DLLs needed next to the .exe.
export CXXSTDLIB_x86_64_pc_windows_gnu=static=stdc++
export CARGO_TARGET_X86_64_PC_WINDOWS_GNU_RUSTFLAGS="-L native=$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libstdc++.a)") -C link-arg=-static"
cargo build --release --target $T

# Fail if the .exe still wants a MinGW DLL a normal Windows PC doesn't have.
if x86_64-w64-mingw32-objdump -p target/$T/release/gimlistream.exe | grep -iE "DLL Name: (libstdc|libgcc|libwinpthread)"; then
  echo "error: exe depends on MinGW DLLs" >&2; exit 1
fi

# Zadig (GPLv3, redistributed unmodified; source link in README): pinned to the Akeo-signed 2.9 release.
ZADIG_URL=https://github.com/pbatard/libwdi/releases/download/v1.5.1/zadig-2.9.exe
ZADIG_SHA256=4ecaa95df3da3621486a043aef8b3050b8bafe7c901402871e816229ef82039b
mkdir -p target/zadig
[ -f target/zadig/zadig.exe ] || curl -fsSL -o target/zadig/zadig.exe "$ZADIG_URL"
echo "$ZADIG_SHA256  target/zadig/zadig.exe" | shasum -a 256 -c -

OUT=dist/Gimlistream-windows
rm -rf "$OUT" "$OUT.zip" && mkdir -p "$OUT"
cp target/$T/release/gimlistream.exe target/zadig/zadig.exe "$OUT/"
cp assets/README-windows.txt "$OUT/README.txt"
(cd dist && zip -qr Gimlistream-windows.zip Gimlistream-windows)
rm -rf "$OUT"
