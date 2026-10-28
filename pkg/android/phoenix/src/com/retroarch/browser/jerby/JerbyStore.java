package com.retroarch.browser.jerby;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A curated, freely redistributable SNES catalog with verified downloads. */
final class JerbyStore {
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/jamesbarruga/jerby/main/jerby/store/catalog.json";

    static final class Game {
        final String id, title, description, license, cover, url, archiveHash, romHash, romName;
        Game(JSONObject json) throws JSONException, IOException {
            id = json.getString("id");
            title = json.getString("title");
            description = json.getString("description");
            license = json.getString("license");
            cover = json.getString("cover");
            url = json.getString("url");
            archiveHash = json.getString("archiveSha256");
            romHash = json.getString("romSha256");
            romName = JerbyStorage.safeName(json.getString("romName"));
            if (!url.startsWith("https://") || !archiveHash.matches("[0-9a-fA-F]{64}")
                    || !romHash.matches("[0-9a-fA-F]{64}"))
                throw new IOException("Invalid game catalog entry");
        }
    }

    interface Progress { void update(int percent); }

    private JerbyStore() {}

    static List<Game> loadBundled(Context context) throws IOException {
        try (InputStream in = context.getAssets().open("jerby-store.json")) {
            return parse(read(in, 256 * 1024));
        }
    }

    static List<Game> loadRemote() throws IOException {
        return parse(readUrl(CATALOG_URL, 256 * 1024));
    }

    private static List<Game> parse(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray games = root.getJSONArray("games");
            if (games.length() > 100) throw new IOException("Game catalog is too large");
            List<Game> result = new ArrayList<>();
            for (int i = 0; i < games.length(); i++) result.add(new Game(games.getJSONObject(i)));
            return result;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Could not read the game catalog", error);
        }
    }

    static File installed(Context context, Game game) throws IOException {
        return JerbyStorage.findByHash(context, game.romHash);
    }

    static File download(Context context, Game game, Progress progress) throws IOException {
        File temp = File.createTempFile(".jerby-download-", ".tmp", JerbyStorage.romDir(context));
        try {
            URL url = new URL(game.url);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Game link is not HTTPS");
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            try {
                if (connection.getResponseCode() != 200) throw new IOException("Download returned HTTP " + connection.getResponseCode());
                if (!"https".equalsIgnoreCase(connection.getURL().getProtocol()))
                    throw new IOException("Game link redirected away from HTTPS");
                long size = connection.getContentLength();
                long total = 0;
                int lastPercent = -1;
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] bytes = new byte[32768];
                    int read;
                    while ((read = in.read(bytes)) != -1) {
                        total += read;
                        if (total > JerbyStorage.MAX_ROM_BYTES) throw new IOException("Download is over 64 MB");
                        out.write(bytes, 0, read);
                        if (size > 0 && progress != null) {
                            int percent = (int)Math.min(100, total * 100 / size);
                            if (percent >= lastPercent + 5 || percent == 100) {
                                lastPercent = percent;
                                progress.update(percent);
                            }
                        }
                    }
                }
                if (total == 0) throw new IOException("Download is empty");
            } finally { connection.disconnect(); }
            if (!JerbyStorage.sha256(temp).equalsIgnoreCase(game.archiveHash))
                throw new IOException("Download checksum did not match the catalog");
            return JerbyStorage.importVerified(context, temp, game.romName, game.romHash);
        } finally { temp.delete(); }
    }

    private static String readUrl(String address, int maxBytes) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Catalog unavailable");
            try (InputStream in = connection.getInputStream()) { return read(in, maxBytes); }
        } finally { connection.disconnect(); }
    }

    private static String read(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int read;
        while ((read = in.read(bytes)) != -1) {
            if (out.size() + read > maxBytes) throw new IOException("Catalog is too large");
            out.write(bytes, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
