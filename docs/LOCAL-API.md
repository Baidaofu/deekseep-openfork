# Local API

`Deekseep` can expose the signed-in DeepSeek account as a local, OpenAI- and
Anthropic-compatible HTTP endpoint. The listener runs **inside the DeepSeek process**, so a
request is executed through DeepSeek's own authenticated transport and PoW manager instead of a
re-implemented client. Nothing is proxied through a third party.

Enable it from the module hub: **本地 API · 实验性 / Local API · Experimental**.

## Endpoints

The gateway serves one protocol mode at a time (`openai` or `anthropic`); the other family's
routes answer `404` with `{"error":{"code":"protocol_mismatch"}}`.

| Mode | Method | Path | Notes |
|---|---|---|---|
| openai | GET | `/v1/models` | Advertised + custom model ids |
| openai | POST | `/v1/chat/completions` | JSON or SSE |
| openai | POST | `/v1/responses` | JSON or SSE |
| anthropic | POST | `/v1/messages` | JSON or SSE |
| anthropic | POST | `/v1/messages/count_tokens` | Token estimate |

* Auth: `Authorization: Bearer <key>` or `x-api-key: <key>`. Anything else is `401`.
* Default listen address `http://127.0.0.1:8765`, port range 1024–65535.
* `allowLan` binds `0.0.0.0`; LAN clients still need the key.
* Streaming writes `data:` frames; OpenAI terminates with `data: [DONE]`, Anthropic ends with
  `message_stop`.
* Reasoning is emitted on its own channel (`reasoning_content` delta / `thinking_delta` block) and
  never mixed into the answer text.

Client examples:

```bash
# OpenAI
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Authorization: Bearer $DEEKSEEP_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"hi"}]}'

# Anthropic / Claude Code
export ANTHROPIC_BASE_URL=http://127.0.0.1:8765
export ANTHROPIC_AUTH_TOKEN=$DEEKSEEP_KEY
```

## Configuration

`dq0_config.json` lives in the **host** app's files directory
(`/data/data/com.deepseek.chat/files/`), because the listener runs in that process:

```json
{
  "enabled": true,
  "port": 8765,
  "protocolMode": "openai",
  "apiKey": "…",
  "https": false,
  "allowLan": false,
  "keepAliveNotification": false,
  "serialRequests": false,
  "antiCensor": false,
  "injectSystemPrompt": false,
  "systemPrompt": "",
  "longContextRelay": false,
  "forceReasoning": false,
  "customModelsJson": "[]",
  "publicRootUrl": "",
  "autoRecovery": true
}
```

Runtime state is written next to it: `deekseep_api.log`, `deekseep_api_status.json`, `dq0.txt`.

## How a request is served

```
client → LocalApiServer (HttpServer, 8 workers)
       → OpenAiRouter / AnthropicRouter      protocol → ApiContract.CompletionRequest
       → HostBackend                          queueing, session lifetime
       → Main.NativeBridge                    openSession / generate / closeSession
       → Main.createThrowawaySession          host i91 create endpoint (Kotlin suspend)
       → Main.mintCompletionPow               host q71 PoW manager
       → Main.newNativeCompletionRequest      clone captured request, set sid/prompt/model/pow
       → host transport r92.b(request, null)  → Flow
       → Main.collectFlowStreaming            patch frames → DeltaSink
       → SSE / JSON response
```

`Main.collectFlowStreaming` decodes the host's JSON-patch stream. New fragments arrive as a
**snapshot** (`SET`) that already contains the first token, later tokens arrive as `APPEND` deltas
or bare `{"v":"…"}` continuations. Snapshots are merged against what the client has already been
sent (`Main.snapshotRemainder`), which is what keeps the first token from being dropped and the
chain of thought out of the answer.

The gateway prefers the native bridge. Until `Main` has captured the host transport (first network
call after start-up) it falls back to the app-level HTTP client, and `LocalApi.SwitchingBridge`
upgrades every subsequent call to the native path without restarting the listener.

## Status

Verified on a Nothing Phone (A142), Android 16, KernelSU + LSPosed 2.2.0, DeepSeek **2.3.6 /
versionCode 249** (mainland):

* `/v1/models` 200, 401 without a key
* `/v1/chat/completions` JSON and SSE return the complete answer
* `/v1/messages` and `/v1/messages/count_tokens` in Anthropic mode
* protocol-mismatch routes rejected with the documented error

Known gaps:

* `usage` counters are reported as `0`; the native stream does not carry a usage block.
* Each request uses a throwaway session, so there is no cross-request conversation reuse yet.
* `forceReasoning`, `longContextRelay`, `antiCensor` and `injectSystemPrompt` are persisted and
  surfaced in the UI, but only the system-prompt injection is applied to the native request.
* HTTPS (`https: true`) reuses the module's per-device CA, but has not been exercised on a device.

## Provenance

The Local API was part of this project while it was still open source; the published 1.7.4 Fix
source edition ships only its host-compatibility helpers (`HostCompat.localApiSession*`) and the
settings entry point. This directory restores the feature as buildable sources, ported onto the
current tree and re-verified on hardware. It remains under the repository licence (GPL-3.0-only).
