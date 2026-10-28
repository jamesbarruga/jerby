package com.retroarch.browser.jerby;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/** Serves this installed APK to a nearby browser while Share Jerby! is open. */
public final class JerbyApkShareService extends Service {
    static final int PORT = 40339;
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread worker;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        showNotification();
        if (!running) {
            running = true;
            worker = new Thread(this::listen, "Jerby APK sharing");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void showNotification() {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(new NotificationChannel("jerby-share", "Share Jerby!", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "jerby-share") : new Notification.Builder(this);
        Notification notification = builder.setContentTitle("Sharing Jerby! on Wi-Fi")
                .setContentText("Keep the share screen open on this phone")
                .setSmallIcon(android.R.drawable.ic_menu_share).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(39, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(39, notification);
    }

    private void listen() {
        try (ServerSocket socket = new ServerSocket(PORT)) {
            server = socket;
            while (running) {
                try {
                    Socket peer = socket.accept();
                    File apk = new File(getApplicationInfo().sourceDir);
                    new Thread(() -> JerbyApkHttp.serve(peer, apk), "Jerby APK download").start();
                } catch (IOException error) { if (!running) break; }
            }
        } catch (IOException ignored) { }
        finally { server = null; }
    }

    @Override public void onDestroy() {
        running = false;
        ServerSocket socket = server;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        super.onDestroy();
    }
}
