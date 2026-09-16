package com.dsmod.probe.localapi;

import com.dsmod.probe.localapi.ApiContract;
import com.dsmod.probe.localapi.ModelCatalog;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class OpenAiRouter {
    private static final String OBJECT_CHAT_CHUNK = "chat.completion.chunk";
    private static final String OBJECT_CHAT_COMPLETION = "chat.completion";
    private static final String ROLE_ASSISTANT = "assistant";
    private final ApiContract.Backend backend;

    public OpenAiRouter(ApiContract.Backend backend) {
        this.backend = backend;
    }

    public void models(HttpExchange httpExchange) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("object", "list");
        JSONArray jSONArray = new JSONArray();
        for (ModelCatalog.Entry entry : ModelCatalog.advertised(customModels())) {
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("id", entry.id);
            jSONObject2.put("object", "model");
            jSONObject2.put("created", 0);
            jSONObject2.put("owned_by", entry.ownedBy);
            jSONArray.put(jSONObject2);
        }
        jSONObject.put("data", jSONArray);
        httpExchange.respond(200, "application/json", jSONObject.toString().getBytes("UTF-8"));
    }

    public void chatCompletions(HttpExchange httpExchange) throws Exception {
        JSONObject parseBody = parseBody(httpExchange);
        String optString = parseBody.optString("model", null);
        boolean z = false;
        boolean optBoolean = parseBody.optBoolean("stream", false);
        if (parseBody.optJSONObject("stream_options") != null && parseBody.optJSONObject("stream_options").optBoolean("include_usage", false)) {
            z = true;
        }
        boolean z2 = z;
        ApiContract.CompletionRequest fromChatRequest = fromChatRequest(parseBody, optString, httpExchange);
        long currentTimeMillis = System.currentTimeMillis();
        if (!this.backend.isReady()) {
            throw new ApiContract.GatewayException(503, "backend_not_ready", "server_error", this.backend.readinessDetail());
        }
        if (optBoolean) {
            streamChat(httpExchange, fromChatRequest, optString, z2, currentTimeMillis);
        } else {
            completeChat(httpExchange, fromChatRequest, optString, currentTimeMillis);
        }
    }

    private void completeChat(HttpExchange httpExchange, ApiContract.CompletionRequest completionRequest, String str, long j) throws Exception {
        httpExchange.respond(200, "application/json", chatCompletionBody(completionRequest.requestId, str, this.backend.complete(completionRequest, noopSink())).toString().getBytes("UTF-8"));
        LocalApiStats.recordSuccess(false, completionRequest.reasoning, System.currentTimeMillis() - j);
    }

    private void streamChat(final HttpExchange httpExchange, ApiContract.CompletionRequest completionRequest, String str, boolean z, long j) throws Exception {
        httpExchange.startStreaming(200, "text/event-stream");
        final StringBuilder sb = new StringBuilder();
        final StringBuilder sb2 = new StringBuilder();
        final String str2 = completionRequest.requestId;
        try {
            ApiContract.CompletionResult complete = this.backend.complete(completionRequest, new ApiContract.DeltaSink() {
                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public boolean isCancelled() {
                    return false;
                }

                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public void onReasoning(String str3) throws Exception {
                    if (str3 == null || str3.isEmpty()) {
                        return;
                    }
                    sb2.append(str3);
                    httpExchange.writeSse(OpenAiRouter.chunkBuilder(str2, null).put("choices", OpenAiRouter.choices(OpenAiRouter.reasoningDelta(str3))).toString());
                }

                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public void onText(String str3) throws Exception {
                    if (str3 == null || str3.isEmpty()) {
                        return;
                    }
                    sb.append(str3);
                    httpExchange.writeSse(OpenAiRouter.chunkBuilder(str2, null).put("choices", OpenAiRouter.choices(OpenAiRouter.contentDelta(str3))).toString());
                }

                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public String publishedTextSnapshot() {
                    return sb.toString();
                }
            });
            if (complete.hasToolCalls()) {
                emitToolCalls(httpExchange, str2, complete);
            }
            httpExchange.writeSse(chunkBuilder(str2, null).put("choices", choices(finishDelta(complete.finishReason))).toString());
            if (z) {
                httpExchange.writeSse(chunkBuilder(str2, null).put("choices", new JSONArray()).put("usage", usage(complete)).toString());
            }
            httpExchange.writeSse(SseReader.DONE);
            httpExchange.endStreaming();
            LocalApiStats.recordSuccess(true, completionRequest.reasoning, System.currentTimeMillis() - j);
        } catch (Error e) {
            failStream(httpExchange, e);
            throw e;
        } catch (Exception e2) {
            failStream(httpExchange, e2);
            throw e2;
        }
    }

    public void responses(HttpExchange httpExchange) throws Exception {
        JSONObject parseBody = parseBody(httpExchange);
        String optString = parseBody.optString("model", null);
        boolean optBoolean = parseBody.optBoolean("stream", false);
        ApiContract.CompletionRequest fromResponsesRequest = fromResponsesRequest(parseBody, optString);
        long currentTimeMillis = System.currentTimeMillis();
        if (!this.backend.isReady()) {
            throw new ApiContract.GatewayException(503, "backend_not_ready", "server_error", this.backend.readinessDetail());
        }
        if (optBoolean) {
            streamResponses(httpExchange, fromResponsesRequest, optString, currentTimeMillis);
            return;
        }
        httpExchange.respond(200, "application/json", responseBody(fromResponsesRequest.requestId, optString, this.backend.complete(fromResponsesRequest, noopSink()), fromResponsesRequest.previousResponseId).toString().getBytes("UTF-8"));
        LocalApiStats.recordSuccess(false, fromResponsesRequest.reasoning, System.currentTimeMillis() - currentTimeMillis);
    }

    private void streamResponses(final HttpExchange httpExchange, ApiContract.CompletionRequest completionRequest, String str, long j) throws Exception {
        httpExchange.startStreaming(200, "text/event-stream");
        String str2 = completionRequest.previousResponseId;
        try {
            ApiContract.CompletionResult complete = this.backend.complete(completionRequest, new ApiContract.DeltaSink() {
                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public boolean isCancelled() {
                    return false;
                }

                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public void onReasoning(String str3) throws Exception {
                    if (str3 != null && !str3.isEmpty()) {
                        httpExchange.writeSse(OpenAiRouter.this.responseEvent("response.reasoning_text.delta", str3));
                    }
                }

                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public void onText(String str3) throws Exception {
                    if (str3 != null && !str3.isEmpty()) {
                        httpExchange.writeSse(OpenAiRouter.this.responseEvent("response.output_text.delta", str3));
                    }
                }
            });
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("type", "response.completed");
            jSONObject.put("response", responseBody(completionRequest.requestId, str, complete, str2));
            httpExchange.writeSse(jSONObject.toString());
            httpExchange.endStreaming();
            LocalApiStats.recordSuccess(true, completionRequest.reasoning, System.currentTimeMillis() - j);
        } catch (Error e) {
            failStream(httpExchange, e);
            throw e;
        } catch (Exception e2) {
            failStream(httpExchange, e2);
            throw e2;
        }
    }

    static void failStream(HttpExchange httpExchange, Throwable th) {
        try {
            httpExchange.writeSse(errorFrame(th));
            httpExchange.writeSse(SseReader.DONE);
            httpExchange.endStreaming();
        } catch (Throwable th2) {
        }
    }

    static String errorFrame(Throwable th) {
        String simpleName;
        String str;
        String str2;
        if (th instanceof ApiContract.GatewayException) {
            ApiContract.GatewayException gatewayException = (ApiContract.GatewayException) th;
            simpleName = gatewayException.getMessage();
            str = gatewayException.type;
            str2 = gatewayException.code;
        } else {
            simpleName = th.getMessage() == null ? th.getClass().getSimpleName() : th.getMessage();
            str = "server_error";
            str2 = "internal_error";
        }
        try {
            JSONObject jSONObject = new JSONObject();
            if (simpleName == null) {
                simpleName = "internal error";
            }
            jSONObject.put("message", simpleName);
            jSONObject.put("type", str);
            jSONObject.put("code", str2);
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("error", jSONObject);
            return jSONObject2.toString();
        } catch (Throwable th2) {
            return "{\"error\":{\"message\":\"internal error\",\"type\":\"server_error\",\"code\":\"internal_error\"}}";
        }
    }

    public String responseEvent(String str, String str2) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("type", str);
        jSONObject.put("delta", str2);
        return jSONObject.toString();
    }

    static ApiContract.CompletionRequest fromChatRequest(JSONObject jSONObject, String str, Object obj) throws Exception {
        JSONArray optJSONArray = jSONObject.optJSONArray("messages");
        StringBuilder sb = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        if (optJSONArray != null) {
            for (int i = 0; i < optJSONArray.length(); i++) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("role", "user");
                    String flattenContent = flattenContent(optJSONObject.opt("content"));
                    if ("system".equals(optString) || "developer".equals(optString)) {
                        if (sb2.length() > 0) {
                            sb2.append("\n\n");
                        }
                        sb2.append(flattenContent);
                    } else {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(ROLE_PREFIX(optString)).append(flattenContent);
                    }
                }
            }
        }
        return new ApiContract.CompletionRequest("chatcmpl-" + Long.toHexString(System.currentTimeMillis()), str, ModelCatalog.resolveRole(str, customModels()), sb2.toString(), sb.toString(), reasoningRequested(str, jSONObject), jSONObject.optBoolean("web_search", true), jSONObject.optInt("max_tokens", jSONObject.optInt("max_completion_tokens", 0)), toolPlan(jSONObject), null, false);
    }

    private static ApiContract.CompletionRequest fromResponsesRequest(JSONObject jSONObject, String str) throws Exception {
        StringBuilder sb = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        Object opt = jSONObject.opt("input");
        if (opt instanceof JSONArray) {
            JSONArray jSONArray = (JSONArray) opt;
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("role", "user");
                    String flattenContent = flattenContent(optJSONObject.opt("content"));
                    if ("system".equals(optString) || "developer".equals(optString)) {
                        if (sb2.length() > 0) {
                            sb2.append("\n\n");
                        }
                        sb2.append(flattenContent);
                    } else {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(ROLE_PREFIX(optString)).append(flattenContent);
                    }
                }
            }
        } else if (opt != null) {
            sb.append(opt.toString());
        }
        String optString2 = jSONObject.optString("instructions", null);
        if (optString2 != null && !optString2.isEmpty()) {
            if (sb2.length() > 0) {
                sb2.append("\n\n");
            }
            sb2.append(optString2);
        }
        return new ApiContract.CompletionRequest("resp-" + Long.toHexString(System.currentTimeMillis()), str, ModelCatalog.resolveRole(str, customModels()), sb2.toString(), sb.toString(), reasoningRequested(str, jSONObject), true, jSONObject.optInt("max_output_tokens", 0), toolPlan(jSONObject), jSONObject.optString("previous_response_id", null), true);
    }

    private static String ROLE_PREFIX(String str) {
        if (ROLE_ASSISTANT.equals(str)) {
            return "Assistant: ";
        }
        if ("tool".equals(str)) {
            return "Tool result: ";
        }
        return "User: ";
    }

    static String flattenContent(Object obj) {
        if (obj == null) {
            return "";
        }
        if (obj instanceof String) {
            return (String) obj;
        }
        if (obj instanceof JSONArray) {
            JSONArray jSONArray = (JSONArray) obj;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("type", "text");
                    if ("text".equals(optString)) {
                        sb.append(optJSONObject.optString("text", ""));
                    } else if ("input_text".equals(optString)) {
                        sb.append(optJSONObject.optString("text", ""));
                    }
                }
            }
            return sb.toString();
        }
        return String.valueOf(obj);
    }

    private static boolean reasoningRequested(String str, JSONObject jSONObject) {
        if (ApiContract.MODEL_EXPERT.equals(ModelCatalog.resolveRole(str, customModels())) || LocalApiConfig.get().forceReasoning) {
            return true;
        }
        Object opt = jSONObject.opt("reasoning_effort");
        return (opt == null || "none".equals(String.valueOf(opt))) ? false : true;
    }

    private static ApiContract.ToolPlan toolPlan(JSONObject jSONObject) {
        final boolean z = (jSONObject.optJSONArray("tools") == null && jSONObject.optJSONArray("functions") == null) ? false : true;
        return new ApiContract.ToolPlan() {
            @Override // com.dsmod.probe.localapi.ApiContract.ToolPlan
            public boolean active() {
                return z;
            }
        };
    }

    private static List<Object> customModels() {
        return ModelCatalog.parseCustom(LocalApiConfig.get().customModelsJson);
    }

    private static JSONObject chatCompletionBody(String str, String str2, ApiContract.CompletionResult completionResult) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("role", ROLE_ASSISTANT);
        if (!completionResult.reasoning.isEmpty()) {
            jSONObject.put("reasoning_content", completionResult.reasoning);
        }
        jSONObject.put("content", completionResult.text);
        if (completionResult.hasToolCalls()) {
            jSONObject.put("tool_calls", renderToolCalls(completionResult));
        }
        JSONObject jSONObject2 = new JSONObject();
        jSONObject2.put("index", 0);
        jSONObject2.put("message", jSONObject);
        jSONObject2.put("finish_reason", completionResult.hasToolCalls() ? "tool_calls" : completionResult.finishReason);
        JSONObject jSONObject3 = new JSONObject();
        jSONObject3.put("id", str);
        jSONObject3.put("object", OBJECT_CHAT_COMPLETION);
        jSONObject3.put("created", System.currentTimeMillis() / 1000);
        jSONObject3.put("model", str2);
        jSONObject3.put("choices", new JSONArray((Collection) Collections.singletonList(jSONObject2)));
        jSONObject3.put("usage", usage(completionResult));
        return jSONObject3;
    }

    private static JSONObject responseBody(String str, String str2, ApiContract.CompletionResult completionResult, String str3) throws Exception {
        JSONArray jSONArray = new JSONArray();
        if (!completionResult.reasoning.isEmpty()) {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("type", "reasoning");
            jSONObject.put("summary", new JSONArray());
            jSONArray.put(jSONObject);
        }
        JSONObject jSONObject2 = new JSONObject();
        jSONObject2.put("type", "message");
        jSONObject2.put("role", ROLE_ASSISTANT);
        jSONObject2.put("status", "completed");
        JSONArray jSONArray2 = new JSONArray();
        JSONObject jSONObject3 = new JSONObject();
        jSONObject3.put("type", "output_text");
        jSONObject3.put("text", completionResult.text);
        jSONArray2.put(jSONObject3);
        jSONObject2.put("content", jSONArray2);
        jSONArray.put(jSONObject2);
        JSONObject jSONObject4 = new JSONObject();
        jSONObject4.put("id", str);
        jSONObject4.put("object", "response");
        jSONObject4.put("created_at", System.currentTimeMillis() / 1000);
        jSONObject4.put("status", "completed");
        jSONObject4.put("model", str2);
        jSONObject4.put("output", jSONArray);
        if (str3 != null && !str3.isEmpty()) {
            jSONObject4.put("previous_response_id", str3);
        }
        jSONObject4.put("usage", usage(completionResult));
        return jSONObject4;
    }

    private static JSONObject usage(ApiContract.CompletionResult completionResult) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("prompt_tokens", 0);
        jSONObject.put("completion_tokens", completionResult.text.length() / 4);
        jSONObject.put("total_tokens", completionResult.text.length() / 4);
        return jSONObject;
    }

    public static JSONObject chunkBuilder(String str, String str2) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("id", str);
        jSONObject.put("object", OBJECT_CHAT_CHUNK);
        jSONObject.put("created", System.currentTimeMillis() / 1000);
        if (str2 != null) {
            jSONObject.put("model", str2);
        }
        return jSONObject;
    }

    public static JSONArray choices(JSONObject jSONObject) throws Exception {
        JSONObject jSONObject2 = new JSONObject();
        jSONObject2.put("index", 0);
        jSONObject2.put("delta", jSONObject);
        return new JSONArray((Collection) Collections.singletonList(jSONObject2));
    }

    public static JSONObject contentDelta(String str) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("content", str);
        return jSONObject;
    }

    public static JSONObject reasoningDelta(String str) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("reasoning_content", str);
        return jSONObject;
    }

    private static JSONObject finishDelta(String str) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("finish_reason", str);
        return jSONObject;
    }

    private static JSONArray renderToolCalls(ApiContract.CompletionResult completionResult) throws Exception {
        JSONArray jSONArray = new JSONArray();
        for (int i = 0; i < completionResult.toolCalls.size(); i++) {
            ApiContract.ToolCall toolCall = completionResult.toolCalls.get(i);
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("name", toolCall.name);
            jSONObject.put("arguments", toolCall.arguments);
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("index", i);
            jSONObject2.put("id", toolCall.id);
            jSONObject2.put("type", toolCall.type);
            jSONObject2.put("function", jSONObject);
            jSONArray.put(jSONObject2);
        }
        return jSONArray;
    }

    private static void emitToolCalls(HttpExchange httpExchange, String str, ApiContract.CompletionResult completionResult) throws Exception {
        for (int i = 0; i < completionResult.toolCalls.size(); i++) {
            ApiContract.ToolCall toolCall = completionResult.toolCalls.get(i);
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("name", toolCall.name);
            jSONObject.put("arguments", toolCall.arguments);
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("index", i);
            jSONObject2.put("id", toolCall.id);
            jSONObject2.put("type", toolCall.type);
            jSONObject2.put("function", jSONObject);
            JSONObject jSONObject3 = new JSONObject();
            jSONObject3.put("tool_calls", new JSONArray((Collection) Collections.singletonList(jSONObject2)));
            httpExchange.writeSse(chunkBuilder(str, null).put("choices", choices(jSONObject3)).toString());
        }
    }

    private static ApiContract.DeltaSink noopSink() {
        return new ApiContract.DeltaSink() {
            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public boolean isCancelled() {
                return false;
            }

            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public void onReasoning(String str) {
            }

            @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
            public void onText(String str) {
            }
        };
    }

    static JSONObject parseBody(HttpExchange httpExchange) throws Exception {
        byte[] body = httpExchange.body();
        if (body.length == 0) {
            return new JSONObject();
        }
        try {
            return new JSONObject(new String(body, "UTF-8"));
        } catch (JSONException e) {
            throw new ApiContract.GatewayException(LocalApiStats.LOG_LINES, "invalid_json", "invalid_request_error", "Request body is not valid JSON.");
        }
    }
}
