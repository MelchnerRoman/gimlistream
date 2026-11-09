#!/bin/sh
# Builds a universal Gimlistream.app + Gimlistream.zip in dist/.
set -e
cd "$(dirname "$0")"
export MACOSX_DEPLOYMENT_TARGET=11.0
cargo build --release --target aarch64-apple-darwin
cargo build --release --target x86_64-apple-darwin

VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1) # Cargo.toml is the single version source
APP=dist/Gimlistream.app
rm -rf "$APP" dist/Gimlistream.zip && mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
lipo -create -output "$APP/Contents/MacOS/gimlistream" \
  target/aarch64-apple-darwin/release/gimlistream target/x86_64-apple-darwin/release/gimlistream
# Icon: assets/AppIcon.icns, made from assets/icon-source.jpeg with assets/make-icon.swift + iconutil.
cp assets/AppIcon.icns "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Gimlistream</string>
  <key>CFBundleDisplayName</key><string>Gimlistream</string>
  <key>CFBundleIdentifier</key><string>com.gimlistream</string>
  <key>CFBundleExecutable</key><string>gimlistream</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
# ponytail: ad-hoc signature only; use a Developer ID + notarytool to skip the Gatekeeper warning.
codesign --force --deep -s - "$APP"
ditto -c -k --keepParent "$APP" dist/Gimlistream.zip
