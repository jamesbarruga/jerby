package com.retroarch.browser.jerby;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded ZIP unpacker. Only SNES ROM entries leave the temporary directory. */
final class JerbyArchive {
    static final class Rom {
        final File file;
        final String name;
        Rom(File file, String name) { this.file = file; this.name = name; }
    }

    private JerbyArchive() {}

    static List<Rom> extract(File archive, File directory, long maxBytes) throws IOException {
        List<Rom> roms = new ArrayList<>();
        File current = null;
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 128) throw new IOException("ZIP contains too many files");
                String path = entry.getName().replace('\\', '/');
                String name = path.substring(path.lastIndexOf('/') + 1);
                String lower = name.toLowerCase(Locale.US);
                boolean snes = !entry.isDirectory() && (lower.endsWith(".sfc") || lower.endsWith(".smc"));
                if (snes && roms.size() >= 32) throw new IOException("ZIP contains too many games");
                if (snes) current = File.createTempFile(".jerby-unzip-", ".tmp", directory);
                long entryBytes = 0;
                try (FileOutputStream out = snes ? new FileOutputStream(current) : null) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        entryBytes += read;
                        if (total > maxBytes) throw new IOException("ZIP expands beyond 64 MB");
                        if (out != null) out.write(buffer, 0, read);
                    }
                }
                if (snes) {
                    if (entryBytes == 0) throw new IOException("ZIP contains an empty game");
                    roms.add(new Rom(current, name));
                    current = null;
                }
                zip.closeEntry();
            }
            if (roms.isEmpty()) throw new IOException("ZIP has no .sfc or .smc game");
            return roms;
        } catch (IOException | RuntimeException error) {
            if (current != null) current.delete();
            for (Rom rom : roms) rom.file.delete();
            throw error;
        }
    }
}
