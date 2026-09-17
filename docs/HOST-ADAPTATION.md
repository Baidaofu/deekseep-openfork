# Host adaptation

DeepSeek is minified with R8, so every release can rename every host class while keeping the
contract intact. The module has two mechanisms for that: **DexKit symbol resolution** (added by
this fork) and **rollout/gray-flag auto-discovery** (rewritten by this fork). Both are additive —
the original per-generation symbol table is still there as a fallback.

## DexKit symbol resolution

`HostSymbols` opens the installed host APK with [DexKit](https://github.com/LuckyPray/DexKit) and
locates host symbols structurally, cached per host `versionCode` in
`/data/data/com.deepseek.chat/files/deekseep_dexkit_symbols.json`.

| Symbol | Query | Verified on |
|---|---|---|
| completion request body | class declaring a constructor with the 11 serialized field types (`String, Integer, String, ArrayList, boolean, boolean, String, boolean, String, String, int`) | unique match on all four builds below |

The constructor parameter list is the **serialized wire contract**, so it survives obfuscation and
is identical across channels. The kotlinx `@SerialName`
(`com.deepseek.chat.network.chat.model.chat.ChatFullCompletionRequest`) is unique in every build
too, but it lands on the generated `$$serializer`, so it is only used to cross-check the detected
generation.

Measured on a Nothing A142 (Android 16, LSPosed 2.2.0) inside the DeepSeek process:

| APK | `versionCode` | completion request | kotlin serializer | account store | PoW | time |
|---|---|---|---|---|---|---|
| mainland 2.3.6 (installed) | 249 | `nx0` | `lx0` | – | – | 20 ms |
| Google Play 2.3.6 | 250 | `gz0` | `ez0` | `k5` | `lu0` | 269 ms |
| Google Play 2.3.4 | 246 | `gz0` | `ez0` | `k5` | `l43` | 265 ms |
| APKPure 2.5.2 | 274 | `r51` | `p51` | `j5` | `ck1` | 193 ms |

The last row is the interesting one: **2.5.2 has no entry in the module's symbol table at all**,
yet the structural query resolves it, which is what makes a host update survivable without a module
release. Google Play builds share one R8 map (`gz0`), the mainland build uses another (`nx0`).

`NativeBridge` prefers the resolved class and falls back to the table, and verifies the 11-parameter
shape on the live class loader before use, so a wrong guess degrades instead of crashing the host.

### Native library loading

DexKit is Kotlin and does not load its own native engine, so the module calls
`System.loadLibrary("dexkit")` itself. Two details matter:

* `lib/<abi>/libdexkit.so` must be **stored uncompressed** in the APK (the build does
  `zip -0`) so `zipalign -p 4` can page-align it; a deflated library cannot be mapped from inside
  the APK and loading fails with `UnsatisfiedLinkError`.
* If the framework ever stops exposing the module's library directory, `DexKitSupport` falls back
  to extracting the library from the module APK into the host's private files directory.

DexKit's native engine keeps the dex set **process-globally**, so two bridges alive at once read
each other's classes. Every bridge use is therefore serialised on one lock, and the multi-APK probe
runs on the same thread after resolution.

## Rollout (gray) feature auto-discovery

`RemoteFeatureFlags` no longer shows a fixed list. The visible list is the union of:

1. the curated entries (labels, descriptions and inverted semantics kept exactly as before),
2. every `kv_remote_settings_*` key compiled into the host dex, found by `HostDexStrings`,
3. every such key the host has actually stored, reported by `preferences.contains()`.

`HostDexStrings` is a dependency-free DEX string-pool reader (~210 lines): it walks
`string_ids` from the header, skips the ULEB128 length, decodes MUTF-8 and filters on raw bytes
before allocating. Measured offline: 146 keys on mainland 2.3.6 and Google Play 2.3.6, 144 on
Google Play 2.3.4, 168 on 2.5.2, ~13 ms per APK.

On device (mainland 2.3.6):

```
features: curated=11 visible=11 discovered=0 overridable=11      (before the dex scan)
features: after dex scan visible=147 discovered=136 overridable=11
```

The key list is cached in `/data/data/com.deepseek.chat/files/deekseep_feature_catalog.json`,
invalidated by host `versionCode`.

### Why discovered entries are read-only

Values are read live from the host's own MMKV, and only **Booleans** can be forced on/off, because
writing a Boolean into a key the host reads as an integer would break the host. Determining the
type of a discovered key turned out to be impossible through this host's MMKV:

* `getAll()` throws `UnsupportedOperationException: Intentionally Not Supported. Use allKeys()
  instead, getAll() not implement because type-erasure inside mmkv`.
* `allKeys()` is a *private native* method in this build — R8 removed the public wrapper.
* The typed getters (`getBoolean`, `getString`, …) do **not** validate the stored type: probing the
  112 stored keys with `string → int → long → float → boolean` reported 112 strings, and with the
  reverse order reported 112 booleans.

`refreshCatalog` detects that "every key resolved to the same type" case and marks discovered
entries `UNKNOWN` rather than guessing, so they are listed and read as present, but not writable.
The curated Boolean entries keep working exactly as before.

Two ways to lift the limitation, if wanted later:

1. parse the MMKV file format directly (the module runs as the host app, so
   `/data/data/com.deepseek.chat/files/mmkv/mmkv.default` is readable), or
2. infer each key's type from its read site in the host dex, e.g. a DexKit batch string query over
   the 146 keys looking at which typed getter the enclosing method invokes.

## Reproducing on another channel

`HostSymbols` exposes a debug hook: a marker file listing APK paths, one per line, makes the module
report what DexKit resolves for each of them **without installing them**:

```bash
# push the APKs somewhere the DeepSeek process can read, then
printf '%s\n' /data/data/com.deepseek.chat/files/dexkit-probe/*.apk \
  > /data/data/com.deepseek.chat/files/deekseep_dexkit_probe.txt
# restart DeepSeek, then
grep DEXKIT /data/data/com.deepseek.chat/files/deekseep_vision.log
```

That is how the Google Play and 2.5.2 rows above were produced on a device that has only the
mainland build installed.
