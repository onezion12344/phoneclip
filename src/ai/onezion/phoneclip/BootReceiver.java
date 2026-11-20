package ai.onezion.phoneclip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Config.enabled(context)) return;
        if (Config.server(context).isEmpty()) return;
        ClipService.start(context);
    }
}
