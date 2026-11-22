package ai.onezion.phoneclip;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Transparent one-shot activity: Android 13+ only allows clipboard reads in the
 * foreground, so phone → Mac clipboard needs this quick hop.
 */
public class SendClipActivity extends Activity {
    private boolean sent;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (sent) return;
        sent = true;
        new Thread(() -> {
            String msg;
            try {
                msg = ClipService.sendClipboardNow(this);
            } catch (Exception e) {
                msg = "发送失败: " + e.getClass().getSimpleName();
            }
            String finalMsg = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, finalMsg, Toast.LENGTH_SHORT).show();
                finish();
            });
        }, "send-clip").start();
    }
}
