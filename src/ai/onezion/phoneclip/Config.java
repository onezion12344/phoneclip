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

    static long lastRev(Context c) { return prefs(c).getLong("lastRev", -1); }
    static void setLastRev(Context c, long rev) { prefs(c).edit().putLong("lastRev", rev).apply(); }
    static String lastServer(Context c) { return prefs(c).getString("lastServer", ""); }
    static void setLastServer(Context c, String s) { prefs(c).edit().putString("lastServer", s).apply(); }

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

    // ---- photos: daily VLM observation --------------------------------------
    static boolean vlmOn(Context c) { return prefs(c).getBoolean("vlmOn", false); }
    static String vlmBase(Context c) { return clean(prefs(c).getString("vlmBase", "")); }
    static String vlmKey(Context c) { return clean(prefs(c).getString("vlmKey", "")); }
    static String vlmModel(Context c) {
        String m = clean(prefs(c).getString("vlmModel", ""));
        return m.isEmpty() ? "gpt-4o-mini" : m;
    }
    static int vlmHour(Context c) { return prefs(c).getInt("vlmHour", 22); }
    static long lastVlmRun(Context c) { return prefs(c).getLong("lastVlmRun", 0); }
    static void setLastVlmRun(Context c, long epochSec) {
        prefs(c).edit().putLong("lastVlmRun", epochSec).apply();
    }
    static void saveVlm(Context c, boolean on, String base, String key, String model, int hour) {
        String b = clean(base);
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        prefs(c).edit().putBoolean("vlmOn", on).putString("vlmBase", b)
                .putString("vlmKey", clean(key)).putString("vlmModel", clean(model))
                .putInt("vlmHour", hour).apply();
    }

    // ---- recorder: daily sync -----------------------------------------------
    static boolean recOn(Context c) { return prefs(c).getBoolean("recOn", false); }
    /** media = 压缩后发文件；transcript = 只发转写文本 */
    static String recMode(Context c) { return prefs(c).getString("recMode", "media"); }
    static int recHour(Context c) { return prefs(c).getInt("recHour", 23); }
    static long lastRecRun(Context c) { return prefs(c).getLong("lastRecRun", 0); }
    static void setLastRecRun(Context c, long epochSec) {
        prefs(c).edit().putLong("lastRecRun", epochSec).apply();
    }
    static void saveRecorder(Context c, boolean on, String mode, int hour) {
        prefs(c).edit().putBoolean("recOn", on).putString("recMode", mode)
                .putInt("recHour", hour).apply();
    }
    static String lastRecName(Context c) { return prefs(c).getString("lastRecName", ""); }
    static void setLastRecName(Context c, String name) {
        prefs(c).edit().putString("lastRecName", name).apply();
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
