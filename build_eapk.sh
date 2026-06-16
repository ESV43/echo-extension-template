#!/bin/bash
set -e

echo "========================================================="
echo "   Building Monochrome Extension for Echo Music App   "
echo "========================================================="

# 1. Setup local writable SDK if not already configured
LOCAL_SDK="/home/esv/android-sdk-local"
if [ ! -d "$LOCAL_SDK" ]; then
    echo "Configuring local SDK environment..."
    mkdir -p "$LOCAL_SDK"
    for dir in /opt/android-sdk/*; do
        name=$(basename "$dir")
        if [ "$name" != "licenses" ] && [ "$name" != "build-tools" ] && [ "$name" != "platforms" ]; then
            ln -sf "$dir" "$LOCAL_SDK/$name"
        fi
    done
    mkdir -p "$LOCAL_SDK/build-tools"
    mkdir -p "$LOCAL_SDK/platforms"
    if [ -d "/opt/android-sdk/build-tools/37.0.0" ]; then
        ln -sf /opt/android-sdk/build-tools/37.0.0 "$LOCAL_SDK/build-tools/37.0.0"
    fi
    mkdir -p "$LOCAL_SDK/licenses"
    echo "Accepting Android SDK licenses..."
    yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$LOCAL_SDK" --licenses
fi

# 2. Write local.properties if missing
if [ ! -f local.properties ]; then
    echo "Writing local.properties..."
    echo "sdk.dir=$LOCAL_SDK" > local.properties
fi

# 3. Clean and build the APK
echo "Running Gradle build..."
chmod +x ./gradlew
./gradlew clean assembleDebug

# 4. Generate the .eapk package
OUTPUT_APK="app/build/outputs/apk/debug/app-debug.apk"
OUTPUT_EAPK="monochrome_music.eapk"

if [ -f "$OUTPUT_APK" ]; then
    cp "$OUTPUT_APK" "$OUTPUT_EAPK"
    echo "---------------------------------------------------------"
    echo "SUCCESS: Created $OUTPUT_EAPK successfully!"
    echo "File size: $(du -sh "$OUTPUT_EAPK" | cut -f1)"
    echo "Location: $(pwd)/$OUTPUT_EAPK"
    echo "---------------------------------------------------------"
    echo "To install it in Echo Music:"
    echo "1. Transfer the $OUTPUT_EAPK file to your Android device."
    echo "2. Rename the extension back to .apk if your file manager doesn't recognize it, or use Echo's built-in installer."
    echo "3. Alternatively, install it directly as an Android APK (since .eapk is a valid signed debug APK)."
    echo "========================================================="
else
    echo "ERROR: Output APK not found at $OUTPUT_APK"
    exit 1
fi
