package com.dsmod.probe.localapi;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class HttpServer {
    private Thread acceptThread;
    private final Handler handler;
    private final ExecutorService pool;
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeJobs = new AtomicInteger();

    public interface Handler {
        void handle(HttpExchange httpExchange) throws IOException;
    }

    public HttpServer(Handler handler, int i) {
        this.handler = handler;
        this.pool = Executors.newFixedThreadPool(Math.max(2, i));
    }

    public int start(ServerSocket serverSocket) throws IOException {
        this.serverSocket = serverSocket;
        this.running.set(true);
        this.acceptThread = new Thread(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                HttpServer.this.acceptLoop();
            }
        }, "Deekseep-local-api");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        return serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return this.running.get() && this.acceptThread != null && this.acceptThread.isAlive();
    }

    public int boundPort() {
        if (this.serverSocket == null) {
            return 0;
        }
        return this.serverSocket.getLocalPort();
    }

    public String boundHost() {
        InetAddress inetAddress;
        if (this.serverSocket == null || (inetAddress = this.serverSocket.getInetAddress()) == null) {
            return null;
        }
        return inetAddress.getHostAddress();
    }

    public void stop() {
        this.running.set(false);
        ServerSocket serverSocket = this.serverSocket;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
            }
        }
        Thread thread = this.acceptThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        this.pool.shutdownNow();
    }

    public int activeRequests() {
        return this.activeJobs.get();
    }

    public void acceptLoop() {
        while (this.running.get()) {
            try {
                final Socket accept = this.serverSocket.accept();
                configure(accept);
                this.activeJobs.incrementAndGet();
                try {
                    this.pool.execute(new Runnable() {
                        @Override // java.lang.Runnable
                        public void run() {
                            HttpServer.this.serve(accept);
                        }
                    });
                } catch (Throwable th) {
                    this.activeJobs.decrementAndGet();
                    close(accept);
                }
            } catch (SocketException e) {
                if (!this.running.get()) {
                    return;
                }
            } catch (IOException e2) {
                return;
            }
        }
    }

    private void configure(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            socket.setKeepAlive(false);
        } catch (SocketException e) {
        }
    }

    public void serve(Socket socket) {
        try {
            HttpExchange parse = HttpExchange.parse(socket.getInputStream(), socket.getOutputStream());
            if (parse != null) {
                try {
                    this.handler.handle(parse);
                } catch (Throwable th) {
                    try {
                        if (!parse.headersWritten()) {
                            parse.respond(500, "application/json", errorBody(500, "internal_error", String.valueOf(th.getMessage())));
                        }
                    } catch (Throwable th2) {
                    }
                }
            }
        } catch (Throwable th3) {
        }
        this.activeJobs.decrementAndGet();
        close(socket);
    }

    private static byte[] errorBody(int i, String str, String str2) {
        try {
            JSONObject jSONObject = new JSONObject();
            if (str2 == null) {
                str2 = "";
            }
            jSONObject.put("message", str2);
            jSONObject.put("type", i >= 500 ? "server_error" : "invalid_request_error");
            jSONObject.put("code", str);
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("error", jSONObject);
            return jSONObject2.toString().getBytes("UTF-8");
        } catch (Throwable th) {
            return "{\"error\":{\"message\":\"internal error\"}}".getBytes();
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
        }
    }
}
