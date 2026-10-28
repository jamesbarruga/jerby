package com.retroarch.browser.jerby;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.EditorInfo;
import com.retroarch.browser.retroactivity.RetroActivityFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Enumeration;
import java.util.UUID;

/** A landscape game shelf: one tap plays, and a second open phone joins itself. */
public final class JerbyActivity extends Activity {
    private static final int PICK_ROM = 7;
    private static final int PICK_SITE_ROM = 8;
    private static final int DARK = Color.rgb(7, 25, 43);
    private static final int PANEL = Color.rgb(17, 47, 67);
    private static final int TEAL = Color.rgb(39, 213, 204);
    private LinearLayout gamesView;
    private TextView status;
    private Button viewButton;
    private Button muteButton;
    private Button myGamesTab;
    private Button discoverButton;
    private TextView heading;
    private boolean gridView;
    private boolean discoverTab;
    private boolean storeLoading;
    private boolean downloading;
    private boolean sharing;
    private final List<JerbyStore.Game> storeGames = new ArrayList<>();
    private final List<File> storeInstalled = new ArrayList<>();
    private final List<JerbyRomsGames.Game> siteGames = new ArrayList<>();
    private String siteQuery = "";
    private String siteError;
    private int sitePage;
    private int siteRequest;
    private boolean siteLoading;
    private boolean siteMore;
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

    @Override protected void onDestroy() {
        if (sharing) stopService(new Intent(this, JerbyApkShareService.class));
        super.onDestroy();
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

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(tabs, new LinearLayout.LayoutParams(-1, dp(48)));
        myGamesTab = action("My Games", () -> showTab(false));
        discoverButton = action("Discover", () -> showTab(true));
        tabs.addView(myGamesTab, buttonLayout(126));
        tabs.addView(discoverButton, buttonLayout(126));
        tabs.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        tabs.addView(action("Share Jerby!", this::shareApp), buttonLayout(142));

        heading = text("Pick a game", 25, Color.WHITE);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(heading);
        status = text("Tap to play. Another open phone on this Wi-Fi joins automatically.", 14,
                Color.rgb(166, 219, 222));
        status.setPadding(0, dp(2), 0, dp(10));
        page.addView(status);
        gamesView = new LinearLayout(this);
        gamesView.setOrientation(LinearLayout.VERTICAL);
        page.addView(gamesView);
        styleTabs();
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
        if (name.equalsIgnoreCase("jerby catch")) return "Jerby Catch";
        if (name.equalsIgnoreCase("jerby pong")) return "Jerby Pong";
        return name;
    }

    private void styleTabs() {
        myGamesTab.setBackground(panel(discoverTab ? PANEL : Color.rgb(21, 124, 133), 18, 0));
        discoverButton.setBackground(panel(discoverTab ? Color.rgb(21, 124, 133) : PANEL, 18, 0));
    }

    private void showTab(boolean discover) {
        discoverTab = discover;
        heading.setText(discover ? "Discover games" : "Pick a game");
        styleTabs();
        if (discover) {
            say("Pick a game. Online downloads need internet.");
            if (storeGames.isEmpty()) loadStore();
            if (sitePage == 0 && !siteLoading) loadSiteGames(false);
        } else say("Tap to play. Another open phone on this Wi-Fi joins automatically.");
        refreshGames();
    }

