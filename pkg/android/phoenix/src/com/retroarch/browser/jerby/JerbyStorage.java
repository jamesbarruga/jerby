package com.retroarch.browser.jerby;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class JerbyStorage {
    static final long MAX_ROM_BYTES = 64L * 1024L * 1024L;
    static final String CORE_NAME = "bsnes_mercury_performance_libretro_android.so";

    private JerbyStorage() {}

    static File romDir(Context context) {
        File dir = new File(context.getFilesDir(), "roms");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create game folder");
        return dir;
    }

    static File coreFile(Context context) {
        File dir = new File(context.getApplicationInfo().dataDir, "cores");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create core folder");
        return new File(dir, CORE_NAME);
    }

    static void installBundled(Context context) throws IOException {
        File game = new File(romDir(context), "jerby_dash.sfc");
        if (!game.exists()) copyAsset(context, "jerby_dash.sfc", game);
        File tag = new File(romDir(context), "jerby_tag.sfc");
        if (!tag.exists()) copyAsset(context, "jerby_tag.sfc", tag);
        File core = coreFile(context);
        if (!core.exists()) copyAsset(context, "cores/" + CORE_NAME, core);
    }

    private static void copyAsset(Context context, String asset, File destination) throws IOException {
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    static String safeName(String value) {
        String name = value == null ? "game.sfc" : value.replaceAll("[^A-Za-z0-9._ -]", "_");
        name = name.replaceAll("^\\.+", "");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        String lower = name.toLowerCase();
        if (!(lower.endsWith(".sfc") || lower.endsWith(".smc") || lower.endsWith(".fig")
                || lower.endsWith(".swc") || lower.endsWith(".zip")))
            throw new IllegalArgumentException("Choose a SNES .sfc, .smc, .fig, .swc, or .zip game file");
        return name;
    }

    static File[] games(Context context) {
        File[] files = romDir(context).listFiles(file -> file.isFile() && isRom(file.getName()));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private static boolean isRom(String name) {
        try { safeName(name); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static File findByHash(Context context, String hash) throws IOException {
        for (File file : games(context)) if (sha256(file).equalsIgnoreCase(hash)) return file;
        return null;
    }

    static File receive(Context context, InputStream in, String name, long length, String expectedHash)
            throws IOException {
        name = safeName(name);
        if (length < 1 || length > MAX_ROM_BYTES) throw new IOException("Invalid game size");
        if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Invalid game checksum");
        File destination = new File(romDir(context), expectedHash.substring(0, 8) + "-" + name);
        File temp = new File(romDir(context), ".incoming-" + System.nanoTime());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                long remaining = length;
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (n < 0) throw new IOException("Game transfer ended early");
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            if (!sha256(temp).equalsIgnoreCase(expectedHash)) throw new IOException("Game checksum mismatch");
            if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace game");
            if (!temp.renameTo(destination)) throw new IOException("Cannot save game");
            return destination;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    static File importRom(Context context, InputStream in, String name) throws IOException {
        File temp = new File(romDir(context), ".import-" + System.nanoTime());
        try {
            long total = 0;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_ROM_BYTES) throw new IOException("Game is over 64 MB");
                    out.write(buffer, 0, n);
                }
            }
            if (total == 0) throw new IOException("Game file is empty");
            if (isZip(temp)) {
                List<JerbyArchive.Rom> extracted = JerbyArchive.extract(temp, romDir(context), MAX_ROM_BYTES);
                try {
                    File first = null;
                    for (JerbyArchive.Rom rom : extracted) {
                        File saved = saveRom(context, rom.file, rom.name, null);
                        if (first == null) first = saved;
                    }
                    return first;
                } finally { for (JerbyArchive.Rom rom : extracted) rom.file.delete(); }
            }
            if (name != null && name.toLowerCase(Locale.US).endsWith(".zip"))
                throw new IOException("Download is not a valid ZIP");
            return saveRom(context, temp, safeName(name), null);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    static File importVerified(Context context, File download, String name, String expectedRomHash)
            throws IOException {
        if (expectedRomHash == null || !expectedRomHash.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Invalid catalog checksum");
        if (!isZip(download)) return saveRom(context, download, safeName(name), expectedRomHash);
        List<JerbyArchive.Rom> extracted = JerbyArchive.extract(download, romDir(context), MAX_ROM_BYTES);
        try {
            for (JerbyArchive.Rom rom : extracted)
                if (sha256(rom.file).equalsIgnoreCase(expectedRomHash))
                    return saveRom(context, rom.file, rom.name, expectedRomHash);
            throw new IOException("Downloaded game checksum did not match the catalog");
        } finally { for (JerbyArchive.Rom rom : extracted) rom.file.delete(); }
    }

    private static File saveRom(Context context, File source, String name, String expectedHash)
            throws IOException {
        name = safeName(name);
        if (name.toLowerCase(Locale.US).endsWith(".zip")) throw new IOException("ZIP must contain a SNES game");
        String hash = sha256(source);
        if (expectedHash != null && !hash.equalsIgnoreCase(expectedHash))
            throw new IOException("Downloaded game checksum did not match the catalog");
        File destination = new File(romDir(context), hash.substring(0, 8) + "-" + name);
        if (destination.exists()) {
            if (sha256(destination).equalsIgnoreCase(hash)) return destination;
            throw new IOException("A different game already has this name");
        }
        if (!source.renameTo(destination)) throw new IOException("Cannot save game");
        return destination;
    }

    private static boolean isZip(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int count = 0;
            while (count < magic.length) {
                int read = in.read(magic, count, magic.length - count);
                if (read < 0) return false;
                count += read;
            }
            return magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        }
    }
}
