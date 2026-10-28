package com.retroarch.browser.jerby;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public final class JerbyTransferService extends Service {
    static final int PORT = 40337;
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread worker;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra("game") == null) return START_NOT_STICKY;
        File game = new File(intent.getStringExtra("game"));
        if (!game.isFile() || game.length() > JerbyStorage.MAX_ROM_BYTES) return START_NOT_STICKY;
        showNotification(game.getName());
        closeServer();
        running = true;
        worker = new Thread(() -> serve(game), "Jerby game transfer");
        worker.start();
        return START_NOT_STICKY;
    }

    private void showNotification(String game) {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(new NotificationChannel("jerby-lan", "Jerby LAN", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "jerby-lan") : new Notification.Builder(this);
        Notification notification = builder.setContentTitle("Jerby! LAN game")
                .setContentText("Ready to share " + game + " with your other phone")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(37, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(37, notification);
    }

    private void serve(File game) {
        try (ServerSocket socket = new ServerSocket(PORT)) {
            server = socket;
            while (running) {
                try (Socket peer = socket.accept()) { sendGame(peer, game); }
                catch (IOException ignored) { if (!running) break; }
            }
        } catch (IOException ignored) { }
        finally { server = null; }
    }

    private void sendGame(Socket peer, File game) throws IOException {
        peer.setSoTimeout(60000);
        DataOutputStream out = new DataOutputStream(peer.getOutputStream());
        DataInputStream in = new DataInputStream(peer.getInputStream());
        out.writeUTF("JERBY1");
        out.writeUTF(game.getName());
        out.writeLong(game.length());
        out.writeUTF(JerbyStorage.sha256(game));
        out.flush();
        if (in.readBoolean()) {
            try (FileInputStream file = new FileInputStream(game)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = file.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    private void closeServer() {
        running = false;
        ServerSocket old = server;
        if (old != null) try { old.close(); } catch (IOException ignored) { }
    }

    @Override public void onDestroy() {
        closeServer();
        super.onDestroy();
    }
}
