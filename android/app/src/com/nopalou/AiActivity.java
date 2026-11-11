package com.nopalou;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 「AI 识别」设置：接口、模型、复核时机、队列状态，以及截图识别入口。 */
public class AiActivity extends Activity {
    private Switch enabled;
    private EditText base, key, model, vision, dailyLimit;
    private RadioButton now, charging, cloudBackend, localBackend;
    private TextView status, localStatus;
    private LinearLayout api, localBox;
    private EditText localUrl;
    private Switch mobileData;
    private static final int PICK_MODEL = 41;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshLocal();
            if (LocalModelStore.downloading(AiActivity.this)) ui.postDelayed(this, 2000);
        }
    };
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.apply(this);
        build();
    }

    private int dp(float n) { return Ui.dp(this, n); }

    private void build() {
        AiConfig cfg = AiConfig.load(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = CourseUi.column(this);
        sv.addView(root);
        setContentView(sv);
        Ui.systemBars(this, sv);

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(4), dp(8), dp(12), dp(8));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bar);
        bar.addView(CourseUi.icon(this, "prev", "返回", this::finish));
        bar.addView(CourseUi.text(this, "AI 识别", 21, Ui.TEXT, true));

        LinearLayout card = section(root);
        TextView intro = CourseUi.text(this,
                "本机规则先即时出卡。拿不准的消息、缺事项或缺时间的卡，会排队交给大模型复核：判断是不是待办，"
                        + "补全标题、截止时间、地点和提交方式。截图识别也用它。\n"
                        + "选云端接口时，这些消息原文会发送到下面填写的接口，密钥由 Android 系统密钥库加密保护；"
                        + "选本机小模型时，消息只在手机上处理，不联网、不计额度，但较慢且不支持截图识别。", 13, Ui.SUB, false);
        intro.setLineSpacing(dp(4), 1);
        card.addView(intro);
        CourseUi.gap(card, 14);
        enabled = new Switch(this);
        enabled.setText("开启 AI 复核与截图识别");
        enabled.setTextColor(Ui.TEXT);
        enabled.setTextSize(15);
        enabled.setChecked(cfg.enabled);
        card.addView(enabled, CourseUi.row(-2));

        LinearLayout which = section(root);
        which.addView(CourseUi.text(this, "用哪个模型复核", 16, Ui.TEXT, true));
        RadioGroup backends = new RadioGroup(this);
        cloudBackend = radio("云端接口（通义千问、DeepSeek 等，快，支持截图）");
        localBackend = radio("本机小模型（消息不出手机，约 1 GB，首次需下载）");
        backends.addView(cloudBackend);
        backends.addView(localBackend);
        (cfg.local ? localBackend : cloudBackend).setChecked(true);
        which.addView(backends);
        backends.setOnCheckedChangeListener((group, id) -> showBackend());

        localBox = section(root);
        localBox.addView(CourseUi.text(this, "本机小模型", 16, Ui.TEXT, true));
        TextView localIntro = CourseUi.text(this,
                "推荐 " + LocalLlmCore.RECOMMENDED_NAME + "，约 1 GB，只下载一次，存放在楼外的专属目录，卸载时一并删除。"
                        + "也可以导入任意 llama.cpp 格式（.gguf）的模型，建议不超过 1 GB。复核在后台进行，一批约半分钟到一分钟，选“只在充电时”更省电。",
                12, Ui.SUB, false);
        localIntro.setLineSpacing(dp(3), 1);
        localIntro.setPadding(0, dp(8), 0, 0);
        localBox.addView(localIntro);
        localStatus = CourseUi.text(this, "", 13, Ui.TEXT, false);
        localStatus.setLineSpacing(dp(3), 1);
        localStatus.setPadding(0, dp(10), 0, 0);
        localBox.addView(localStatus);
        localUrl = field(localBox, "下载地址", LocalLlmCore.RECOMMENDED_URL, LocalModelStore.downloadUrl(this), InputType.TYPE_TEXT_VARIATION_URI);
        mobileData = new Switch(this);
        mobileData.setText("允许用移动数据下载（约 1 GB 流量）");
        mobileData.setTextColor(Ui.TEXT);
        mobileData.setTextSize(13);
        mobileData.setPadding(0, dp(8), 0, dp(4));
        localBox.addView(mobileData, CourseUi.row(-2));
        CourseUi.gap(localBox, 10);
        localBox.addView(CourseUi.action(this, "下载模型", true, this::download), CourseUi.row(dp(48)));
        CourseUi.gap(localBox, 10);
        localBox.addView(CourseUi.action(this, "从文件导入 .gguf", false, () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(pick, PICK_MODEL); }
            catch (RuntimeException e) { localStatus.setText("这台手机没有可用的文件选择器"); }
        }), CourseUi.row(dp(48)));
        CourseUi.gap(localBox, 10);
        localBox.addView(CourseUi.action(this, "删除模型 / 取消下载", false, () -> {
            try { LocalModelStore.delete(this); }
            catch (RuntimeException e) { localStatus.setText(e.getMessage()); return; }
            refreshLocal();
            refresh();
        }), CourseUi.row(dp(48)));

        api = section(root);
        api.addView(CourseUi.text(this, "接口", 16, Ui.TEXT, true));
        LinearLayout presets = new LinearLayout(this);
        presets.setPadding(0, dp(10), 0, 0);
        api.addView(presets);
        presets.addView(CourseUi.action(this, "通义千问", false, () -> preset(AiConfig.DEFAULT_BASE, "qwen-plus", "qwen-vl-plus")),
                weight());
        presets.addView(CourseUi.action(this, "DeepSeek", false, () -> preset("https://api.deepseek.com", "deepseek-chat", "")),
                weight());
        base = field(api, "接口地址（OpenAI 兼容）", AiConfig.DEFAULT_BASE, cfg.base, InputType.TYPE_TEXT_VARIATION_URI);
        key = field(api, "API Key", "sk-…", cfg.key, InputType.TYPE_TEXT_VARIATION_PASSWORD);
        model = field(api, "文字模型（复核用）", AiConfig.DEFAULT_MODEL, cfg.model, 0);
        vision = field(api, "图片模型（截图识别用，需支持看图）", AiConfig.DEFAULT_VISION, cfg.vision, 0);
        TextView visionTip = CourseUi.text(this, "图片模型可留空。DeepSeek 目前不支持看图，选它时截图识别不可用，文字复核照常。", 11, Ui.MUTE, false);
        visionTip.setPadding(0, dp(6), 0, 0);
        api.addView(visionTip);

        LinearLayout when = section(root);
        when.addView(CourseUi.text(this, "什么时候复核", 16, Ui.TEXT, true));
        RadioGroup g = new RadioGroup(this);
        now = radio("有网就复核（约 20 秒攒一批，结果最快）");
        charging = radio("只在充电时批量复核（省电省流量）");
        g.addView(now);
        g.addView(charging);
        (AiConfig.MODE_CHARGING.equals(cfg.mode) ? charging : now).setChecked(true);
        when.addView(g);

        LinearLayout actions = section(root);
        status = CourseUi.text(this, "", 13, Ui.SUB, false);
        status.setLineSpacing(dp(4), 1);
        actions.addView(status);
        CourseUi.gap(actions, 14);
        actions.addView(CourseUi.action(this, "保存", true, this::save), CourseUi.row(dp(48)));
        CourseUi.gap(actions, 10);
        actions.addView(CourseUi.action(this, "测试连接", false, this::test), CourseUi.row(dp(48)));
        CourseUi.gap(actions, 10);
        actions.addView(CourseUi.action(this, "立即处理排队中的消息", false, () -> {
            save();
            AiJob.runNow(this);
            status.setText("已开始处理，联网后几秒内完成。\n" + AiConfig.summary(this));
        }), CourseUi.row(dp(48)));
        CourseUi.gap(actions, 10);
        actions.addView(CourseUi.action(this, "清空队列", false, () -> {
            try { AiQueue.clear(this); refresh(); }
            catch (RuntimeException error) { status.setText("队列未清空，原文件仍保留，请稍后重试"); }
        }), CourseUi.row(dp(48)));
        CourseUi.gap(actions, 10);
        actions.addView(CourseUi.action(this, "重试失败的请求", false, () -> {
            try {
                int count = AiQueue.retryFailed(this);
                AiJob.schedule(this);
                status.setText("已重新排队 " + count + " 条。过期或与当前事项不符的结果不会写回。\n" + AiConfig.summary(this));
            } catch (RuntimeException error) { status.setText("请求未重新排队，请先检查队列存档"); }
        }), CourseUi.row(dp(48)));

        LinearLayout budget = section(root);
        budget.addView(CourseUi.text(this, "每日 AI 请求上限", 16, Ui.TEXT, true));
        dailyLimit = new EditText(this);
        dailyLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        dailyLimit.setText(String.valueOf(cfg.dailyLimit));
        budget.addView(dailyLimit);
        budget.addView(CourseUi.text(this, "范围 1–500，文字批次、图片和连接测试均计数，失败请求也计数。达到上限后保留队列，次日继续。token 数仅统计服务端已返回的用量。", 12, Ui.SUB, false));

        LinearLayout shot = section(root);
        shot.addView(CourseUi.text(this, "截图识别", 16, Ui.TEXT, true));
        TextView how = CourseUi.text(this,
                "通知只显示“[图片]”时，楼外读不到内容。截下群聊或通知页面，在相册里“分享 → 楼外”，"
                        + "或者点下面的按钮选图，楼外会逐条转写并出卡。复制的文字也可以直接分享给楼外。", 13, Ui.SUB, false);
        how.setLineSpacing(dp(4), 1);
        how.setPadding(0, dp(8), 0, dp(12));
        shot.addView(how);
        shot.addView(CourseUi.action(this, "选一张截图识别", true,
                () -> startActivity(new Intent(this, ShotActivity.class).putExtra(ShotActivity.EXTRA_PICK, true))),
                CourseUi.row(dp(48)));
        CourseUi.gap(root, 24);
        showBackend();
        refresh();
    }

    private void showBackend() {
        boolean local = localBackend.isChecked();
        localBox.setVisibility(local ? View.VISIBLE : View.GONE);
        api.setVisibility(local ? View.GONE : View.VISIBLE);
        refreshLocal();
    }

    private void refreshLocal() {
        if (localStatus == null) return;
        String line;
        if (!LocalLlm.supported()) {
            line = "不可用：" + LocalLlm.unavailableReason();
        } else {
            String progress = LocalModelStore.pollDownload(this);
            if (LocalModelStore.present(this)) {
                long size = LocalModelStore.size(this);
                line = "已就绪：" + LocalModelStore.name(this) + "（" + LocalLlmCore.sizeText(size) + "）"
                        + (size > LocalLlmCore.SOFT_LIMIT_BYTES ? "\n文件偏大，较旧的手机可能运行缓慢" : "");
                if (!progress.isEmpty()) line = progress + "\n" + line;
            } else {
                line = progress.isEmpty() ? "还没有模型文件" : progress;
            }
        }
        localStatus.setText(line);
    }

    private void download() {
        String why = LocalModelStore.startDownload(this, localUrl.getText().toString(), mobileData.isChecked());
        if (why != null) { localStatus.setText(why); return; }
        localStatus.setText(mobileData.isChecked() ? "已开始下载，可在通知栏查看进度" : "已开始下载，连上 Wi-Fi 时进行，可在通知栏查看进度");
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 1500);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MODEL || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
            localStatus.setText("请选择 .gguf 格式的模型文件");
            return;
        }
        localStatus.setText("正在导入 " + name + "，约 1 GB 需要十几秒…");
        io.execute(() -> {
            String msg;
            try {
                LocalModelStore.importFrom(this, uri, name);
                msg = null;
            } catch (Exception e) {
                msg = "导入失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String text = msg;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                refreshLocal();
                if (text != null) localStatus.setText(text);
                AiJob.reschedule(this);
                refresh();
            });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (RuntimeException ignored) {
            // 取不到文件名时按路径最后一段
        }
        String last = uri.getLastPathSegment();
        return last == null ? "model.gguf" : last.substring(last.lastIndexOf('/') + 1);
    }

    private void refresh() {
        status.setText("当前：" + AiConfig.summary(this) + AiQueue.failureSummary(this));
    }

    private void preset(String b, String m, String v) {
        base.setText(b);
        model.setText(m);
        vision.setText(v);
    }

    private boolean save() {
        try {
        int limit = Integer.parseInt(dailyLimit.getText().toString().trim());
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("每日请求上限应为 1–500");
        AiConfig.save(this, enabled.isChecked(), localBackend.isChecked() ? AiConfig.BACKEND_LOCAL : AiConfig.BACKEND_CLOUD,
                base.getText().toString(), key.getText().toString(),
                model.getText().toString(), vision.getText().toString(),
                charging.isChecked() ? AiConfig.MODE_CHARGING : AiConfig.MODE_NOW, limit);
        } catch (RuntimeException e) { status.setText(e instanceof NumberFormatException ? "请填写有效的每日请求上限" : e.getMessage()); return false; }
        AiConfig cfg = AiConfig.load(this);
        if (cfg.enabled && !cfg.local && !cfg.base.startsWith("https://")) {
            status.setText("接口地址必须是 https:// 开头");
            return false;
        }
        AiJob.reschedule(this);
        refresh();
        return true;
    }

    private void test() {
        if (!save()) return;
        AiConfig cfg = AiConfig.load(this);
        if (cfg.local) { testLocal(cfg); return; }
        if (!cfg.base.startsWith("https://")) { status.setText("接口地址必须是 https:// 开头"); return; }
        if (cfg.key.isEmpty()) {
            status.setText("先填写 API Key");
            return;
        }
        status.setText("正在测试…");
        io.execute(() -> {
            String msg;
            try {
                new AiClient(cfg).ping();
                msg = "连接成功：" + cfg.model + " 可用" + (cfg.enabled ? "" : "（记得打开上面的开关）");
            } catch (Exception e) {
                msg = "连接失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String text = msg;
            runOnUiThread(() -> { if (!isDestroyed()) status.setText(text); });
        });
    }

    private void testLocal(AiConfig cfg) {
        if (!LocalLlm.supported()) { status.setText("本机模型不可用：" + LocalLlm.unavailableReason()); return; }
        if (!LocalModelStore.present(this)) { status.setText("先下载或导入模型文件"); return; }
        status.setText("正在加载本机模型并试跑，首次约 5~20 秒…");
        io.execute(() -> {
            String msg;
            long t0 = System.currentTimeMillis();
            try (AiClient client = new AiClient(cfg)) {
                client.ping();
                msg = "本机模型可用：" + LocalModelStore.name(this) + "，用时 " + (System.currentTimeMillis() - t0) / 1000.0 + " 秒"
                        + (cfg.enabled ? "" : "（记得打开上面的开关）");
            } catch (Exception e) {
                msg = "本机模型试跑失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String text = msg;
            runOnUiThread(() -> { if (!isDestroyed()) status.setText(text); });
        });
    }

    private LinearLayout section(LinearLayout root) {
        LinearLayout card = CourseUi.column(this);
        card.setPadding(dp(20), dp(18), dp(20), dp(20));
        Ui.cardSurface(card, 20);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(-1, -2);
        c.setMargins(dp(16), dp(12), dp(16), 0);
        root.addView(card, c);
        return card;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1);
        p.setMargins(0, 0, dp(8), 0);
        return p;
    }

    private RadioButton radio(String text) {
        RadioButton r = new RadioButton(this);
        r.setText(text);
        r.setTextColor(Ui.TEXT);
        r.setTextSize(14);
        r.setPadding(dp(4), dp(8), 0, dp(8));
        return r;
    }

    private EditText field(LinearLayout root, String label, String hint, String value, int variation) {
        TextView t = CourseUi.text(this, label, 12, Ui.SUB, false);
        t.setPadding(0, dp(12), 0, dp(6));
        root.addView(t);
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.MUTE);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.FILL);
        g.setCornerRadius(dp(14));
        e.setBackground(g);
        if (value != null) e.setText(value);
        root.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (status != null) refresh();
        if (localStatus != null) {
            refreshLocal();
            if (LocalModelStore.downloading(this)) { ui.removeCallbacks(poll); ui.postDelayed(poll, 2000); }
        }
    }

    @Override
    public void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
