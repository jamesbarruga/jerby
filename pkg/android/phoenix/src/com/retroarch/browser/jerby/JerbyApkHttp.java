package com.retroarch.browser.jerby;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Small read-only HTTP server for the local app-sharing page and APK. */
final class JerbyApkHttp {
    private JerbyApkHttp() {}

    static void serve(Socket peer, File apk) {
        try (Socket socket = peer) {
            socket.setSoTimeout(15000);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            byte[] request = new byte[8192];
            int count = 0, previous = -1, endings = 0, value;
            while (count < request.length && (value = input.read()) != -1) {
                request[count++] = (byte)value;
                if (previous == '\r' && value == '\n') endings++;
                else if (value != '\r') endings = 0;
                previous = value;
                if (endings >= 2) break;
            }
            String line = new String(request, 0, count, StandardCharsets.US_ASCII).split("\\r?\\n", 2)[0];
            if (!(line.startsWith("GET / ") || line.startsWith("GET /jerby.apk "))) {
                respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (line.startsWith("GET / ")) {
                String html = "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                        + "<title>Get Jerby!</title><style>body{font:18px system-ui;background:#071f31;color:white;"
                        + "padding:28px;max-width:480px;margin:auto}a{display:block;background:#20cfc5;color:#06252e;"
                        + "padding:18px;border-radius:16px;text-align:center;font-weight:bold;text-decoration:none}"
                        + "small{color:#b8d8df}</style><h1>Jerby! 🎮</h1>"
                        + "<p>Play SNES games together. Use the same Wi-Fi or the first phone's hotspot; internet is not needed.</p>"
                        + "<a href=/jerby.apk>Download Jerby! for Android</a>"
                        + "<p><small>After downloading, open the APK and allow installation from your browser if asked."
                        + " This page works while sharing stays open on the first phone.</small></p>";
                respond(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!apk.isFile()) {
                respond(out, 500, "text/plain", "APK unavailable".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                    + "Content-Disposition: attachment; filename=\"Jerby.apk\"\r\n"
                    + "Content-Length: " + apk.length() + "\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            try (FileInputStream file = new FileInputStream(apk)) {
                byte[] bytes = new byte[32768];
                int read;
                while ((read = file.read(bytes)) != -1) out.write(bytes, 0, read);
            }
            out.flush();
        } catch (IOException ignored) { }
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
