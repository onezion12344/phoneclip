package ai.onezion.phoneclip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** Shared action chooser used by the share sheet and the in-app pickers. */
final class Actions {
    static void choose(Activity a, ArrayList<Uri> uris, Runnable onDone) {
        if (uris.isEmpty()) {
            onDone.run();
            return;
        }
        String mime = a.getContentResolver().getType(uris.get(0));
        boolean media = mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"));
        if (!media) {
            send(a, uris);
            onDone.run();
            return;
        }
        String[] items = {
                "发送到 Mac",
                "只保留音频",
                Config.tSync(a) ? "转写（并同步文本到 Mac）" : "转写",
        };
        boolean video = mime.startsWith("video/");
        String[] withCompress = video ? new String[] {
                items[0], items[1], "压缩到 720p 并发送", items[2]
        } : items;
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("要做什么？")
                .setItems(withCompress, (d, which) -> {
                    if (which == 0) send(a, uris);
                    else if (which == 1) audioOnly(a, uris.get(0), mime);
                    else if (video && which == 2) compress(a, uris.get(0));
                    else transcribe(a, uris.get(0), mime);
                })
                .create();
        dialog.setOnDismissListener(d -> onDone.run());
        dialog.show();
    }

    private static void send(Activity a, ArrayList<Uri> uris) {
        toast(a, "正在复制…");
        new Thread(() -> {
            try {
                ShareActivity.copyAndPublish(a, uris);
                toast(a, uris.size() == 1 ? "已复制到手机 + Mac" : "已复制 " + uris.size() + " 个文件");
            } catch (Exception e) {
                toast(a, "失败：" + msg(e));
            }
        }, "action-send").start();
    }

    private static void audioOnly(Activity a, Uri uri, String mime) {
        ArrayList<Uri> one = new ArrayList<>();
        one.add(uri);
        if (!mime.startsWith("video/")) {
            send(a, one);
            return;
        }
        toast(a, "正在提取音轨…");
        new Thread(() -> {
            try {
                File m4a = Media.extractAudio(a, uri, baseName(a, uri));
                ArrayList<Uri> out = new ArrayList<>();
                out.add(Uri.fromFile(m4a));
                ShareActivity.copyAndPublish(a, out);
                toast(a, "已提取音频并发送");
            } catch (Exception e) {
                toast(a, "失败：" + msg(e));
            }
        }, "action-audio").start();
    }

    private static void compress(Activity a, Uri uri) {
        toast(a, "正在压缩到 720p（长视频需要几分钟）…");
        android.content.Intent i = new android.content.Intent(a, ClipService.class);
        i.putExtra("action", "compress");
        i.putExtra("uri", uri.toString());
        i.putExtra("name", baseName(a, uri));
        a.startForegroundService(i);
    }

    private static void transcribe(Activity a, Uri uri, String mime) {
        toast(a, "正在准备…");
        new Thread(() -> {
            try {
                String name = baseName(a, uri);
                File audio;
                String audioMime;
                if (mime.startsWith("video/")) {
                    audio = Media.extractAudio(a, uri, name);
                    audioMime = "audio/mp4";
                    toast(a, "音轨已提取，正在转写…");
                } else {
                    audio = copyToDerived(a, uri, name);
                    audioMime = mime;
                }
                String text = Transcription.run(a, audio, audioMime);
                Transcription.saveToDownloads(a, name + ".txt", text);
                String message = "转写完成：" + text.length() + " 字";
                if (Config.tSync(a)) {
                    Transcription.syncToMac(a, name + ".txt", text);
                    message += "，已同步到 Mac";
                }
                toast(a, message);
            } catch (Exception e) {
                toast(a, "转写失败：" + msg(e));
            }
        }, "action-transcribe").start();
    }

    private static File copyToDerived(Context ctx, Uri uri, String name) throws Exception {
        File dir = new File(ctx.getFilesDir(), "derived");
        dir.mkdirs();
        String ext = "";
        String mime = ctx.getContentResolver().getType(uri);
        if (mime != null && mime.contains("/")) ext = "." + mime.substring(mime.indexOf('/') + 1);
        File out = new File(dir, name + ext);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new Exception("无法读取音频");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private static String baseName(Context ctx, Uri uri) {
        String name = ClipService.displayName(ctx, uri);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void toast(Activity a, String s) {
        a.runOnUiThread(() -> Toast.makeText(a, s, Toast.LENGTH_SHORT).show());
    }
}
