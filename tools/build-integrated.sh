#!/bin/bash
# Build the experimental arm64 Casa APK from the pinned original projects.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
TASK_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$TASK_ROOT"
python3 tools/upstreams.py verify
mkdir -p .integration dist
TASK_NATIVE_PIN=$(sha256sum upstream.lock.json tools/build-integrated.sh | sha256sum | cut -d' ' -f1)
TASK_NATIVE_AAR="$TASK_ROOT/upstream/tailscale-android/android/libs/libtailscale.aar"
if [[ ! -f "$TASK_NATIVE_AAR" || ! -f .integration/native.sha256 || "$(cat .integration/native.sha256)" != "$TASK_NATIVE_PIN" ]]; then
    (
        cd upstream/tailscale-android
        export ANDROID_SDK_ROOT="$ANDROID_HOME"
        export PATH="$PWD/tool:$PWD/android/build/go/bin:$PATH"
        make version "$PWD/android/build/go/bin/gomobile"
        mkdir -p android/libs
        android/build/go/bin/gomobile bind -target android/arm64 -androidapi 26 \
            -tags "$(./build-tags.sh) ts_omit_cachenetmap" \
            -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384 $(./version-ldflags.sh)" \
            -o "$TASK_NATIVE_AAR" ./libtailscale
    )
    printf '%s\n' "$TASK_NATIVE_PIN" > .integration/native.sha256
fi
python3 tools/prepare-integrated.py
TASK_GRADLE_ARGS=(
    -p .integration/home-assistant-android -PnoStrictMode=true -Pabis=arm64-v8a
    --no-parallel --max-workers=2 --no-configuration-cache --console=plain
    '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'
)
# Format in a separate invocation so source rewriting cannot race KSP workers.
.integration/home-assistant-android/gradlew "${TASK_GRADLE_ARGS[@]}" :app:ktlintFormat
.integration/home-assistant-android/gradlew "${TASK_GRADLE_ARGS[@]}" \
    :app:assembleMinimalDebug :app:testMinimalDebugUnitTest \
    --tests 'io.homeassistant.companion.android.hatail.*' --write-locks
cp .integration/home-assistant-android/app/build/outputs/apk/minimal/debug/app-minimal-debug.apk \
    dist/hatailapk-casa-debug.apk
sha256sum dist/hatailapk-casa-debug.apk
