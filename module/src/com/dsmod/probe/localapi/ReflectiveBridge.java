package com.dsmod.probe.localapi;

import com.dsmod.probe.HostCompat;
import com.dsmod.probe.localapi.ApiContract;
import com.dsmod.probe.localapi.HostBackend;
import com.dsmod.probe.localapi.SseReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReflectiveBridge implements HostBackend.Bridge {
    private static final String NATIVE_ENDPOINT = "https://chat.deepseek.com/api/v0/chat/completions";
    private volatile String lastFailure;
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicInteger openSessions = new AtomicInteger();

    @Override // com.dsmod.probe.localapi.HostBackend.Bridge
    public String openSession(String str, String str2) throws Exception {
        if (!this.available.get()) {
            throw new ApiContract.GatewayException(503, "native_unavailable", "server_error", this.lastFailure == null ? "Native bridge offline" : this.lastFailure);
        }
        if (this.openSessions.get() >= 32) {
            throw new ApiContract.GatewayException(429, "too_many_sessions", "rate_limit_error", "Too many concurrent local API sessions.");
        }
        if (HostCompat.localApiSessionCreateMethod() == null) {
            throw new ApiContract.GatewayException(503, "host_not_supported", "server_error", "This DeepSeek build exposes no local session members.");
        }
        this.openSessions.incrementAndGet();
        return "session-" + System.nanoTime();
    }

    @Override // com.dsmod.probe.localapi.HostBackend.Bridge
    public ApiContract.CompletionResult generate(ApiContract.CompletionRequest completionRequest, String str, ApiContract.DeltaSink deltaSink) throws Exception {
        StringBuilder sb = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        streamUpstream(completionRequest, guard(deltaSink, sb, sb2));
        LocalApiStats.log(completionRequest.requestId + " model=" + completionRequest.requestedModel + " role=" + completionRequest.nativeModel + " chars=" + sb.length());
        return new ApiContract.CompletionResult(sb.toString(), sb2.toString(), "stop");
    }

    @Override // com.dsmod.probe.localapi.HostBackend.Bridge
    public void closeSession(String str) throws Exception {
        this.openSessions.decrementAndGet();
        HostCompat.localApiSessionDeleteMethod();
    }

    private void streamUpstream(ApiContract.CompletionRequest completionRequest, final ApiContract.DeltaSink deltaSink) throws Exception {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(NATIVE_ENDPOINT).openConnection();
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setConnectTimeout(120000);
        httpURLConnection.setReadTimeout(120000);
        httpURLConnection.setRequestProperty("Content-Type", "application/json");
        httpURLConnection.setRequestProperty("Accept", "application/json, text/event-stream");
        httpURLConnection.setDoOutput(true);
        byte[] bytes = nativePayload(completionRequest).toString().getBytes("UTF-8");
        httpURLConnection.setFixedLengthStreamingMode(bytes.length);
        httpURLConnection.getOutputStream().write(bytes);
        int responseCode = httpURLConnection.getResponseCode();
        if (responseCode >= 400) {
            throw new ApiContract.GatewayException(mapStatus(responseCode), "upstream_rejected", responseCode >= 500 ? "server_error" : "invalid_request_error", "Upstream returned HTTP " + responseCode);
        }
        InputStream inputStream = httpURLConnection.getInputStream();
        try {
            SseReader.read(inputStream, new SseReader.Visitor() {
                @Override // com.dsmod.probe.localapi.SseReader.Visitor
                public void onEvent(String str) {
                    JSONObject jSONObject;
                    JSONObject optJSONObject;
                    JSONObject asJson = SseReader.asJson(str);
                    if (asJson == null) {
                        return;
                    }
                    JSONArray optJSONArray = asJson.optJSONArray("choices");
                    if (optJSONArray != null && optJSONArray.length() > 0 && (optJSONObject = optJSONArray.optJSONObject(0)) != null) {
                        jSONObject = optJSONObject.optJSONObject("delta");
                    } else {
                        jSONObject = null;
                    }
                    if (jSONObject != null) {
                        ReflectiveBridge.this.emit(deltaSink, jSONObject.optString("reasoning_content", null), jSONObject.optString("content", null));
                    }
                }

                @Override // com.dsmod.probe.localapi.SseReader.Visitor
                public boolean isCancelled() {
                    return deltaSink.isCancelled();
                }
            });
            try {
                inputStream.close();
            } catch (Throwable th) {
            }
        } catch (Throwable th2) {
            try {
                inputStream.close();
            } catch (Throwable th3) {
            }
            throw th2;
        }
    }

    public void emit(ApiContract.DeltaSink deltaSink, String str, String str2) {
        if (str != null) {
            try {
                if (!str.isEmpty()) {
                    deltaSink.onReasoning(str);
                }
            } catch (Throwable th) {
                return;
            }
        }
        if (str2 != null && !str2.isEmpty()) {
            try {
                deltaSink.onText(str2);
            } catch (Exception e) {
                // The consumer reports cancellation through isCancelled(); a failed delta is not
                // fatal for the turn, so remember it and keep draining the stream.
                this.lastFailure = e.getMessage();
            }
        }
    }

    private static JSONObject nativePayload(ApiContract.CompletionRequest completionRequest) throws Exception {
        JSONArray jSONArray = new JSONArray();
        if (completionRequest.systemPrompt != null && !completionRequest.systemPrompt.isEmpty()) {
            jSONArray.put(new JSONObject().put("role", "system").put("content", completionRequest.systemPrompt));
        }
        jSONArray.put(new JSONObject().put("role", "user").put("content", completionRequest.prompt));
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("model", completionRequest.requestedModel == null ? "deepseek-chat" : completionRequest.requestedModel);
        jSONObject.put("messages", jSONArray);
        jSONObject.put("stream", true);
        if (completionRequest.search) {
            jSONObject.put("search_enabled", true);
        }
        if (completionRequest.reasoning) {
            jSONObject.put("thinking_enabled", true);
        }
        return jSONObject;
    }

    private static int mapStatus(int i) {
        if (i == 401 || i == 403) {
            return 401;
        }
        if (i == 429) {
            return 429;
        }
        if (i >= 500) {
            return 502;
        }
        return i;
    }

    private ApiContract.DeltaSink guard(final ApiContract.DeltaSink deltaSink, final StringBuilder sb, final StringBuilder sb2) {
        return new ApiContract.DeltaSink() {
            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public boolean isCancelled() {
                return deltaSink.isCancelled();
            }

            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public boolean isSatisfied() {
                return deltaSink.isSatisfied();
            }

            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public void onUpstreamStarted() throws Exception {
                deltaSink.onUpstreamStarted();
            }

            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public void onReasoning(String str) throws Exception {
                sb2.append(str);
                deltaSink.onReasoning(str);
            }

            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public void onText(String str) throws Exception {
                sb.append(str);
                deltaSink.onText(str);
            }

            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public String publishedTextSnapshot() {
                return sb.toString();
            }
        };
    }

    public boolean isAvailable() {
        return this.available.get();
    }

    void markUnavailable(Throwable th) {
        String simpleName;
        if (th == null) {
            simpleName = null;
        } else {
            simpleName = th.getMessage() == null ? th.getClass().getSimpleName() : th.getMessage();
        }
        this.lastFailure = simpleName;
        this.available.set(false);
    }
}
