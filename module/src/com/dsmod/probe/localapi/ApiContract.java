package com.dsmod.probe.localapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ApiContract {
    public static final long COMPLETION_REQUEST_BUDGET_MS = 600000;
    public static final String MODEL_DEFAULT = "default";
    public static final String MODEL_EXPERT = "expert";
    public static final String MODEL_VISION = "vision";
    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";

    public interface Backend {
        CompletionResult complete(CompletionRequest completionRequest, DeltaSink deltaSink) throws Exception;

        boolean isReady();

        String readinessDetail();
    }

    public interface ToolPlan {
        boolean active();
    }

    private ApiContract() {
    }

    public interface DeltaSink {
        boolean isCancelled();

        void onReasoning(String str) throws Exception;

        void onText(String str) throws Exception;

        default boolean isSatisfied() {
            return false;
        }

        default void onUpstreamStarted() throws Exception {
        }

        default String publishedTextSnapshot() {
            return "";
        }
    }

    public static final class GatewayException extends Exception {
        private static final long serialVersionUID = 1;
        public final String code;
        public final int status;
        public final String type;

        public GatewayException(int i, String str, String str2, String str3) {
            super(str3);
            this.status = i;
            this.code = str;
            this.type = str2;
        }

        public GatewayException(int i, String str, String str2) {
            this(i, str, "invalid_request_error", str2);
        }
    }

    public static final class ErrorBody {
        public String code;
        public String message;
        public String param;
        public String type;

        public ErrorBody() {
            this.message = "";
            this.type = "server_error";
            this.param = null;
            this.code = null;
        }

        public ErrorBody(String str, String str2, String str3) {
            this.message = "";
            this.type = "server_error";
            this.param = null;
            this.code = null;
            this.message = str == null ? "" : str;
            this.type = str2;
            this.code = str3;
        }
    }

    public static final class CompletionResult {
        public final String finishReason;
        public final String reasoning;
        public final String text;
        public final List<ToolCall> toolCalls;

        public CompletionResult(String str, String str2, String str3, List<ToolCall> list) {
            List<ToolCall> emptyList;
            this.text = str == null ? "" : str;
            this.reasoning = str2 == null ? "" : str2;
            this.finishReason = (str3 == null || str3.isEmpty()) ? "stop" : str3;
            if (list == null || list.isEmpty()) {
                emptyList = Collections.emptyList();
            } else {
                emptyList = Collections.unmodifiableList(new ArrayList(list));
            }
            this.toolCalls = emptyList;
        }

        public CompletionResult(String str, String str2, String str3) {
            this(str, str2, str3, Collections.emptyList());
        }

        public boolean hasToolCalls() {
            return !this.toolCalls.isEmpty();
        }
    }

    public static final class ToolCall {
        public final String arguments;
        public final String id;
        public final String name;
        public final String type;

        public ToolCall(String str, String str2, String str3, String str4) {
            this.id = str;
            this.type = (str2 == null || str2.isEmpty()) ? "function" : str2;
            this.name = str3;
            this.arguments = str4 == null ? "{}" : str4;
        }
    }

    public static final class ToolHistory {
        public final Set<String> completedCalls;
        public final Map<String, String> knownCalls;
        public final Set<String> repeatableCalls;

        public ToolHistory(Map<String, String> map, Set<String> set) {
            this.knownCalls = new HashMap();
            this.completedCalls = new HashSet();
            this.repeatableCalls = new HashSet();
            if (map != null) {
                this.knownCalls.putAll(map);
            }
            if (set != null) {
                this.completedCalls.addAll(set);
            }
        }

        public ToolHistory() {
            this(null, null);
        }
    }

    public static final class CompletionRequest {
        public final Set<String> completedToolCalls;
        public final long deadlineAtMs;
        public final List<String> fileIds;
        public final Map<String, String> knownToolCalls;
        public final int maxOutputTokens;
        public final String nativeConversationId;
        public final String nativeModel;
        public final Integer nativeParentMessageId;
        public final String previousResponseId;
        public final String prompt;
        public final boolean reasoning;
        public final Set<String> repeatableCompletedToolCalls;
        public final String requestId;
        public final String requestedModel;
        public final boolean responsesApi;
        public final boolean search;
        public final String sessionScope;
        public final String systemPrompt;
        public final ToolPlan toolPlan;

        public CompletionRequest(String str, String str2, String str3, String str4, String str5, boolean z, boolean z2, int i, ToolPlan toolPlan, String str6, boolean z3, String str7, String str8, Integer num, List<String> list, Map<String, String> map, Set<String> set, Set<String> set2, long j) {
            String str9;
            List<String> emptyList;
            Map<String, String> emptyMap;
            Set<String> emptySet;
            Set<String> emptySet2;
            this.requestId = str;
            this.requestedModel = str2;
            this.nativeModel = str3;
            this.systemPrompt = str4;
            this.prompt = str5;
            this.reasoning = z;
            this.search = z2;
            this.maxOutputTokens = i;
            this.toolPlan = toolPlan;
            this.previousResponseId = str6;
            this.responsesApi = z3;
            this.sessionScope = str7;
            Integer num2 = null;
            if (str8 == null || str8.isEmpty()) {
                str9 = null;
            } else {
                str9 = str8;
            }
            this.nativeConversationId = str9;
            if (num != null && num.intValue() > 0) {
                num2 = num;
            }
            this.nativeParentMessageId = num2;
            if (list == null || list.isEmpty()) {
                emptyList = Collections.emptyList();
            } else {
                emptyList = Collections.unmodifiableList(new ArrayList(list));
            }
            this.fileIds = emptyList;
            if (map == null || map.isEmpty()) {
                emptyMap = Collections.emptyMap();
            } else {
                emptyMap = Collections.unmodifiableMap(new HashMap(map));
            }
            this.knownToolCalls = emptyMap;
            if (set == null || set.isEmpty()) {
                emptySet = Collections.emptySet();
            } else {
                emptySet = Collections.unmodifiableSet(new HashSet(set));
            }
            this.completedToolCalls = emptySet;
            if (set2 == null || set2.isEmpty()) {
                emptySet2 = Collections.emptySet();
            } else {
                emptySet2 = Collections.unmodifiableSet(new HashSet(set2));
            }
            this.repeatableCompletedToolCalls = emptySet2;
            this.deadlineAtMs = j;
        }

        public CompletionRequest(String str, String str2, String str3, String str4, String str5, boolean z, boolean z2, int i, ToolPlan toolPlan, String str6, boolean z3) {
            this(str, str2, str3, str4, str5, z, z2, i, toolPlan, str6, z3, null, null, null, Collections.emptyList(), Collections.emptyMap(), Collections.emptySet(), Collections.emptySet(), System.currentTimeMillis() + 600000);
        }

        public boolean toolsActive() {
            return this.toolPlan != null && this.toolPlan.active();
        }

        public boolean auxiliary() {
            if (this.requestedModel == null) {
                return false;
            }
            String lowerCase = this.requestedModel.toLowerCase(Locale.US);
            return lowerCase.equals("deepseek-aux") || lowerCase.startsWith("deepseek-aux-");
        }

        public boolean interactiveAgent() {
            return toolsActive() && !auxiliary();
        }

        public long remainingMs() {
            return this.deadlineAtMs - System.currentTimeMillis();
        }

        public CompletionRequest withPrompt(String str) {
            return new CompletionRequest(this.requestId, this.requestedModel, this.nativeModel, this.systemPrompt, str, this.reasoning, this.search, this.maxOutputTokens, this.toolPlan, this.previousResponseId, this.responsesApi, this.sessionScope, this.nativeConversationId, this.nativeParentMessageId, this.fileIds, this.knownToolCalls, this.completedToolCalls, this.repeatableCompletedToolCalls, this.deadlineAtMs);
        }

        public CompletionRequest withNativeConversation(String str, Integer num) {
            return new CompletionRequest(this.requestId, this.requestedModel, this.nativeModel, this.systemPrompt, this.prompt, this.reasoning, this.search, this.maxOutputTokens, this.toolPlan, this.previousResponseId, this.responsesApi, this.sessionScope, str, num, this.fileIds, this.knownToolCalls, this.completedToolCalls, this.repeatableCompletedToolCalls, this.deadlineAtMs);
        }

        public CompletionRequest withToolHistory(ToolHistory toolHistory) {
            if (toolHistory == null) {
                return this;
            }
            return new CompletionRequest(this.requestId, this.requestedModel, this.nativeModel, this.systemPrompt, this.prompt, this.reasoning, this.search, this.maxOutputTokens, this.toolPlan, this.previousResponseId, this.responsesApi, this.sessionScope, this.nativeConversationId, this.nativeParentMessageId, this.fileIds, toolHistory.knownCalls, toolHistory.completedCalls, toolHistory.repeatableCalls, this.deadlineAtMs);
        }
    }
}
