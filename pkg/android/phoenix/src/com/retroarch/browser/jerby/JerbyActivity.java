package com.retroarch.browser.jerby;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A landscape game shelf: one tap plays, and a second open phone joins itself. */
public final class JerbyActivity extends Activity {
    private static final int PICK_ROM = 7;
    private static final int DARK = Color.rgb(7, 25, 43);
    private static final int PANEL = Color.rgb(17, 47, 67);
    private static final int TEAL = Color.rgb(39, 213, 204);
    private LinearLayout gamesView;
    private TextView status;
    private Button viewButton;
    private Button muteButton;
    private boolean gridView;
    private boolean hosting;
    private boolean launchedGame;
    private volatile boolean listening;
    private volatile boolean joining;
    private volatile String ignoredSession;
    private volatile long lastAttemptAt;
    private DatagramSocket discoverySocket;
    private Thread discoveryThread;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener nsdDiscovery;
    private WifiManager.MulticastLock multicastLock;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        gridView = getSharedPreferences("jerby-ui", MODE_PRIVATE).getBoolean("grid", true);
        try {
            JerbyStorage.installBundled(this);
            JerbyControls.prepare(this);
        } catch (IOException e) {
            new AlertDialog.Builder(this).setMessage("Could not prepare games: " + e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
        buildCatalog();
        refreshGames();
    }

    @Override protected void onResume() {
        super.onResume();
        if (hosting && launchedGame) {
            stopService(new Intent(this, JerbyTransferService.class));
            hosting = false;
            launchedGame = false;
        }
        startDiscovery();
    }

    @Override protected void onPause() {
        stopDiscovery();
        super.onPause();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable panel(int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private Button action(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackground(panel(PANEL, 18, Color.rgb(50, 103, 121)));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void buildCatalog() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { DARK, Color.rgb(12, 51, 68) }));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(72)));
        boolean compact = getResources().getConfiguration().screenWidthDp < 700;
        ImageView logo = new ImageView(this);
        try (InputStream in = getAssets().open("jerby-wordmark.png")) {
            logo.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (IOException ignored) { }
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        header.addView(logo, new LinearLayout.LayoutParams(dp(compact ? 125 : 185), dp(68)));
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        viewButton = action(gridView ? "☷  List" : "▦  Grid", () -> {
            gridView = !gridView;
            getSharedPreferences("jerby-ui", MODE_PRIVATE).edit().putBoolean("grid", gridView).apply();
            viewButton.setText(gridView ? "☷  List" : "▦  Grid");
            refreshGames();
        });
        header.addView(viewButton, buttonLayout(compact ? 78 : 100));
        muteButton = action("", () -> {
            JerbyControls.setMuted(this, !JerbyControls.muted(this));
            muteButton.setText(JerbyControls.muted(this) ? "🔇 Muted" : "🔊 Sound");
        });
        muteButton.setText(JerbyControls.muted(this) ? "🔇 Muted" : "🔊 Sound");
        header.addView(muteButton, buttonLayout(compact ? 90 : 108));
        header.addView(action("Controls", () -> startActivity(new Intent(this, JerbyControlsActivity.class))), buttonLayout(compact ? 90 : 105));
        header.addView(action("+  Add game", this::addGame), buttonLayout(compact ? 98 : 116));

        TextView heading = text("Pick a game", 25, Color.WHITE);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(heading);
        status = text("Tap to play. Another open phone on this Wi-Fi joins automatically.", 14,
                Color.rgb(166, 219, 222));
        status.setPadding(0, dp(2), 0, dp(10));
        page.addView(status);
        gamesView = new LinearLayout(this);
        gamesView.setOrientation(LinearLayout.VERTICAL);
        page.addView(gamesView);
    }

