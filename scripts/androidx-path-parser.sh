#!/usr/bin/env bash

# Resolve the official Google AndroidX Core implementation used to parse Material icon paths,
# then stage only PathParser and its two implementation classes for the hand-built Xposed APKs.
# The Compose build consumes the normal Maven dependency directly.
prepare_androidx_path_parser() {
    local output_root="$1"
    local version="1.18.0"
    local cache_root="${GRADLE_USER_HOME:-$HOME/.gradle}"
    local aar="${ANDROIDX_CORE_AAR:-}"
    local classes_jar="$output_root/androidx-core-classes.jar"
    local stage="$output_root/androidx-path-parser-classes"
    local result="$output_root/androidx-path-parser.jar"

    if [[ -z "$output_root" || "$output_root" == "/" ]]; then
        echo "Invalid AndroidX staging directory" >&2
        return 1
    fi
    mkdir -p "$output_root" "$stage"
    # Pinned artifact; keeps the hand-built APKs reproducible across CI images.
    local pinned_sha256="311d83ac67d394076ec21d12ed2d10a44b59cb2929b7dce00e5a90a93842e37d"
    output_root="$(cd "$output_root" && pwd)"
    classes_jar="$output_root/androidx-core-classes.jar"
    stage="$output_root/androidx-path-parser-classes"
    result="$output_root/androidx-path-parser.jar"
    if [[ -z "$aar" ]]; then
        aar="$(find "$cache_root/caches/modules-2/files-2.1/androidx.core/core/$version" \
                -type f -name "core-$version.aar" 2>/dev/null | head -n1)"
    fi
    if [[ -z "$aar" || ! -f "$aar" ]]; then
        aar="$output_root/core-$version.aar"
        curl -fsSL --connect-timeout 15 --max-time 120 \
            "https://dl.google.com/dl/android/maven2/androidx/core/core/$version/core-$version.aar" \
            -o "$aar"
    fi
    # An interrupted download must not silently degrade into an empty classes.jar.
    if ! unzip -l "$aar" classes.jar >/dev/null 2>&1; then
        echo "AndroidX core-$version.aar has no classes.jar (bad download?): $aar" >&2
        rm -f "$aar"
        return 1
    fi
    local got_sha256="${ANDROIDX_CORE_SHA256:-$(sha256sum "$aar" | cut -d" " -f1)}"
    if [[ -n "$pinned_sha256" && "$got_sha256" != "$pinned_sha256" ]]; then
        echo "AndroidX core-$version.aar digest mismatch: $got_sha256 != $pinned_sha256" >&2
        return 1
    fi
    unzip -p "$aar" classes.jar > "$classes_jar"
    if [[ ! -s "$classes_jar" ]]; then
        echo "AndroidX core-$version classes.jar extraction produced no data: $aar" >&2
        return 1
    fi
    (
        cd "$stage"
        jar xf "$classes_jar" \
            'androidx/core/graphics/PathParser.class' \
            'androidx/core/graphics/PathParser$ExtractFloatResult.class' \
            'androidx/core/graphics/PathParser$PathDataNode.class'
    )
    jar cf "$result" -C "$stage" androidx/core/graphics
    printf '%s\n' "$result"
}
