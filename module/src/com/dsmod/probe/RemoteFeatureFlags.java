package com.dsmod.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Manages DeepSeek's native local overrides for verified boolean feature settings. */
final class RemoteFeatureFlags {
    static final int FORCE_OFF = -1;
    static final int FOLLOW = 0;
    static final int FORCE_ON = 1;

    static final String CONFIG_FILE =
            "/data/data/com.deepseek.chat/files/deekseep_remote_feature_overrides.json";
    static final String ATTACHMENT_GUIDE_PROMPTS =
            "deekseep_model_config_attachment_guide_prompts";

    /** Discovered rollout catalog; written once per host build. */
    static final String CATALOG_FILE =
            "/data/data/com.deepseek.chat/files/deekseep_feature_catalog.json";
    private static final String DISCOVERED_PREFIX = "kv_remote_settings_";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_UNKNOWN = "unknown";
    private static final String HOST_PACKAGE = "com.deepseek.chat";

    static final class Feature {
        final String key;
        final String zh;
        final String en;
        final String detailZh;
        final String detailEn;
        final boolean inverted;
        final boolean nativeBoolean;

        Feature(String key, String zh, String en, String detailZh, String detailEn,
                boolean inverted, boolean nativeBoolean) {
            this.key = key;
            this.zh = zh;
            this.en = en;
            this.detailZh = detailZh;
            this.detailEn = detailEn;
            this.inverted = inverted;
            this.nativeBoolean = nativeBoolean;
        }
    }

    /*
     * Most entries are DeepSeek Boolean settings. The attachment guide is a 2.3.4 model-config
     * rollout rather than a standalone Boolean key, but is kept in the same manager because it is
     * presented and overridden with the same three-state contract.
     */
    static final Feature[] FEATURES = {
            feature("conversation_search_enabled", "会话搜索", "Conversation search",
                    "控制 DeepSeek 自带的会话搜索入口。",
                    "Controls DeepSeek's built-in conversation search entry."),
            feature("show_new_chat_button_above_input", "输入框上方新建对话",
                    "New chat above input",
                    "控制输入区域上方的原生新建对话按钮。",
                    "Controls the native new-chat button above the composer."),
            feature("voice_input_enabled", "语音输入", "Voice input",
                    "控制 DeepSeek 自带的语音输入能力。",
                    "Controls DeepSeek's built-in voice input."),
            invertedFeature("hide_assistant_avatar", "显示助手头像", "Show assistant avatar",
                    "开启时显示聊天页的助手头像。",
                    "Shows assistant avatars in chat when enabled."),
            feature("copy_text_without_markdown_syntax", "复制纯文本",
                    "Copy without Markdown",
                    "复制消息时移除 Markdown 标记。",
                    "Removes Markdown syntax when copying a message."),
            feature("select_text_without_markdown_syntax", "选择纯文本",
                    "Select without Markdown",
                    "选择消息文字时使用移除 Markdown 后的文本。",
                    "Uses text without Markdown syntax for text selection."),
            feature("optimize_markdown", "Markdown 优化", "Markdown optimization",
                    "控制 DeepSeek 的新版 Markdown 渲染优化。",
                    "Controls DeepSeek's optimized Markdown renderer."),
            feature("sse_auto_scroll_one_screen", "流式回复整屏跟随",
                    "One-screen stream follow",
                    "控制流式生成时的一屏自动滚动策略。",
                    "Controls one-screen auto scrolling while streaming."),
            feature("allow_file_with_search", "联网搜索允许文件", "Files with web search",
                    "控制上传文件与联网搜索能否同时使用。",
                    "Controls whether files and web search can be used together."),
            feature("disable_single_dollar_latex", "禁用单美元公式",
                    "Disable single-dollar LaTeX",
                    "不把单个美元符号包裹的内容解析为公式。",
                    "Prevents single-dollar spans from being parsed as LaTeX."),
            modelFeature(ATTACHMENT_GUIDE_PROMPTS, "上传图片候选语句",
                    "Attachment prompt suggestions",
                    "上传图片或文件后，在输入框上方显示原生候选语句。",
                    "Shows DeepSeek's native prompt suggestions above the composer after "
                            + "an image or file is attached.")
    };

