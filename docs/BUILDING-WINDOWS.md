# Building on Windows (Git Bash / MSYS2)

The upstream scripts are written for Linux/Termux. This fork additionally builds and
passes the full regression suite on Windows. Verified combination:

| Component | Version used |
|---|---|
| JDK | Temurin 17.0.20 |
| Android SDK | `ANDROID_HOME` with `platforms/android-35` and `build-tools/35.0.0` |
| Shell | Git for Windows Bash (MSYS2) |
| Extra tools | `zip` and `strings` (see below) |

## Required environment

```bash
export ANDROID_SDK_ROOT="/c/Users/<you>/AppData/Local/Android/Sdk"   # or ANDROID_HOME
export AAPT2="$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2.exe"
export D8="$ANDROID_SDK_ROOT/build-tools/35.0.0/d8.bat"
export ZIPALIGN="$ANDROID_SDK_ROOT/build-tools/35.0.0/zipalign.exe"
export APKSIGNER="$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner.bat"
```

`scripts/android-tools.sh` can discover them by itself on Linux, but on Windows the
`build-tools` binaries are named `d8.bat` / `apksigner.bat`, which the POSIX `find`
lookup does not match, so exporting the four variables above is the reliable path.

`zip` and `strings` are used by the packaging step and by
`scripts/test-universal-feature-parity.sh`. Git for Windows ships neither. Either
install them (`pacman -S zip binutils` inside an MSYS2 shell) or drop small shims on
`PATH`; a Python-backed `zip` (append mode) and `strings` shim are sufficient.

## Build

```bash
bash scripts/build-all.sh
```

Products land in `dist/`:

* `deekseep-universal-v1.7.4-fix.apk` — domestic universal build
* `deekseep-google-play-universal-v1.7.4-fix.apk` — Google Play universal build
* `SHA256SUMS.txt`

## Windows portability changes in this fork

1. `-encoding UTF-8` is passed to every `javac` invocation. The sources contain UTF-8
   Chinese comments and string literals, and the Windows JDK otherwise defaults to the
   ANSI code page (`GBK`), which fails the compile.
2. `d8` receives its inputs through an `@argfile` instead of the command line. Passing
   ~1.5k class files as argv exceeds the Windows `CreateProcess` command-line limit
   (`Argument list too long`). Paths written into the argfile are converted with
   `cygpath -w` so the Windows JDK can resolve them.
3. `scripts/android-tools.sh` gains `android_cp_normalize`, a no-op on Linux/macOS that
   rewrites POSIX `:`-separated classpaths into Windows `;`-separated ones. MSYS only
   rewrites argv path lists heuristically, so a classpath that mixes absolute and
   relative entries reached `javac` unconverted and silently resolved nothing.
4. `AgentRunStore.persist` falls back to delete-then-rename when `File.renameTo` cannot
   replace an existing destination. POSIX `rename(2)` replaces atomically, but Windows
   `MoveFile` refuses, which made the durable agent ledger unreadable after a reload.
   The fallback is strictly more robust on Android's emulated/FUSE storage as well.

None of these change the shipped Android behaviour on Linux/Android; they only make the
same sources build and self-test on a Windows host.
