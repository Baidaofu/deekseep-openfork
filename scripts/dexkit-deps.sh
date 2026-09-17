#!/usr/bin/env bash
#
# Stage DexKit and its runtime dependencies for the hand-built module APKs.
#
# DexKit is what lets the module locate DeepSeek symbols structurally instead of relying only on
# a hard-coded R8 name table, so one module build can follow a host update across channels.
# It is Kotlin, so kotlin-stdlib (and flatbuffers for its query encoding) are runtime deps, and
# it does NOT load its own native library: the integrator must System.loadLibrary("dexkit").
#
# Usage:  DEXKIT_JARS="$(prepare_dexkit_deps "$OUT")"
#         ... -cp "$ANDROID_JAR:$ANDROIDX_PATH_PARSER_JAR:$DEXKIT_JARS"
# Side effect: writes "$OUT/lib/<abi>/libdexkit.so" for packaging.

DEXKIT_VERSION="2.0.6"
DEXKIT_AAR_SHA256="1067b6478eb5b4072f0df67b341871f2bba08a7fb6c13aa33d5ca6adadfacb82"
KOTLIN_STDLIB_VERSION="1.5.0"
KOTLIN_STDLIB_SHA256="52283996fe4067cd7330288b96ae67ecd463614dc741172c54d9d349ab6a9cd7"
FLATBUFFERS_VERSION="23.5.26"
FLATBUFFERS_SHA256="8d10cac2ea9878896077ba437d76fdb1b9a07f55a863c560bb8a024b04103f8b"

# Every ABI DexKit publishes. The manifest keeps extractNativeLibs=true, so the loader can pull
# the library out of the APK on any of them.
DEXKIT_ABIS="arm64-v8a armeabi-v7a x86 x86_64"

dexkit_cache_dir() {
    local root
    root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    printf '%s\n' "${DEXKIT_CACHE_DIR:-$root/build-deps}"
}

# fetch <url> <dest> <sha256>
dexkit_fetch() {
    local url="$1" dest="$2" want="$3"
    if [[ -f "$dest" ]] && printf '%s  %s\n' "$want" "$dest" | sha256sum -c - >/dev/null 2>&1; then
        return 0
    fi
    mkdir -p "$(dirname "$dest")"
    rm -f "$dest"
    if ! curl -fsSL --connect-timeout 20 --max-time 300 "$url" -o "$dest"; then
        echo "Failed to download $url" >&2
        return 1
    fi
    if ! printf '%s  %s\n' "$want" "$dest" | sha256sum -c - >/dev/null 2>&1; then
        echo "Digest mismatch for $(basename "$dest")" >&2
        rm -f "$dest"
        return 1
    fi
    return 0
}

dexkit_maven_url() {
    printf 'https://repo1.maven.org/maven2/%s\n' "$1"
}

# prepare_dexkit_deps <staging-dir>; prints a ':'-separated jar list for javac/d8.
prepare_dexkit_deps() {
    local out="$1"
    local cache
    cache="$(dexkit_cache_dir)"

    if [[ -z "$out" || "$out" == "/" ]]; then
        echo "Invalid DexKit staging directory" >&2
        return 1
    fi
    mkdir -p "$out" "$cache"
    out="$(cd "$out" && pwd)"

    local aar="$cache/dexkit-$DEXKIT_VERSION.aar"
    local kotlin="$cache/kotlin-stdlib-$KOTLIN_STDLIB_VERSION.jar"
    local flatbuffers="$cache/flatbuffers-java-$FLATBUFFERS_VERSION.jar"

    dexkit_fetch "$(dexkit_maven_url "org/luckypray/dexkit/$DEXKIT_VERSION/dexkit-$DEXKIT_VERSION.aar")" \
        "$aar" "$DEXKIT_AAR_SHA256" || return 1
    dexkit_fetch "$(dexkit_maven_url "org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_STDLIB_VERSION/kotlin-stdlib-$KOTLIN_STDLIB_VERSION.jar")" \
        "$kotlin" "$KOTLIN_STDLIB_SHA256" || return 1
    dexkit_fetch "$(dexkit_maven_url "com/google/flatbuffers/flatbuffers-java/$FLATBUFFERS_VERSION/flatbuffers-java-$FLATBUFFERS_VERSION.jar")" \
        "$flatbuffers" "$FLATBUFFERS_SHA256" || return 1

    local classes="$out/dexkit-classes.jar"
    if ! unzip -p "$aar" classes.jar > "$classes" 2>/dev/null || [[ ! -s "$classes" ]]; then
        echo "DexKit AAR has no classes.jar (bad download?): $aar" >&2
        return 1
    fi

    # Native library per ABI, packaged under lib/ so the platform extracts it on install.
    local abi
    for abi in $DEXKIT_ABIS; do
        mkdir -p "$out/lib/$abi"
        if ! unzip -p "$aar" "jni/$abi/libdexkit.so" > "$out/lib/$abi/libdexkit.so" 2>/dev/null \
                || [[ ! -s "$out/lib/$abi/libdexkit.so" ]]; then
            echo "DexKit AAR is missing jni/$abi/libdexkit.so" >&2
            return 1
        fi
    done

    printf '%s:%s:%s\n' "$classes" "$kotlin" "$flatbuffers"
}
