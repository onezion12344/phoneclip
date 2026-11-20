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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
        Media.Photo p = Media.newestCameraPhoto(this, since);
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
        if (text == null || text.length() == 0) return "剪贴板里没有文字";
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        Http.post(server + "/v1/clip?kind=text", Config.token(ctx),
                "text/plain; charset=utf-8", body);
        return "已发送文字 " + text.length() + " 字";
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
