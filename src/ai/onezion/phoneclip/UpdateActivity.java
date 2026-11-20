package ai.onezion.phoneclip;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;

/** Downloads the latest GitHub release APK and hands it to the system installer. */
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
            Uri uri = Uri.parse("content://" + ClipProvider.AUTHORITY + "/update/"
                    + Update.ASSET);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开安装器: " + e.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }

    private void done(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
