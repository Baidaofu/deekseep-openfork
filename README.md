# deekseep-openfork

An LSPosed/Xposed module for the official DeepSeek Android app, forked from
[lllucccian/Deekseep](https://github.com/lllucccian/Deekseep) `v1.7.4-fix` (Open edition).

This fork is maintained independently. It is **not** the upstream project, and it is not
affiliated with DeepSeek, High-Flyer, LSPosed, libxposed or Xposed.

[简体中文](README_CN.md)

---

## What this fork changes

| Change | Detail |
|---|---|
| **Open Local API** | Upstream ships the Local API only as a closed, server-keyed payload in its Closed edition. This fork implements it as plain buildable sources: an OpenAI- and Anthropic-compatible HTTP endpoint served from inside the DeepSeek process, reusing the host's own authenticated transport and PoW. → [docs/LOCAL-API.md](docs/LOCAL-API.md) |
| **DexKit host adaptation** | Host symbols are located structurally instead of by a per-channel R8 name table, so a DeepSeek update can be followed without a module release. Verified against mainland 2.3.6, Google Play 2.3.6/2.3.4 and 2.5.2 (which the name table does not cover at all). → [docs/HOST-ADAPTATION.md](docs/HOST-ADAPTATION.md) |
| **Gray-flag auto-discovery** | The rollout/gray feature manager no longer shows a fixed 11-entry list: it unions the curated entries with every `kv_remote_settings_*` key compiled into the installed host (147 entries on mainland 2.3.6, 136 of them discovered). → [docs/HOST-ADAPTATION.md](docs/HOST-ADAPTATION.md) |
| **Windows host build** | Encoding, `d8` argv limit, classpath conversion and `renameTo` fixes, so `scripts/build-all.sh` and the whole regression suite pass under Git Bash / MSYS2. → [docs/BUILDING-WINDOWS.md](docs/BUILDING-WINDOWS.md) |
| **Working CI** | The upstream workflow failed on every run: `android-actions/setup-android` tries to install the long-removed `tools` package and `sdkmanager` exits 1. Replaced with a direct SDK install; the workflow is green on this fork. |
| **Build robustness** | `scripts/androidx-path-parser.sh` now verifies the pinned AAR digest and aborts on a truncated download instead of silently compiling against an empty `classes.jar`. |

The feature core itself is upstream's work and is unchanged apart from the fixes listed above.

## Verified on hardware

| | |
|---|---|
| Device | Nothing Phone (A142), Android 16 |
| Framework | KernelSU + LSPosed 2.2.0 |
| Host app | DeepSeek 2.3.6, `versionCode` 249 (mainland) |

Only that combination was exercised end to end. Every other supported host version is
inherited from upstream and untested here.

## Local API

Enable it from the module hub: **本地 API · 实验性 / Local API · Experimental**.

```bash
# OpenAI-compatible
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Authorization: Bearer $DEEKSEEP_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"hi"}]}'

# Anthropic-compatible (Claude Code)
export ANTHROPIC_BASE_URL=http://127.0.0.1:8765
export ANTHROPIC_AUTH_TOKEN=$DEEKSEEP_KEY
```

Routes: `/v1/models`, `/v1/chat/completions`, `/v1/responses` in OpenAI mode; `/v1/messages`
and `/v1/messages/count_tokens` in Anthropic mode. Default listener `http://127.0.0.1:8765`.
See [docs/LOCAL-API.md](docs/LOCAL-API.md) for configuration, the request flow and the known gaps.

## Features

Inherited from the upstream feature core, unchanged: account and privacy, chat and history,
appearance, backup and restore, global search, notifications, process management, remote
feature flags, Agent tooling, diagnostics, and the compatibility layer for DeepSeek 2.2.x
through 2.3.6. See [docs/FEATURES.md](docs/FEATURES.md) and [DISCLAIMER.md](DISCLAIMER.md).

## Install

1. Install and configure LSPosed/Xposed first, then enable its module scope.
2. Install an APK from [Releases](../../releases).
3. Enable **Deekseep** in LSPosed and scope it to `com.deepseek.chat` **only**.
4. Force-stop and reopen DeepSeek. Reboot only if your framework does not reload the target process.

Release APKs are signed with a locally generated development key (`module/debug.keystore`, not
committed). Re-sign with your own key before distributing anything.

## Build

```bash
# Linux / CI: JDK 17, Android SDK platform 35 and build-tools 35.0.0
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash scripts/build-all.sh          # builds both channels and runs every regression test
```

Windows (Git Bash / MSYS2) needs a few extra environment variables — see
[docs/BUILDING-WINDOWS.md](docs/BUILDING-WINDOWS.md).

Products land in `dist/` with `SHA256SUMS.txt`.

## Known gaps

* `usage` token counters are always `0`; the native DeepSeek stream carries no usage block.
* Each request uses a throwaway session, so there is no cross-request conversation reuse yet.
* `forceReasoning`, `longContextRelay` and `antiCensor` are persisted and shown in the UI but
  are not applied to the native request.
* `https: true` (the module's per-device CA) has not been exercised on a device.

## Upstream and licence

Forked from [lllucccian/Deekseep](https://github.com/lllucccian/Deekseep) at `v1.7.4-fix`.
The feature core, the Closed edition and its release process belong to that project. Its
community channels (QQ group, Telegram) and sponsorship are run by the upstream author, not
by this fork.

Licensed under [GPL-3.0-only](LICENSE), as the upstream licence requires. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
