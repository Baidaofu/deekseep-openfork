package com.dsmod.probe.localapi;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LocalApiStats {
    private static final String LOG_FILE = "deekseep_api.log";
    public static final int LOG_LINES = 400;
    public static final long LOG_WINDOW_MS = 21600000;
    private static final String STATUS_FILE = "deekseep_api_status.json";
    private static long contextRelayCached;
    private static long contextRelayFailed;
    private static long contextRelaySuccess;
    private static File directory;
    private static long failures;
    private static long latencyMaxMs;
    private static long latencySumMs;
    private static long reasoningCount;
    private static long recoveryCount;
    private static long streamingCount;
    private static long toolRounds;
    private static long total;
    private static final Object LOCK = new Object();
    private static long startedAt = System.currentTimeMillis();
    private static final List<String> RECENT = new ArrayList();

    private LocalApiStats() {
    }

    public static void initialize(File file) {
        directory = file;
    }

    public static void recordSuccess(boolean z, boolean z2, long j) {
        record(false, z, z2, j);
    }

    public static void recordFailure(long j) {
        record(true, false, false, j);
    }

    private static void record(boolean z, boolean z2, boolean z3, long j) {
        synchronized (LOCK) {
            total++;
            if (z) {
                failures++;
            } else {
                if (z2) {
                    streamingCount++;
                }
                if (z3) {
                    reasoningCount++;
                }
            }
            latencySumMs += Math.max(0L, j);
            if (j > latencyMaxMs) {
                latencyMaxMs = j;
            }
            // Requests are recorded after the completion log line, so persist here too or the
            // counters the settings panel reads would stay at zero.
            persistLocked(snapshot());
        }
    }

    public static void noteToolRound() {
        synchronized (LOCK) {
            toolRounds++;
        }
    }

    public static void noteContextRelay(boolean z, boolean z2) {
        synchronized (LOCK) {
            try {
                if (z2) {
                    contextRelayCached++;
                } else if (z) {
                    contextRelaySuccess++;
                } else {
                    contextRelayFailed++;
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public static void noteRecovery() {
        synchronized (LOCK) {
            recoveryCount++;
        }
    }

    public static void log(String str) {
        if (str == null) {
            return;
        }
        String str2 = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + str;
        synchronized (LOCK) {
            RECENT.add(str2);
            while (RECENT.size() > 400) {
                RECENT.remove(0);
            }
            flushLogLocked();
        }
    }

    public static JSONObject snapshot() {
        JSONObject jSONObject;
        synchronized (LOCK) {
            long j = total - failures;
            jSONObject = new JSONObject();
            try {
                jSONObject.put("started_at", startedAt);
                jSONObject.put("uptime_ms", System.currentTimeMillis() - startedAt);
                jSONObject.put("total_requests", total);
                jSONObject.put("successful_requests", j);
                jSONObject.put("failed_requests", failures);
                jSONObject.put("streaming_requests", streamingCount);
                jSONObject.put("reasoning_requests", reasoningCount);
                jSONObject.put("tool_rounds", toolRounds);
                jSONObject.put("average_latency_ms", j != 0 ? latencySumMs / j : 0L);
                jSONObject.put("max_latency_ms", latencyMaxMs);
                jSONObject.put("context_relay_success", contextRelaySuccess);
                jSONObject.put("context_relay_cached", contextRelayCached);
                jSONObject.put("context_relay_failed", contextRelayFailed);
                jSONObject.put("auto_recovery_count", recoveryCount);
                persistLocked(jSONObject);
            } catch (Throwable th) {
                return jSONObject;
            }
        }
        return jSONObject;
    }

    public static String summary() {
        JSONObject snapshot = snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("requests=").append(snapshot.optLong("total_requests", 0L));
        sb.append(" ok=").append(snapshot.optLong("successful_requests", 0L));
        sb.append(" err=").append(snapshot.optLong("failed_requests", 0L));
        sb.append(" avg=").append(snapshot.optLong("average_latency_ms", 0L)).append("ms");
        sb.append(" max=").append(snapshot.optLong("max_latency_ms", 0L)).append("ms");
        sb.append(" stream=").append(snapshot.optLong("streaming_requests", 0L));
        sb.append(" tools=").append(snapshot.optLong("tool_rounds", 0L));
        return sb.toString();
    }

    public static String recentLog() {
        String sb;
        synchronized (LOCK) {
            StringBuilder sb2 = new StringBuilder();
            for (int size = RECENT.size() - 1; size >= 0; size--) {
                sb2.append(RECENT.get(size)).append('\n');
            }
            sb = sb2.toString();
        }
        return sb;
    }

    public static void reset() {
        synchronized (LOCK) {
            startedAt = System.currentTimeMillis();
            total = 0L;
            failures = 0L;
            streamingCount = 0L;
            toolRounds = 0L;
            reasoningCount = 0L;
            latencySumMs = 0L;
            latencyMaxMs = 0L;
            contextRelaySuccess = 0L;
            contextRelayCached = 0L;
            contextRelayFailed = 0L;
            RECENT.clear();
        }
    }

    private static void persistLocked(JSONObject jSONObject) {
        File file = directory == null ? null : new File(directory, STATUS_FILE);
        if (file == null) {
            return;
        }
        try {
            LocalApiConfig.writeAtomic(file, jSONObject.toString().getBytes("UTF-8"));
        } catch (Throwable th) {
        }
    }

    private static void flushLogLocked() {
        File file = directory == null ? null : new File(directory, LOG_FILE);
        if (file == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            System.currentTimeMillis();
            Iterator<String> it = RECENT.iterator();
            while (it.hasNext()) {
                sb.append(it.next()).append('\n');
            }
            if (sb.length() == 0) {
                sb.append('\n');
            }
            LocalApiConfig.writeAtomic(file, sb.toString().getBytes("UTF-8"));
        } catch (Throwable th) {
        }
        // The status snapshot feeds the settings panel and the floating console; log() is
        // the single choke point every request already goes through, so refresh it here.
        try {
            persistLocked(snapshot());
        } catch (Throwable th) {
        }
    }

    public static JSONArray logAsArray() {
        ArrayList arrayList;
        JSONArray jSONArray = new JSONArray();
        synchronized (LOCK) {
            arrayList = new ArrayList(RECENT);
        }
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            jSONArray.put((String) it.next());
        }
        return jSONArray;
    }
}
