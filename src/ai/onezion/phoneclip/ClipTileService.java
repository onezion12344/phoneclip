package ai.onezion.phoneclip;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap sends the current clipboard to the Mac. */
public class ClipTileService extends TileService {
    @Override public void onClick() {
        Intent intent = new Intent(this, SendClipActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            try {
                startActivityAndCollapse(intent);
            } catch (Exception ignored) {
            }
        }
    }
}
