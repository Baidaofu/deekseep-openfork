package com.dsmod.probe.localapi;

import android.content.Context;
import com.dsmod.probe.localapi.ApiContract;
import com.dsmod.probe.localapi.LocalApiConfig;
import java.util.List;
import java.util.Locale;

public final class HostBackend implements ApiContract.Backend {
    public static final long AGENT_QUEUE_WAIT_MS = 60000;
    public static final long AUX_QUEUE_WAIT_MS = 8000;
    public static final long CHAT_QUEUE_WAIT_MS = 30000;
    public static final long MIN_START_INTERVAL_MS = 200;
    public static final int NATIVE_PERMIT_COUNT = 8;
    public static final long NATIVE_TIMEOUT_SECONDS = 120;
    public static final long NORMAL_COOLDOWN_MS = 500;
    public static final long QUEUE_POLL_MS = 250;
    public static final long REQUEST_BUDGET_MS = 600000;
    public static final int SESSION_MAX = 32;
    public static final String SESSION_META = "__deekseep_meta";
    public static final String SESSION_RETIRED = "__deekseep_retired";
    public static final long SESSION_TTL_MS = 86400000;
    public static final long TOOL_HANDOFF_COOLDOWN_MS = 250;
    private final Bridge bridge;
    private final Context context;
    private long lastStartAtMs;

    public interface Bridge {
        void closeSession(String str) throws Exception;

        ApiContract.CompletionResult generate(ApiContract.CompletionRequest completionRequest, String str, ApiContract.DeltaSink deltaSink) throws Exception;

        String openSession(String str, String str2) throws Exception;
    }

    public HostBackend(Context context, Bridge bridge) {
        this.context = context == null ? null : context.getApplicationContext();
        this.bridge = bridge;
    }

    @Override // com.dsmod.probe.localapi.ApiContract.Backend
    public boolean isReady() {
        if (this.bridge == null) {
            return false;
        }
        return true;
    }

    @Override // com.dsmod.probe.localapi.ApiContract.Backend
    public String readinessDetail() {
        if (this.bridge == null) {
            return "No native bridge is available";
        }
        return "Ready";
    }

    @Override // com.dsmod.probe.localapi.ApiContract.Backend
    public ApiContract.CompletionResult complete(ApiContract.CompletionRequest completionRequest, ApiContract.DeltaSink deltaSink) throws Exception {
        if (this.bridge == null) {
            throw new ApiContract.GatewayException(503, "backend_unavailable", "server_error", "No native bridge is available.");
        }
        if (completionRequest == null || completionRequest.prompt == null || completionRequest.prompt.trim().isEmpty()) {
            throw new ApiContract.GatewayException(LocalApiStats.LOG_LINES, "empty_prompt", "invalid_request_error", "The request contained no message content.");
        }
        if (completionRequest.remainingMs() <= 0) {
            throw new ApiContract.GatewayException(408, "request_expired", "timeout", "The request budget elapsed before it started.");
        }
        awaitStartSlot();
        String str = null;
        try {
            str = this.bridge.openSession(completionRequest.sessionScope, completionRequest.nativeModel);
            ApiContract.CompletionRequest applyPromptPolicy = applyPromptPolicy(completionRequest);
            try {
                deltaSink.onUpstreamStarted();
            } catch (Throwable th) {
            }
            ApiContract.CompletionResult generate = this.bridge.generate(applyPromptPolicy, str, deltaSink);
            LocalApiStats.log(completionRequest.requestId + " model=" + completionRequest.requestedModel + " role=" + completionRequest.nativeModel + " chars=" + (generate.text == null ? 0 : generate.text.length()));
            return generate;
        } finally {
            if (str != null) {
                try {
                    this.bridge.closeSession(str);
                } catch (Throwable th2) {
                }
            }
        }
    }

    private void awaitStartSlot() {
        while (true) {
            long currentTimeMillis = System.currentTimeMillis();
            long j = this.lastStartAtMs + 200;
            if (currentTimeMillis >= j) {
                this.lastStartAtMs = currentTimeMillis;
                return;
            }
            sleepQuietly(j - currentTimeMillis);
        }
    }

    private ApiContract.CompletionRequest applyPromptPolicy(ApiContract.CompletionRequest completionRequest) {
        String str;
        LocalApiConfig.State state = LocalApiConfig.get();
        if (!state.injectSystemPrompt || state.systemPrompt.isEmpty()) {
            return completionRequest;
        }
        if (completionRequest.systemPrompt != null && !completionRequest.systemPrompt.isEmpty()) {
            str = state.systemPrompt + "\n\n" + completionRequest.systemPrompt;
        } else {
            str = state.systemPrompt;
        }
        return new ApiContract.CompletionRequest(completionRequest.requestId, completionRequest.requestedModel, completionRequest.nativeModel, str, completionRequest.prompt, completionRequest.reasoning, completionRequest.search, completionRequest.maxOutputTokens, completionRequest.toolPlan, completionRequest.previousResponseId, completionRequest.responsesApi, completionRequest.sessionScope, completionRequest.nativeConversationId, completionRequest.nativeParentMessageId, completionRequest.fileIds, completionRequest.knownToolCalls, completionRequest.completedToolCalls, completionRequest.repeatableCompletedToolCalls, completionRequest.deadlineAtMs);
    }

    static boolean wantsVision(String str, List<String> list) {
        return (list == null || list.isEmpty() || !ApiContract.MODEL_VISION.equals(String.valueOf(str).toLowerCase(Locale.US))) ? false : true;
    }

    private static void sleepQuietly(long j) {
        if (j <= 0) {
            return;
        }
        try {
            Thread.sleep(j);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
