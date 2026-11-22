package ai.onezion.phoneclip;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** OpenAI-compatible /v1/audio/transcriptions client, run on the phone. */
final class Transcription {
    static String run(Context ctx, File audio, String mime) throws Exception {
        String base = Config.tBase(ctx);
        if (base.isEmpty()) throw new Exception("未配置转写端点");
        String boundary = "----PhoneClip" + System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/audio/transcriptions").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setChunkedStreamingMode(1 << 16);
        c.setConnectTimeout(15000);
        c.setReadTimeout(600000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        String key = Config.tKey(ctx);
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);

        try (OutputStream out = c.getOutputStream()) {
            field(out, boundary, "model", Config.tModel(ctx));
            field(out, boundary, "response_format", "json");
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + audio.getName() + "\"\r\n"
                    + "Content-Type: " + (mime == null ? "application/octet-stream" : mime)
                    + "\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new FileInputStream(audio)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
        return new JSONObject(body).optString("text", "");
    }

    static Uri saveToDownloads(Context ctx, String name, String text) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/PhoneClip/Transcripts");
        Uri uri = ctx.getContentResolver()
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("无法写入 Downloads");
        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("无法写入文件");
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }

    /** Optional: copy the transcript into the Mac's Documents/PhoneClip/Transcripts. */
    static void syncToMac(Context ctx, String name, String text) throws Exception {
        String url = Config.server(ctx) + "/v1/file?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8.name())
                + "&silent=1&dir=transcripts";
        Http.post(url, Config.token(ctx), "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static void field(OutputStream out, String boundary, String name, String value)
            throws Exception {
        String s = "--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n";
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream src = in; java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