    private static final Object LOCK = new Object();
    private static volatile Map<String, Integer> modes = Collections.emptyMap();
    private static volatile Map<String, Boolean> legacyServerValues = Collections.emptyMap();
    private static volatile boolean loaded;
    private static volatile boolean installed;
    private static volatile boolean migrated;
    private static volatile int loadedFormatVersion;

    /** Curated entries followed by every rollout key discovered in the installed host. */
    private static volatile Feature[] visible = FEATURES;
    private static volatile Map<String, String> discoveredTypes = Collections.emptyMap();
    private static volatile Map<String, String> cachedCatalogTypes = Collections.emptyMap();
    private static volatile int catalogVersionCode = -1;
    private static volatile boolean catalogLoaded;
    private static volatile int refreshedForVersionCode = -1;
    private static volatile int cachedHostVersionCode = -1;
    private static volatile Context cachedHostContext;
    private static volatile boolean discoveryStarted;

    private RemoteFeatureFlags() {}

    private static Feature feature(String suffix, String zh, String en,
            String detailZh, String detailEn) {
        return new Feature("kv_remote_settings_" + suffix, zh, en, detailZh, detailEn,
                false, true);
    }

    private static Feature invertedFeature(String suffix, String zh, String en,
            String detailZh, String detailEn) {
        return new Feature("kv_remote_settings_" + suffix, zh, en, detailZh, detailEn,
                true, true);
    }

    private static Feature modelFeature(String key, String zh, String en,
            String detailZh, String detailEn) {
        return new Feature(key, zh, en, detailZh, detailEn, false, false);
    }

    static String localKey(String remoteKey) {
        final String prefix = "kv_remote_settings_";
        if (remoteKey == null || !remoteKey.startsWith(prefix)) return remoteKey;
        return "kv_settings_" + remoteKey.substring(prefix.length());
    }

    static Feature featureForKey(String key) {
        if (key == null) return null;
        for (Feature feature : visible) if (feature.key.equals(key)) return feature;
        return null;
    }

    /** True for any key the module may hold an override for, curated or discovered. */
    static boolean isFeatureKey(String key) {
        if (featureForKey(key) != null) return true;
        return isDiscoveredKey(key);
    }

    /** True when the host stores this key as a Boolean, so a forced value can be written. */
    static boolean isBooleanKey(String key) {
        Feature feature = featureForKey(key);
        if (feature != null) return feature.nativeBoolean;
        return TYPE_BOOLEAN.equals(discoveredTypes.get(key));
    }

    /**
     * Curated entries first, then every {@code kv_remote_settings_*} key found in the installed
     * DeepSeek build. Cheap after the first call: it merges the cached catalog with the live MMKV
     * keys and never scans the dex itself.
     */
    static Feature[] visibleFeatures(ClassLoader loader) {
        ensureCatalog(loader);
        return visible;
    }

    /** Number of rollout keys discovered beyond the curated list. */
    static int discoveredCount(ClassLoader loader) {
        ensureCatalog(loader);
        return Math.max(0, visible.length - FEATURES.length);
    }

    /** Number of entries the user can actually force on or off. */
    static int overridableCount(ClassLoader loader) {
        int count = 0;
        for (Feature feature : visibleFeatures(loader)) {
            if (isOverridable(feature)) count++;
        }
        return count;
    }

