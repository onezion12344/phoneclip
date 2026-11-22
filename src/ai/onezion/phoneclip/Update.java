package ai.onezion.phoneclip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Self-update from GitHub Releases: tag vX.Y.Z + asset PhoneClip.apk. */
final class Update {
    static final String TAG = "PhoneClip";
    static final String REPO = "onezion12344/phoneclip";
    static final String ASSET = "PhoneClip.apk";
    static final String CHANNEL = "update";
    static final int NOTIF_ID = 48;

    static final class Info {
        String version;
        String url;
        long size;
    }

    static Info latest() throws Exception {
        String body = Http.getTextOrNull(
                "https://api.github.com/repos/" + REPO + "/releases/latest", "");
        if (body == null) return null;
        JSONObject o = new JSONObject(body);
        Info info = new Info();
        info.version = o.optString("tag_name", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (ASSET.equals(a.optString("name"))) {
                    info.url = a.optString("browser_download_url");
                    info.size = a.optLong("size");
                }
            }
        }
        if (info.version.isEmpty() || info.url == null || info.url.isEmpty()) return null;
        return info;
    }

    static String installed(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    static boolean isNewer(String latest, String installed) {
        int[] a = nums(latest);
        int[] b = nums(installed);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] nums(String v) {
        if (v == null) return new int[0];
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        List<Integer> out = new ArrayList<>();
        for (String p : v.split("[^0-9]+")) {
            if (!p.isEmpty()) out.add(Integer.parseInt(p));
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static File download(Context ctx, Info info) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(info.url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PhoneClip");
        File dir = new File(ctx.getFilesDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("no dir");
        File apk = new File(dir, ASSET);
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (info.size > 0 && apk.length() != info.size) {
            apk.delete();
            throw new java.io.IOException("download incomplete");
        }
        return apk;
    }

    static void checkAndNotify(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            Info info = latest();
            String installed = installed(ctx);
            Log.i(TAG, "update check: latest=" + (info == null ? "none" : info.version)
                    + " installed=" + installed);
            if (info == null || !isNewer(info.version, installed)) return;

            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent pi = PendingIntent.getActivity(ctx, 2,
                    new Intent(ctx, UpdateActivity.class).putExtra("version", info.version),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTIF_ID, new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("PhoneClip " + info.version + " 可用")
                    .setContentText("点击下载并安装")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception e) {
            Log.w(TAG, "update check: " + e);
        }
    }
}
