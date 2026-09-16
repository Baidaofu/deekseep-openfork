package com.dsmod.probe.localapi;

import android.content.Context;
import com.dsmod.probe.localapi.LocalApiConfig;

public final class LocalApi {
    private static final Object LOCK = new Object();
    private static volatile HostBackend backend;
    private static volatile LocalApiServer server;

    private LocalApi() {
    }

    public static void initialize(Context context) {
        Context applicationContext = context == null ? null : context.getApplicationContext();
        if (applicationContext == null) {
            return;
        }
        LocalApiConfig.initialize(applicationContext.getFilesDir());
        LocalApiStats.initialize(applicationContext.getFilesDir());
    }

    public static boolean start(Context context) throws Exception {
        Context applicationContext = context == null ? null : context.getApplicationContext();
        if (applicationContext == null) {
            return false;
        }
        initialize(applicationContext);
        synchronized (LOCK) {
            if (server != null && server.isRunning()) {
                return true;
            }
            LocalApiServer localApiServer = new LocalApiServer(backend(applicationContext));
            if (!localApiServer.start(applicationContext)) {
                return false;
            }
            server = localApiServer;
            LocalApiConfig.setEnabled(true);
            LocalApiStats.log("listener started on " + localApiServer.loopbackRoot());
            return true;
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            LocalApiServer localApiServer = server;
            if (localApiServer != null) {
                localApiServer.stop();
                LocalApiStats.log("listener stopped");
            }
            server = null;
            LocalApiConfig.setEnabled(false);
        }
    }

    public static boolean isRunning() {
        LocalApiServer localApiServer = server;
        return localApiServer != null && localApiServer.isRunning();
    }

    public static void onHostResumed(Context context) {
        Context applicationContext = context == null ? null : context.getApplicationContext();
        if (applicationContext == null) {
            return;
        }
        try {
            initialize(applicationContext);
            LocalApiConfig.State state = LocalApiConfig.get();
            if (state.enabled) {
                if (!isRunning()) {
                    start(applicationContext);
                }
                if (state.keepAliveNotification) {
                    try {
                        applicationContext.startService(KeepAliveService.createIntent(applicationContext));
                        return;
                    } catch (Throwable th) {
                        return;
                    }
                }
                return;
            }
            if (isRunning()) {
                stop();
                try {
                    applicationContext.stopService(KeepAliveService.createIntent(applicationContext));
                } catch (Throwable th2) {
                }
            }
        } catch (Throwable th3) {
            try {
                LocalApiStats.log("autostart failed: " + th3);
            } catch (Throwable th4) {
            }
        }
    }

    public static String status(Context context) {
        StringBuilder sb = new StringBuilder();
        LocalApiServer localApiServer = server;
        LocalApiConfig.State state = LocalApiConfig.get();
        sb.append("enabled=").append(state.enabled);
        sb.append(" running=").append(isRunning());
        sb.append(" protocol=").append(state.protocolMode);
        if (localApiServer != null) {
            sb.append(" scheme=").append(localApiServer.scheme());
            sb.append(" host=").append(localApiServer.boundHost());
            sb.append(" port=").append(localApiServer.httpPort() == 0 ? localApiServer.httpsPort() : localApiServer.httpPort());
            sb.append('\n').append("openai=").append(localApiServer.openAiBaseUrl());
            sb.append('\n').append("anthropic=").append(localApiServer.anthropicBaseUrl());
        }
        sb.append('\n').append("keepalive=").append(KeepAliveService.isRunning());
        sb.append('\n').append(LocalApiStats.summary());
        String publicRoot = PublicTunnel.publicRoot();
        if (publicRoot != null && !publicRoot.isEmpty()) {
            sb.append('\n').append("public=").append(publicRoot);
        }
        return sb.toString();
    }

    public static String openAiBaseUrl() {
        LocalApiServer localApiServer = server;
        if (localApiServer == null) {
            return null;
        }
        return localApiServer.openAiBaseUrl();
    }

    public static String anthropicBaseUrl() {
        LocalApiServer localApiServer = server;
        if (localApiServer == null) {
            return null;
        }
        return localApiServer.anthropicBaseUrl();
    }

    public static String connectionCard(Context context) {
        LocalApiServer localApiServer = server;
        if (localApiServer == null) {
            return "Local API is stopped";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Deekseep Local API\n");
        sb.append("API key: ").append(LocalApiConfig.get().apiKey).append('\n');
        sb.append("Protocol: ").append(LocalApiConfig.get().protocolMode).append('\n');
        sb.append("OpenAI base URL: ").append(localApiServer.openAiBaseUrl()).append('\n');
        sb.append("Anthropic base URL: ").append(localApiServer.anthropicBaseUrl()).append('\n');
        if (PublicTunnel.publicRoot() != null && !PublicTunnel.publicRoot().isEmpty()) {
            sb.append("Public origin: ").append(PublicTunnel.publicRoot()).append('\n');
        }
        return sb.toString();
    }

    public static void setBackend(HostBackend hostBackend) {
        backend = hostBackend;
    }

    /**
     * Publishes the in-process bridge onto the captured DeepSeek transport. The listener can be
     * started from {@link #onHostResumed} before the host has issued its first request, so the
     * gateway must not latch the app-level fallback: every call is routed through
     * {@link SwitchingBridge}, which prefers the native host bridge as soon as it exists.
     */
    public static void setNativeBridge(HostBackend.Bridge bridge) {
        nativeBridge = bridge;
    }

    private static HostBackend backend(Context context) {
        HostBackend hostBackend = backend;
        if (hostBackend != null) {
            return hostBackend;
        }
        HostBackend created = new HostBackend(context, SWITCHING);
        backend = created;
        return created;
    }

    /** Prefers the in-process native bridge; falls back to the app-level HTTP client. */
    private static final class SwitchingBridge implements HostBackend.Bridge {
        private HostBackend.Bridge active() {
            HostBackend.Bridge bridge = nativeBridge;
            return bridge != null ? bridge : FALLBACK;
        }

        @Override public String openSession(String clientKey, String scope) throws Exception {
            return active().openSession(clientKey, scope);
        }

        @Override public ApiContract.CompletionResult generate(ApiContract.CompletionRequest request,
                                                              String sessionId,
                                                              ApiContract.DeltaSink sink) throws Exception {
            return active().generate(request, sessionId, sink);
        }

        @Override public void closeSession(String sessionId) throws Exception {
            active().closeSession(sessionId);
        }
    }

    private static final ReflectiveBridge FALLBACK = new ReflectiveBridge();
    private static final HostBackend.Bridge SWITCHING = new SwitchingBridge();
    private static volatile HostBackend.Bridge nativeBridge;
}