    /**
     * Scans the host dex for rollout keys the server has not pushed yet. This is the expensive
     * step, so it runs on a daemon thread and only once per process; {@code onUpdated} is invoked
     * from that background thread when the visible list grew.
     */
    static void discoverAsync(final ClassLoader loader, final Runnable onUpdated) {
        if (discoveryStarted) return;
        discoveryStarted = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (refreshCatalog(loader, true) && onUpdated != null) onUpdated.run();
                } catch (Throwable error) {
                    Main.log("feature rollout discovery failed: " + error);
                }
            }
        }, "Deekseep-feature-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /** Rebuilds the visible list from the cache plus live MMKV; scans the dex only when allowed. */
    private static void ensureCatalog(ClassLoader loader) {
        int versionCode = hostVersionCode();
        if (versionCode > 0 && refreshedForVersionCode == versionCode) return;
        synchronized (LOCK) {
            if (versionCode > 0 && refreshedForVersionCode == versionCode) return;
            refreshCatalog(loader, false);
        }
    }

    private static boolean refreshCatalog(ClassLoader loader, boolean allowDexScan) {
        int versionCode = hostVersionCode();
        if (!catalogLoaded && versionCode > 0) {
            catalogLoaded = true;
            loadCatalog(versionCode);
        }
        LinkedHashMap<String, String> types = new LinkedHashMap<String, String>();
        boolean needDexScan = !(versionCode > 0 && versionCode == catalogVersionCode
                && !cachedCatalogTypes.isEmpty());
        if (!needDexScan) types.putAll(cachedCatalogTypes);

        boolean allowed = needDexScan && allowDexScan;
        if (allowed) {
            Set<String> fromDex = HostDexStrings.stringsWithPrefix(
                    hostSourceDir(), hostSplitSourceDirs(), DISCOVERED_PREFIX);
            for (String key : fromDex) {
                if (!types.containsKey(key)) types.put(key, TYPE_UNKNOWN);
            }
        }

        // Typing runs after the key list exists: MMKV refuses getAll() ("type-erasure inside
        // mmkv") and this host build has no public allKeys() left after R8, so the list comes from
        // the dex and each stored key is probed with the typed getters, which validate the type in
        // native code.
        SharedPreferences preferences = AccountManager.defaultMmkv(loader);
        if (preferences != null) {
            int probed = 0;
            int booleans = 0;
            for (String key : new ArrayList<String>(types.keySet())) {
                try {
                    if (!preferences.contains(key)) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                String type = typeOfKey(preferences, key);
                types.put(key, type);
                probed++;
                if (TYPE_BOOLEAN.equals(type)) booleans++;
            }
            if (probed > 8 && booleans == probed) {
                // Every stored key resolved to the same type, which means the getters are lenient
                // and reported the default instead of validating the stored tag. Do not offer
                // write access on a guess: discovered entries stay read-only.
                for (String key : new ArrayList<String>(types.keySet())) {
                    if (!isCuratedKey(key)) types.put(key, TYPE_UNKNOWN);
                }
                Main.log("feature rollout: probed=" + probed
                        + " keys all reported the same type; the host MMKV getters do not"
                        + " validate types, so discovered entries stay read-only");
            } else {
                Main.log("feature rollout: probed=" + probed + " boolean=" + booleans
                        + " keys=" + types.size());
            }
        }

        if (allowed && versionCode > 0) {
            cachedCatalogTypes = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(types));
            catalogVersionCode = versionCode;
            saveCatalog(versionCode, types);
        }

        // Latch only when the live source was readable; otherwise a call that ran before MMKV
        // was initialised would pin an empty catalog for the rest of the process.
        if (versionCode > 0 && preferences != null) refreshedForVersionCode = versionCode;
        if (types.equals(discoveredTypes)) return false;
        discoveredTypes = Collections.unmodifiableMap(types);
        visible = buildVisible(types);
        return true;
    }

    private static Feature[] buildVisible(Map<String, String> types) {
        ArrayList<Feature> list = new ArrayList<Feature>(FEATURES.length + types.size());
        HashSet<String> curated = new HashSet<String>();
        for (Feature feature : FEATURES) {
            list.add(feature);
            curated.add(feature.key);
        }
        ArrayList<String> discovered = new ArrayList<String>();
        for (String key : types.keySet()) {
            if (!curated.contains(key)) discovered.add(key);
        }
        Collections.sort(discovered);
        for (String key : discovered) {
            String label = key.substring(DISCOVERED_PREFIX.length());
            boolean overridable = TYPE_BOOLEAN.equals(types.get(key));
            // Discovered entries carry no module-written description, so they show the key.
            list.add(new Feature(key, label, label, key, key, false, overridable));
        }
        return list.toArray(new Feature[list.size()]);
    }

    private static boolean isDiscoveredKey(String key) {
        return key != null && key.startsWith(DISCOVERED_PREFIX)
                && key.length() > DISCOVERED_PREFIX.length();
    }

    /**
     * MMKV's typed getters validate the stored type in native code, so the first getter that does
     * not throw identifies the type. Anything ambiguous stays UNKNOWN and therefore read-only.
     */
    private static String typeOfKey(SharedPreferences preferences, String key) {
        // MMKV's SharedPreferences getBoolean() is lenient: it never throws on a type mismatch.
        // decodeString() does validate the stored type tag, so strings are probed first and the
        // numeric getters after; a key that survives none of them stays UNKNOWN (read-only).
        try { preferences.getString(key, null); return TYPE_STRING; } catch (Throwable ignored) {}
        try { preferences.getInt(key, 0); return TYPE_INT; } catch (Throwable ignored) {}
        try { preferences.getLong(key, 0L); return TYPE_LONG; } catch (Throwable ignored) {}
        try { preferences.getFloat(key, 0.0f); return TYPE_FLOAT; } catch (Throwable ignored) {}
        try { preferences.getBoolean(key, false); return TYPE_BOOLEAN; } catch (Throwable ignored) {}
        return TYPE_UNKNOWN;
    }

    private static boolean isCuratedKey(String key) {
        for (Feature feature : FEATURES) {
            if (feature.key.equals(key)) return true;
        }
        return false;
    }

    private static String typeOf(Object value) {
        if (value instanceof Boolean) return TYPE_BOOLEAN;
        if (value instanceof Integer) return TYPE_INT;
        if (value instanceof Long) return TYPE_LONG;
        if (value instanceof Float) return TYPE_FLOAT;
        if (value instanceof String) return TYPE_STRING;
        return TYPE_UNKNOWN;
    }

    private static int hostVersionCode() {
        int cached = cachedHostVersionCode;
        if (cached > 0) return cached;
        int looked = lookupHostVersionCode();
        if (looked > 0) cachedHostVersionCode = looked;
        return looked;
    }

    private static int lookupHostVersionCode() {
        Context context = hostContext();
        if (context == null) return -1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(HOST_PACKAGE, 0);
            if (info == null) return -1;
            if (Build.VERSION.SDK_INT >= 28) return (int) info.getLongVersionCode();
            return info.versionCode;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * The listener runs inside the DeepSeek process, so its own Application is the host one. Main
     * keeps a private reference as well; both are tried so ordering does not matter.
     */
    private static Context hostContext() {
        Context context = cachedHostContext;
        if (context != null) return context;
        Context found = null;
        // Main assigns this before it calls enforce(), so it is the reliable source. The
        // ActivityThread accessor is only a fallback because hidden-API enforcement may refuse it.
        try {
            Field field = Main.class.getDeclaredField("hostApplicationContext");
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Context) found = (Context) value;
        } catch (Throwable ignored) {
        }
        if (found == null) {
            try {
                Object application = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (application instanceof Context) found = (Context) application;
            } catch (Throwable ignored) {
            }
        }
        if (found != null) cachedHostContext = found;
        return found;
    }

    private static String hostSourceDir() {
        Context context = hostContext();
        return context == null ? null : context.getApplicationInfo().sourceDir;
    }

    private static String[] hostSplitSourceDirs() {
        Context context = hostContext();
        return context == null ? null : context.getApplicationInfo().splitSourceDirs;
    }

    private static void loadCatalog(int versionCode) {
        File file = new File(CATALOG_FILE);
        if (!file.isFile() || file.length() <= 0 || file.length() > 256 * 1024L) return;
        FileReader reader = null;
        try {
            reader = new FileReader(file);
            StringBuilder json = new StringBuilder((int) file.length());
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count > 0) json.append(buffer, 0, count);
            }
            JSONObject root = new JSONObject(json.toString());
            if (root.optInt("versionCode", -1) != versionCode) return;
            JSONObject typeMap = root.optJSONObject("types");
            LinkedHashMap<String, String> types = new LinkedHashMap<String, String>();
            if (typeMap != null) {
                Iterator<String> keys = typeMap.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (isDiscoveredKey(key)) {
                        types.put(key, typeMap.optString(key, TYPE_UNKNOWN));
                    }
                }
            }
            cachedCatalogTypes = Collections.unmodifiableMap(types);
            catalogVersionCode = versionCode;
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
        }
    }

    private static void saveCatalog(int versionCode, Map<String, String> types) {
        File target = new File(CATALOG_FILE);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        File temp = new File(CATALOG_FILE + ".tmp");
        FileWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            root.put("format", "deekseep-feature-catalog");
            root.put("versionCode", versionCode);
            JSONArray keys = new JSONArray();
            JSONObject typeMap = new JSONObject();
            for (Map.Entry<String, String> entry : types.entrySet()) {
                keys.put(entry.getKey());
                typeMap.put(entry.getKey(), entry.getValue());
            }
            root.put("keys", keys);
            root.put("types", typeMap);
            writer = new FileWriter(temp, false);
            writer.write(root.toString());
            writer.flush();
            writer.close();
            writer = null;
            if (target.exists() && !target.delete()) return;
            temp.renameTo(target);
        } catch (Throwable ignored) {
        } finally {
            if (writer != null) try { writer.close(); } catch (Throwable ignored) {}
            if (temp.exists()) try { temp.delete(); } catch (Throwable ignored) {}
        }
    }

    private static boolean wasManagedByVersion1(String key) {
        if (featureForKey(key) != null) return true;
        return "kv_remote_settings_show_new_chat_button_above_input".equals(key)
                || "kv_remote_settings_hide_assistant_avatar".equals(key)
                || "kv_remote_settings_sse_auto_scroll_one_screen".equals(key);
    }

    /** Any entry with a usable key is listed, including read-only discovered rollouts. */
    static boolean isSupported(Feature feature) {
        return feature != null && feature.key != null && feature.key.length() > 0;
    }

    /** Only Boolean rollouts (and the 2.3.4 model-config entry) can be forced. */
    static boolean isOverridable(Feature feature) {
        return feature != null && (feature.nativeBoolean
                || (ATTACHMENT_GUIDE_PROMPTS.equals(feature.key) && HostCompat.isV234()));
    }

    static boolean hostValue(Feature feature, boolean userValue) {
        return feature != null && feature.inverted ? !userValue : userValue;
    }

    static int userModeFromHost(Feature feature, boolean hostValue) {
        return hostValue(feature, hostValue) ? FORCE_ON : FORCE_OFF;
    }

    /** Config fallback used before the host MMKV is available. */
    static int mode(String key) {
        ensureLoaded();
        Integer value = modes.get(key);
        return value == null ? FOLLOW : value.intValue();
    }

    /** The actual state in DeepSeek's own local-settings layer. */
    static int mode(ClassLoader loader, String key) {
        Feature feature = featureForKey(key);
        if (feature != null && !feature.nativeBoolean) return mode(key);
        SharedPreferences preferences = AccountManager.defaultMmkv(loader);
        String local = localKey(key);
        if (preferences != null && local != null) {
            try {
                if (preferences.contains(local)) {
                    return userModeFromHost(featureForKey(key),
                            preferences.getBoolean(local, false));
                }
                return FOLLOW;
            } catch (Throwable ignored) {}
        }
        return mode(key);
    }

    static boolean effectiveValue(ClassLoader loader, String key) {
        int actualMode = mode(loader, key);
        if (actualMode != FOLLOW) return actualMode == FORCE_ON;
        return rawValue(loader, key, false);
    }

    /** Reads the untouched value last delivered by DeepSeek's server. */
    static boolean rawValue(ClassLoader loader, String key, boolean fallback) {
        Feature feature = featureForKey(key);
        // 2.3.4 ships both the image/file prompt resources and hard-coded fallback lists. The
        // server can replace those lists through model_configs_v1, but there is no Boolean MMKV
        // key to read. Therefore FOLLOW accurately means using the native available state.
        if (feature != null && !feature.nativeBoolean) return true;
        SharedPreferences preferences = AccountManager.defaultMmkv(loader);
        if (preferences == null) return fallback;
        boolean hostFallback = hostValue(feature, fallback);
        try { return hostValue(feature, preferences.getBoolean(key, hostFallback)); }
        catch (Throwable ignored) { return fallback; }
    }

    static boolean setMode(ClassLoader loader, String key, int wanted) {
        Feature feature = featureForKey(key);
        if (feature == null || !isOverridable(feature)
                || (wanted != FORCE_OFF && wanted != FOLLOW && wanted != FORCE_ON)) return false;
        SharedPreferences preferences = AccountManager.defaultMmkv(loader);
        if (feature.nativeBoolean && preferences == null) return false;
        synchronized (LOCK) {
            ensureLoadedLocked();
            HashMap<String, Integer> next = new HashMap<>(modes);
            if (wanted == FOLLOW) next.remove(key); else next.put(key, wanted);
            if (!saveLocked(next)) return false;
            try {
                if (!feature.nativeBoolean) {
                    modes = Collections.unmodifiableMap(next);
                    return true;
                }
                SharedPreferences.Editor editor = preferences.edit();
                if (wanted == FOLLOW) editor.remove(localKey(key));
                else editor.putBoolean(localKey(key),
                        hostValue(feature, wanted == FORCE_ON));
                if (!editor.commit()) return false;
            } catch (Throwable error) {
                Main.log("native feature-setting write failed key=" + key + ": " + error);
                return false;
            }
            modes = Collections.unmodifiableMap(next);
            return true;
        }
    }

    static boolean resetAll(ClassLoader loader) {
        SharedPreferences preferences = AccountManager.defaultMmkv(loader);
        if (preferences == null) return false;
        synchronized (LOCK) {
            ensureLoadedLocked();
            if (!saveLocked(Collections.<String, Integer>emptyMap())) return false;
            try {
                SharedPreferences.Editor editor = preferences.edit();
                for (Feature feature : visibleFeatures(loader)) {
                    if (feature.nativeBoolean) editor.remove(localKey(feature.key));
                }
                if (!editor.commit()) return false;
            } catch (Throwable error) {
                Main.log("native feature-setting reset failed: " + error);
                return false;
            }
            modes = Collections.emptyMap();
            return true;
        }
    }

    static int overriddenCount() {
        ensureLoaded();
        return modes.size();
    }

    static int overriddenCount(ClassLoader loader) {
        int count = 0;
        for (Feature feature : visibleFeatures(loader)) {
            if (isOverridable(feature) && mode(loader, feature.key) != FOLLOW) count++;
        }
        return count;
    }

    /**
     * Applies overrides before DeepSeek constructs its feature repositories. This is the same
     * layer used by DeepSeek's own internal settings screen, so cached server values cannot win.
     */
    static void install(Main module, ClassLoader loader) {
        if (module == null || loader == null || installed) return;
        synchronized (LOCK) {
            if (installed) return;
            ensureLoadedLocked();
            installed = true;
            Main.log("installed DeepSeek native feature-setting manager (2.2.x/2.3.x)");
        }
    }

    static void enforce(ClassLoader loader) {
        synchronized (LOCK) {
            ensureLoadedLocked();
            // MMKV may not yet be initialised at the package-load callback. Activity resume is the
            // first stable host lifecycle point; perform the one-time v1 migration here.
            if (!migrated) migrated = migrateAndEnforceLocked(loader);
            else enforceLocked(loader, false);
        }
    }

    private static boolean migrateAndEnforceLocked(ClassLoader loader) {
        SharedPreferences preferences = AccountManager.defaultMmkv(loader);
        if (preferences == null) return false;
        try {
            SharedPreferences.Editor editor = preferences.edit();

            // Version 1 incorrectly overwrote the server layer. Restore its remembered value once.
            for (Map.Entry<String, Boolean> entry : legacyServerValues.entrySet()) {
                if (wasManagedByVersion1(entry.getKey()) && entry.getValue() != null) {
                    editor.putBoolean(entry.getKey(), entry.getValue().booleanValue());
                }
            }

            // v2/v3 could leave this remote key carrying the module's former forced value. The
            // host default is hide=true (show=false) in every inspected generation. Remove the
            // polluted value and its rollout id once, then let DeepSeek refresh it normally.
            if (loadedFormatVersion >= 2 && loadedFormatVersion < 4) {
                editor.remove("kv_remote_settings_hide_assistant_avatar");
                editor.remove("kv_remote_settings_id_hide_assistant_avatar");
            }

            // Preserve overrides made by DeepSeek's own hidden settings UI when adopting v2.
            HashMap<String, Integer> adopted = new HashMap<>(modes);
            HashMap<String, Feature> known = new HashMap<String, Feature>();
            for (Feature feature : visibleFeatures(loader)) known.put(feature.key, feature);
            for (Feature feature : visibleFeatures(loader)) {
                if (!feature.nativeBoolean) continue;
                String local = localKey(feature.key);
                if (!adopted.containsKey(feature.key) && preferences.contains(local)) {
                    adopted.put(feature.key, userModeFromHost(feature,
                            preferences.getBoolean(local, false)));
                }
            }
            modes = Collections.unmodifiableMap(adopted);
            for (Map.Entry<String, Integer> entry : adopted.entrySet()) {
                Feature feature = known.get(entry.getKey());
                if (feature != null && feature.nativeBoolean && isOverridable(feature)) {
                    editor.putBoolean(localKey(entry.getKey()),
                            hostValue(feature, entry.getValue() == FORCE_ON));
                }
            }
            if (!editor.commit()) return false;
            for (Map.Entry<String, Integer> entry : adopted.entrySet()) {
                Feature feature = known.get(entry.getKey());
                if (feature != null && feature.nativeBoolean && isOverridable(feature)) {
                    boolean user = entry.getValue() == FORCE_ON;
                    Main.log("native feature override applied key=" + entry.getKey()
                            + " user=" + user + " host=" + hostValue(feature, user));
                }
            }
            legacyServerValues = Collections.emptyMap();
            return saveLocked(adopted);
        } catch (Throwable error) {
            Main.log("native feature-setting migration failed: " + error);
            return false;
        }
    }

    private static boolean enforceLocked(ClassLoader loader, boolean commit) {
        SharedPreferences preferences = AccountManager.defaultMmkv(loader);
        if (preferences == null) return false;
        try {
            SharedPreferences.Editor editor = preferences.edit();
            HashMap<String, Feature> known = new HashMap<String, Feature>();
            for (Feature feature : visibleFeatures(loader)) known.put(feature.key, feature);
            for (Map.Entry<String, Integer> entry : modes.entrySet()) {
                Feature feature = known.get(entry.getKey());
                if (feature != null && feature.nativeBoolean && isOverridable(feature)) {
                    editor.putBoolean(localKey(entry.getKey()),
                            hostValue(feature, entry.getValue() == FORCE_ON));
                }
            }
            if (commit) return editor.commit();
            editor.apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOCK) { ensureLoadedLocked(); }
    }

    private static void ensureLoadedLocked() {
        if (loaded) return;
        HashMap<String, Integer> loadedModes = new HashMap<>();
        HashMap<String, Boolean> loadedLegacyServer = new HashMap<>();
        int formatVersion = 0;
        File file = new File(CONFIG_FILE);
        if (file.isFile() && file.length() <= 64 * 1024L) {
            FileReader reader = null;
            try {
                reader = new FileReader(file);
                StringBuilder json = new StringBuilder((int) file.length());
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    if (count > 0) json.append(buffer, 0, count);
                }
                JSONObject root = new JSONObject(json.length() == 0 ? "{}" : json.toString());
                formatVersion = root.optInt("version", 0);
                JSONObject overrides = root.optJSONObject("overrides");
                if (overrides != null) {
                    Iterator<String> keys = overrides.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        int value = overrides.optInt(key, FOLLOW);
                        if (isFeatureKey(key) && value != FOLLOW) {
                            loadedModes.put(key, value > 0 ? FORCE_ON : FORCE_OFF);
                        }
                    }
                }
                // Only v1 wrote this object. It is consumed during install and omitted thereafter.
                JSONObject originals = root.optJSONObject("server_values");
                if (originals != null) {
                    Iterator<String> keys = originals.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (wasManagedByVersion1(key) && originals.has(key)) {
                            loadedLegacyServer.put(key, originals.optBoolean(key));
                        }
                    }
                }
            } catch (Throwable ignored) {
                loadedModes.clear();
                loadedLegacyServer.clear();
                formatVersion = 0;
            } finally {
                if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
            }
        }
        modes = Collections.unmodifiableMap(loadedModes);
        legacyServerValues = Collections.unmodifiableMap(loadedLegacyServer);
        loadedFormatVersion = formatVersion;
        loaded = true;
    }

    private static boolean saveLocked(Map<String, Integer> nextModes) {
        File target = new File(CONFIG_FILE);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        File temp = new File(CONFIG_FILE + ".tmp");
        FileWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            root.put("format", "deekseep-native-feature-overrides");
            root.put("version", 4);
            JSONObject overrides = new JSONObject();
            for (Map.Entry<String, Integer> entry : nextModes.entrySet()) {
                if (isFeatureKey(entry.getKey()) && entry.getValue() != FOLLOW) {
                    overrides.put(entry.getKey(), entry.getValue().intValue());
                }
            }
            root.put("overrides", overrides);
            writer = new FileWriter(temp, false);
            writer.write(root.toString());
            writer.flush();
            writer.close();
            writer = null;
            if (target.exists() && !target.delete()) return false;
            return temp.renameTo(target);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (writer != null) try { writer.close(); } catch (Throwable ignored) {}
            if (temp.exists() && !temp.equals(target)) {
                try { temp.delete(); } catch (Throwable ignored) {}
            }
        }
    }
}