    private LinearLayout.LayoutParams buttonLayout(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(46));
        params.leftMargin = dp(7);
        return params;
    }

    private String title(File game) {
        String name = game.getName().replaceFirst("^[0-9a-f]{8}-", "")
                .replaceFirst("(?i)\\.(sfc|smc|fig|swc|zip)$", "")
                .replace('_', ' ').replace('-', ' ').trim();
        if (name.equalsIgnoreCase("jerby dash")) return "Jerby Dash";
        if (name.equalsIgnoreCase("jerby tag")) return "Jerby Tag";
        return name;
    }

    private void refreshGames() {
        gamesView.removeAllViews();
        File[] games = JerbyStorage.games(this);
        if (games.length == 0) { gamesView.addView(text("Add a SNES game to begin.", 18, Color.WHITE)); return; }
        if (!gridView) {
            for (File game : games) gamesView.addView(gameCard(game, false));
            return;
        }
        int columns = getResources().getConfiguration().screenWidthDp >= 700 ? 3 : 2;
        for (int first = 0; first < games.length; first += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            gamesView.addView(row);
            for (int column = 0; column < columns; column++) {
                int position = first + column;
                if (position < games.length) {
                    LinearLayout.LayoutParams positionParams = new LinearLayout.LayoutParams(0, dp(192), 1);
                    positionParams.setMargins(dp(4), dp(4), dp(4), dp(6));
                    row.addView(gameCard(games[position], true), positionParams);
                } else row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
        }
    }

    private View gameCard(File game, boolean grid) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(panel(PANEL, 18, Color.rgb(54, 110, 128)));
        card.setOnClickListener(v -> play(game));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, grid ? dp(182) : dp(102));
        cardParams.setMargins(dp(4), dp(4), dp(4), dp(6));
        card.setLayoutParams(cardParams);

        String asset = game.getName().equals("jerby_dash.sfc") ? "jerby-dash-cover.png"
                : game.getName().equals("jerby_tag.sfc") ? "jerby-tag-cover.png" : null;
        if (asset != null) {
            ImageView cover = new ImageView(this);
            try (InputStream in = getAssets().open(asset)) { cover.setImageBitmap(BitmapFactory.decodeStream(in)); }
            catch (IOException ignored) { }
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setBackground(panel(Color.rgb(9, 31, 51), 10, 0));
            card.addView(cover, new LinearLayout.LayoutParams(grid ? -1 : dp(125), grid ? dp(130) : dp(82)));
        } else {
            TextView cover = text("✦  SNES\n" + title(game), 18, Color.rgb(255, 227, 122));
            cover.setGravity(Gravity.CENTER);
            cover.setMaxLines(3);
            cover.setEllipsize(android.text.TextUtils.TruncateAt.END);
            GradientDrawable coverBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] { Color.rgb(43, 85, 127), Color.rgb(91, 52, 119) });
            coverBackground.setCornerRadius(dp(10));
            cover.setBackground(coverBackground);
            card.addView(cover, new LinearLayout.LayoutParams(grid ? -1 : dp(125), grid ? dp(130) : dp(82)));
        }
        LinearLayout description = new LinearLayout(this);
        description.setOrientation(LinearLayout.VERTICAL);
        description.setGravity(Gravity.CENTER_VERTICAL);
        description.setPadding(grid ? 0 : dp(14), 0, 0, 0);
        TextView title = text(title(game), 18, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        description.addView(title);
        if (!grid) description.addView(text("Tap to play", 13, TEAL));
        card.addView(description, new LinearLayout.LayoutParams(grid ? -1 : 0, grid ? dp(38) : -1, grid ? 0 : 1));
        return card;
    }

    private void say(String message) { if (status != null) status.setText(message); }

    private boolean launch(File game, String mode, String hostIp) {
        if (game == null || !game.isFile()) { say("Game file is missing."); return false; }
        File config;
        try { config = JerbyControls.prepare(this); }
        catch (IOException e) { say("Controls could not load: " + e.getMessage()); return false; }
        Intent play = new Intent(this, RetroActivityFuture.class);
        play.putExtra("ROM", game.getAbsolutePath());
        play.putExtra("LIBRETRO", JerbyStorage.coreFile(this).getAbsolutePath());
        play.putExtra("CONFIGFILE", config.getAbsolutePath());
        if (mode != null) play.putExtra("JERBY_NETPLAY_MODE", mode);
        if (hostIp != null) play.putExtra("JERBY_NETPLAY_HOST", hostIp);
        startActivity(play);
        return true;
    }

    private void play(File game) {
        Intent service = new Intent(this, JerbyTransferService.class);
        service.putExtra("game", game.getAbsolutePath());
        String session = UUID.randomUUID().toString();
        service.putExtra("session", session);
        ignoredSession = session;
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        hosting = true;
        launchedGame = true;
        if (!launch(game, "host", null)) {
            hosting = false;
            launchedGame = false;
            stopService(service);
        }
    }

    private void startDiscovery() {
        if (discoveryThread != null) return;
        listening = true;
        startNsd();
        discoveryThread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(JerbyTransferService.DISCOVERY_PORT));
                socket.setSoTimeout(2000);
                discoverySocket = socket;
                byte[] buffer = new byte[128];
                while (listening) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try { socket.receive(packet); }
                    catch (SocketTimeoutException ignored) { continue; }
                    String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (!message.matches("JERBY2\\|[0-9a-fA-F-]{36}")) continue;
                    String session = message.substring(7);
                    attemptJoin(packet.getAddress().getHostAddress(), session);
                }
            } catch (IOException ignored) { }
            finally {
                if (discoverySocket != null && discoverySocket.isClosed()) discoverySocket = null;
                if (discoveryThread == Thread.currentThread()) discoveryThread = null;
            }
        }, "Jerby LAN discovery");
        discoveryThread.start();
    }

    private void stopDiscovery() {
        listening = false;
        DatagramSocket socket = discoverySocket;
        if (socket != null) socket.close();
        discoverySocket = null;
        discoveryThread = null;
        if (nsdManager != null && nsdDiscovery != null)
            try { nsdManager.stopServiceDiscovery(nsdDiscovery); }
            catch (Exception ignored) { }
        nsdDiscovery = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    private void startNsd() {
        try {
            WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("jerby-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            nsdManager = (NsdManager)getSystemService(NSD_SERVICE);
            if (nsdManager == null) return;
            nsdDiscovery = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String type) { }
                @Override public void onDiscoveryStopped(String type) { }
                @Override public void onStartDiscoveryFailed(String type, int error) { }
                @Override public void onStopDiscoveryFailed(String type, int error) { }
                @Override public void onServiceLost(NsdServiceInfo service) { }
                @Override public void onServiceFound(NsdServiceInfo service) {
                    String name = service.getServiceName();
                    if (!listening || name == null || !name.startsWith("Jerby-")) return;
                    String session = name.substring(6);
                    if (!session.matches("[0-9a-fA-F-]{36}")) return;
                    try {
                        nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo info, int error) { }
                            @Override public void onServiceResolved(NsdServiceInfo info) {
                                if (info.getPort() == JerbyTransferService.PORT && info.getHost() instanceof Inet4Address)
                                    attemptJoin(info.getHost().getHostAddress(), session);
                            }
                        });
                    } catch (Exception ignored) { }
                }
            };
            nsdManager.discoverServices("_jerby._tcp.", NsdManager.PROTOCOL_DNS_SD, nsdDiscovery);
        } catch (Exception ignored) { }
    }

    private synchronized void attemptJoin(String ip, String session) {
        if (!listening || joining || session.equals(ignoredSession)
                || System.currentTimeMillis() - lastAttemptAt < 5000) return;
        joining = true;
        lastAttemptAt = System.currentTimeMillis();
        new Thread(() -> join(ip, session), "Jerby auto join").start();
    }

    private void join(String ip, String session) {
        runOnUiThread(() -> say("Joining a game on your Wi-Fi…"));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, JerbyTransferService.PORT), 7000);
            socket.setSoTimeout(60000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            if (!"JERBY1".equals(in.readUTF())) throw new IOException("Not a Jerby! game");
            String name = JerbyStorage.safeName(in.readUTF());
            long length = in.readLong();
            String hash = in.readUTF();
            if (length < 1 || length > JerbyStorage.MAX_ROM_BYTES) throw new IOException("Invalid game size");
            File game = JerbyStorage.findByHash(this, hash);
            out.writeBoolean(game == null);
            out.flush();
            if (game == null) {
                runOnUiThread(() -> say("Copying " + name + " over Wi-Fi…"));
                game = JerbyStorage.receive(this, in, name, length, hash);
            }
            File ready = game;
            runOnUiThread(() -> {
                if (!listening) return;
                ignoredSession = session;
                refreshGames();
                say("Starting " + title(ready) + "…");
                launch(ready, "client", ip);
            });
        } catch (Exception e) {
            runOnUiThread(() -> say("Could not join yet. Waiting for the host…"));
        } finally { joining = false; }
    }

    private void addGame() {
        new AlertDialog.Builder(this).setTitle("Add a SNES game")
                .setItems(new String[] { "Import from this phone", "Download from a link" },
                        (dialog, which) -> { if (which == 0) pickRom(); else downloadDialog(); }).show();
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
                runOnUiThread(() -> { refreshGames(); say("Added " + title(game)); });
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
                .setPositiveButton("Download", (dialog, which) -> download(input.getText().toString().trim()))
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
                    runOnUiThread(() -> { refreshGames(); say("Added " + title(game)); });
                }
            } catch (Exception e) { runOnUiThread(() -> say("Download failed: " + e.getMessage())); }
        }, "Jerby download").start();
    }
}
