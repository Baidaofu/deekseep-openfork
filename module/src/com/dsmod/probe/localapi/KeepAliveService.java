package com.dsmod.probe.localapi;

import android.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KeepAliveService extends Service {
    public static final String ACTION_HEARTBEAT = "com.dsmod.probe.action.LOCAL_API_HEARTBEAT";
    public static final String CHANNEL_ID = "dq0";
    private static final String CHANNEL_NAME = "DeepSeek Local API";
    public static final long HEARTBEAT_INTERVAL_MS = 5000;
    private static final int NOTIFICATION_ID = 54689;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final String WAKE_LOCK_TAG = "Deekseep:ka";
    public static final long WATCHDOG_TIMEOUT_MS = 90000;
    private volatile long lastConfirmedAtMs;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean beating = new AtomicBoolean(false);
    private final Runnable heartbeat = new Runnable() {
        @Override // java.lang.Runnable
        public void run() {
            if (!KeepAliveService.this.beating.get()) {
                return;
            }
            try {
                Intent intent = new Intent(KeepAliveService.ACTION_HEARTBEAT);
                intent.setPackage(KeepAliveService.this.getPackageName());
                intent.putExtra("timestamp", SystemClock.elapsedRealtime());
                KeepAliveService.this.sendBroadcast(intent);
            } catch (Throwable th) {
            }
            KeepAliveService.this.checkWatchdog();
            KeepAliveService.this.handler.postDelayed(this, KeepAliveService.HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        RUNNING.set(true);
        this.lastConfirmedAtMs = SystemClock.elapsedRealtime();
        LocalApiStats.log("keepalive service started");
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        if (intent != null && ACTION_HEARTBEAT.equals(intent.getAction())) {
            acknowledge();
            return 1;
        }
        startBeating();
        return 1;
    }

    @Override // android.app.Service
    public void onDestroy() {
        stopBeating();
        releaseWakeLock();
        RUNNING.set(false);
        LocalApiStats.log("keepalive service stopped");
        super.onDestroy();
    }

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void acknowledge(Intent intent) {
    }

    private void acknowledge() {
        this.lastConfirmedAtMs = SystemClock.elapsedRealtime();
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static Intent createIntent(Context context) {
        return new Intent(context, (Class<?>) KeepAliveService.class);
    }

    private void startBeating() {
        if (this.beating.compareAndSet(false, true)) {
            this.lastConfirmedAtMs = SystemClock.elapsedRealtime();
            this.handler.post(this.heartbeat);
        }
    }

    private void stopBeating() {
        this.beating.set(false);
        this.handler.removeCallbacks(this.heartbeat);
    }

    public void checkWatchdog() {
        long elapsedRealtime = SystemClock.elapsedRealtime() - this.lastConfirmedAtMs;
        if (elapsedRealtime < WATCHDOG_TIMEOUT_MS) {
            return;
        }
        LocalApiStats.log("keepalive watchdog fired after " + elapsedRealtime + "ms of silence");
        stopBeating();
        if (LocalApiConfig.get().autoRecovery) {
            LocalApiStats.noteRecovery();
            try {
                LocalApi.start(getApplicationContext());
                return;
            } catch (Throwable th) {
                return;
            }
        }
        stopSelf();
    }

    private void createChannel() {
        NotificationManager notificationManager;
        if (Build.VERSION.SDK_INT < 26 || (notificationManager = (NotificationManager) getSystemService("notification")) == null) {
            return;
        }
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, 2);
        notificationChannel.setDescription("Keeps the local API and its streams reachable");
        notificationChannel.setShowBadge(false);
        notificationManager.createNotificationChannel(notificationChannel);
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle(CHANNEL_NAME).setContentText("The local listener and its streams are running").setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).setCategory("service").setVisibility(-1);
        return builder.build();
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService("power");
        if (powerManager == null) {
            return;
        }
        this.wakeLock = powerManager.newWakeLock(1, WAKE_LOCK_TAG);
        try {
            this.wakeLock.setReferenceCounted(false);
        } catch (Throwable th) {
        }
        try {
            this.wakeLock.acquire();
        } catch (Throwable th2) {
        }
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock wakeLock = this.wakeLock;
        this.wakeLock = null;
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Throwable th) {
            }
        }
    }
}
