package com.retroarch.browser.jerby;

import android.content.Context;
import android.text.Html;
import android.webkit.WebSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JerbyRomsGames {
    private static final String BASE = "https://www.romsgames.net";
    private static final Pattern CARD = Pattern.compile(
            "<a\\s+href=\"(/super-nintendo-rom-[^\"]+/)\"[^>]*>\\s*<img[^>]*\\bsrc=\"([^\"]+)\"[^>]*\\balt=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static final class Game {
        final String title;
        final String pageUrl;
        final String coverUrl;

        Game(String title, String pageUrl, String coverUrl) {
            this.title = title;
            this.pageUrl = pageUrl;
            this.coverUrl = coverUrl;
        }
    }

    private JerbyRomsGames() {}

    static List<Game> load(Context context, String query, int page) throws IOException {
        if (page < 1 || page > 500) throw new IOException("Invalid catalog page");
        String address = query.isEmpty()
                ? BASE + "/roms/super-nintendo/?page=" + page + "&sort=popularity"
                : BASE + "/search/?q=" + URLEncoder.encode(query, "UTF-8") + "&page=" + page;
        HttpURLConnection connection = (HttpURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(context));
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Catalog returned HTTP " + connection.getResponseCode());
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol()))
                throw new IOException("Catalog redirected away from HTTPS");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = new byte[8192];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    if (output.size() + read > 1024 * 1024) throw new IOException("Catalog page is too large");
                    output.write(bytes, 0, read);
                }
            }
            return parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    static List<Game> parse(String html) {
        LinkedHashMap<String, Game> found = new LinkedHashMap<>();
        Matcher matcher = CARD.matcher(html);
        while (matcher.find() && found.size() < 60) {
            String path = matcher.group(1);
            String title = Html.fromHtml(matcher.group(3)).toString().trim();
            String cover = matcher.group(2);
            if (cover.startsWith("//")) cover = "https:" + cover;
            if (!cover.startsWith("https://cache.downloadroms.io/")) cover = null;
            if (!title.isEmpty()) found.put(path, new Game(title, BASE + path, cover));
        }
        return new ArrayList<>(found.values());
    }

    static boolean isGamePage(String address) {
        try {
            URL url = new URL(address);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && ("www.romsgames.net".equalsIgnoreCase(url.getHost())
                    || "romsgames.net".equalsIgnoreCase(url.getHost()))
                    && url.getPath().matches("/super-nintendo-rom-[A-Za-z0-9-]+/");
        } catch (Exception ignored) { return false; }
    }
}
