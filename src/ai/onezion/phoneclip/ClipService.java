package ai.onezion.phoneclip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ClipService extends Service {
    private static final String TAG = "PhoneClip";
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 47;

    private HandlerThread thread;
    private Handler bg;
    private ClipboardManager clipboard;
    private ContentObserver photoObserver;

    private long lastRev = -1;
    private String lastSentHash = "";
    private long lastPhotoEvent;
    private long lastClipStamp;

    private volatile boolean running = true;
    private Thread pollThread;

    private final Runnable photoTask = () -> uploadNewest(false);

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("OneZion Clip")
                .setContentText("Watching camera + clipboard")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } catch (Exception e) {
                startForeground(NOTIF_ID, n);
            }
        } else {
            startForeground(NOTIF_ID, n);
        }

        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        thread = new HandlerThread("phoneclip");
        thread.start();
        bg = new Handler(thread.getLooper());

        if (Config.lastPhotoTime(this) == 0) {
            Config.setLastPhotoTime(this, System.currentTimeMillis() / 1000L);
        }

        photoObserver = new ContentObserver(bg) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                long now = SystemClock.uptimeMillis();
                if (now - lastPhotoEvent < 500) return;
                lastPhotoEvent = now;
                bg.removeCallbacks(photoTask);
                bg.postDelayed(photoTask, 1200);
            }
        };
        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, photoObserver);

        // No clipboard listener: Android 13+ denies background clipboard reads on
        // this ROM. Phone → Mac text/image goes through the Quick Settings tile.

        pollThread = new Thread(this::pollLoop, "mac-poll");
        pollThread.start();

        bg.postDelayed(this::checkUpdate, 45000);
    }

    private void checkUpdate() {
        Update.checkAndNotify(this);
        if (running) bg.postDelayed(this::checkUpdate, 24L * 60 * 60 * 1000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getStringExtra("action");
        Log.i(TAG, "action: " + action);
        if ("newest".equals(action)) bg.post(() -> uploadNewest(true));
        if ("test".equals(action)) bg.post(this::sendTest);
        if ("push".equals(action)) bg.post(this::pushLocalClipQuietly);
        if ("update".equals(action)) bg.post(this::checkUpdate);
        if ("sharetest".equals(action)) bg.post(this::shareTest);
        if ("transcribetest".equals(action)) {
            String path = intent.getStringExtra("path");
            if (path != null) bg.post(() -> transcribeFile(new File(path)));
        }
        if ("compresstest".equals(action)) {
            String path = intent.getStringExtra("path");
            if (path != null) bg.post(() -> compressFile(new File(path)));
        }
        if ("compress".equals(action)) {
            String uri = intent.getStringExtra("uri");
            String name = intent.getStringExtra("name");
            if (uri != null) bg.post(() -> compressAndSend(Uri.parse(uri), name));
        }
        if ("offer".equals(action)) {
            ArrayList<String> paths = intent.getStringArrayListExtra("paths");
            ArrayList<String> mimes = intent.getStringArrayListExtra("mimes");
            if (paths != null && !paths.isEmpty()) {
                bg.post(() -> offer(paths, mimes));
            }
        }
        if ("copy".equals(action)) {
            String text = intent.getStringExtra("text");
            if (text != null) bg.post(() -> {
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("test", text));
                    Log.i(TAG, "copy action applied (" + text.length() + " ch)");
                } catch (Exception e) {
                    Log.w(TAG, "copy", e);
                }
            });
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (pollThread != null) pollThread.interrupt();
        if (photoObserver != null) {
            try { getContentResolver().unregisterContentObserver(photoObserver); } catch (Exception ignored) {}
        }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context ctx) {
        Intent i = new Intent(ctx, ClipService.class);
        ctx.startForegroundService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ClipService.class));
    }

    // ---- photos ------------------------------------------------------------

    private void uploadNewest(boolean force) {
        String server = Config.server(this);
        if (server.isEmpty()) return;
        long since = force ? 0 : Config.lastPhotoTime(this);
        boolean all = force || "all".equals(Config.scope(this));
        Media.Photo p = Media.newestPhoto(this, since, all);
        if (p == null) return;
        try {
            byte[] jpeg = Media.jpegFromUri(this, p.uri, 2560, 90);
            String name = URLEncoder.encode(jpegName(p.name), StandardCharsets.UTF_8.name());
            Http.post(server + "/v1/photo?name=" + name,
                    Config.token(this), "image/jpeg", jpeg);
            Config.setLastPhotoTime(this, p.dateAdded);
            note("Photo → Mac (" + jpeg.length / 1024 + " KB)");
        } catch (Exception e) {
            Log.w(TAG, "photo upload failed", e);
            note("Photo failed: " + e.getClass().getSimpleName());
            bg.postDelayed(photoTask, 2500);
        }
    }

    // ---- phone -> Mac clipboard -------------------------------------------

    private void pushLocalClipQuietly() {
        try {
            pushLocalClip();
            Log.i(TAG, "push: done");
        } catch (Exception e) {
            lastClipStamp = 0;
            Log.w(TAG, "push: " + e);
        }
    }

    private void pushLocalClip() throws Exception {
        String server = Config.server(this);
        if (server.isEmpty()) return;
        if (!Settings.canDrawOverlays(this)) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;

        long stamp = clip.getDescription() == null ? 0 : clip.getDescription().getTimestamp();
        if (stamp != 0 && stamp == lastClipStamp) return;
        lastClipStamp = stamp;

        ClipData.Item item = clip.getItemAt(0);

        Uri uri = item.getUri();
        CharSequence text = item.getText();

        if (uri != null && isImageUri(this, uri)) {
            byte[] jpeg = Media.jpegFromUri(this, uri, 1600, 80);
            String hash = "i" + Media.sha1(jpeg);
            if (hash.equals(lastSentHash)) return;
            Http.post(server + "/v1/clip?kind=image", Config.token(this), "image/jpeg", jpeg);
            lastSentHash = hash;
            note("Image → Mac (" + jpeg.length / 1024 + " KB)");
        } else if (text != null) {
            String s = text.toString();
            if (s.isEmpty() || s.length() > 200000) return;
            byte[] body = s.getBytes(StandardCharsets.UTF_8);
            String hash = "t" + Media.sha1(body);
            if (hash.equals(lastSentHash)) return;
            Http.post(server + "/v1/clip?kind=text", Config.token(this),
                    "text/plain; charset=utf-8", body);
            lastSentHash = hash;
            note("Text → Mac (" + s.length() + " ch)");
        }
    }

    // ---- Mac -> phone clipboard -------------------------------------------

    private void pollLoop() {
        int backoff = 3000;
        while (running) {
            String server = Config.server(this);
            if (server.isEmpty()) {
                sleep(15000);
                continue;
            }
            try {
                String body = Http.getTextOrNull(
                        server + "/v1/clip?rev=" + lastRev + "&wait=240",
                        Config.token(this), 265000);
                backoff = 3000;
                if (body != null) applyRemote(body);
            } catch (Exception e) {
                if (!running) return;
                Log.w(TAG, "pull: " + e);
                sleep(backoff);
                backoff = Math.min(backoff * 2, 60000);
            }
        }
    }

    private void applyRemote(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        long rev = o.getLong("rev");
        String kind = o.optString("kind", "");
        if ("text".equals(kind)) {
            String text = o.optString("text", "");
            if (!text.isEmpty()) setTextClip(text);
        } else if ("image".equals(kind)) {
            String server = Config.server(this);
            byte[] png = Http.getBytes(server + o.getString("url"), Config.token(this));
            setImageClip(png);
        }
        lastRev = rev;

        JSONObject cmd = o.optJSONObject("cmd");
        if (cmd != null) handleCommand(cmd);
    }

    // ---- sidecar: phone files for the Mac app -------------------------------

    private void handleCommand(JSONObject cmd) {
        String id = cmd.optString("id", "");
        String op = cmd.optString("op", "");
        String path = cmd.optString("path", "");
        try {
            if ("list".equals(op)) {
                postResult(id, listDir(path));
            } else if ("get".equals(op)) {
                File f = new File(path);
                if (!f.isFile() || !allowedPath(f)) throw new Exception("not a file");
                uploadSilent(this, f);
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("name", f.getName());
                res.put("size", f.length());
                postResult(id, res);
            } else {
                JSONObject res = new JSONObject();
                res.put("ok", false);
                res.put("error", "unknown op");
                postResult(id, res);
            }
        } catch (Exception e) {
            try {
                JSONObject res = new JSONObject();
                res.put("ok", false);
                res.put("error", e.getClass().getSimpleName());
                postResult(id, res);
            } catch (Exception ignored) {}
        }
    }

    private JSONObject listDir(String path) throws Exception {
        File dir = new File(path.isEmpty() ? "/sdcard" : path);
        if (!dir.isDirectory() || !allowedPath(dir)) throw new Exception("denied");
        File[] files = dir.listFiles();
        JSONArray entries = new JSONArray();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith(".")) continue;
                if (dir.getName().equals("Android")
                        && (name.equals("data") || name.equals("obb"))) continue;
                JSONObject e = new JSONObject();
                e.put("name", name);
                e.put("dir", f.isDirectory());
                e.put("size", f.length());
                e.put("mtime", f.lastModified() / 1000L);
                entries.put(e);
                if (entries.length() >= 2000) break;
            }
        }
        JSONObject res = new JSONObject();
        res.put("ok", true);
        res.put("path", dir.getAbsolutePath());
        res.put("entries", entries);
        return res;
    }

    private boolean allowedPath(File f) throws Exception {
        String p = f.getCanonicalPath();
        return p.equals("/storage/emulated/0") || p.startsWith("/storage/emulated/0/");
    }

    private static String mimeOfPath(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private void postResult(String id, JSONObject res) throws Exception {
        String url = Config.server(this) + "/v1/sidecar/result?id="
                + URLEncoder.encode(id, StandardCharsets.UTF_8.name());
        Http.post(url, Config.token(this), "application/json",
                res.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void setTextClip(String text) {
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Mac", text));
            lastSentHash = "t" + Media.sha1(text.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "applied text clip (" + text.length() + " ch)");
        } catch (Exception e) {
            Log.w(TAG, "set text clip", e);
        }
    }

    private void setImageClip(byte[] png) throws Exception {
        File dir = new File(getFilesDir(), "clip");
        if (!dir.exists() && !dir.mkdirs()) return;
        File file = new File(dir, "mac.png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(png);
        }
        Uri uri = Uri.parse("content://" + ClipProvider.AUTHORITY + "/clip/mac.png");
        clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "Mac image", uri));
        // Hash the re-encoded form so pushLocalClip recognises it as our own copy.
        lastSentHash = "i" + Media.sha1(Media.jpegFromFile(file, 1600, 80));
        Log.i(TAG, "applied image clip (" + png.length / 1024 + " KB)");
    }

    // ---- helpers -----------------------------------------------------------

    private void transcribeFile(File file) {
        try {
            String text = Transcription.run(this, file, "audio/mp4");
            Transcription.saveToDownloads(this, file.getName() + ".txt", text);
            if (Config.tSync(this)) {
                Transcription.syncToMac(this, file.getName() + ".txt", text);
            }
            note("转录完成：" + text.length() + " 字");
        } catch (Exception e) {
            Log.w(TAG, "transcribetest", e);
            note("转录失败：" + e.getClass().getSimpleName());
        }
    }

    private void compressFile(File file) {
        try {
            String base = file.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            File out = VideoCompressor.compress(this, Uri.fromFile(file), base);
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(Uri.fromFile(out));
            ShareActivity.copyAndPublish(this, uris);
            note("压缩完成：" + out.length() / 1024 + " KB");
        } catch (Exception e) {
            Log.w(TAG, "compresstest", e);
            note("压缩失败：" + e.getClass().getSimpleName());
        }
    }

    private void shareTest() {
        try {
            Media.Photo p = Media.newestPhoto(this, 0, true);
            if (p == null) {
                note("share test: no photo");
                return;
            }
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(p.uri);
            ShareActivity.copyAndPublish(this, uris);
            note("share test ok");
        } catch (Exception e) {
            Log.w(TAG, "share test", e);
            note("share test failed: " + e.getClass().getSimpleName());
        }
    }

    private void compressAndSend(Uri uri, String name) {
        try {
            note("正在压缩 720p…");
            File out = VideoCompressor.compress(this, uri, name == null ? "video" : name);
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(Uri.fromFile(out));
            ShareActivity.copyAndPublish(this, uris);
            note("已压缩并发送（" + out.length() / 1024 + " KB）");
        } catch (Exception e) {
            Log.w(TAG, "compress", e);
            note("压缩失败：" + e.getClass().getSimpleName());
        }
    }

    static boolean isRunning(Context ctx) {
        android.app.ActivityManager am =
                (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info
                : am.getRunningServices(Integer.MAX_VALUE)) {
            if (ClipService.class.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }

    static int countDir(String path) throws Exception {
        File dir = new File(path);
        if (!dir.isDirectory()) throw new Exception("not a directory");
        String[] names = dir.list();
        return names == null ? 0 : names.length;
    }

    static boolean isImageUri(Context ctx, Uri uri) {
        String type = ctx.getContentResolver().getType(uri);
        if (type != null && type.startsWith("image/")) return true;
        String s = uri.toString().toLowerCase();
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png")
                || s.endsWith(".webp") || s.endsWith(".heic") || s.endsWith(".heif");
    }

    /** Runs only while an Activity is foreground, where clipboard reads are allowed. */
    static String sendClipboardNow(Context ctx) throws Exception {
        String server = Config.server(ctx);
        if (server.isEmpty()) return "未配置服务器";
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "剪贴板为空";
        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        if (uri != null && isImageUri(ctx, uri)) {
            byte[] jpeg = Media.jpegFromUri(ctx, uri, 1600, 80);
            Http.post(server + "/v1/clip?kind=image",
                    Config.token(ctx), "image/jpeg", jpeg);
            return "已发送图片 " + (jpeg.length / 1024) + " KB";
        }
        CharSequence text = item.getText();
        if (text == null || text.length() == 0) {
            if (uri != null) {
                String name = displayName(ctx, uri);
                uploadUri(ctx, uri, name, null, 0, 1);
                return "已发送文件 " + name;
            }
            return "剪贴板里没有文字";
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        Http.post(server + "/v1/clip?kind=text", Config.token(ctx),
                "text/plain; charset=utf-8", body);
        return "已发送文字 " + text.length() + " 字";
    }

    static String displayName(Context ctx, Uri uri) {
        String name = null;
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) name = "file";
        return name.replace('/', '_');
    }

    static String mimeOf(Context ctx, Uri uri) {
        String t = ctx.getContentResolver().getType(uri);
        return t == null ? "application/octet-stream" : t;
    }

    static void uploadUri(Context ctx, Uri uri, String name, String batch,
                          int index, int total) throws Exception {
        String url = Config.server(ctx) + "/v1/file?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8.name());
        if (batch != null) {
            url += "&batch=" + URLEncoder.encode(batch, "UTF-8") + "&i=" + index + "&n=" + total;
        }
        long len = -1;
        try (android.content.res.AssetFileDescriptor afd =
                     ctx.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null) len = afd.getLength();
        } catch (Exception ignored) {}
        java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("no stream");
        Http.postStream(url, Config.token(ctx), mimeOf(ctx, uri), in, len);
    }

    /** Files already copied into our storage by ShareActivity. */
    private void offer(ArrayList<String> paths, ArrayList<String> mimes) {
        String server = Config.server(this);
        if (server.isEmpty()) return;
        try {
            if (paths.size() == 1 && mimes != null && !mimes.isEmpty()
                    && mimes.get(0).startsWith("image/")) {
                File f = new File(paths.get(0));
                byte[] jpeg = Media.jpegFromFile(f, 2560, 90);
                String name = URLEncoder.encode(jpegName(stripStamp(f.getName())),
                        StandardCharsets.UTF_8.name());
                Http.post(server + "/v1/photo?name=" + name,
                        Config.token(this), "image/jpeg", jpeg);
                note("Photo → Mac (" + jpeg.length / 1024 + " KB)");
                return;
            }
            String batch = "b" + System.currentTimeMillis();
            for (int i = 0; i < paths.size(); i++) {
                File f = new File(paths.get(i));
                String mime = mimes != null && i < mimes.size()
                        ? mimes.get(i) : "application/octet-stream";
                uploadPath(this, f, mime, batch, i, paths.size());
            }
            note(paths.size() == 1 ? "File → Mac" : "Files → Mac (" + paths.size() + ")");
        } catch (Exception e) {
            Log.w(TAG, "offer", e);
            note("Send failed: " + e.getClass().getSimpleName());
        }
    }

    static void uploadPath(Context ctx, File file, String mime, String batch,
                           int index, int total) throws Exception {
        String name = URLEncoder.encode(stripStamp(file.getName()),
                StandardCharsets.UTF_8.name());
        String url = Config.server(ctx) + "/v1/file?name=" + name
                + "&batch=" + URLEncoder.encode(batch, "UTF-8")
                + "&i=" + index + "&n=" + total;
        java.io.InputStream in = new java.io.FileInputStream(file);
        Http.postStream(url, Config.token(ctx), mime, in, file.length());
    }

    /** Sidecar download: save on the Mac without touching its clipboard. */
    static void uploadSilent(Context ctx, File file) throws Exception {
        String name = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8.name());
        String url = Config.server(ctx) + "/v1/file?name=" + name + "&silent=1";
        java.io.InputStream in = new java.io.FileInputStream(file);
        Http.postStream(url, Config.token(ctx), mimeOfPath(file), in, file.length());
    }

    private static String stripStamp(String name) {
        int dash = name.indexOf('-');
        if (dash < 0) return name;
        int dash2 = name.indexOf('-', dash + 1);
        if (dash2 < 0) return name;
        String head = name.substring(0, dash);
        if (head.isEmpty() || !head.matches("\\d+")) return name;
        return name.substring(dash2 + 1);
    }

    private void sendTest() {
        String server = Config.server(this);
        if (server.isEmpty()) return;
        try {
            Http.post(server + "/v1/photo?name=test.jpg",
                    Config.token(this), "image/jpeg", testJpeg());
            note("Test image → Mac");
        } catch (Exception e) {
            note("Test failed: " + e.getClass().getSimpleName());
        }
    }

    static byte[] testJpeg() {
        Bitmap bmp = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(20, 24, 38));
        c.drawRect(0, 0, 640, 400, p);
        p.setColor(Color.rgb(120, 220, 180));
        c.drawCircle(320, 200, 90, p);
        p.setColor(Color.WHITE);
        p.setTextSize(36);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("PhoneClip test", 320, 340, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static String jpegName(String name) {
        if (name == null || name.isEmpty()) return "photo.jpg";
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + ".jpg";
    }

    private void note(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("OneZion Clip")
                    .setContentText(text)
                    .setOngoing(true)
                    .build());
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Clip bridge", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
