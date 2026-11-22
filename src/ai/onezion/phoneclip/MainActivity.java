package ai.onezion.phoneclip;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    // Yellow Sheep palette: deep-space navy + coral / gold / teal.
    private static final int BG = 0xFF0D1B2A;
    private static final int CARD = 0xFF162236;
    private static final int BORDER = 0xFF1E3050;
    private static final int CORAL = 0xFFE07A5F;
    private static final int GOLD = 0xFFD4A843;
    private static final int TEAL = 0xFF3A8A8A;
    private static final int TEXT = 0xFFF0E6D8;
    private static final int MUTED = 0xFF8A9AB0;

    private static final int REQ_FILES = 21;
    private static final int REQ_PHOTOS = 22;

    private EditText serverField;
    private EditText tokenField;
    private TextView statusView;
    private TextView filesAccessView;
    private View statusDot;
    private TextView statusText;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
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

    // ---- UI ----------------------------------------------------------------

    private ScrollView buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xFF132238, BG, BG });
        root.setBackground(bg);

        root.addView(header());
        root.addView(connectionCard());
        root.addView(sendCard());
        root.addView(syncCard());
        root.addView(aboutCard());

        statusView = text("", 12, MUTED);
        statusView.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        row.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(14);
        row.addView(titles, tp);

        TextView title = text("OneZion Clip", 25, TEXT);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        title.getPaint().setShader(new LinearGradient(0, 0, 0, dp(30),
                GOLD, CORAL, Shader.TileMode.CLAMP));
        titles.addView(title);
        TextView sub = text("照片 → Mac 剪贴板", 12, MUTED);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0xFF1B2A42);
        chipBg.setCornerRadius(dp(20));
        chipBg.setStroke(dp(1), BORDER);
        chip.setBackground(chipBg);
        chip.setPadding(dp(10), dp(6), dp(12), dp(6));

        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(TEAL);
        statusDot.setBackground(dot);
        chip.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        statusText = text("已停止", 12, TEXT);
        statusText.setPadding(dp(7), 0, 0, 0);
        chip.addView(statusText);
        row.addView(chip);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private View connectionCard() {
        Card card = card(GOLD);
        card.content.addView(sectionTitle("连接"));

        card.content.addView(fieldLabel("服务器地址"));
        serverField = field("http://<mac-ip>:47411", Config.server(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.content.addView(serverField);

        card.content.addView(fieldLabel("Token"));
        tokenField = field("安装时打印的 token", Config.token(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.content.addView(tokenField);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.topMargin = dp(16);
        TextView start = pill("Start bridge", true, v -> startBridge());
        row.addView(start, half);
        TextView stop = pill("Stop", false, v -> stopBridge());
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.topMargin = dp(16);
        half2.leftMargin = dp(10);
        row.addView(stop, half2);
        card.content.addView(row);

        TextView battery = text("允许后台运行（忽略电池优化）", 13, MUTED);
        battery.setPadding(dp(4), dp(14), dp(4), dp(2));
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {}
        });
        card.content.addView(battery);
        return card.box;
    }

    private View sendCard() {
        Card card = card(CORAL);
        card.content.addView(sectionTitle("发送"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(pill("选择文件…", false, v -> pickFiles()), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(10);
        row.addView(pill("选择照片…", false, v -> pickPhotos()), half2);
        card.content.addView(row);

        TextView clip = pill("发送当前剪贴板", true,
                v -> startActivity(new Intent(this, SendClipActivity.class)));
        LinearLayout.LayoutParams clipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clipLp.topMargin = dp(12);
        card.content.addView(clip, clipLp);

        card.content.addView(fieldLabel("文字"));
        final EditText compose = field("粘贴或输入一段文字", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        compose.setSingleLine(false);
        compose.setMinLines(3);
        compose.setMaxLines(6);
        compose.setGravity(Gravity.TOP | Gravity.START);
        card.content.addView(compose);

        TextView send = pill("发送文字到 Mac", false, v -> sendComposedText(compose));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.topMargin = dp(12);
        card.content.addView(send, sendLp);
        return card.box;
    }

    private View syncCard() {
        Card card = card(TEAL);
        card.content.addView(sectionTitle("同步"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(text("全相册自动同步", 15, TEXT));
        TextView hint = text("包含截图、微信保存图；关闭则只同步相机文件夹", 12, MUTED);
        hint.setPadding(0, dp(4), dp(12), 0);
        labels.addView(hint);

        Switch sw = new Switch(this);
        sw.setChecked("all".equals(Config.scope(this)));
        sw.setThumbTintList(ColorStateList.valueOf(CORAL));
        sw.setTrackTintList(ColorStateList.valueOf(0xFF2A3A52));
        sw.setOnCheckedChangeListener((v, checked) -> {
            Config.setScope(this, checked ? "all" : "camera");
            Config.setLastPhotoTime(this, System.currentTimeMillis() / 1000L);
            statusView.setText(checked ? "范围：全部照片" : "范围：仅相机文件夹");
        });
        row.addView(sw);
        card.content.addView(row);
        return card.box;
    }

    @Override protected void onResume() {
        super.onResume();
        if (filesAccessView != null) filesAccessView.setText(filesAccessLabel());
    }

    private View aboutCard() {
        Card card = card(GOLD);
        card.content.addView(sectionTitle("关于"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text("版本 " + versionName(), 14, MUTED),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(pill("检查更新", false, v -> checkUpdates()));
        card.content.addView(row);

        filesAccessView = text(filesAccessLabel(), 13, MUTED);
        filesAccessView.setPadding(dp(4), dp(14), dp(4), dp(2));
        filesAccessView.setOnClickListener(v -> openAllFilesAccess());
        card.content.addView(filesAccessView);

        TextView settings = text("打开应用设置（自启动 / 后台权限）", 13, MUTED);
        settings.setPadding(dp(4), dp(14), dp(4), dp(2));
        settings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        });
        card.content.addView(settings);
        return card.box;
    }

    private String filesAccessLabel() {
        boolean ok = Build.VERSION.SDK_INT < 30
                || Environment.isExternalStorageManager();
        return ok ? "所有文件访问：已开启（Sidecar 可用）"
                  : "所有文件访问：未开启（点击开启，供 Mac 浏览手机文件）";
    }

    private void openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {}
            }
        }
    }

    // ---- design helpers ----------------------------------------------------

    private final class Card {
        LinearLayout box;
        LinearLayout content;
    }

    private Card card(int accent) {
        Card c = new Card();
        c.box = new LinearLayout(this);
        c.box.setOrientation(LinearLayout.HORIZONTAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), BORDER);
        c.box.setBackground(ripple(bg));

        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(accent);
        barBg.setCornerRadii(new float[] { dp(22), dp(22), 0, 0, 0, 0, dp(22), dp(22) });
        bar.setBackground(barBg);
        c.box.addView(bar, new LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT));

        c.content = new LinearLayout(this);
        c.content.setOrientation(LinearLayout.VERTICAL);
        c.content.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.box.addView(c.content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.box.setLayoutParams(lp);
        return c;
    }

    private TextView sectionTitle(String label) {
        TextView t = text(label, 11, MUTED);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView fieldLabel(String label) {
        TextView t = text(label, 11, MUTED);
        t.setLetterSpacing(0.05f);
        t.setPadding(dp(4), dp(12), 0, dp(6));
        return t;
    }

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(inputType);
        e.setTextSize(14);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF101C2E);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), BORDER);
        e.setBackground(bg);
        return e;
    }

    private TextView pill(String label, boolean primary, View.OnClickListener onClick) {
        TextView v = text(label, 14, primary ? 0xFF1B120C : TEXT);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(26));
        if (primary) {
            bg.setColor(CORAL);
        } else {
            bg.setColor(0xFF1B2A42);
            bg.setStroke(dp(1), BORDER);
        }
        v.setBackground(ripple(bg));
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(onClick);
        return v;
    }

    private RippleDrawable ripple(GradientDrawable bg) {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null);
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- actions -----------------------------------------------------------

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FILES);
    }

    private void pickPhotos() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent("android.provider.action.PICK_IMAGES");
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(i, REQ_PHOTOS);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        toast("正在复制…");
        new Thread(() -> {
            try {
                ShareActivity.copyAndPublish(this, uris);
                runOnUiThread(() -> toast(uris.size() == 1
                        ? "已复制到手机 + Mac" : "已复制 " + uris.size() + " 个文件"));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> toast("失败: " + msg));
            }
        }, "pick").start();
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
        refresh();
        statusView.setText("已启动：照片自动同步；剪贴板用磁贴发送。");
    }

    private void stopBridge() {
        Config.setEnabled(this, false);
        ClipService.stop(this);
        refresh();
        statusView.setText("已停止。");
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
        if (statusText != null) statusText.setText(running ? "运行中" : "已停止");
        if (statusDot != null) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(running ? TEAL : 0xFF5A6577);
            statusDot.setBackground(dot);
        }
        if (statusView != null && statusView.getText().length() == 0) {
            statusView.setText("照片自动同步；文字/文件用上方按钮或「发送剪贴板」磁贴。");
        }
    }

    private void sendComposedText(EditText field) {
        String text = field.getText().toString();
        if (text.isEmpty()) {
            toast("Nothing to send");
            return;
        }
        new Thread(() -> {
            try {
                Http.post(Config.server(this) + "/v1/clip?kind=text", Config.token(this),
                        "text/plain; charset=utf-8",
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    field.setText("");
                    statusView.setText("文字已发送（" + text.length() + " 字）");
                });
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName();
                runOnUiThread(() -> statusView.setText("发送失败：" + msg));
            }
        }, "send-text").start();
    }

    private void checkUpdates() {
        statusView.setText("正在检查更新…");
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
                    msg = "已是最新版本（" + Update.installed(this) + "）";
                }
            } catch (Exception e) {
                msg = "检查失败：" + e.getClass().getSimpleName();
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
