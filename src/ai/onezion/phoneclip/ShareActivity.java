package ai.onezion.phoneclip;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Share-sheet entry: send a picture to the Mac without waiting for the camera watcher. */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            finish();
            return;
        }
        String server = Config.server(this);
        if (server.isEmpty()) {
            toast("Set up the bridge first");
            finish();
            return;
        }
        toast("Sending to Mac…");
        new Thread(() -> {
            try {
                byte[] jpeg = Media.jpegFromUri(this, uri, 2560, 90);
                String name = URLEncoder.encode("share.jpg", StandardCharsets.UTF_8.name());
                Http.post(server + "/v1/photo?name=" + name,
                        Config.token(this), "image/jpeg", jpeg);
                runOnUiThread(() -> toast("Sent to Mac"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Failed: " + e.getClass().getSimpleName()));
            } finally {
                runOnUiThread(this::finish);
            }
        }, "share-upload").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
