package ai.onezion.phoneclip;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves files under filesDir (clip images and the downloaded update APK) as
 * content URIs. Clipboard and the installer get a temporary read grant.
 */
public class ClipProvider extends ContentProvider {
    static final String AUTHORITY = "ai.onezion.phoneclip.files";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            File root = getContext().getFilesDir().getCanonicalFile();
            File file = new File(root, uri.getPath()).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)) {
                throw new FileNotFoundException(uri.toString());
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(uri.toString());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File file = new File(getContext().getFilesDir(), uri.getPath()).getCanonicalFile();
            String[] cols = { "_display_name", "_size" };
            android.database.MatrixCursor c = new android.database.MatrixCursor(cols);
            c.addRow(new Object[] { file.getName(), file.length() });
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public String getType(Uri uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        if (path.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".m4a")) return "audio/mp4";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".pdf")) return "application/pdf";
        if (path.endsWith(".txt")) return "text/plain";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        return "image/png";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
