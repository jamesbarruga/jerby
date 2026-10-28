package com.retroarch.browser.jerby;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.retroarch.browser.retroactivity.RetroActivityFuture;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;

public final class JerbyActivity extends Activity {
    private static final int PICK_ROM = 7;
    private final int teal = Color.rgb(0, 151, 160);
    private final int dark = Color.rgb(7, 33, 49);
    private File selected;
    private LinearLayout gameList;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(dark);
        getWindow().setNavigationBarColor(dark);
        try { JerbyStorage.installBundled(this); }
        catch (IOException e) { new AlertDialog.Builder(this).setMessage("Cannot install bundled game: " + e.getMessage()).setPositiveButton("OK", null).show(); }
        buildUi();
        refreshGames();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 251, 251));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 24);
        scroll.addView(root);
        setContentView(scroll);

        ImageView logo = new ImageView(this);
        try (InputStream in = getAssets().open("jerby-wordmark.png")) {
            logo.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (IOException ignored) { }
        logo.setAdjustViewBounds(true);
        root.addView(logo, new LinearLayout.LayoutParams(-1, 150));

        TextView subtitle = label("SNES games together, on the same Wi-Fi", 17, dark);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);
        status = label("Choose a game below.", 14, Color.DKGRAY);
        status.setPadding(0, 12, 0, 16);
        root.addView(status);

        root.addView(label("Your games", 22, dark));
        gameList = new LinearLayout(this);
        gameList.setOrientation(LinearLayout.VERTICAL);
        root.addView(gameList);

        root.addView(button("Play solo", () -> launch(selected, null, null)));
        root.addView(button("Host LAN game", this::host));
        root.addView(button("Join LAN game", this::joinDialog));
        root.addView(button("Stop hosting", () -> {
            stopService(new Intent(this, JerbyTransferService.class));
            say("LAN hosting stopped.");
        }));
        root.addView(label("Add more games", 22, dark));
        root.addView(button("Import ROM from phone", this::pickRom));
        root.addView(button("Download ROM from link", this::downloadDialog));
        TextView note = label("Use SNES game files you have the right to use. Jerby! shares a missing game directly between your two phones on the local network.", 13, Color.DKGRAY);
        note.setPadding(0, 18, 0, 0);
        root.addView(note);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void refreshGames() {
        gameList.removeAllViews();
        File[] games = JerbyStorage.games(this);
        if (selected == null || !selected.exists()) selected = games.length == 0 ? null : games[0];
        for (File game : games) {
            Button item = button(game.getName().replaceFirst("^[0-9a-f]{8}-", ""), () -> {
                selected = game;
                refreshGames();
                say("Selected " + game.getName());
            });
            if (game.equals(selected)) item.setText("✓  " + item.getText());
            item.setTextColor(game.equals(selected) ? teal : dark);
            gameList.addView(item);
        }
    }

    private void say(String message) { status.setText(message); }

    private void launch(File game, String mode, String hostIp) {
        if (game == null || !game.isFile()) { say("Choose a game first."); return; }
        Intent play = new Intent(this, RetroActivityFuture.class);
        play.putExtra("ROM", game.getAbsolutePath());
        play.putExtra("LIBRETRO", JerbyStorage.coreFile(this).getAbsolutePath());
        // The ROM and core live in app-private storage, so RetroArch does not
        // need its legacy all-files permission prompt.
        play.putExtra("CONFIGFILE", "");
        if (mode != null) play.putExtra("JERBY_NETPLAY_MODE", mode);
        if (hostIp != null) play.putExtra("JERBY_NETPLAY_HOST", hostIp);
        startActivity(play);
    }

    private void host() {
        if (selected == null || !selected.isFile()) { say("Choose a game first."); return; }
        Intent service = new Intent(this, JerbyTransferService.class);
        service.putExtra("game", selected.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        String ip = localWifiIp();
        new AlertDialog.Builder(this).setTitle("Host a LAN game")
                .setMessage("On your son's phone, tap Join LAN game and enter:\n" + ip +
                        "\n\nJerby! will copy this game to his phone if it is missing. Both phones need the same Wi-Fi network.")
                .setPositiveButton("Start playing", (d, which) -> launch(selected, "host", null))
                .setNegativeButton("Cancel", (d, which) -> stopService(new Intent(this, JerbyTransferService.class)))
                .show();
    }

    private void joinDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.1.42");
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this).setTitle("Join LAN game")
                .setMessage("Enter the IP address shown on the host phone.")
                .setView(input)
                .setPositiveButton("Join", (d, which) -> join(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null).show();
    }

    private void join(String ip) {
        if (!ip.matches("(\\d{1,3}\\.){3}\\d{1,3}")) { say("Enter a local IPv4 address."); return; }
        say("Checking the host's game…");
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, JerbyTransferService.PORT), 7000);
                socket.setSoTimeout(60000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                if (!"JERBY1".equals(in.readUTF())) throw new IOException("This is not a Jerby! host");
                String name = JerbyStorage.safeName(in.readUTF());
                long length = in.readLong();
                String hash = in.readUTF();
                if (length < 1 || length > JerbyStorage.MAX_ROM_BYTES) throw new IOException("Invalid game size");
                File game = JerbyStorage.findByHash(this, hash);
                boolean missing = game == null;
                out.writeBoolean(missing);
                out.flush();
                if (missing) {
                    runOnUiThread(() -> say("Copying " + name + " over Wi-Fi…"));
                    game = JerbyStorage.receive(this, in, name, length, hash);
                }
                File ready = game;
                runOnUiThread(() -> { selected = ready; refreshGames(); launch(ready, "client", ip); });
            } catch (Exception e) {
                runOnUiThread(() -> say("Could not join: " + e.getMessage()));
            }
        }, "Jerby join").start();
    }

    private String localWifiIp() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    if (network.getName().startsWith("wlan") || network.getName().startsWith("wifi")) return address.getHostAddress();
                    if (fallback == null && address.isSiteLocalAddress()) fallback = address.getHostAddress();
                }
            }
            return fallback == null ? "(check Wi-Fi settings)" : fallback;
        } catch (Exception e) { return "(check Wi-Fi settings)"; }
    }

    private void pickRom() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, PICK_ROM);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ROM || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        }
        String filename = name;
        say("Importing game…");
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open game file");
                File game = JerbyStorage.importRom(this, in, filename);
                runOnUiThread(() -> { selected = game; refreshGames(); say("Imported " + game.getName()); });
            } catch (Exception e) { runOnUiThread(() -> say("Import failed: " + e.getMessage())); }
        }, "Jerby import").start();
    }

    private void downloadDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://example.com/game.sfc");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Download a SNES game")
                .setMessage("Paste a direct HTTPS link to a game ROM you may use.")
                .setView(input)
                .setPositiveButton("Download", (d, which) -> download(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null).show();
    }

    private void download(String link) {
        say("Downloading game…");
        new Thread(() -> {
            try {
                URL url = new URL(link);
                if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Use a direct HTTPS link");
                String path = url.getPath();
                String name = JerbyStorage.safeName(path.substring(path.lastIndexOf('/') + 1));
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                try (InputStream in = connection.getInputStream()) {
                    File game = JerbyStorage.importRom(this, in, name);
                    runOnUiThread(() -> { selected = game; refreshGames(); say("Downloaded " + game.getName()); });
                }
            } catch (Exception e) { runOnUiThread(() -> say("Download failed: " + e.getMessage())); }
        }, "Jerby download").start();
    }
}
