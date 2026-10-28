package com.retroarch.browser.jerby;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class JerbyCoverLoader {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    private JerbyCoverLoader() {}

    static void load(Activity activity, ImageView view, String address) {
        if (address == null) return;
        view.setTag(address);
        Bitmap cached = CACHE.get(address);
        if (cached != null) { view.setImageBitmap(cached); return; }
        WORKERS.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(address);
                if (!"https".equals(url.getProtocol()) || !"cache.downloadroms.io".equals(url.getHost())) return;
                connection = (HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 2 * 1024 * 1024) return;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (bytes.size() + read > 2 * 1024 * 1024) return;
                        bytes.write(buffer, 0, read);
                    }
                }
                byte[] data = bytes.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 400 || bounds.outHeight / options.inSampleSize > 400)
                    options.inSampleSize *= 2;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap == null) return;
                CACHE.put(address, bitmap);
                activity.runOnUiThread(() -> {
                    if (address.equals(view.getTag())) view.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
        });
    }
}
