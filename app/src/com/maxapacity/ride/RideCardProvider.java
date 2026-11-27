package com.maxapacity.ride;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.UUID;

/** Only finished, randomly named cards in the private cache can be opened. */
public final class RideCardProvider extends ContentProvider {
    static final String AUTHORITY = "com.maxapacity.ride.cards";
    private static File directory(Context c) { return new File(c.getCacheDir(), "ride-cards"); }

    static Uri save(Context c, Bitmap image) throws IOException {
        File dir = directory(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cache unavailable");
        File[] old = dir.listFiles();
        if (old != null) for (File file : old) {
            if (file.lastModified() < System.currentTimeMillis() - 86400000L) file.delete();
        }
        String name = UUID.randomUUID() + ".jpg";
        File file = new File(dir, name);
        try (OutputStream out = new FileOutputStream(file)) {
            if (!image.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("Encoding failed");
        } catch (IOException e) { file.delete(); throw e; }
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (!AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1 || name == null
                || !name.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.jpg"))
            throw new IllegalArgumentException("Unknown ride card");
        return new File(directory(getContext()), name);
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { resolve(uri); return "image/jpeg"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
        File f = resolve(uri);
        if (columns == null) columns = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        if (f.isFile()) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) row.add(OpenableColumns.SIZE.equals(column) ? f.length()
                    : OpenableColumns.DISPLAY_NAME.equals(column) ? "MaxRidr-ride.jpg" : null);
        }
        return cursor;
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
