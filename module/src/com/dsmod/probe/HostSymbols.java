package com.dsmod.probe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves host symbols structurally with DexKit instead of trusting a hand-maintained R8 name
 * table, so one build follows a DeepSeek update across channels.
 *
 * <p>Each symbol is anchored on a string that survives obfuscation because it is part of the app's
 * wire contract, then verified by shape before it is used. Verified on mainland 2.3.6 (code249),
 * Google Play 2.3.6 (code250), Google Play 2.3.4 (code246) and 2.5.2 (code274): all four ship the
 * anchors below exactly once.</p>
 *
 * <p>Results are cached per host versionCode, so the dex scan happens once per app update.</p>
 */
final class HostSymbols {
    static final String COMPLETION_REQUEST = "completion_request";

    /**
     * kotlinx.serialization emits the fully qualified @SerialName of the completion request body.
     * It is unique in every build we checked, but it lands on the generated {@code $$serializer},
     * so it is only used as a cross-check of the detected generation.
     */
    private static final String ANCHOR_COMPLETION_REQUEST =
            "com.deepseek.chat.network.chat.model.chat.ChatFullCompletionRequest";

    /**
     * Constructor parameter types of the completion request data class. These are the serialized
     * field types, i.e. the wire contract, so they survive R8 and are identical across the
     * mainland and Google Play channels. Verified unique on mainland 2.3.6 (code249).
     */
    private static final Class<?>[] REQUEST_CTOR_TYPES = {
            String.class, Integer.class, String.class, java.util.ArrayList.class,
            boolean.class, boolean.class, String.class, boolean.class,
            String.class, String.class, int.class
    };
    /** MMKV key holding the signed-in account; identifies the host's account store. */
    private static final String ANCHOR_ACCOUNT_STORE = "key_user_info";
    /** Header the host attaches to every completion call; identifies the PoW plumbing. */
    private static final String ANCHOR_POW_HEADER = "X-DS-PoW-Response";

    private static final String CACHE_FILE =
            "/data/data/com.deepseek.chat/files/deekseep_dexkit_symbols.json";
    private static final String PROBE_FILE =
            "/data/data/com.deepseek.chat/files/deekseep_dexkit_probe.txt";

    private static final Object LOCK = new Object();

    /**
     * DexKit's native engine holds the dex set process-globally, so two bridges open at the same
     * time read each other's classes. Observed: a probe over a Google Play APK returned the
     * installed host's symbols while the host's own resolution was running. Every bridge use is
     * therefore serialised.
     */
    private static final Object DEXKIT_LOCK = new Object();
    private static volatile Map<String, String> symbols = Collections.emptyMap();
    private static volatile boolean loaded;
    private static volatile boolean running;

    private HostSymbols() {}

    /** Resolved host class name for a logical symbol, or null when unresolved. */
    static String get(String logicalName) {
        return symbols.get(logicalName);
    }

    static int resolvedCount() {
        return symbols.size();
    }

