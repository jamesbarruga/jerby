package com.retroarch.browser.jerby;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only URI for Android's offline Bluetooth / nearby share sheet. */
public final class JerbyApkProvider extends ContentProvider {
    private File apk() { return new File(getContext().getApplicationInfo().sourceDir); }

    private void check(Uri uri) throws FileNotFoundException {
        if (!"/jerby.apk".equals(uri.getPath())) throw new FileNotFoundException("Unknown file");
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "/jerby.apk".equals(uri.getPath()) ? "application/vnd.android.package-archive" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(apk(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!"/jerby.apk".equals(uri.getPath())) return null;
        String[] columns = projection == null
                ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "Jerby.apk";
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = apk().length();
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
