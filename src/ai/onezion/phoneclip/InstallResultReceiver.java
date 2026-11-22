package ai.onezion.phoneclip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

public class InstallResultReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "update";

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            // installing an update kills the bridge process; offer a one-tap restart
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "更新完成，请打开 PhoneClip 恢复同步", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                NotificationManager nm = context.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
                PendingIntent open = PendingIntent.getActivity(context, 3,
                        new Intent(context, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                nm.notify(49, new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("PhoneClip 已更新")
                        .setContentText("点击打开，恢复照片与剪贴板同步")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build());
            } catch (Exception e) {
                Toast.makeText(context, "更新完成，请打开 PhoneClip 恢复同步", Toast.LENGTH_LONG).show();
            }
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "更新失败: " + (msg == null ? String.valueOf(status) : msg),
                    Toast.LENGTH_LONG).show();
        }
    }
}
