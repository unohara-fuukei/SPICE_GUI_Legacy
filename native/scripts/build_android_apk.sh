#!/usr/bin/env bash
# Build the native SPICE GUI APK for Android 10+ (arm64, targetSdk 29).
# Usage: bash native/scripts/build_android_apk.sh [output.apk]
set -euo pipefail

SDK=/home/user/android-sdk
NDK=$SDK/ndk/29.0.13846066
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-36/android.jar
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/android/SPICE_GUI-native-arm64.apk}"
KEYSTORE="$ROOT/android/debug.keystore"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

cd "$ROOT"
source ~/.cargo/env
echo "==> cargo build (aarch64-linux-android, release)"
cargo build --release --target aarch64-linux-android --features android
SO="target/aarch64-linux-android/release/libspice_native.so"

echo "==> aapt2 link (targetSdk 29)"
"$BT/aapt2" link -I "$JAR" \
    --manifest android/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 29 \
    --version-code 1 --version-name 0.1.0 \
    -o "$STAGE/base.apk"

echo "==> add native lib"
cp "$SO" "$STAGE/libspice_native.so"
(cd "$STAGE" && zip -q base.apk -d "lib/*" || true; mkdir -p lib/arm64-v8a && mv libspice_native.so lib/arm64-v8a/ && zip -q base.apk lib/arm64-v8a/libspice_native.so)

echo "==> zipalign"
"$BT/zipalign" -f 4 "$STAGE/base.apk" "$STAGE/aligned.apk"

echo "==> sign"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias spice -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SPICE Native Debug,O=SPICE,C=CN"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$STAGE/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT" | head -2

mkdir -p "$(dirname "$OUT")"
echo "==> done: $OUT ($(du -h "$OUT" | cut -f1))"
