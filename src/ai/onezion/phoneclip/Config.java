package ai.onezion.phoneclip;

import android.content.Context;
import android.content.SharedPreferences;

final class Config {
    private static final String NAME = "phoneclip";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String server(Context c) {
        return clean(prefs(c).getString("server", ""));
    }

    static String token(Context c) {
        return clean(prefs(c).getString("token", ""));
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    static void save(Context c, String server, String token) {
        String s = clean(server);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        prefs(c).edit().putString("server", s).putString("token", clean(token)).apply();
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
    }

    static long lastPhotoTime(Context c) {
        return prefs(c).getLong("lastPhoto", 0);
    }

    static void setLastPhotoTime(Context c, long seconds) {
        prefs(c).edit().putLong("lastPhoto", seconds).apply();
    }

    static String scope(Context c) {
        return prefs(c).getString("scope", "all");
    }

    static void setScope(Context c, String scope) {
        prefs(c).edit().putString("scope", scope).apply();
    }

    static String tBase(Context c) { return clean(prefs(c).getString("tBase", "")); }
    static String tKey(Context c) { return clean(prefs(c).getString("tKey", "")); }
    static String tModel(Context c) {
        String m = clean(prefs(c).getString("tModel", ""));
        return m.isEmpty() ? "whisper-1" : m;
    }
    static boolean tSync(Context c) { return prefs(c).getBoolean("tSync", false); }

    static void saveTranscribe(Context c, String base, String key, String model, boolean sync) {
        String b = clean(base);
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        prefs(c).edit()
                .putString("tBase", b)
                .putString("tKey", clean(key))
                .putString("tModel", clean(model))
                .putBoolean("tSync", sync)
                .apply();
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
