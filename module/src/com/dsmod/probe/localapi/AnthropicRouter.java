package com.dsmod.probe.localapi;

import com.dsmod.probe.localapi.ApiContract;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AnthropicRouter {
    private static final String VERSION = "2023-06-01";
    private final ApiContract.Backend backend;

    public AnthropicRouter(ApiContract.Backend backend) {
        this.backend = backend;
    }

    public void messages(HttpExchange httpExchange) throws Exception {
        JSONObject parseBody = OpenAiRouter.parseBody(httpExchange);
        String optString = parseBody.optString("model", null);
        boolean optBoolean = parseBody.optBoolean("stream", false);
        ApiContract.CompletionRequest fromMessagesRequest = fromMessagesRequest(parseBody, optString);
        long currentTimeMillis = System.currentTimeMillis();
        if (!this.backend.isReady()) {
            throw new ApiContract.GatewayException(503, "backend_not_ready", "server_error", this.backend.readinessDetail());
        }
        if (optBoolean) {
            streamMessages(httpExchange, fromMessagesRequest, currentTimeMillis);
        } else {
            httpExchange.respond(200, "application/json", messageBody(this.backend.complete(fromMessagesRequest, noopSink()), optString).toString().getBytes("UTF-8"));
            LocalApiStats.recordSuccess(false, fromMessagesRequest.reasoning, System.currentTimeMillis() - currentTimeMillis);
        }
    }

    public void countTokens(HttpExchange httpExchange) throws Exception {
        JSONArray optJSONArray = OpenAiRouter.parseBody(httpExchange).optJSONArray("messages");
        int i = 0;
        if (optJSONArray != null) {
            int i2 = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    i2 += OpenAiRouter.flattenContent(optJSONObject.opt("content")).length();
                }
                i++;
            }
            i = i2;
        }
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("input_tokens", Math.max(1, i / 4));
        httpExchange.respond(200, "application/json", jSONObject.toString().getBytes("UTF-8"));
    }

    private void streamMessages(final HttpExchange httpExchange, ApiContract.CompletionRequest completionRequest, long j) throws Exception {
        httpExchange.startStreaming(200, "text/event-stream");
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("type", "message_start");
        jSONObject.put("message", new JSONObject().put("id", completionRequest.requestId).put("type", "message").put("role", "assistant").put("model", completionRequest.requestedModel).put("content", new JSONArray()).put("stop_reason", JSONObject.NULL).put("usage", new JSONObject().put("input_tokens", 0).put("output_tokens", 0)));
        httpExchange.writeSse(jSONObject.toString());
        if (completionRequest.reasoning) {
            httpExchange.writeSse(blockStart("thinking").toString());
        }
        httpExchange.writeSse(blockStart("text").toString());
        try {
            ApiContract.CompletionResult complete = this.backend.complete(completionRequest, new ApiContract.DeltaSink() {
                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public boolean isCancelled() {
                    return false;
                }

                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public void onReasoning(String str) throws Exception {
                    if (str != null && !str.isEmpty()) {
                        httpExchange.writeSse(AnthropicRouter.blockDelta("thinking_delta", "thinking", str).toString());
                    }
                }

                @Override // com.dsmod.probe.localapi.ApiContract.DeltaSink
                public void onText(String str) throws Exception {
                    if (str != null && !str.isEmpty()) {
                        httpExchange.writeSse(AnthropicRouter.blockDelta("text_delta", "text", str).toString());
                    }
                }
            });
            if (completionRequest.reasoning) {
                httpExchange.writeSse(blockStop().put("index", 0).toString());
            }
            httpExchange.writeSse(blockStop().put("index", completionRequest.reasoning ? 1 : 0).toString());
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("type", "message_delta");
            jSONObject2.put("delta", new JSONObject().put("stop_reason", complete.hasToolCalls() ? "tool_use" : "end_turn"));
            jSONObject2.put("usage", new JSONObject().put("output_tokens", Math.max(1, complete.text.length() / 4)));
            httpExchange.writeSse(jSONObject2.toString());
            httpExchange.writeSse(new JSONObject().put("type", "message_stop").toString());
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
            httpExchange.endStreaming();
        } catch (Throwable th2) {
        }
    }

    static String errorFrame(Throwable th) {
        String simpleName;
        String str = "api_error";
        if (th instanceof ApiContract.GatewayException) {
            ApiContract.GatewayException gatewayException = (ApiContract.GatewayException) th;
            simpleName = gatewayException.getMessage();
            if ("authentication_error".equals(gatewayException.type)) {
                str = "authentication_error";
            } else if ("invalid_request_error".equals(gatewayException.type)) {
                str = "invalid_request_error";
            }
        } else {
            simpleName = th.getMessage() == null ? th.getClass().getSimpleName() : th.getMessage();
        }
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("type", str);
            if (simpleName == null) {
                simpleName = "internal error";
            }
            jSONObject.put("message", simpleName);
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("type", "error");
            jSONObject2.put("error", jSONObject);
            return jSONObject2.toString();
        } catch (Throwable th2) {
            return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"internal error\"}}";
        }
    }

    private static JSONObject blockStart(String str) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("type", "content_block_start");
        JSONObject jSONObject2 = new JSONObject();
        jSONObject2.put("type", str);
        if ("thinking".equals(str)) {
            jSONObject2.put("thinking", "");
        } else if ("text".equals(str)) {
            jSONObject2.put("text", "");
        }
        jSONObject.put("content_block", jSONObject2);
        return jSONObject;
    }

    public static JSONObject blockDelta(String str, String str2, String str3) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("type", "content_block_delta");
        JSONObject jSONObject2 = new JSONObject();
        jSONObject2.put("type", str);
        jSONObject2.put(str2, str3);
        jSONObject.put("delta", jSONObject2);
        return jSONObject;
    }

    private static JSONObject blockStop() throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("type", "content_block_stop");
        return jSONObject;
    }

    private static ApiContract.CompletionRequest fromMessagesRequest(JSONObject jSONObject, String str) throws Exception {
        StringBuilder sb = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        Object opt = jSONObject.opt("system");
        if (opt != null) {
            sb2.append(OpenAiRouter.flattenContent(opt));
        }
        JSONArray optJSONArray = jSONObject.optJSONArray("messages");
        if (optJSONArray != null) {
            for (int i = 0; i < optJSONArray.length(); i++) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("role", "user");
                    String flattenContent = OpenAiRouter.flattenContent(optJSONObject.opt("content"));
                    if ("assistant".equals(optString)) {
                        sb.append("Assistant: ").append(flattenContent);
                    } else {
                        sb.append("User: ").append(flattenContent);
                    }
                    if (i < optJSONArray.length() - 1) {
                        sb.append('\n');
                    }
                }
            }
        }
        return new ApiContract.CompletionRequest("msg_" + Long.toHexString(System.currentTimeMillis()), str, ModelCatalog.resolveRole(str, customModels()), sb2.toString(), sb.toString(), jSONObject.optJSONObject("thinking") != null || ApiContract.MODEL_EXPERT.equals(ModelCatalog.resolveRole(str, customModels())) || LocalApiConfig.get().forceReasoning, true, jSONObject.optInt("max_tokens", 0), toolPlan(jSONObject), null, false);
    }

    private static ApiContract.ToolPlan toolPlan(JSONObject jSONObject) {
        final boolean z = jSONObject.optJSONArray("tools") != null;
        return new ApiContract.ToolPlan() {
            @Override // com.dsmod.probe.localapi.ApiContract.ToolPlan
            public boolean active() {
                return z;
            }
        };
    }

    private static JSONObject messageBody(ApiContract.CompletionResult completionResult, String str) throws Exception {
        JSONArray jSONArray = new JSONArray();
        if (!completionResult.reasoning.isEmpty()) {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("type", "thinking");
            jSONObject.put("thinking", completionResult.reasoning);
            jSONArray.put(jSONObject);
        }
        if (!completionResult.text.isEmpty()) {
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("type", "text");
            jSONObject2.put("text", completionResult.text);
            jSONArray.put(jSONObject2);
        }
        Iterator<ApiContract.ToolCall> it = completionResult.toolCalls.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            ApiContract.ToolCall next = it.next();
            JSONObject jSONObject3 = new JSONObject();
            jSONObject3.put("type", "tool_use");
            jSONObject3.put("id", next.id);
            jSONObject3.put("name", next.name);
            jSONObject3.put("input", new JSONObject(next.arguments));
            jSONArray.put(jSONObject3);
        }
        JSONObject jSONObject4 = new JSONObject();
        jSONObject4.put("id", "msg_" + Long.toHexString(System.currentTimeMillis()));
        jSONObject4.put("type", "message");
        jSONObject4.put("role", "assistant");
        jSONObject4.put("model", str);
        jSONObject4.put("content", jSONArray);
        jSONObject4.put("stop_reason", completionResult.hasToolCalls() ? "tool_use" : "end_turn");
        jSONObject4.put("usage", new JSONObject().put("input_tokens", 0).put("output_tokens", Math.max(1, completionResult.text.length() / 4)));
        jSONObject4.put("anthropic_version", VERSION);
        return jSONObject4;
    }

    private static List<Object> customModels() {
        return ModelCatalog.parseCustom(LocalApiConfig.get().customModelsJson);
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
}
