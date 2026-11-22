package ai.onezion.phoneclip;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Daily background work: photo observation and recorder sync. */
final class Jobs {
    private static final String TAG = "PhoneClip";

    static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000;
    }

    static void photoVlm(Context ctx, boolean force) {
        try {
            long since = force ? 0 : Config.lastVlmRun(ctx);
            android.util.Log.i(TAG, "photo vlm: since=" + since);
            Media.Photo p = Media.newestPhoto(ctx, since, true);
            android.util.Log.i(TAG, "photo vlm: photo=" + (p == null ? "none" : p.name));
            if (p == null) return;
            byte[] jpeg = Media.jpegFromUri(ctx, p.uri, 1280, 85);
            String text = Vision.observe(ctx, jpeg,
                    "文件名 " + p.name + "，入库时间 " + p.dateAdded);
            android.util.Log.i(TAG, "photo vlm: text=" + text.length() + " ch");
            if (text.isEmpty()) return;
            String name = "obs-" + p.name.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
            Transcription.saveToDownloads(ctx, name, text);
            try {
                Transcription.syncToMac(ctx, name, text);
            } catch (Exception e) {
                Log.w(TAG, "vlm sync: " + e);
            }
            Config.setLastVlmRun(ctx, System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            Log.w(TAG, "photo vlm: " + e);
        }
    }

    static void recorderSync(Context ctx) {
        try {
            long since = Config.lastRecRun(ctx);
            List<File> files = recordingsSince(ctx, since);
            String mode = Config.recMode(ctx);
            for (File f : files) {
                String base = stripExt(f.getName());
                if ("transcript".equals(mode)) {
                    String text = Transcription.run(ctx, f, "audio/*");
                    if (text.isEmpty()) continue;
                    String name = base + ".txt";
                    Transcription.saveToDownloads(ctx, name, text);
                    try {
                        Transcription.syncToMac(ctx, name, text);
                    } catch (Exception e) {
                        Log.w(TAG, "rec sync: " + e);
                    }
                } else {
                    File out = AudioCompressor.compress(ctx, Uri.fromFile(f), base);
                    ClipService.uploadSilent(ctx, out);
                }
                Config.setLastRecName(ctx, f.getName());
            }
            Config.setLastRecRun(ctx, System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            Log.w(TAG, "recorder sync: " + e);
        }
    }

    private static List<File> recordingsSince(Context ctx, long sinceSec) {
        List<File> out = new ArrayList<>();
        String[] cols = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED };
        String sel = MediaStore.Audio.Media.DATE_ADDED + " > ?";
        try (Cursor c = ctx.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, sel,
                new String[] { String.valueOf(sinceSec) },
                MediaStore.Audio.Media.DATE_ADDED + " ASC")) {
            if (c == null) return out;
            int dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA);
            while (c.moveToNext() && out.size() < 5) {
                String path = dataCol >= 0 ? c.getString(dataCol) : null;
                if (path == null) continue;
                File f = new File(path);
                if (!f.isFile() || f.length() == 0) continue;
                if (f.getName().startsWith("pc_test")) continue;
                out.add(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "recorder query: " + e);
        }
        return out;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
