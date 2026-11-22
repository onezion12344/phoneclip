package ai.onezion.phoneclip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Token-gated command entry point so the Mac (or adb) can drive the service
 * without bringing an Activity to the foreground.
 */
public class CmdReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String expected = Config.token(context);
        String token = intent.getStringExtra("token");
        if (expected.isEmpty() || token == null || !token.equals(expected)) {
            return;
        }
        Intent svc = new Intent(context, ClipService.class);
        svc.putExtra("action", intent.getStringExtra("action"));
        svc.putExtra("text", intent.getStringExtra("text"));
        svc.putExtra("path", intent.getStringExtra("path"));
        ShareActivity.startServiceSafely(context, svc);
    }
}
