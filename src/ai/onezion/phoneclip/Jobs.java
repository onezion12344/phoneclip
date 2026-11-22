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
            String text = Vision.observe(ctx, jpeg, photoMeta(ctx, p));
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

    static void recorderSync(Context ctx, boolean force) {
        try {
            long since = force ? startOfToday() : Config.lastRecRun(ctx);
            List<File> files = recordingsSince(ctx, since);
            android.util.Log.i(TAG, "recorder: since=" + since + " found=" + files.size());
            String mode = Config.recMode(ctx);
            for (File f : files) {
                String base = stripExt(f.getName());
                if ("transcript".equals(mode)) {
                    File audio = isVideo(f)
                            ? Media.extractAudio(ctx, Uri.fromFile(f), base + "-audio")
                            : f;
                    String text = Transcription.run(ctx, audio, "audio/*");
                    if (text.isEmpty()) continue;
                    String name = base + ".txt";
                    Transcription.saveToDownloads(ctx, name, text);
                    try {
                        Transcription.syncToMac(ctx, name, text);
                    } catch (Exception e) {
                        Log.w(TAG, "rec sync: " + e);
                    }
                } else {
                    File out = isVideo(f)
                            ? VideoCompressor.compress(ctx, Uri.fromFile(f), base)
                            : AudioCompressor.compress(ctx, Uri.fromFile(f), base);
                    ClipService.uploadSilent(ctx, out);
                }
                Config.setLastRecName(ctx, f.getName());
            }
            Config.setLastRecRun(ctx, System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            Log.w(TAG, "recorder sync: " + e);
        }
    }

    private static final String[] RECORDING_DIRS = {
            "/sdcard/Recordings", "/sdcard/Recordings/voice", "/sdcard/MySounds",
            "/sdcard/DCIM/Recordings", "/sdcard/Music/Recordings",
    };
    private static final String[] AUDIO_EXT = {
            ".m4a", ".mp3", ".aac", ".wav", ".amr", ".ogg", ".opus",
    };
    private static final String[] VIDEO_EXT = {
            ".mp4", ".mov", ".mkv", ".3gp", ".webm", ".m4v",
    };

    /** MediaStore only exposes files the app owns without READ_MEDIA_AUDIO, so the
     *  recorder directories are also scanned directly (all-files access is granted). */
    private static List<File> recordingsSince(Context ctx, long sinceSec) {
        List<File> out = new ArrayList<>();
        for (String dir : RECORDING_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || f.length() == 0) continue;
                if (f.lastModified() / 1000 < sinceSec) continue;
                if ((isAudio(f) || isVideo(f)) && out.size() < 5) out.add(f);
            }
        }
        if (!out.isEmpty()) return out;
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

    /** Name, capture/added time, size and pixel dimensions when the provider exposes them. */
    private static String photoMeta(Context ctx, Media.Photo p) {
        StringBuilder b = new StringBuilder("文件名 ").append(p.name)
                .append("，入库时间 ").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(p.dateAdded * 1000L)));
        try (android.database.Cursor c = ctx.getContentResolver().query(p.uri,
                new String[] { MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                        MediaStore.Images.Media.DATE_TAKEN },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                long size = c.getLong(0);
                int w = c.getInt(1), h = c.getInt(2);
                long taken = c.getLong(3);
                if (size > 0) b.append("，").append(size / 1024).append(" KB");
                if (w > 0 && h > 0) b.append("，").append(w).append("x").append(h);
                if (taken > 0) b.append("，拍摄于 ").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(taken)));
            }
        } catch (Exception ignored) {
        }
        return b.toString();
    }

    static boolean isAudio(File f) {
        String n = f.getName().toLowerCase();
        for (String ext : AUDIO_EXT) if (n.endsWith(ext)) return true;
        return false;
    }

    static boolean isVideo(File f) {
        String n = f.getName().toLowerCase();
        for (String ext : VIDEO_EXT) if (n.endsWith(ext)) return true;
        return false;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
