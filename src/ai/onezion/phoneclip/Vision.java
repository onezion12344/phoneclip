package ai.onezion.phoneclip;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** OpenAI-compatible vision call used for the daily photo observation. */
final class Vision {
    static String observe(Context ctx, byte[] jpeg, String meta) throws Exception {
        String base = Config.vlmBase(ctx);
        if (base.isEmpty()) throw new Exception("未配置 VLM 端点");
        if (jpeg == null || jpeg.length == 0) return "";

        String dataUri = "data:image/jpeg;base64,"
                + Base64.encodeToString(jpeg, Base64.NO_WRAP);
        JSONObject image = new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject().put("url", dataUri));
        JSONObject prompt = new JSONObject().put("type", "text").put("text",
                "用中文写 2-4 句 observation：画面主体、场景、可读文字、以及值得记下的信息。"
                        + "不要寒暄，不要编造看不清的细节。\n元数据：" + meta);
        JSONArray content = new JSONArray().put(prompt).put(image);
        JSONObject body = new JSONObject()
                .put("model", Config.vlmModel(ctx))
                .put("max_tokens", 500)
                .put("messages", new JSONArray().put(
                        new JSONObject().put("role", "user").put("content", content)));

        HttpURLConnection c = (HttpURLConnection)
                new URL(base + "/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "application/json");
        String key = Config.vlmKey(ctx);
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(payload);
        }
        int code = c.getResponseCode();
        String text = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) throw new Exception("VLM HTTP " + code + ": " + text);
        JSONObject o = new JSONObject(text);
        return o.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "").trim();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