    /** Loads the persisted table and, on a cache miss, scans the host dex in the background. */
    static void initializeAsync(final Context hostContext, final String[] apkPaths,
                                final int versionCode) {
        if (hostContext == null) return;
        synchronized (LOCK) {
            if (running || loaded) return;
            running = true;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Map<String, String> cached = readCache(versionCode);
                    if (!cached.isEmpty()) {
                        symbols = cached;
                        loaded = true;
                        Main.log("host symbols: loaded " + cached.size()
                                + " resolved entries from cache for code" + versionCode);
                    } else {
                        Map<String, String> resolved = resolve(hostContext, apkPaths);
                        symbols = resolved;
                        loaded = true;
                        writeCache(versionCode, resolved);
                        Main.log("host symbols: resolved " + resolved.size()
                                + " entries with DexKit for code" + versionCode
                                + " " + resolved);
                    }
                } catch (Throwable t) {
                    Main.log("host symbols: resolution failed: " + Main.safeThrowableMessage(t));
                } finally {
                    // Same thread as the resolution so the two DexKit uses cannot interleave.
                    try { probeFromMarker(hostContext); } catch (Throwable ignored) {}
                    synchronized (LOCK) { running = false; }
                }
            }
        }, "Deekseep-DexKit-Symbols");
        worker.setDaemon(true);
        worker.start();
    }

    /** Reports what DexKit can resolve for arbitrary APKs; used to validate other channels. */
    static void probe(Context hostContext, String[] apkPaths) {
        if (apkPaths == null || apkPaths.length == 0) return;
        if (!DexKitSupport.ensureLibrary(hostContext)) {
            Main.extLog("[DEXKIT] probe skipped: library unavailable ("
                    + DexKitSupport.failureReason() + ")");
            return;
        }
        for (String apk : apkPaths) {
            if (apk == null || apk.length() == 0) continue;
            if (!new File(apk).isFile()) {
                Main.extLog("[DEXKIT] probe missing file: " + apk);
                continue;
            }
            DexKitBridge bridge = null;
            long startedAt = SystemClock.elapsedRealtime();
            synchronized (DEXKIT_LOCK) {
            try {
                bridge = DexKitBridge.create(apk);
                if (bridge == null || !bridge.isValid()) {
                    Main.extLog("[DEXKIT] probe invalid bridge for " + apk);
                    continue;
                }
                ClassDataList byShape = findByConstructorShape(bridge);
                String request = null;
                if (byShape != null) {
                    // Report the structural candidate as-is: the live shape check only works for
                    // the installed host, not for the other channels the probe is pointed at.
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < byShape.size() && i < 4; i++) {
                        ClassData candidate = byShape.get(i);
                        String name = candidate == null ? "?" : candidate.getName();
                        if (i > 0) names.append(',');
                        names.append(name);
                        if (request == null) request = name;
                    }
                    Main.extLog("[DEXKIT] probe " + apk
                            + " dex=" + bridge.getDexNum()
                            + " shapeMatch=" + request
                            + " shapeCandidates=" + byShape.size() + "[" + names + "]"
                            + " serializerAnchor=" + lookup(bridge, ANCHOR_COMPLETION_REQUEST)
                            + " account_store=" + lookup(bridge, ANCHOR_ACCOUNT_STORE)
                            + " pow=" + lookup(bridge, ANCHOR_POW_HEADER)
                            + " in " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                } else {
                    Main.extLog("[DEXKIT] probe " + apk + " shape query returned nothing");
                }
            } catch (Throwable t) {
                Main.extLog("[DEXKIT] probe failed for " + apk + ": "
                        + Main.safeThrowableMessage(t));
            } finally {
                if (bridge != null) try { bridge.close(); } catch (Throwable ignored) {}
            }
            }
        }
    }

    /** Runs the probe when the marker file lists APK paths, one per line. */
    static void probeFromMarker(Context hostContext) {
        try {
            File marker = new File(PROBE_FILE);
            if (!marker.isFile()) return;
            java.util.List<String> paths = new java.util.ArrayList<>();
            BufferedReader reader = new BufferedReader(new FileReader(marker));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.length() > 0 && !trimmed.startsWith("#")) paths.add(trimmed);
                }
            } finally {
                reader.close();
            }
            Main.extLog("[DEXKIT] probe marker lists " + paths.size() + " apk(s)");
            probe(hostContext, paths.toArray(new String[0]));
        } catch (Throwable t) {
            Main.extLog("[DEXKIT] probe marker failed: " + Main.safeThrowableMessage(t));
        }
    }

    // ── resolution ───────────────────────────────────────────────────────────

    private static Map<String, String> resolve(Context hostContext, String[] apkPaths) {
        Map<String, String> out = new HashMap<>();
        if (!DexKitSupport.ensureLibrary(hostContext)) {
            Main.log("host symbols: DexKit unavailable (" + DexKitSupport.failureReason() + ")");
            return out;
        }
        String[] paths = apkPaths;
        if (paths == null || paths.length == 0) return out;

        DexKitBridge bridge = null;
        synchronized (DEXKIT_LOCK) {
        try {
            for (String path : paths) {
                if (path == null || path.length() == 0) continue;
                DexKitBridge candidate = DexKitBridge.create(path);
                if (candidate != null && candidate.isValid()) {
                    bridge = candidate;
                    break;
                }
            }
            if (bridge == null) return out;

            ClassDataList byShape = findByConstructorShape(bridge);
            String request = null;
            if (byShape != null) {
                for (int i = 0; i < byShape.size(); i++) {
                    ClassData candidate = byShape.get(i);
                    String name = candidate == null ? null : candidate.getName();
                    if (name != null && isCompletionRequest(name)) {
                        request = name;
                        break;
                    }
                }
            }
            String anchored = lookup(bridge, ANCHOR_COMPLETION_REQUEST);
            if (request != null) {
                out.put(COMPLETION_REQUEST, request);
            }
            Main.log("host symbols: shapeCandidates=" + (byShape == null ? -1 : byShape.size())
                    + " request=" + request + " serializerAnchor=" + anchored
                    + " (anchor names the generated $$serializer, not the data class)");
        } catch (Throwable t) {
            Main.log("host symbols: resolve failed: " + Main.safeThrowableMessage(t));
        } finally {
            if (bridge != null) try { bridge.close(); } catch (Throwable ignored) {}
        }
        }
        return out;
    }

    /**
     * Finds the completion request class by the shape of its constructor alone. No obfuscated name
     * and no per-channel table is involved, which is what makes a new DeepSeek build resolvable
     * without shipping a module update.
     */
    private static ClassDataList findByConstructorShape(DexKitBridge bridge) {
        try {
            return bridge.findClass(FindClass.create().matcher(ClassMatcher.create().methods(
                    MethodsMatcher.create().add(MethodMatcher.create()
                            .name("<init>").paramTypes(REQUEST_CTOR_TYPES)))));
        } catch (Throwable t) {
            Main.log("host symbols: constructor shape query failed: "
                    + Main.safeThrowableMessage(t));
            return null;
        }
    }

    private static String lookup(DexKitBridge bridge, String anchor) {
        try {
            ClassDataList list = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings(anchor)));
            if (list == null || list.isEmpty()) return null;
            ClassData data = list.get(0);
            return data == null ? null : data.getName();
        } catch (Throwable t) {
            Main.log("host symbols: query for '" + anchor + "' failed: "
                    + Main.safeThrowableMessage(t));
            return null;
        }
    }

    /**
     * The completion request body is a Kotlin data class with an 11 parameter constructor. A string
     * anchor alone is not enough, so the shape is re-checked on the live class loader.
     */
    private static boolean isCompletionRequest(String className) {
        try {
            ClassLoader loader = Main.currentHostClassLoader();
            if (loader == null) return false;
            Class<?> candidate = Class.forName(className, false, loader);
            for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
                Class<?>[] p = constructor.getParameterTypes();
                if (p.length == 11 && p[0] == String.class && p[2] == String.class
                        && p[4] == boolean.class && p[5] == boolean.class
                        && p[7] == boolean.class && p[8] == String.class
                        && p[9] == String.class && p[10] == int.class) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ── cache ────────────────────────────────────────────────────────────────

    private static Map<String, String> readCache(int versionCode) {
        Map<String, String> out = new HashMap<>();
        try {
            File file = new File(CACHE_FILE);
            if (!file.isFile()) return out;
            byte[] buffer = new byte[(int) Math.min(file.length(), 65536)];
            FileInputStream in = new FileInputStream(file);
            int total = 0;
            try {
                while (total < buffer.length) {
                    int read = in.read(buffer, total, buffer.length - total);
                    if (read < 0) break;
                    total += read;
                }
            } finally {
                in.close();
            }
            JSONObject root = new JSONObject(new String(buffer, 0, total, StandardCharsets.UTF_8));
            if (root.optInt("versionCode", -1) != versionCode) return new HashMap<>();
            JSONObject entries = root.optJSONObject("symbols");
            if (entries == null) return out;
            java.util.Iterator<String> keys = entries.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = entries.optString(key, null);
                if (value != null && value.length() > 0) out.put(key, value);
            }
        } catch (Throwable ignored) {
            return new HashMap<>();
        }
        return out;
    }

    private static void writeCache(int versionCode, Map<String, String> resolved) {
        try {
            JSONObject entries = new JSONObject();
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                entries.put(entry.getKey(), entry.getValue());
            }
            JSONObject root = new JSONObject();
            root.put("versionCode", versionCode);
            root.put("symbols", entries);
            root.put("resolvedAt", System.currentTimeMillis());
            File target = new File(CACHE_FILE);
            File tmp = new File(CACHE_FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (target.exists() && !target.delete()) return;
            tmp.renameTo(target);
        } catch (Throwable ignored) {}
    }

    /** Convenience for callers that already hold the host package info. */
    static int hostVersionCode(PackageInfo info) {
        if (info == null) return -1;
        return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
    }
}
