package com.dsmod.probe.localapi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LocalApiConfig {
    public static final int DEFAULT_PORT = 8765;
    private static final String FILE = "dq0_config.json";
    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_PORT = 65535;
    public static final int MIN_KEY_LENGTH = 8;
    public static final int MIN_PORT = 1024;
    private static File directory;
    private static volatile State state;
    private static final Object LOCK = new Object();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList();

    public interface Listener {
        void onChanged(LocalApiConfig localApiConfig);
    }

    public static final class State {
        public final boolean allowLan;
        public final boolean antiCensor;
        public final String apiKey;
        public final boolean autoRecovery;
        public final String customModelsJson;
        public final boolean enabled;
        public final boolean forceReasoning;
        public final boolean https;
        public final boolean injectSystemPrompt;
        public final boolean keepAliveNotification;
        public final boolean longContextRelay;
        public final int port;
        public final String protocolMode;
        public final String publicRootUrl;
        public final boolean serialRequests;
        public final String systemPrompt;

        State(boolean z, int i, String str, String str2, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6, boolean z7, String str3, boolean z8, boolean z9, String str4, String str5, boolean z10) {
            this.enabled = z;
            this.port = i;
            this.protocolMode = str;
            this.apiKey = str2;
            this.https = z2;
            this.allowLan = z3;
            this.keepAliveNotification = z4;
            this.serialRequests = z5;
            this.antiCensor = z6;
            this.injectSystemPrompt = z7;
            this.systemPrompt = str3 == null ? "" : str3;
            this.longContextRelay = z8;
            this.forceReasoning = z9;
            this.customModelsJson = str4 == null ? "[]" : str4;
            this.publicRootUrl = str5 != null ? str5 : "";
            this.autoRecovery = z10;
        }

        boolean get(String str) {
            if ("enabled".equals(str)) {
                return this.enabled;
            }
            if ("https".equals(str)) {
                return this.https;
            }
            if ("allowLan".equals(str)) {
                return this.allowLan;
            }
            if ("keepAliveNotification".equals(str)) {
                return this.keepAliveNotification;
            }
            if ("serialRequests".equals(str)) {
                return this.serialRequests;
            }
            if ("antiCensor".equals(str)) {
                return this.antiCensor;
            }
            if ("injectSystemPrompt".equals(str)) {
                return this.injectSystemPrompt;
            }
            if ("longContextRelay".equals(str)) {
                return this.longContextRelay;
            }
            if ("forceReasoning".equals(str)) {
                return this.forceReasoning;
            }
            if ("autoRecovery".equals(str)) {
                return this.autoRecovery;
            }
            throw new IllegalArgumentException(str);
        }
    }

    private LocalApiConfig() {
    }

    public static void initialize(File file) {
        directory = file;
        load();
    }

    public static State get() {
        State state2 = state;
        if (state2 == null) {
            return load();
        }
        return state2;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void setEnabled(boolean z) {
        update("enabled", Boolean.valueOf(z));
    }

    public static boolean setPort(int i) {
        if (i < 1024 || i > 65535) {
            return false;
        }
        update("port", Integer.valueOf(i));
        return true;
    }

    public static String setCustomKey(String str) {
        Validation validateKey = validateKey(str);
        if (!validateKey.ok) {
            return validateKey.reason;
        }
        update("apiKey", str);
        return null;
    }

    public static String rotateKey() {
        String generateKey = generateKey();
        update("apiKey", generateKey);
        return generateKey;
    }

    public static boolean setProtocolMode(String str) {
        String str2 = ApiContract.PROTOCOL_ANTHROPIC;
        if (!ApiContract.PROTOCOL_ANTHROPIC.equals(str)) {
            str2 = ApiContract.PROTOCOL_OPENAI;
        }
        update("protocolMode", str2);
        return true;
    }

    public static void setHttps(boolean z) {
        update("https", Boolean.valueOf(z));
    }

    public static void setAllowLan(boolean z) {
        update("allowLan", Boolean.valueOf(z));
    }

    public static void setKeepAliveNotification(boolean z) {
        update("keepAliveNotification", Boolean.valueOf(z));
    }

    public static void setSerialRequests(boolean z) {
        update("serialRequests", Boolean.valueOf(z));
    }

    public static void setAntiCensor(boolean z) {
        update("antiCensor", Boolean.valueOf(z));
    }

    public static void setInjectSystemPrompt(boolean z) {
        update("injectSystemPrompt", Boolean.valueOf(z));
    }

    public static void setSystemPrompt(String str) {
        if (str == null) {
            str = "";
        }
        update("systemPrompt", str);
    }

    public static void setLongContextRelay(boolean z) {
        update("longContextRelay", Boolean.valueOf(z));
    }

    public static void setForceReasoning(boolean z) {
        update("forceReasoning", Boolean.valueOf(z));
    }

    public static void setCustomModelsJson(String str) {
        if (str == null) {
            str = "[]";
        }
        update("customModelsJson", str);
    }

    public static void setPublicRootUrl(String str) {
        if (str == null) {
            str = "";
        }
        update("publicRootUrl", str);
    }

    public static void setAutoRecovery(boolean z) {
        update("autoRecovery", Boolean.valueOf(z));
    }

    public static final class Validation {
        public final boolean ok;
        public final String reason;

        Validation(boolean z, String str) {
            this.ok = z;
            this.reason = str;
        }
    }

    public static Validation validateKey(String str) {
        if (str == null) {
            return new Validation(false, "key_missing");
        }
        if (str.length() < 8 || str.length() > 256) {
            return new Validation(false, "key_length");
        }
        for (int i = 0; i < str.length(); i++) {
            char charAt = str.charAt(i);
            if (charAt <= ' ' || charAt > '~') {
                return new Validation(false, "key_charset");
            }
        }
        return new Validation(true, null);
    }

    public static String generateKey() {
        byte[] bArr = new byte[32];
        new SecureRandom().nextBytes(bArr);
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 32; i++) {
            sb.append(String.format(Locale.US, "%02x", Integer.valueOf(bArr[i] & 255)));
        }
        return sb.toString();
    }

    private static State load() {
        synchronized (LOCK) {
            State defaults = defaults();
            File file = file();
            if (file == null || !file.isFile()) {
                state = defaults;
                return defaults;
            }
            try {
                state = fromJson(new JSONObject(new String(readAll(file), "UTF-8")), defaults);
            } catch (Throwable th) {
                state = defaults;
            }
            return state;
        }
    }

    private static State defaults() {
        return new State(false, DEFAULT_PORT, ApiContract.PROTOCOL_OPENAI, generateKey(), false, false, true, true, false, false, "", true, false, "[]", "", true);
    }

    private static State fromJson(JSONObject jSONObject, State state2) {
        boolean optBoolean = jSONObject.optBoolean("enabled", state2.enabled);
        int clampPort = clampPort(jSONObject.optInt("port", state2.port), state2.port);
        String optString = jSONObject.optString("protocolMode", state2.protocolMode);
        String str = ApiContract.PROTOCOL_ANTHROPIC;
        if (!ApiContract.PROTOCOL_ANTHROPIC.equals(optString)) {
            str = ApiContract.PROTOCOL_OPENAI;
        }
        return new State(optBoolean, clampPort, str, keyOrGenerate(jSONObject.optString("apiKey", null)), jSONObject.optBoolean("https", state2.https), jSONObject.optBoolean("allowLan", state2.allowLan), jSONObject.optBoolean("keepAliveNotification", state2.keepAliveNotification), jSONObject.optBoolean("serialRequests", state2.serialRequests), jSONObject.optBoolean("antiCensor", state2.antiCensor), jSONObject.optBoolean("injectSystemPrompt", state2.injectSystemPrompt), jSONObject.optString("systemPrompt", state2.systemPrompt), jSONObject.optBoolean("longContextRelay", state2.longContextRelay), jSONObject.optBoolean("forceReasoning", state2.forceReasoning), jSONObject.optString("customModelsJson", state2.customModelsJson), jSONObject.optString("publicRootUrl", state2.publicRootUrl), jSONObject.optBoolean("autoRecovery", state2.autoRecovery));
    }

    private static void update(String str, Object obj) {
        State with = with(get(), str, obj);
        synchronized (LOCK) {
            state = with;
            persist(with);
        }
        notifyChanged();
    }

    private static void notifyChanged() {
        Iterator<Listener> it = LISTENERS.iterator();
        while (it.hasNext()) {
            try {
                it.next().onChanged(null);
            } catch (Throwable th) {
            }
        }
    }

    private static State with(State state2, String str, Object obj) {
        Object valueOf = "port".equals(str) ? Integer.valueOf(clampPort(((Integer) obj).intValue(), state2.port)) : obj;
        if ("apiKey".equals(str) && valueOf != null && !validateKey(String.valueOf(valueOf)).ok) {
            return state2;
        }
        if ("https".equals(str)) {
            valueOf = (Boolean) valueOf;
        }
        if ("allowLan".equals(str)) {
            valueOf = (Boolean) valueOf;
        }
        boolean[] zArr = new boolean[10];
        zArr[0] = state2.enabled;
        zArr[1] = state2.https;
        zArr[2] = state2.allowLan;
        zArr[3] = state2.keepAliveNotification;
        zArr[4] = state2.serialRequests;
        zArr[5] = state2.antiCensor;
        zArr[6] = state2.injectSystemPrompt;
        zArr[7] = state2.longContextRelay;
        zArr[8] = state2.forceReasoning;
        zArr[9] = state2.autoRecovery;
        String[] strArr = {"enabled", "https", "allowLan", "keepAliveNotification", "serialRequests", "antiCensor", "injectSystemPrompt", "longContextRelay", "forceReasoning", "autoRecovery"};
        int i = 0;
        for (int i2 = 10; i < i2; i2 = 10) {
            if (strArr[i].equals(str)) {
                zArr[i] = ((Boolean) valueOf).booleanValue();
            }
            i++;
        }
        return new State(zArr[0], str.equals("port") ? ((Integer) valueOf).intValue() : state2.port, str.equals("protocolMode") ? String.valueOf(valueOf) : state2.protocolMode, str.equals("apiKey") ? String.valueOf(valueOf) : state2.apiKey, zArr[1], zArr[2], zArr[3], zArr[4], zArr[5], zArr[6], str.equals("systemPrompt") ? String.valueOf(valueOf) : state2.systemPrompt, zArr[7], zArr[8], str.equals("customModelsJson") ? String.valueOf(valueOf) : state2.customModelsJson, str.equals("publicRootUrl") ? String.valueOf(valueOf) : state2.publicRootUrl, zArr[9]);
    }

    private static void persist(State state2) {
        File file = file();
        if (file == null) {
            return;
        }
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("enabled", state2.enabled);
            jSONObject.put("port", state2.port);
            jSONObject.put("protocolMode", state2.protocolMode);
            jSONObject.put("apiKey", state2.apiKey);
            jSONObject.put("https", state2.https);
            jSONObject.put("allowLan", state2.allowLan);
            jSONObject.put("keepAliveNotification", state2.keepAliveNotification);
            jSONObject.put("serialRequests", state2.serialRequests);
            jSONObject.put("antiCensor", state2.antiCensor);
            jSONObject.put("injectSystemPrompt", state2.injectSystemPrompt);
            jSONObject.put("systemPrompt", state2.systemPrompt);
            jSONObject.put("longContextRelay", state2.longContextRelay);
            jSONObject.put("forceReasoning", state2.forceReasoning);
            jSONObject.put("customModelsJson", state2.customModelsJson);
            jSONObject.put("publicRootUrl", state2.publicRootUrl);
            jSONObject.put("autoRecovery", state2.autoRecovery);
            writeAtomic(file, jSONObject.toString().getBytes("UTF-8"));
        } catch (Throwable th) {
        }
    }

    private static File file() {
        if (directory == null) {
            return null;
        }
        return new File(directory, FILE);
    }

    private static int clampPort(int i, int i2) {
        if (i < 1024 || i > 65535) {
            return i2;
        }
        return i;
    }

    private static String keyOrGenerate(String str) {
        return validateKey(str).ok ? str : generateKey();
    }

    static byte[] readAll(File file) throws Exception {
        int length = (int) file.length();
        byte[] bArr = new byte[length];
        FileInputStream fileInputStream = new FileInputStream(file);
        int i = 0;
        while (i < length) {
            try {
                int read = fileInputStream.read(bArr, i, length - i);
                if (read < 0) {
                    break;
                }
                i += read;
            } finally {
                fileInputStream.close();
            }
        }
        return bArr;
    }

    static void writeAtomic(File file, byte[] bArr) throws Exception {
        File file2 = new File(file.getPath() + ".new");
        FileOutputStream fileOutputStream = new FileOutputStream(file2, false);
        try {
            fileOutputStream.write(bArr);
            fileOutputStream.getFD().sync();
            fileOutputStream.close();
            if (file.exists() && !file.delete()) {
                throw new IOException("could not replace " + file.getName());
            }
            if (!file2.renameTo(file)) {
                throw new IOException("could not commit " + file.getName());
            }
        } catch (Throwable th) {
            fileOutputStream.close();
            throw th;
        }
    }

    public static String exportJson() {
        State state2 = get();
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("enabled", state2.enabled);
            jSONObject.put("port", state2.port);
            jSONObject.put("protocolMode", state2.protocolMode);
            jSONObject.put("https", state2.https);
            jSONObject.put("allowLan", state2.allowLan);
            jSONObject.put("serialRequests", state2.serialRequests);
            jSONObject.put("antiCensor", state2.antiCensor);
            jSONObject.put("injectSystemPrompt", state2.injectSystemPrompt);
            jSONObject.put("systemPrompt", state2.systemPrompt);
            jSONObject.put("longContextRelay", state2.longContextRelay);
            jSONObject.put("forceReasoning", state2.forceReasoning);
            jSONObject.put("customModels", new JSONArray(state2.customModelsJson));
            jSONObject.put("publicRootUrl", state2.publicRootUrl);
            jSONObject.put("autoRecovery", state2.autoRecovery);
            return jSONObject.toString(2);
        } catch (Throwable th) {
            return "{}";
        }
    }
}
