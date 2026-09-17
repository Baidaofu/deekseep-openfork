package com.dsmod.probe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads DexKit's native engine and lets callers open a bridge over an APK.
 *
 * <p>DexKit ships Java/Kotlin classes plus {@code libdexkit.so} but deliberately does not load the
 * library itself. The module runs inside the DeepSeek process, so the library is loaded in two
 * steps: first through the framework's own search path (LSPosed exposes the module's native
 * library directory), then by extracting it from the module APK into the host's private files
 * directory and loading it by absolute path.</p>
 */
final class DexKitSupport {
    private static final String TAG = "dexkit";
    private static final String LIB_NAME = "libdexkit.so";
    private static final String MODULE_PACKAGE = "com.dsmod.probe";
    private static final String[] PACKAGED_ABIS = {
            "arm64-v8a", "armeabi-v7a", "x86_64", "x86"
    };

    private static volatile boolean attempted;
    private static volatile boolean available;
    private static volatile String failure;

    private DexKitSupport() {}

    static boolean isAvailable() {
        return available;
    }

    static String failureReason() {
        return failure;
    }

    /** Idempotent; safe to call from any thread. */
    static synchronized boolean ensureLibrary(Context hostContext) {
        if (attempted) return available;
        attempted = true;

        try {
            System.loadLibrary("dexkit");
            available = true;
            Main.log(TAG + ": native library loaded through the framework search path");
            return true;
        } catch (Throwable first) {
            failure = "loadLibrary: " + Main.safeThrowableMessage(first);
        }

        try {
            File extracted = extractLibrary(hostContext);
            if (extracted != null) {
                // dlopen only needs read + execute; the file stays inside the host's private dir.
                extracted.setReadable(true, true);
                extracted.setExecutable(true, true);
                System.load(extracted.getAbsolutePath());
                available = true;
                failure = null;
                Main.log(TAG + ": native library loaded from " + extracted.getAbsolutePath());
            }
        } catch (Throwable second) {
            failure = "extract: " + Main.safeThrowableMessage(second);
            Main.log(TAG + ": native library unavailable (" + failure + ")");
        }
        return available;
    }

    private static File extractLibrary(Context hostContext) throws Exception {
        if (hostContext == null) return null;
        String abi = preferredAbi();
        File dir = new File(hostContext.getFilesDir(), ".deekseep_dexkit");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File target = new File(dir, "libdexkit-" + abi + ".so");
        if (target.isFile() && target.length() > 0) return target;

        String apk = moduleApkPath(hostContext);
        if (apk == null) return null;

        ZipFile zip = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            zip = new ZipFile(apk);
            ZipEntry entry = zip.getEntry("lib/" + abi + "/" + LIB_NAME);
            if (entry == null) return null;
            in = zip.getInputStream(entry);
            File tmp = new File(dir, LIB_NAME + ".tmp");
            out = new FileOutputStream(tmp, false);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
            out.close();
            out = null;
            if (target.exists() && !target.delete()) return null;
            if (!tmp.renameTo(target)) return null;
            return target;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            if (zip != null) try { zip.close(); } catch (Throwable ignored) {}
        }
    }

    private static String preferredAbi() {
        try {
            String[] supported = Build.SUPPORTED_ABIS;
            if (supported != null) {
                for (String abi : supported) {
                    for (String packaged : PACKAGED_ABIS) {
                        if (packaged.equals(abi)) return abi;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "arm64-v8a";
    }

    private static String moduleApkPath(Context hostContext) {
        try {
            ApplicationInfo info = hostContext.getPackageManager()
                    .getApplicationInfo(MODULE_PACKAGE, 0);
            return info == null ? null : info.sourceDir;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
