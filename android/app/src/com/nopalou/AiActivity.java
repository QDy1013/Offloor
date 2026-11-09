package com.nopalou;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
    private RadioButton now, charging;
    private TextView status;
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
                        + "开启后，这些消息原文会发送到下面填写的接口；密钥由 Android 系统密钥库加密保护，保存在本机。", 13, Ui.SUB, false);
        intro.setLineSpacing(dp(4), 1);
        card.addView(intro);
        CourseUi.gap(card, 14);
        enabled = new Switch(this);
        enabled.setText("开启 AI 复核与截图识别");
        enabled.setTextColor(Ui.TEXT);
        enabled.setTextSize(15);
        enabled.setChecked(cfg.enabled);
        card.addView(enabled, CourseUi.row(-2));

        LinearLayout api = section(root);
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
        refresh();
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
        AiConfig.save(this, enabled.isChecked(), base.getText().toString(), key.getText().toString(),
                model.getText().toString(), vision.getText().toString(),
                charging.isChecked() ? AiConfig.MODE_CHARGING : AiConfig.MODE_NOW, limit);
        } catch (RuntimeException e) { status.setText(e instanceof NumberFormatException ? "请填写有效的每日请求上限" : e.getMessage()); return false; }
        AiConfig cfg = AiConfig.load(this);
        if (cfg.enabled && !cfg.base.startsWith("https://")) {
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
    }

    @Override
    public void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