    private void refreshGames() {
        gamesView.removeAllViews();
        if (discoverTab) { renderStore(); return; }
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

        String filename = game.getName().replaceFirst("^[0-9a-f]{8}-", "");
        String asset = filename.equals("jerby_dash.sfc") ? "jerby-dash-cover.png"
                : filename.equals("jerby_tag.sfc") ? "jerby-tag-cover.png"
                : filename.equals("jerby_catch.sfc") ? "jerby-catch-cover.png"
                : filename.equals("jerby_pong.sfc") ? "jerby-pong-cover.png" : null;
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

    private void loadStore() {
        if (storeLoading) return;
        storeLoading = true;
        say("Opening Discover…");
        new Thread(() -> {
            try {
                publishStore(JerbyStore.loadBundled(this));
                try { publishStore(JerbyStore.loadRemote()); }
                catch (Exception offline) { /* Bundled catalog remains available. */ }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    storeLoading = false;
                    if (discoverTab) say("Discover is unavailable: " + e.getMessage());
                });
            }
        }, "Jerby store catalog").start();
    }

    private void publishStore(List<JerbyStore.Game> found) throws IOException {
        List<File> installed = new ArrayList<>();
        for (JerbyStore.Game game : found) installed.add(JerbyStore.installed(this, game));
        runOnUiThread(() -> {
            if (downloading) return;
            storeLoading = false;
            storeGames.clear(); storeGames.addAll(found);
            storeInstalled.clear(); storeInstalled.addAll(installed);
            if (discoverTab) { say("Tap a game to download and play."); refreshGames(); }
        });
    }

    private void renderStore() {
        if (storeGames.isEmpty()) {
            gamesView.addView(text(storeLoading ? "Loading games…" : "No games available yet.", 18, Color.WHITE));
        } else if (!gridView) {
            for (int index = 0; index < storeGames.size(); index++)
                gamesView.addView(storeCard(index, false));
        } else {
            int columns = getResources().getConfiguration().screenWidthDp >= 700 ? 3 : 2;
            for (int first = 0; first < storeGames.size(); first += columns) {
                LinearLayout row = new LinearLayout(this);
                gamesView.addView(row);
                for (int column = 0; column < columns; column++) {
                    int index = first + column;
                    if (index < storeGames.size()) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(225), 1);
                        params.setMargins(dp(4), dp(4), dp(4), dp(6));
                        row.addView(storeCard(index, true), params);
                    } else row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                }
            }
        }
        renderSiteGames();
    }

    private void loadSiteGames(boolean append) {
        int request = ++siteRequest;
        int page = append ? sitePage + 1 : 1;
        String query = siteQuery;
        siteLoading = true;
        siteError = null;
        if (!append) { siteGames.clear(); sitePage = 0; siteMore = false; }
        if (discoverTab) refreshGames();
        new Thread(() -> {
            try {
                List<JerbyRomsGames.Game> found = JerbyRomsGames.load(this, query, page);
                runOnUiThread(() -> {
                    if (request != siteRequest) return;
                    if (!append) siteGames.clear();
                    siteGames.addAll(found);
                    sitePage = page;
                    siteMore = !found.isEmpty();
                    siteLoading = false;
                    if (discoverTab) refreshGames();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (request != siteRequest) return;
                    siteLoading = false;
                    siteError = error.getMessage();
                    if (discoverTab) refreshGames();
                });
            }
        }, "Jerby RomsGames catalog").start();
    }

    private void renderSiteGames() {
        TextView heading = text("More SNES games · RomsGames", 21, Color.WHITE);
        heading.setPadding(dp(4), dp(22), 0, dp(2));
        gamesView.addView(heading);
        gamesView.addView(text("Online source. Download games you are licensed to use.", 13,
                Color.rgb(166, 219, 222)));
        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        gamesView.addView(search);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(siteQuery);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(166, 190, 205));
        input.setHint("Search SNES games");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        Runnable runSearch = () -> {
            String query = input.getText().toString().trim();
            if (query.equals(siteQuery) && sitePage > 0) return;
            siteQuery = query;
            loadSiteGames(false);
        };
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            runSearch.run();
            return true;
        });
        search.addView(action("Search", runSearch), buttonLayout(96));
        if (!siteQuery.isEmpty()) search.addView(action("Clear", () -> {
            siteQuery = "";
            loadSiteGames(false);
        }), buttonLayout(75));

        if (siteGames.isEmpty() && siteLoading) gamesView.addView(text("Loading online games…", 17, Color.WHITE));
        else if (siteGames.isEmpty()) gamesView.addView(text(siteError == null
                ? "No SNES games found." : "Online catalog unavailable: " + siteError, 16, Color.WHITE));
        if (!gridView) {
            for (JerbyRomsGames.Game game : siteGames) gamesView.addView(siteCard(game, false));
        } else {
            int columns = getResources().getConfiguration().screenWidthDp >= 700 ? 3 : 2;
            for (int first = 0; first < siteGames.size(); first += columns) {
                LinearLayout row = new LinearLayout(this);
                gamesView.addView(row);
                for (int column = 0; column < columns; column++) {
                    int index = first + column;
                    if (index < siteGames.size()) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(190), 1);
                        params.setMargins(dp(4), dp(4), dp(4), dp(6));
                        row.addView(siteCard(siteGames.get(index), true), params);
                    } else row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                }
            }
        }
        if (siteLoading && !siteGames.isEmpty()) gamesView.addView(text("Loading more…", 16, Color.WHITE));
        else if (siteError != null && !siteGames.isEmpty())
            gamesView.addView(action("Retry loading games", () -> loadSiteGames(true)));
        else if (siteMore) gamesView.addView(action("More games  ↓", () -> loadSiteGames(true)));
    }

    private View siteCard(JerbyRomsGames.Game game, boolean grid) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(panel(PANEL, 18, Color.rgb(54, 110, 128)));
        if (!grid) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(108));
            params.setMargins(dp(4), dp(4), dp(4), dp(6));
            card.setLayoutParams(params);
        }
        card.setOnClickListener(view -> {
            Intent download = new Intent(this, JerbyRomsGamesDownloadActivity.class);
            download.putExtra(JerbyRomsGamesDownloadActivity.GAME_URL, game.pageUrl);
            download.putExtra(JerbyRomsGamesDownloadActivity.GAME_TITLE, game.title);
            startActivityForResult(download, PICK_SITE_ROM);
        });
        FrameLayout cover = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(43, 85, 127), Color.rgb(91, 52, 119) });
        background.setCornerRadius(dp(10));
        cover.setBackground(background);
        cover.setClipToOutline(true);
        TextView placeholder = text("✦  SNES", 20, Color.rgb(255, 227, 122));
        placeholder.setGravity(Gravity.CENTER);
        cover.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        JerbyCoverLoader.load(this, image, game.coverUrl);
        card.addView(cover, new LinearLayout.LayoutParams(grid ? -1 : dp(130), grid ? dp(118) : dp(88)));
        LinearLayout description = new LinearLayout(this);
        description.setOrientation(LinearLayout.VERTICAL);
        description.setGravity(Gravity.CENTER_VERTICAL);
        description.setPadding(grid ? 0 : dp(12), 0, 0, 0);
        TextView title = text(game.title, 17, Color.WHITE);
        title.setMaxLines(grid ? 2 : 1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        description.addView(title);
        description.addView(text("↓  Download & Play", 13, TEAL));
        card.addView(description, new LinearLayout.LayoutParams(grid ? -1 : 0, grid ? dp(58) : -1, grid ? 0 : 1));
        return card;
    }

    private View storeCard(int index, boolean grid) {
        JerbyStore.Game game = storeGames.get(index);
        File installed = storeInstalled.get(index);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(panel(PANEL, 18, Color.rgb(54, 110, 128)));
        card.setOnClickListener(v -> { if (installed != null) play(installed); else downloadStore(index); });
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, grid ? dp(215) : dp(112));
        cardParams.setMargins(dp(4), dp(4), dp(4), dp(6));
        card.setLayoutParams(cardParams);

        ImageView cover = new ImageView(this);
        try (InputStream in = getAssets().open(game.cover)) {
            cover.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (IOException ignored) { }
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setBackground(panel(Color.rgb(9, 31, 51), 10, 0));
        card.addView(cover, new LinearLayout.LayoutParams(grid ? -1 : dp(130), grid ? dp(126) : dp(90)));

        LinearLayout description = new LinearLayout(this);
        description.setOrientation(LinearLayout.VERTICAL);
        description.setGravity(Gravity.CENTER_VERTICAL);
        description.setPadding(grid ? 0 : dp(12), 0, 0, 0);
        TextView title = text(game.title, 18, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        description.addView(title);
        TextView subtitle = text(game.description, 12, Color.rgb(182, 216, 222));
        subtitle.setMaxLines(grid ? 2 : 1);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        description.addView(subtitle);
        description.addView(text(installed == null ? "↓  Download & Play  ·  " + game.license
                : "✓  Play  ·  Installed", 13, TEAL));
        card.addView(description, new LinearLayout.LayoutParams(grid ? -1 : 0, grid ? dp(78) : -1, grid ? 0 : 1));
        return card;
    }

    private void downloadStore(int index) {
        if (downloading) { say("A game is already downloading…"); return; }
        downloading = true;
        JerbyStore.Game game = storeGames.get(index);
        say("Downloading " + game.title + "…");
        new Thread(() -> {
            try {
                File installed = JerbyStore.installed(this, game);
                if (installed == null) installed = JerbyStore.download(this, game,
                        percent -> runOnUiThread(() -> say("Downloading " + game.title + "… " + percent + "%")));
                File ready = installed;
                runOnUiThread(() -> {
                    downloading = false;
                    if (index < storeInstalled.size()) storeInstalled.set(index, ready);
                    refreshGames();
                    say("Starting " + game.title + "…");
                    play(ready);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { downloading = false; say("Download failed: " + e.getMessage()); });
            }
        }, "Jerby store download").start();
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
                    if (network.getName().startsWith("wlan") || network.getName().startsWith("wifi"))
                        return address.getHostAddress();
                    if (fallback == null && address.isSiteLocalAddress()) fallback = address.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception ignored) { return null; }
    }

    private void shareApp() {
        String ip = localWifiIp();
        String link = ip == null ? null : "http://" + ip + ":" + JerbyApkShareService.PORT + "/";
        if (link != null) {
            Intent service = new Intent(this, JerbyApkShareService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            sharing = true;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(18), dp(4), dp(18), dp(8));
        TextView instructions = text(link == null
                ? "Send the APK directly with Android's nearby or Bluetooth sharing. No internet needed."
                : "Scan on the new phone. Use the same Wi-Fi or connect it to this phone's hotspot. No internet needed.", 15, DARK);
        content.addView(instructions);
        if (link != null) {
            try {
                BitMatrix matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 420, 420);
                Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
                for (int y = 0; y < matrix.getHeight(); y++)
                    for (int x = 0; x < matrix.getWidth(); x++)
                        bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                ImageView qr = new ImageView(this);
                qr.setImageBitmap(bitmap);
                content.addView(qr, new LinearLayout.LayoutParams(dp(170), dp(170)));
            } catch (Exception e) { say("Could not make QR code: " + e.getMessage()); }
            TextView address = text(link, 16, Color.rgb(12, 93, 117));
            address.setTextIsSelectable(true);
            content.addView(address);
            Button copy = new Button(this);
            copy.setText("Copy link");
            copy.setAllCaps(false);
            copy.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Jerby! download", link));
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
            });
            content.addView(copy);
        }
        Button offline = new Button(this);
        offline.setText("Send APK with Android sharing");
        offline.setAllCaps(false);
        offline.setOnClickListener(v -> {
            Uri apk = Uri.parse("content://" + getPackageName() + ".apk/jerby.apk");
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/vnd.android.package-archive");
            send.putExtra(Intent.EXTRA_STREAM, apk);
            send.setClipData(ClipData.newRawUri("Jerby! APK", apk));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Send Jerby! to another phone"));
        });
        content.addView(offline);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Share Jerby!")
                .setView(content).setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(ignored -> {
            if (sharing) stopService(new Intent(this, JerbyApkShareService.class));
            sharing = false;
        });
        dialog.show();
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
        if (game.getName().toLowerCase(java.util.Locale.US).endsWith(".zip")) {
            say("Unpacking game…");
            new Thread(() -> {
                try (InputStream in = new FileInputStream(game)) {
                    File ready = JerbyStorage.importRom(this, in, game.getName());
                    if (ready == null) throw new IOException("ZIP has no SNES game");
                    game.delete();
                    runOnUiThread(() -> { refreshGames(); play(ready); });
                } catch (Exception e) { runOnUiThread(() -> say("Could not open ZIP: " + e.getMessage())); }
            }, "Jerby unpack game").start();
            return;
        }
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
            if (game.getName().toLowerCase(java.util.Locale.US).endsWith(".zip")) {
                File archive = game;
                try (InputStream zipped = new FileInputStream(archive)) {
                    game = JerbyStorage.importRom(this, zipped, archive.getName());
                }
                archive.delete();
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
        if (request == PICK_SITE_ROM) {
            if (result == RESULT_OK && data != null) {
                String path = data.getStringExtra(JerbyRomsGamesDownloadActivity.ROM_PATH);
                if (path != null) try {
                    File game = new File(path);
                    String directory = JerbyStorage.romDir(this).getCanonicalPath() + File.separator;
                    if (game.isFile() && game.getCanonicalPath().startsWith(directory)) {
                        showTab(false);
                        play(game);
                    }
                } catch (IOException error) { say("Could not open game: " + error.getMessage()); }
            }
            return;
        }
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
                runOnUiThread(() -> { showTab(false); say("Added " + title(game)); });
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
                String name = path.substring(path.lastIndexOf('/') + 1);
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                try { name = JerbyStorage.safeName(name); }
                catch (IllegalArgumentException missingExtension) {
                    String disposition = connection.getHeaderField("Content-Disposition");
                    if (disposition != null && disposition.contains("filename=")) {
                        String headerName = disposition.substring(disposition.indexOf("filename=") + 9)
                                .replace("\"", "").split(";")[0].trim();
                        try { name = JerbyStorage.safeName(headerName); }
                        catch (IllegalArgumentException ignored) { name = "download.sfc"; }
                    } else name = "download.sfc";
                }
                String filename = name;
                try (InputStream in = connection.getInputStream()) {
                    File game = JerbyStorage.importRom(this, in, filename);
                    runOnUiThread(() -> { showTab(false); say("Added " + title(game)); });
                }
            } catch (Exception e) { runOnUiThread(() -> say("Download failed: " + e.getMessage())); }
        }, "Jerby download").start();
    }
}
