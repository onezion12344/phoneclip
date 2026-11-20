package ai.onezion.phoneclip;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText serverField;
    private EditText tokenField;
    private TextView statusView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        applyIntent(getIntent());
        refresh();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
        refresh();
    }

    private void applyIntent(Intent intent) {
        String server = intent.getStringExtra("server");
        String token = intent.getStringExtra("token");
        if (server != null) {
            Config.save(this, server, token);
            serverField.setText(Config.server(this));
            tokenField.setText(Config.token(this));
        }
        if (intent.getBooleanExtra("start", false)) startBridge();
        if (intent.getBooleanExtra("stop", false)) stopBridge();
        if (intent.getBooleanExtra("newest", false)) serviceAction("newest");
        if (intent.getBooleanExtra("test", false)) serviceAction("test");
        if (intent.getBooleanExtra("push", false)) serviceAction("push");
        String copy = intent.getStringExtra("copy");
        if (copy != null) {
            Intent i = new Intent(this, ClipService.class);
            i.putExtra("action", "copy").putExtra("text", copy);
            startForegroundService(i);
        }
    }

    private ScrollView buildUi() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(16, 18, 24));

        TextView title = new TextView(this);
        title.setText("OneZion Clip");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Photos go to the Mac clipboard automatically. Text/images: use the “发送剪贴板” Quick Settings tile.");
        sub.setTextColor(Color.rgb(160, 168, 184));
        sub.setPadding(0, pad / 4, 0, pad);
        root.addView(sub);

        serverField = new EditText(this);
        serverField.setHint("http://<mac-ip>:47411");
        serverField.setSingleLine(true);
        serverField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverField.setText(Config.server(this));
        root.addView(serverField);

        tokenField = new EditText(this);
        tokenField.setHint("token");
        tokenField.setSingleLine(true);
        tokenField.setText(Config.token(this));
        root.addView(tokenField);

        root.addView(button("Start bridge", v -> startBridge()));
        root.addView(button("Stop bridge", v -> stopBridge()));
        root.addView(button("Ignore battery optimisation", v -> {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            try { startActivity(i); } catch (Exception ignored) {}
        }));
        root.addView(button("Send newest camera photo", v -> serviceAction("newest")));
        root.addView(button("Send test image", v -> serviceAction("test")));
        root.addView(button("Send current clipboard to Mac", v ->
                startActivity(new Intent(this, SendClipActivity.class))));
        root.addView(button("Check for updates", v -> checkUpdates()));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(120, 220, 180));
        statusView.setPadding(0, pad, 0, 0);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Button button(String label, android.view.View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(onClick);
        return b;
    }

    private void startBridge() {
        Config.save(this, serverField.getText().toString(), tokenField.getText().toString());
        if (Config.server(this).isEmpty()) {
            toast("Server URL is empty");
            return;
        }
        Config.setEnabled(this, true);
        requestPermissions();
        ClipService.start(this);
        statusView.setText("Started. Watching camera + clipboard.");
    }

    private void stopBridge() {
        Config.setEnabled(this, false);
        ClipService.stop(this);
        statusView.setText("Stopped.");
    }

    private void serviceAction(String action) {
        Config.save(this, serverField.getText().toString(), tokenField.getText().toString());
        Intent i = new Intent(this, ClipService.class);
        i.putExtra("action", action);
        startForegroundService(i);
        statusView.setText("Sent: " + action);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.POST_NOTIFICATIONS,
            }, 1);
        }
    }

    private void refresh() {
        boolean running = Config.enabled(this);
        statusView.setText((running ? "Running" : "Stopped")
                + " · photos auto-sync; clipboard via the “发送剪贴板” tile");
    }

    private void checkUpdates() {
        statusView.setText("Checking for updates…");
        new Thread(() -> {
            String msg;
            boolean open = false;
            try {
                Update.Info info = Update.latest();
                if (info == null) {
                    msg = "No release found";
                } else if (Update.isNewer(info.version, Update.installed(this))) {
                    msg = "Update " + info.version + " available";
                    open = true;
                } else {
                    msg = "Up to date (" + Update.installed(this) + ")";
                }
            } catch (Exception e) {
                msg = "Update check failed: " + e.getClass().getSimpleName();
            }
            String finalMsg = msg;
            boolean finalOpen = open;
            runOnUiThread(() -> {
                statusView.setText(finalMsg);
                if (finalOpen) startActivity(new Intent(this, UpdateActivity.class));
            });
        }, "update-check").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        for (int i = 0; i < results.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) {
                toast("Permission denied: " + perms[i]);
            }
        }
    }
}
