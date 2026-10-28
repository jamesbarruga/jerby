package com.retroarch.browser.jerby;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.UUID;

public final class JerbyTransferService extends Service {
    static final int PORT = 40337;
    static final int DISCOVERY_PORT = 40338;
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile File game;
    private volatile String sessionId;
    private Thread worker;
    private Thread announcer;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra("game") == null) return START_NOT_STICKY;
        File selected = new File(intent.getStringExtra("game"));
        if (!selected.isFile() || selected.length() > JerbyStorage.MAX_ROM_BYTES) return START_NOT_STICKY;
        game = selected;
        String requestedSession = intent.getStringExtra("session");
        sessionId = requestedSession == null ? UUID.randomUUID().toString() : requestedSession;
        showNotification(selected.getName());
        if (!running) {
            running = true;
            worker = new Thread(this::serve, "Jerby game transfer");
            announcer = new Thread(this::announce, "Jerby LAN discovery");
            worker.start();
            announcer.start();
        }
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

    private void serve() {
        try (ServerSocket socket = new ServerSocket(PORT)) {
            server = socket;
            while (running) {
                try (Socket peer = socket.accept()) {
                    File selected = game;
                    if (selected != null && selected.isFile()) sendGame(peer, selected);
                }
                catch (IOException ignored) { if (!running) break; }
            }
        } catch (IOException ignored) { }
        finally { server = null; }
    }

    private void announce() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            // Give the native netplay host time to reach the game before peers join.
            Thread.sleep(2500);
            registerNsd();
            while (running) {
                String id = sessionId;
                if (id != null) {
                    byte[] message = ("JERBY2|" + id).getBytes(StandardCharsets.UTF_8);
                    sendAnnouncement(socket, InetAddress.getByName("255.255.255.255"), message);
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        NetworkInterface network = interfaces.nextElement();
                        if (!network.isUp() || network.isLoopback()) continue;
                        for (InterfaceAddress address : network.getInterfaceAddresses())
                            if (address.getBroadcast() != null)
                                sendAnnouncement(socket, address.getBroadcast(), message);
                    }
                }
                Thread.sleep(1500);
            }
        } catch (Exception ignored) { }
    }

    private void sendAnnouncement(DatagramSocket socket, InetAddress destination, byte[] message) {
        try { socket.send(new DatagramPacket(message, message.length, destination, DISCOVERY_PORT)); }
        catch (IOException ignored) { }
    }

    private void registerNsd() {
        try {
            nsdManager = (NsdManager)getSystemService(NSD_SERVICE);
            if (nsdManager == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("Jerby-" + sessionId);
            info.setServiceType("_jerby._tcp.");
            info.setPort(PORT);
            registrationListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo service) { }
                @Override public void onRegistrationFailed(NsdServiceInfo service, int error) { }
                @Override public void onServiceUnregistered(NsdServiceInfo service) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo service, int error) { }
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception ignored) { }
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
        if (announcer != null) announcer.interrupt();
        if (nsdManager != null && registrationListener != null)
            try { nsdManager.unregisterService(registrationListener); }
            catch (Exception ignored) { }
        super.onDestroy();
    }
}
