package ai.onezion.phoneclip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Http {
    private static final int TIMEOUT_MS = 15000;

    static String getTextOrNull(String url, String token) throws IOException {
        return getTextOrNull(url, token, TIMEOUT_MS);
    }

    static String getTextOrNull(String url, String token, int readTimeoutMs) throws IOException {
        HttpURLConnection c = open(url, token, "GET", readTimeoutMs);
        try {
            int code = c.getResponseCode();
            if (code == 204) return null;
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = new String(readAll(in), StandardCharsets.UTF_8);
            if (code >= 400) throw new IOException("HTTP " + code + ": " + body);
            return body;
        } finally {
            c.disconnect();
        }
    }

    static byte[] getBytes(String url, String token) throws IOException {
        HttpURLConnection c = open(url, token, "GET");
        try {
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            byte[] body = readAll(in);
            if (code >= 400) throw new IOException("HTTP " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    static void post(String url, String token, String contentType, byte[] body) throws IOException {
        HttpURLConnection c = open(url, token, "POST");
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": "
                        + new String(readAll(c.getErrorStream()), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String token, String method) throws IOException {
        return open(url, token, method, TIMEOUT_MS);
    }

    private static HttpURLConnection open(String url, String token, String method,
                                          int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(readTimeoutMs);
        c.setUseCaches(false);
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("X-PhoneClip-Token", token);
        }
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
