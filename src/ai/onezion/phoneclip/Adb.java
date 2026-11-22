package ai.onezion.phoneclip;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Root-only helper: persist TCP adb so the Mac can reconnect without USB. */
final class Adb {
    static boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            String out = read(p);
            p.waitFor();
            return out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    static String tcpPort() {
        try {
            Process p = new ProcessBuilder("getprop", "service.adb.tcp.port")
                    .redirectErrorStream(true).start();
            String out = read(p).trim();
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    /** Sets persist.adb.tcp.port and restarts adbd; returns a user-facing message. */
    static String enableTcpip() {
        try {
            Process p = new ProcessBuilder("su", "-c",
                    "setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; "
                    + "stop adbd; start adbd; getprop service.adb.tcp.port")
                    .redirectErrorStream(true).start();
            String out = read(p).trim();
            int code = p.waitFor();
            return code == 0 && out.endsWith("5555") ? "无线 ADB 已开启（5555）" : "开启失败：" + out;
        } catch (Exception e) {
            return "开启失败：" + e.getClass().getSimpleName();
        }
    }

    private static String read(Process p) throws Exception {
        try (InputStream in = p.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }
}
