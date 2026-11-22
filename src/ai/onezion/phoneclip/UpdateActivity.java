package ai.onezion.phoneclip;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/** Downloads the latest GitHub release APK and installs it via PackageInstaller. */
public class UpdateActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Toast.makeText(this, "正在获取更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Update.Info info = Update.latest();
                if (info == null) {
                    done("没有可用的发布版本");
                    return;
                }
                String installed = Update.installed(this);
                if (!Update.isNewer(info.version, installed)) {
                    done("已是最新版本 " + installed);
                    return;
                }
                File apk = Update.download(this, info);
                install(apk);
            } catch (Exception e) {
                done("更新失败: " + e.getClass().getSimpleName());
            }
        }, "update").start();
    }

    private void install(File apk) {
        runOnUiThread(() -> {
            if (!apk.isFile() || apk.length() == 0) {
                done("下载失败");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "请先允许 OneZion Clip 安装应用", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
                finish();
                return;
            }
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                int sessionId = installer.createSession(params);
                PackageInstaller.Session session = installer.openSession(sessionId);
                try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                     FileInputStream in = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent callback = new Intent(this, InstallResultReceiver.class);
                PendingIntent pending = PendingIntent.getBroadcast(this, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
                session.close();
                finish();
            } catch (Exception e) {
                done("安装失败: " + e.getClass().getSimpleName());
            }
        });
    }

    private void done(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
