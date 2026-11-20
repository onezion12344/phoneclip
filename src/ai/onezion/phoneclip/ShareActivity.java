package ai.onezion.phoneclip;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;

/** Share-sheet entry: images go to the clipboard as pictures, other files as files. */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        }
        if (uris.isEmpty()) {
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
                send(uris);
                runOnUiThread(() -> toast(uris.size() == 1 ? "Sent to Mac" : "Sent " + uris.size() + " files"));
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName();
                runOnUiThread(() -> toast("Failed: " + msg));
            } finally {
                runOnUiThread(this::finish);
            }
        }, "share-upload").start();
    }

    private void send(ArrayList<Uri> uris) throws Exception {
        if (uris.size() == 1 && ClipService.isImageUri(this, uris.get(0))) {
            byte[] jpeg = Media.jpegFromUri(this, uris.get(0), 2560, 90);
            Http.post(Config.server(this) + "/v1/photo?name=share.jpg",
                    Config.token(this), "image/jpeg", jpeg);
            return;
        }
        String batch = "b" + System.currentTimeMillis();
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            String name = ClipService.displayName(this, uri);
            ClipService.uploadUri(this, uri, name, batch, i, uris.size());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
