package ai.onezion.phoneclip;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Share target: copy the shared file(s) into our own storage, put OUR content
 * URI on the phone clipboard (so any app can paste), and hand the same files to
 * the service for upload to the Mac.
 */
public class ShareActivity extends Activity {
    private static final long MAX_TOTAL = 200L * 1024 * 1024;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        }
        if (uris.isEmpty()) {
            finish();
            return;
        }
        toast("选择动作…");
        Actions.choose(this, uris, this::finish);
    }

    /** Copy into our storage (so the URI grant survives), set the phone clipboard, upload. */
    static void copyAndPublish(android.content.Context ctx, ArrayList<Uri> uris) throws Exception {
        java.io.File dir = new java.io.File(ctx.getFilesDir(), "share");
        dir.mkdirs();
        prune(dir);

        String stamp = String.valueOf(System.currentTimeMillis());
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> mimes = new ArrayList<>();
        long total = 0;

        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            String name = ClipService.displayName(ctx, uri);
            String mime = ClipService.mimeOf(ctx, uri);
            String stored = stamp + "-" + i + "-" + name;
            java.io.File dst = new java.io.File(dir, stored);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dst)) {
                if (in == null) throw new java.io.IOException("no stream for " + name);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_TOTAL) {
                        dst.delete();
                        throw new java.io.IOException("超过 200 MB 上限");
                    }
                    out.write(buf, 0, n);
                }
            }
            paths.add(dst.getAbsolutePath());
            mimes.add(mime);
        }

        Uri first = Uri.parse("content://" + ClipProvider.AUTHORITY + "/share/"
                + new java.io.File(paths.get(0)).getName());
        ClipData clip;
        if (paths.size() == 1) {
            clip = ClipData.newUri(ctx.getContentResolver(), "PhoneClip", first);
        } else {
            String[] types = mimes.toArray(new String[0]);
            clip = new ClipData(new ClipDescription("PhoneClip", types),
                    new ClipData.Item(first));
            for (int i = 1; i < paths.size(); i++) {
                clip.addItem(new ClipData.Item(Uri.parse("content://" + ClipProvider.AUTHORITY
                        + "/share/" + new java.io.File(paths.get(i)).getName())));
            }
        }
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(ctx.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(clip);
        android.util.Log.i("PhoneClip", "clip set: " + clip.getItemCount() + " item(s)");

        Intent svc = new Intent(ctx, ClipService.class);
        svc.putExtra("action", "offer");
        svc.putStringArrayListExtra("paths", paths);
        svc.putStringArrayListExtra("mimes", mimes);
        startServiceSafely(ctx, svc);
    }

    /** Android 12+ throws when a background context starts a foreground service. */
    static void startServiceSafely(android.content.Context ctx, Intent svc) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Exception e) {
            android.util.Log.w("PhoneClip", "startForegroundService failed, retrying", e);
            try {
                ctx.startService(svc);
            } catch (Exception e2) {
                android.util.Log.w("PhoneClip", "service start failed", e2);
            }
        }
    }

    /** Keep only the most recent shares; older clipboard files are unlikely to be pasted. */
    private static void prune(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files == null || files.length < 12) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 8; i < files.length; i++) files[i].delete();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
