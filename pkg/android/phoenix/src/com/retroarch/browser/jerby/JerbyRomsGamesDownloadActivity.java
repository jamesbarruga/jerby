package com.retroarch.browser.jerby;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Runs the source site's own download form and CAPTCHA before importing its file. */
public final class JerbyRomsGamesDownloadActivity extends Activity {
    static final String GAME_URL = "gameUrl";
    static final String GAME_TITLE = "gameTitle";
    static final String ROM_PATH = "romPath";

    private WebView browser;
    private TextView status;
    private String gameUrl;
    private String gameTitle;
    private volatile boolean canceled;
    private volatile HttpURLConnection activeConnection;
    private boolean autoStarted;
    private boolean downloading;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        gameUrl = getIntent().getStringExtra(GAME_URL);
        gameTitle = getIntent().getStringExtra(GAME_TITLE);
        if (gameUrl == null || !JerbyRomsGames.isGamePage(gameUrl)) { finish(); return; }
        if (gameTitle == null || gameTitle.isEmpty()) gameTitle = "SNES game";

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(7, 25, 43));
        LinearLayout top = new LinearLayout(this);
        top.setPadding(18, 10, 18, 10);
        top.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(top, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setText("Opening download for " + gameTitle + "…");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this);
        close.setText("Cancel");
        close.setAllCaps(false);
        close.setOnClickListener(view -> finish());
        top.addView(close);
        browser = new WebView(this);
        page.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);

        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true);
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (autoStarted || !JerbyRomsGames.isGamePage(url)) return;
                autoStarted = true;
                status.setText("Starting download… Solve the CAPTCHA if it appears.");
                view.evaluateJavascript("(function(){var n=0,t=setInterval(function(){" +
                        "var b=document.querySelector('button[data-media-id]');" +
                        "if(b&&typeof downloadFormSubmit==='function'&&(typeof grecaptcha!=='undefined'||n>10)){" +
                        "clearInterval(t);b.click();}" +
                        "else if(++n>30){clearInterval(t);}" +
                        "},500)})()", null);
            }
        });
        browser.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimeType, long contentLength) {
                if (downloading) return;
                if (!url.startsWith("https://")) {
                    status.setText("This download cannot be imported securely.");
                    return;
                }
                downloading = true;
                status.setText("Downloading and preparing " + gameTitle + "…");
                String cookie = cookieFor(url);
                String referer = browser.getUrl();
                new Thread(() -> importDownload(url, userAgent, cookie, referer,
                        contentDisposition, mimeType, contentLength), "Jerby website download").start();
            }
        });
        browser.loadUrl(gameUrl);
    }

    private static String cookieFor(String url) {
        try { return CookieManager.getInstance().getCookie(url); }
        catch (Exception ignored) { return null; }
    }

    private void importDownload(String address, String userAgent, String cookie, String referer,
            String disposition, String mimeType, long expectedLength) {
        try {
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Download must use HTTPS");
            if (expectedLength > JerbyStorage.MAX_ROM_BYTES) throw new IOException("Game is over 64 MB");
            String declared = mimeType == null ? "" : mimeType.toLowerCase(Locale.US);
            if (declared.contains("html") || declared.contains("javascript") || declared.contains("android.package"))
                throw new IOException("Site did not send a SNES game file");
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            activeConnection = connection;
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(30000);
            if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (referer != null) connection.setRequestProperty("Referer", referer);
            try {
                if (connection.getResponseCode() != 200)
                    throw new IOException("Download returned HTTP " + connection.getResponseCode());
                if (!"https".equalsIgnoreCase(connection.getURL().getProtocol()))
                    throw new IOException("Download redirected away from HTTPS");
                String type = connection.getContentType();
                if (type != null && (type.toLowerCase(Locale.US).contains("text/html")
                        || type.toLowerCase(Locale.US).contains("text/javascript")))
                    throw new IOException("Site sent a web page instead of a game");
                if (connection.getContentLengthLong() > JerbyStorage.MAX_ROM_BYTES)
                    throw new IOException("Game is over 64 MB");
                String name = gameTitle + ".sfc";
                String header = connection.getHeaderField("Content-Disposition");
                if (header == null) header = disposition;
                if (header != null && header.contains("filename=")) {
                    String candidate = header.substring(header.indexOf("filename=") + 9)
                            .replace("\"", "").split(";")[0].trim();
                    try { name = JerbyStorage.safeName(candidate); }
                    catch (IllegalArgumentException ignored) { }
                }
                try (InputStream raw = connection.getInputStream();
                     BufferedInputStream input = new BufferedInputStream(raw)) {
                    input.mark(32);
                    byte[] start = new byte[16];
                    int count = 0;
                    while (count < start.length) {
                        int read = input.read(start, count, start.length - count);
                        if (read < 0) break;
                        count += read;
                    }
                    input.reset();
                    String prefix = new String(start, 0, count, StandardCharsets.ISO_8859_1)
                            .toLowerCase(Locale.US);
                    if (count < 4 || prefix.startsWith("<!doctype") || prefix.startsWith("<html")
                            || prefix.startsWith("{\"")
                            || (start[0] == 'M' && start[1] == 'Z')
                            || (start[0] == 0x7f && start[1] == 'E' && start[2] == 'L' && start[3] == 'F'))
                        throw new IOException("Site did not send a SNES game file");
                    File game = JerbyStorage.importRom(this, input, name);
                    if (!canceled) runOnUiThread(() -> {
                        if (canceled) return;
                        Intent result = new Intent().putExtra(ROM_PATH, game.getAbsolutePath());
                        setResult(RESULT_OK, result);
                        finish();
                    });
                }
            } finally { connection.disconnect(); activeConnection = null; }
        } catch (Exception error) {
            if (!canceled) runOnUiThread(() -> {
                if (canceled) return;
                downloading = false;
                status.setText("Download failed: " + error.getMessage() + "  Tap Save Game to retry.");
            });
        }
    }

    @Override protected void onDestroy() {
        canceled = true;
        if (activeConnection != null) activeConnection.disconnect();
        if (browser != null) {
            browser.stopLoading();
            browser.destroy();
        }
        super.onDestroy();
    }
}
