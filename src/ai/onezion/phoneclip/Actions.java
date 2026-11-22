package ai.onezion.phoneclip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** Shared action chooser used by the share sheet and the in-app pickers. */
final class Actions {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void choose(Activity a, ArrayList<Uri> uris, Runnable onDone) {
        if (uris.isEmpty()) {
            onDone.run();
            return;
        }
        final Context app = a.getApplicationContext();
        String mime = a.getContentResolver().getType(uris.get(0));
        boolean media = mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"));
        if (!media) {
            send(app, uris, onDone);
            return;
        }

        String transcribe = Config.tSync(app) ? "转写（并同步文本到 Mac）" : "转写";
        ArrayList<String> items = new ArrayList<>();
        items.add("发送到 Mac");
        boolean video = mime.startsWith("video/");
        if (video) {
            items.add("只保留音频");
            items.add("压缩到 720p 并发送");
        } else {
            items.add("压缩音频并发送");
        }
        items.add(transcribe);

        final boolean[] acted = {false};
        AlertDialog dialog = new AlertDialog.Builder(
                a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("要做什么？")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    acted[0] = true;
                    String label = items.get(which);
                    if (label.equals("发送到 Mac")) send(app, uris, onDone);
                    else if (label.equals("只保留音频")) audioOnly(app, uris.get(0), mime, onDone);
                    else if (label.startsWith("压缩到")) compress(app, uris.get(0), onDone);
                    else if (label.startsWith("压缩音频")) compressAudio(app, uris.get(0), onDone);
                    else transcribe(app, uris.get(0), mime, onDone);
                })
                .create();
        dialog.setOnDismissListener(d -> {
            if (!acted[0]) onDone.run();
        });
        dialog.show();
    }

    private static void send(Context c, ArrayList<Uri> uris, Runnable done) {
        toast(c, "正在复制…");
        new Thread(() -> {
            try {
                ShareActivity.copyAndPublish(c, uris);
                toast(c, uris.size() == 1 ? "已复制到手机 + Mac" : "已复制 " + uris.size() + " 个文件");
            } catch (Exception e) {
                toast(c, "失败：" + msg(e));
            } finally {
                done.run();
            }
        }, "action-send").start();
    }

    private static void audioOnly(Context c, Uri uri, String mime, Runnable done) {
        ArrayList<Uri> one = new ArrayList<>();
        one.add(uri);
        if (!mime.startsWith("video/")) {
            send(c, one, done);
            return;
        }
        toast(c, "正在提取音轨…");
        new Thread(() -> {
            try {
                File m4a = Media.extractAudio(c, uri, baseName(c, uri));
                ArrayList<Uri> out = new ArrayList<>();
                out.add(Uri.fromFile(m4a));
                ShareActivity.copyAndPublish(c, out);
                toast(c, "已提取音频并发送");
            } catch (Exception e) {
                toast(c, "失败：" + msg(e));
            } finally {
                done.run();
            }
        }, "action-audio").start();
    }

    private static void compress(Context c, Uri uri, Runnable done) {
        toast(c, "正在准备…");
        dispatchCompress(c, uri, "compress", "正在压缩到 720p（长视频需要几分钟）…", done);
    }

    private static void compressAudio(Context c, Uri uri, Runnable done) {
        toast(c, "正在准备…");
        dispatchCompress(c, uri, "compressaudio", "正在压缩音频（AAC 96k）…", done);
    }

    /** Copy the picked file into app storage first: the picker grant dies with the Activity. */
    private static void dispatchCompress(Context c, Uri uri, String action,
                                         String notice, Runnable done) {
        new Thread(() -> {
            try {
                String name = baseName(c, uri);
                File src = copyToDerived(c, uri, name);
                Intent i = new Intent(c, ClipService.class);
                i.putExtra("action", action);
                i.putExtra("uri", Uri.fromFile(src).toString());
                i.putExtra("name", name);
                ShareActivity.startServiceSafely(c, i);
                toast(c, notice);
            } catch (Exception e) {
                toast(c, "失败：" + msg(e));
            } finally {
                done.run();
            }
        }, "action-compress").start();
    }

    private static void transcribe(Context c, Uri uri, String mime, Runnable done) {
        toast(c, "正在准备…");
        new Thread(() -> {
            try {
                String name = baseName(c, uri);
                File audio;
                String audioMime;
                if (mime.startsWith("video/")) {
                    audio = Media.extractAudio(c, uri, name);
                    audioMime = "audio/mp4";
                    toast(c, "音轨已提取，正在转写…");
                } else {
                    audio = copyToDerived(c, uri, name);
                    audioMime = mime;
                }
                String text = Transcription.run(c, audio, audioMime);
                Transcription.saveToDownloads(c, name + ".txt", text);
                String message = "转写完成：" + text.length() + " 字";
                if (Config.tSync(c)) {
                    Transcription.syncToMac(c, name + ".txt", text);
                    message += "，已同步到 Mac";
                }
                toast(c, message);
            } catch (Exception e) {
                toast(c, "转写失败：" + msg(e));
            } finally {
                done.run();
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

    private static void toast(Context c, String s) {
        Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        MAIN.post(() -> Toast.makeText(app, s, Toast.LENGTH_SHORT).show());
    }
}
