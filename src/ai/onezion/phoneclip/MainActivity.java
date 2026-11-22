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
import android.widget.FrameLayout;
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
    private boolean photoWarning;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        applyIntent(getIntent());
        if (Config.enabled(this) && !ClipService.isRunning(this)) ClipService.start(this);
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
        String tBase = intent.getStringExtra("tbase");
        if (tBase != null) {
            // a shell-dropped empty argument can leave "--es" here; treat anything that
            // is neither empty nor an http(s) URL as invalid instead of storing it
            String v = tBase.trim();
            if (v.startsWith("--")) v = "";
            if (v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")) {
                Config.saveTranscribe(this, v, intent.getStringExtra("tkey"),
                        intent.getStringExtra("tmodel"), intent.getBooleanExtra("tsync", false));
            }
        }
        String vlmBase = intent.getStringExtra("vlmbase");
        if (vlmBase != null) {
            String v = vlmBase.trim();
            if (v.startsWith("--")) v = "";
            if (v.isEmpty() || v.startsWith("http")) {
                Config.saveVlm(this, intent.getBooleanExtra("vlmon", true), v,
                        intent.getStringExtra("vlmkey"),
                        intent.getStringExtra("vlmmodel"), Config.vlmHour(this));
            }
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
            ShareActivity.startServiceSafely(this, i);
        }
    }

    // ---- UI ----------------------------------------------------------------

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);
        // API keys are not logins: keep password managers from offering to save them
        outer.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        outer.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(10), dp(20), 0);
        head.addView(header());
        outer.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabContent = new FrameLayout(this);
        outer.addView(tabContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        outer.addView(bottomBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        showTab(0);
        return outer;
    }

    private FrameLayout tabContent;
    private final java.util.Map<Integer, View> tabViews = new java.util.HashMap<>();
    private final java.util.List<TextView> tabButtons = new java.util.ArrayList<>();
    private static final String[] TAB_NAMES = { "连接", "照片", "录音", "文件", "设置" };

    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF101C2C);
        bg.setStroke(dp(1), BORDER);
        bar.setBackground(bg);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = text(TAB_NAMES[i], 13, MUTED);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(18), 0, 0);
            t.setOnClickListener(v -> showTab(idx));
            bar.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            tabButtons.add(t);
        }
        return bar;
    }

    private void showTab(int i) {
        for (java.util.Map.Entry<Integer, View> e : tabViews.entrySet()) {
            e.getValue().setVisibility(e.getKey() == i ? View.VISIBLE : View.GONE);
        }
        View v = tabViews.get(i);
        if (v == null) {
            v = tabView(i);
            tabViews.put(i, v);
            tabContent.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        v.setVisibility(View.VISIBLE);
        for (int k = 0; k < tabButtons.size(); k++) {
            tabButtons.get(k).setTextColor(k == i ? GOLD : MUTED);
        }
        refresh();
    }

    private View tabView(int i) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(6), dp(20), dp(24));
        switch (i) {
            case 0:
                col.addView(connectionCard());
                statusView = text("", 12, MUTED);
                statusView.setPadding(dp(4), dp(18), dp(4), 0);
                col.addView(statusView);
                break;
            case 1:
                col.addView(syncCard());
                col.addView(photosCard());
                break;
            case 2:
                col.addView(recorderCard());
                break;
            case 3:
                col.addView(filesCard());
                break;
            default:
                col.addView(sendCard());
                col.addView(aboutCard());
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View photosCard() {
        Card card = card(GOLD);
        card.content.addView(sectionTitle("照片 · 每日 observation"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("用 VLM 识别当天照片", 15, TEXT));
        TextView hint = text("生成纯文本 observation，发送到 Mac", 12, MUTED);
        hint.setPadding(0, dp(4), dp(12), 0);
        labels.addView(hint);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch on = new Switch(this);
        on.setChecked(Config.vlmOn(this));
        on.setThumbTintList(ColorStateList.valueOf(CORAL));
        on.setTrackTintList(ColorStateList.valueOf(0xFF2A3A52));
        row.addView(on);
        card.content.addView(row);

        card.content.addView(fieldLabel("VLM Base URL（OpenAI 兼容）"));
        final EditText base = field("https://api.openai.com/v1", Config.vlmBase(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.content.addView(base);

        card.content.addView(fieldLabel("API Key"));
        final EditText key = secretField("sk-…", Config.vlmKey(this));
        card.content.addView(key);

        card.content.addView(fieldLabel("Model"));
        final EditText model = field("gpt-4o-mini", Config.vlmModel(this),
                InputType.TYPE_CLASS_TEXT);
        card.content.addView(model);

        card.content.addView(fieldLabel("每天几点运行（0-23）"));
        final EditText hour = field("22", String.valueOf(Config.vlmHour(this)),
                InputType.TYPE_CLASS_NUMBER);
        card.content.addView(hour);

        TextView run = pill("立即识别一张", false, v -> runJob("vlm"));
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        runLp.topMargin = dp(12);
        card.content.addView(run, runLp);

        TextView save = pill("保存照片设置", false, v -> {
            Config.saveVlm(this, on.isChecked(), base.getText().toString(),
                    key.getText().toString(), model.getText().toString(),
                    parseInt(hour.getText().toString(), 22));
            toast("照片设置已保存");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.content.addView(save, lp);
        return card.box;
    }

    private View recorderCard() {
        Card card = card(CORAL);
        card.content.addView(sectionTitle("录音 · 每日同步"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("每天自动同步新录音", 15, TEXT));
        TextView hint = text("按下面的模式处理当天新增的录音", 12, MUTED);
        hint.setPadding(0, dp(4), 0, 0);
        labels.addView(hint);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch on = new Switch(this);
        on.setChecked(Config.recOn(this));
        on.setThumbTintList(ColorStateList.valueOf(CORAL));
        on.setTrackTintList(ColorStateList.valueOf(0xFF2A3A52));
        row.addView(on);
        card.content.addView(row);

        card.content.addView(fieldLabel("模式"));
        final String[] mode = { Config.recMode(this) };
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        TextView media = pill("压缩后发送", "media".equals(mode[0]), v -> {
            mode[0] = "media";
            refreshPills();
        });
        TextView textOnly = pill("只发转写文本", "transcript".equals(mode[0]), v -> {
            mode[0] = "transcript";
            refreshPills();
        });
        modes.addView(media);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dp(10);
        modes.addView(textOnly, mlp);
        pillReset = () -> {
            stylePill(media, "media".equals(mode[0]));
            stylePill(textOnly, "transcript".equals(mode[0]));
        };
        card.content.addView(modes);

        card.content.addView(fieldLabel("每天几点运行（0-23）"));
        final EditText hour = field("23", String.valueOf(Config.recHour(this)),
                InputType.TYPE_CLASS_NUMBER);
        card.content.addView(hour);

        card.content.addView(fieldLabel("转写 Base URL（OpenAI 兼容）"));
        final EditText base = field("https://api.openai.com/v1", Config.tBase(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.content.addView(base);
        card.content.addView(fieldLabel("转写 API Key"));
        final EditText key = secretField("sk-…", Config.tKey(this));
        card.content.addView(key);
        card.content.addView(fieldLabel("转写 Model"));
        final EditText model = field("whisper-1", Config.tModel(this), InputType.TYPE_CLASS_TEXT);
        card.content.addView(model);

        TextView run = pill("立即同步一次", false, v -> runJob("rec"));
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        runLp.topMargin = dp(12);
        card.content.addView(run, runLp);

        TextView save = pill("保存录音设置", false, v -> {
            Config.saveRecorder(this, on.isChecked(), mode[0],
                    parseInt(hour.getText().toString(), 23));
            Config.saveTranscribe(this, base.getText().toString(), key.getText().toString(),
                    model.getText().toString(), true);
            toast("录音设置已保存");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.content.addView(save, lp);
        return card.box;
    }

    private Runnable pillReset;
    private void refreshPills() { if (pillReset != null) pillReset.run(); }
    private void stylePill(TextView p, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(on ? CORAL : 0xFF1B2A42);
        g.setStroke(dp(1), on ? CORAL : BORDER);
        p.setBackground(g);
        p.setTextColor(on ? 0xFF101820 : TEXT);
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private java.io.File fileDir = new java.io.File("/sdcard");
    private LinearLayout fileList;
    private TextView filePath;

    private View filesCard() {
        Card card = card(TEAL);
        card.content.addView(sectionTitle("文件"));
        filePath = text(fileDir.getAbsolutePath(), 12, MUTED);
        card.content.addView(filePath);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(pill("上一级", false, v -> {
            java.io.File up = fileDir.getParentFile();
            if (up != null) { fileDir = up; listFiles(); }
        }));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(10);
        row.addView(pill("刷新", false, v -> listFiles()), rlp);
        card.content.addView(row);

        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        card.content.addView(fileList);

        listFiles();
        return card.box;
    }

    private void listFiles() {
        if (fileList == null) return;
        fileList.removeAllViews();
        filePath.setText(fileDir.getAbsolutePath());
        java.io.File[] items = fileDir.listFiles();
        if (items == null) {
            fileList.addView(text("无法读取该目录", 13, MUTED));
            return;
        }
        java.util.Arrays.sort(items, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        int shown = 0;
        for (java.io.File f : items) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (fileDir.getName().equals("Android")
                    && (name.equals("data") || name.equals("obb"))) continue;
            if (++shown > 200) break;
            TextView t = text((f.isDirectory() ? "▸ " : "· ") + name
                    + (f.isDirectory() ? "" : "  " + (f.length() / 1024) + " KB"), 14,
                    f.isDirectory() ? GOLD : TEXT);
            t.setPadding(dp(2), dp(12), dp(2), dp(12));
            t.setOnClickListener(v -> {
                if (f.isDirectory()) { fileDir = f; listFiles(); }
                else sendFileToMac(f);
            });
            t.setOnLongClickListener(v -> { fileActions(f); return true; });
            fileList.addView(t);
        }
    }

    private void runJob(String kind) {
        Intent i = new Intent(this, ClipService.class);
        i.putExtra("action", "vlm".equals(kind) ? "vlmtest" : "rectest");
        boolean ok = ShareActivity.startServiceSafely(this, i);
        toast(ok ? "已开始，结果会写入 Mac" : "服务未启动，请先启动");
    }

    private void sendFileToMac(java.io.File f) {
        Intent i = new Intent(this, ClipService.class);
        i.putExtra("action", "offer");
        i.putStringArrayListExtra("paths", new java.util.ArrayList<>(
                java.util.Collections.singletonList(f.getAbsolutePath())));
        i.putStringArrayListExtra("mimes", new java.util.ArrayList<>(
                java.util.Collections.singletonList("*/*")));
        boolean ok = ShareActivity.startServiceSafely(this, i);
        toast(ok ? "已发送：" + f.getName() : "Mac 同步未启动，请先启动");
    }

    private void confirmDelete(java.io.File f) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage(f.getAbsolutePath())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    toast(f.delete() ? "已删除" : "删除失败");
                    listFiles();
                }).show();
    }

    private void fileActions(java.io.File f) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(new String[] { "重命名", "删除" }, (d, which) -> {
                    if (which == 0) renameFile(f);
                    else confirmDelete(f);
                }).show();
    }

    private void renameFile(java.io.File f) {
        EditText input = field("新文件名", f.getName(), InputType.TYPE_CLASS_TEXT);
        new android.app.AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/")) {
                        toast("文件名不合法");
                        return;
                    }
                    java.io.File dst = new java.io.File(f.getParentFile(), name);
                    if (dst.exists()) {
                        toast("同名文件已存在");
                        return;
                    }
                    toast(f.renameTo(dst) ? "已重命名" : "重命名失败");
                    listFiles();
                }).show();
    }

    private View header() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView title = text("OneZion Connect", 20, TEXT);
        title.setSingleLine(true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.getPaint().setShader(new LinearGradient(0, 0, 0, dp(24),
                GOLD, CORAL, Shader.TileMode.CLAMP));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        row.addView(title, tp);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0xFF1B2A42);
        chipBg.setCornerRadius(dp(20));
        chipBg.setStroke(dp(1), BORDER);
        chip.setBackground(chipBg);
        chip.setPadding(dp(9), dp(5), dp(11), dp(5));

        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(TEAL);
        statusDot.setBackground(dot);
        chip.addView(statusDot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        statusText = text("已停止", 11, TEXT);
        statusText.setPadding(dp(6), 0, 0, 0);
        chip.addView(statusText);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(4);
        col.addView(row, rlp);

        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sub = text("照片 · 录音 · 文件 · 剪贴板", 12, MUTED);
        sub.setPadding(dp(58), 0, dp(8), 0);
        subRow.addView(sub, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        subRow.addView(chip);
        col.addView(subRow);
        return col;
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
        TextView start = pill("启动", true, v -> startBridge());
        row.addView(start, half);
        TextView stop = pill("停止", false, v -> stopBridge());
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.topMargin = dp(16);
        half2.leftMargin = dp(10);
        row.addView(stop, half2);
        card.content.addView(row);

        TextView adb = pill("无线 ADB（需 root）", false, v -> enableTcpip());
        LinearLayout.LayoutParams adbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adbLp.topMargin = dp(14);
        card.content.addView(adb, adbLp);

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
        refresh();
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

        TextView selfTest = pill("运行自检", false, v -> runSelfTest());
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(14);
        card.content.addView(selfTest, stLp);

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

    private void runSelfTest() {
        Config.save(this, serverField.getText().toString(), tokenField.getText().toString());
        statusView.setText("自检中…");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            boolean allOk = true;

            boolean storage = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
            out.append(storage ? "✓ 全盘访问\n" : "⚠ 全盘访问未开启（Sidecar 不可用）\n");

            boolean service = Config.enabled(this)
                    && ClipService.isRunning(this);
            out.append(service ? "✓ 前台服务\n" : "✗ 前台服务未运行\n");
            allOk &= service;

            String server = Config.server(this);
            try {
                Http.getTextOrNull(server + "/v1/ping", Config.token(this));
                out.append("✓ Mac 可达\n");
            } catch (Exception e) {
                out.append("✗ Mac 不可达（").append(e.getClass().getSimpleName()).append("）\n");
                allOk = false;
            }

            if (storage) {
                try {
                    int n = ClipService.countDir("/sdcard");
                    out.append("✓ 可读 /sdcard（").append(n).append(" 项）\n");
                } catch (Exception e) {
                    out.append("✗ 读 /sdcard 失败\n");
                    allOk = false;
                }
            }

            String tBase = Config.tBase(this);
            if (tBase.isEmpty()) {
                out.append("· 转写端点未配置\n");
            } else {
                try {
                    Http.getTextOrNull(tBase + "/models", Config.tKey(this));
                    out.append("✓ 转写端点可达\n");
                } catch (Exception e) {
                    out.append("· 转写端点无 /models（仍可能可用）\n");
                }
            }

            boolean finalOk = allOk;
            String report = (finalOk ? "自检通过\n" : "自检有问题\n") + out;
            runOnUiThread(() -> statusView.setText(report));
        }, "selftest").start();
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

    /** API keys are opaque strings, not logins: plain text keeps password managers away. */
    private EditText secretField(String hint, String value) {
        EditText e = field(hint, value,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(13);
        // an id/description that looks nothing like a login keeps 1Password quiet
        e.setId(View.generateViewId());
        e.setContentDescription("api key field");
        e.setTag("api-key");
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        e.setSaveEnabled(false);
        e.setFocusableInTouchMode(true);
        return e;
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
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
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
            i.putExtra("android.provider.extra.PICK_IMAGES_MAX", 100);
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
        Actions.choose(this, uris, () -> {});
    }

    private void enableTcpip() {
        toast("检查 root 权限…");
        new Thread(() -> {
            if (!Adb.hasRoot()) {
                toast("没有 root：请在开发者选项里打开「无线调试」");
                return;
            }
            toast(Adb.enableTcpip());
        }, "adb-root").start();
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
        ShareActivity.startServiceSafely(this, i);
        statusView.setText("Sent: " + action);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
            }, 1);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
            }, 1);
        } else if (Build.VERSION.SDK_INT >= 29) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
            }, 1);
        }
    }

    private boolean canReadPhotos() {
        if (Build.VERSION.SDK_INT >= 34) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        boolean running = Config.enabled(this) && ClipService.isRunning(this);
        boolean photos = canReadPhotos();
        if (statusText != null) {
            statusText.setText(!running ? "已停止" : photos ? "运行中" : "运行中（照片权限未授予）");
        }
        if (running && !photos) {
            photoWarning = true;
            if (statusView != null) {
                statusView.setText("没有照片读取权限，拍照不会自动同步。点上方「启动」会再次申请。");
            }
        } else if (photoWarning) {
            photoWarning = false;
            if (statusView != null) {
                statusView.setText("照片自动同步；文字/文件用上方按钮或「发送剪贴板」磁贴。");
            }
        }
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
                toast("权限被拒绝：" + perms[i]);
            }
        }
        refresh();
    }
}
