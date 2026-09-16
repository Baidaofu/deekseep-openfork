package com.dsmod.probe.localapi;

import android.content.Context;
import com.dsmod.probe.localapi.ApiContract;
import com.dsmod.probe.localapi.HttpServer;
import com.dsmod.probe.localapi.LocalApiConfig;
import com.dsmod.probe.localapi.TlsDirector;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import org.json.JSONObject;

public final class LocalApiServer implements HttpServer.Handler {
    static final long HEARTBEAT_INTERVAL_MS = 5000;
    private static final int WORKERS = 8;
    private final AnthropicRouter anthropic;
    private volatile String boundHost;
    private volatile int httpPort;
    private volatile int httpsPort;
    private final OpenAiRouter openAi;
    private HttpServer server;

    public LocalApiServer(ApiContract.Backend backend) {
        this.openAi = new OpenAiRouter(backend);
        this.anthropic = new AnthropicRouter(backend);
    }

    public synchronized boolean start(Context context) throws Exception {
        ServerSocket serverSocket;
        if (this.server != null && this.server.isRunning()) {
            return true;
        }
        LocalApiConfig.State state = LocalApiConfig.get();
        InetAddress loopbackAddress = state.allowLan ? null : InetAddress.getLoopbackAddress();
        if (state.https) {
            serverSocket = openTlsSocket(context, state.port, loopbackAddress);
        } else {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(loopbackAddress, state.port));
        }
        HttpServer httpServer = new HttpServer(this, 8);
        httpServer.start(serverSocket);
        this.server = httpServer;
        this.httpPort = httpServer.boundPort();
        this.httpsPort = state.https ? httpServer.boundPort() : 0;
        this.boundHost = state.allowLan ? "0.0.0.0" : "127.0.0.1";
        return true;
    }

    public synchronized void stop() {
        if (this.server != null) {
            this.server.stop();
            this.server = null;
        }
        this.httpPort = 0;
        this.httpsPort = 0;
    }

    public boolean isRunning() {
        return this.server != null && this.server.isRunning();
    }

    public int httpPort() {
        return this.httpPort;
    }

    public int httpsPort() {
        return this.httpsPort;
    }

    public String boundHost() {
        return this.boundHost;
    }

    public String scheme() {
        return this.httpsPort > 0 ? "https" : "http";
    }

    public String openAiBaseUrl() {
        return authority() + "/v1";
    }

    public String anthropicBaseUrl() {
        return authority();
    }

    private String authority() {
        if (!LocalApiConfig.get().allowLan) {
            return scheme() + "://127.0.0.1:" + (this.httpsPort > 0 ? this.httpsPort : this.httpPort);
        }
        String lanAddress = TlsDirector.lanAddress();
        if (lanAddress == null) {
            lanAddress = "127.0.0.1";
        }
        return scheme() + "://" + lanAddress + ":" + (this.httpsPort > 0 ? this.httpsPort : this.httpPort);
    }

    public String loopbackRoot() {
        return scheme() + "://127.0.0.1:" + (this.httpsPort > 0 ? this.httpsPort : this.httpPort);
    }

    private ServerSocket openTlsSocket(Context context, int i, InetAddress inetAddress) throws Exception {
        TlsDirector.prepare(context);
        TlsDirector.Material material = TlsDirector.material(context);
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(material.serverStore, material.password);
        SSLContext sSLContext = SSLContext.getInstance("TLS");
        sSLContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        SSLServerSocket sSLServerSocket = (SSLServerSocket) sSLContext.getServerSocketFactory().createServerSocket();
        sSLServerSocket.setReuseAddress(true);
        restrictProtocols(sSLServerSocket);
        sSLServerSocket.setUseClientMode(false);
        sSLServerSocket.setNeedClientAuth(false);
        sSLServerSocket.setWantClientAuth(false);
        sSLServerSocket.bind(new InetSocketAddress(inetAddress, i));
        return sSLServerSocket;
    }

    private void restrictProtocols(SSLServerSocket sSLServerSocket) {
        ArrayList arrayList = new ArrayList();
        for (String str : sSLServerSocket.getSupportedProtocols()) {
            if ("TLSv1.3".equals(str) || "TLSv1.2".equals(str)) {
                arrayList.add(str);
            }
        }
        if (!arrayList.isEmpty()) {
            sSLServerSocket.setEnabledProtocols((String[]) arrayList.toArray(new String[arrayList.size()]));
        }
    }

    @Override // com.dsmod.probe.localapi.HttpServer.Handler
    public void handle(HttpExchange httpExchange) throws IOException {
        if ("OPTIONS".equals(httpExchange.method)) {
            httpExchange.respond(204, "text/plain", addCors(new byte[0]));
            return;
        }
        long currentTimeMillis = System.currentTimeMillis();
        try {
            authorize(httpExchange);
            route(httpExchange);
        } catch (ApiContract.GatewayException e) {
            noteFailure(httpExchange, currentTimeMillis, e.status, e.code);
            respondError(httpExchange, e.status, e.code, e.type, e.getMessage());
        } catch (Throwable th) {
            String simpleName = th.getMessage() == null ? th.getClass().getSimpleName() : th.getMessage();
            noteFailure(httpExchange, currentTimeMillis, 500, "internal_error");
            respondError(httpExchange, 500, "internal_error", "server_error", simpleName);
        }
    }

    private static void noteFailure(HttpExchange httpExchange, long j, int i, String str) {
        String path = httpExchange.path();
        if (!isCompletionPath(path)) {
            return;
        }
        LocalApiStats.recordFailure(System.currentTimeMillis() - j);
        LocalApiStats.log("failed " + httpExchange.method + " " + path + " -> " + i + " " + str + " (" + (System.currentTimeMillis() - j) + "ms)");
    }

    static boolean isCompletionPath(String str) {
        return "/v1/chat/completions".equals(str) || "/v1/responses".equals(str) || "/v1/messages".equals(str);
    }

    private void authorize(HttpExchange httpExchange) throws ApiContract.GatewayException {
        if ("GET".equals(httpExchange.method) && "/health".equals(httpExchange.path())) {
            return;
        }
        String str = LocalApiConfig.get().apiKey;
        String bearer = bearer(httpExchange.header("authorization"));
        if (bearer == null) {
            bearer = bearer(httpExchange.header("x-api-key"));
        }
        if (str == null || bearer == null || !constantTimeEquals(str, bearer)) {
            throw new ApiContract.GatewayException(401, "invalid_api_key", "authentication_error", "Incorrect API key provided. Use the key shown in Deekseep settings.");
        }
    }

    static String bearer(String str) {
        if (str == null) {
            return null;
        }
        String trim = str.trim();
        if (trim.length() > 7 && trim.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            return trim.substring(7).trim();
        }
        if (trim.isEmpty()) {
            return null;
        }
        return trim;
    }

    static boolean constantTimeEquals(String str, String str2) {
        if (str == null || str2 == null || str.length() != str2.length()) {
            return false;
        }
        int i = 0;
        for (int i2 = 0; i2 < str.length(); i2++) {
            i |= str.charAt(i2) ^ str2.charAt(i2);
        }
        return i == 0;
    }

    private void route(HttpExchange httpExchange) throws Exception {
        String path = httpExchange.path();
        boolean equals = ApiContract.PROTOCOL_OPENAI.equals(LocalApiConfig.get().protocolMode);
        if ("/health".equals(path)) {
            httpExchange.respond(200, "application/json", health());
            return;
        }
        if ("/v1/models".equals(path)) {
            require(httpExchange, equals, "GET");
            this.openAi.models(httpExchange);
            return;
        }
        if ("/v1/chat/completions".equals(path)) {
            require(httpExchange, equals, "POST");
            this.openAi.chatCompletions(httpExchange);
            return;
        }
        if ("/v1/responses".equals(path)) {
            require(httpExchange, equals, "POST");
            this.openAi.responses(httpExchange);
        } else if ("/v1/messages".equals(path)) {
            require(httpExchange, !equals, "POST");
            this.anthropic.messages(httpExchange);
        } else {
            if ("/v1/messages/count_tokens".equals(path)) {
                require(httpExchange, !equals, "POST");
                this.anthropic.countTokens(httpExchange);
                return;
            }
            throw new ApiContract.GatewayException(404, "unknown_endpoint", "invalid_request_error", "Unknown endpoint: " + path);
        }
    }

    private void require(HttpExchange httpExchange, boolean z, String str) throws ApiContract.GatewayException {
        if (!z) {
            throw new ApiContract.GatewayException(404, "protocol_mismatch", "invalid_request_error", "This endpoint is not served in the active protocol mode.");
        }
        if (!str.equals(httpExchange.method)) {
            throw new ApiContract.GatewayException(405, "method_not_allowed", "invalid_request_error", "Expected " + str + ", got " + httpExchange.method);
        }
    }

    private byte[] health() throws IOException {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("status", isRunning() ? "ok" : "stopped");
            jSONObject.put("protocol", LocalApiConfig.get().protocolMode);
            jSONObject.put("https", this.httpsPort > 0);
            jSONObject.put("port", this.httpsPort > 0 ? this.httpsPort : this.httpPort);
            jSONObject.put("stats", LocalApiStats.snapshot());
            return jSONObject.toString().getBytes("UTF-8");
        } catch (Throwable th) {
            return "{\"status\":\"degraded\"}".getBytes("UTF-8");
        }
    }

    static void respondError(HttpExchange httpExchange, int i, String str, String str2, String str3) throws IOException {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("message", str3);
            jSONObject.put("type", str2);
            jSONObject.put("code", str);
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("error", jSONObject);
            if (!httpExchange.headersWritten()) {
                httpExchange.respond(i, "application/json", jSONObject2.toString().getBytes("UTF-8"));
            }
        } catch (Throwable th) {
            httpExchange.respond(i, "application/json", "{\"error\":{\"message\":\"internal error\"}}".getBytes("UTF-8"));
        }
    }

    private static byte[] addCors(byte[] bArr) {
        return bArr;
    }

    private static SSLServerSocketFactory unusedFactory() {
        return (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
    }
}
