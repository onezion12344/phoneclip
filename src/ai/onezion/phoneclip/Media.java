package ai.onezion.phoneclip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;

final class Media {
    static final class Photo {
        Uri uri;
        long dateAdded;
        String name;
    }

    static Photo newestCameraPhoto(Context ctx, long minDateAdded) {
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
        };
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND "
                + MediaStore.Images.Media.DATE_ADDED + " > ?";
        String[] args = { "DCIM/Camera%", String.valueOf(minDateAdded) };
        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (android.database.Cursor c = ctx.getContentResolver().query(
                base, projection, selection, args, sort)) {
            if (c == null || !c.moveToFirst()) return null;
            Photo p = new Photo();
            p.uri = Uri.withAppendedPath(base, String.valueOf(c.getLong(0)));
            p.dateAdded = c.getLong(1);
            p.name = c.getString(2);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] jpegFromUri(Context ctx, Uri uri, int maxEdge, int quality) throws Exception {
        return jpeg(ImageDecoder.createSource(ctx.getContentResolver(), uri), maxEdge, quality);
    }

    static byte[] jpegFromFile(File file, int maxEdge, int quality) throws Exception {
        return jpeg(ImageDecoder.createSource(file), maxEdge, quality);
    }

    private static byte[] jpeg(ImageDecoder.Source source, int maxEdge, int quality) throws Exception {
        Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            float scale = Math.min(1f, maxEdge / (float) Math.max(w, h));
            if (scale < 1f) {
                decoder.setTargetSize(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(new String(data).hashCode());
        }
    }
}
