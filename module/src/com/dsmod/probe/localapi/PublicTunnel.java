package com.dsmod.probe.localapi;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public final class PublicTunnel {
    private static final AtomicReference<Status> STATUS = new AtomicReference<>(new Status("off", null, null));

    public enum Transport {
        AUTO("auto"),
        HTTP2("http2"),
        QUIC("quic");

        public final String value;

        Transport(String str) {
            this.value = str;
        }

        public Transport next() {
            switch (this) {
                case AUTO:
                    return HTTP2;
                case HTTP2:
                    return QUIC;
                default:
                    return AUTO;
            }
        }

        public static Transport fromValue(String str) {
            for (Transport transport : values()) {
                if (transport.value.equals(str)) {
                    return transport;
                }
            }
            return AUTO;
        }
    }

    public static final class Status {
        public final String error;
        public final String state;
        public final String url;

        public Status(String str, String str2, String str3) {
            this.state = str;
            this.url = str2;
            this.error = str3;
        }
    }

    private PublicTunnel() {
    }

    public static Status status() {
        return STATUS.get();
    }

    private static void publish(String str, String str2, String str3) {
        STATUS.set(new Status(str, str2, str3));
        LocalApiStats.log("tunnel " + str + (str2 == null ? "" : " " + str2) + (str3 != null ? " " + str3 : ""));
    }

    public static synchronized void stop() {
        synchronized (PublicTunnel.class) {
            publish("off", null, null);
        }
    }

    public static synchronized void setPublicRoot(String str) {
        synchronized (PublicTunnel.class) {
            LocalApiConfig.setPublicRootUrl(str == null ? "" : str.trim());
        }
    }

    public static String publicRoot() {
        return LocalApiConfig.get().publicRootUrl;
    }

    public static String validateRoot(String str) {
        if (str == null || str.trim().isEmpty()) {
            return "";
        }
        String trim = str.trim();
        if (!trim.startsWith("http://") && !trim.startsWith("https://")) {
            return null;
        }
        try {
            Uri parse = Uri.parse(trim);
            if (parse.getHost() != null) {
                if (!parse.getHost().isEmpty()) {
                    return trim;
                }
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    static File workDirectory(Context context) {
        Context applicationContext = context == null ? null : context.getApplicationContext();
        File file = applicationContext == null ? null : new File(applicationContext.getFilesDir(), "dq0_tunnel");
        if (file != null && !file.isDirectory() && !file.mkdirs()) {
            return null;
        }
        return file;
    }
}
