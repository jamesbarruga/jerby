package com.retroarch.browser.jerby;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Jerby owns a small landscape touch overlay and only exposes mute and layout. */
final class JerbyControls {
    static final String[] GROUPS = {"dpad", "face", "select", "start", "left", "right"};
    static final String[] LABELS = {"D-PAD", "A  B  X  Y", "SELECT", "START", "L", "R"};
    private static final float[] BASE_X = {.09462366f, .90537634f, .03333333f, .96666667f, .03333333f, .96666667f};
    private static final float[] BASE_Y = {.55640535f, .55640535f, .81835564f, .81835564f, .28680688f, .28680688f};
    private static final float[] DEFAULT_X = {.16f, .84f, .43f, .57f, .16f, .84f};
    private static final float[] DEFAULT_Y = {.62f, .62f, .80f, .80f, .27f, .27f};
    private static final String[] IMAGES = {
            "dpad_down.png", "dpad_left.png", "dpad_right.png", "dpad_up.png",
            "button_a.png", "button_b.png", "button_x.png", "button_y.png",
            "button_select.png", "button_start.png", "button_l1.png", "button_r1.png"
    };

    private JerbyControls() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("jerby-controls", Context.MODE_PRIVATE);
    }

    static float x(Context context, int group) {
        return prefs(context).getFloat(GROUPS[group] + "-x", DEFAULT_X[group]);
    }

    static float y(Context context, int group) {
        return prefs(context).getFloat(GROUPS[group] + "-y", DEFAULT_Y[group]);
    }

    static boolean muted(Context context) { return prefs(context).getBoolean("muted", false); }

    static void setMuted(Context context, boolean muted) {
        prefs(context).edit().putBoolean("muted", muted).apply();
    }

    static void setPosition(Context context, int group, float x, float y) {
        prefs(context).edit().putFloat(GROUPS[group] + "-x", clamp(x, .13f, .87f))
                .putFloat(GROUPS[group] + "-y", clamp(y, .16f, .84f)).apply();
    }

    static void reset(Context context) {
        SharedPreferences.Editor edit = prefs(context).edit();
        for (String group : GROUPS) { edit.remove(group + "-x"); edit.remove(group + "-y"); }
        edit.apply();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int groupForDescription(int index) {
        if (index <= 12) return 0;
        if (index <= 17 || index >= 22) return 1;
        if (index == 18) return 2;
        if (index == 19) return 3;
        if (index == 20) return 4;
        return 5;
    }

    static File prepare(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "jerby-overlay");
        File imageDirectory = new File(directory, "png/default");
        if (!imageDirectory.isDirectory() && !imageDirectory.mkdirs())
            throw new IOException("Cannot make controls folder");
        for (String image : IMAGES) {
            File destination = new File(imageDirectory, image);
            if (destination.exists()) continue;
            try (InputStream in = context.getAssets().open("overlays/gamepads/neo-retropad/png/default/" + image);
                 FileOutputStream out = new FileOutputStream(destination)) {
                byte[] bytes = new byte[8192];
                int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
        }

        File overlay = new File(directory, "jerby-overlay.cfg");
        StringBuilder layout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("jerby-overlay-base.cfg"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("overlay0_desc") && line.contains(" = \"")) {
                    int equals = line.indexOf(" = \"");
                    String key = line.substring(0, equals);
                    String number = key.substring("overlay0_desc".length());
                    if (number.matches("\\d+")) {
                        int group = groupForDescription(Integer.parseInt(number));
                        String[] parts = line.substring(equals + 4, line.length() - 1).split(",", 6);
                        if (parts.length == 6) {
                            float px = Float.parseFloat(parts[1]) + x(context, group) - BASE_X[group];
                            float py = Float.parseFloat(parts[2]) + y(context, group) - BASE_Y[group];
                            parts[1] = String.format(Locale.US, "%.7f", clamp(px, .001f, .999f));
                            parts[2] = String.format(Locale.US, "%.7f", clamp(py, .001f, .999f));
                            StringBuilder descriptor = new StringBuilder(key).append(" = \"");
                            for (int part = 0; part < parts.length; part++) {
                                if (part > 0) descriptor.append(',');
                                descriptor.append(parts[part]);
                            }
                            line = descriptor.append('"').toString();
                        }
                    }
                }
                layout.append(line).append('\n');
            }
        }
        write(overlay, layout.toString());

        File config = new File(context.getFilesDir(), "jerby-retroarch.cfg");
        write(config, "audio_mute_enable = \"" + muted(context) + "\"\n"
                + "input_overlay_enable = \"true\"\n"
                + "input_overlay_enable_autopreferred = \"false\"\n"
                + "input_overlay = \"" + overlay.getAbsolutePath() + "\"\n");
        return config;
    }

    private static void write(File file, String contents) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }
}
