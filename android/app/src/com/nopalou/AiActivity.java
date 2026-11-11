package com.nopalou;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 「AI 识别」设置。
 *
 * 页面顺序按用户的疑问排：先说清楚 AI 复核是干什么的、不会做什么；再用四步清单显示设置进度
 * （选方式 → 准备模型 → 测试 → 开启）；下面才是两种方式各自的设置、复核时机、截图识别，
 * 队列和额度这类不常用的收进「队列与额度」。第一次进来且还没开启时自动弹出设置向导。
 */
public class AiActivity extends Activity {
    private static final int PICK_MODEL = 41;
    private static final String GUIDE_SEEN = "guide_seen";
    private static final String BACKEND_CHOSEN = "backend_chosen";
    private static final String TEST_OK = "test_ok";

    private Switch enabled, mobileData;
    private EditText base, key, model, vision, dailyLimit, localUrl;
    private Ui.Choice localChoice, cloudChoice, nowChoice, chargeChoice;
    private boolean useLocal, charging, binding;
    private TextView status, localStatus, stateTag;
    private LinearLayout api, localBox, which, steps, advanced;
    private ScrollView scroll;

    // 设置向导（底部面板，四页）
    private Dialog guide;
    private LinearLayout guideBody;
    private TextView guideStatus, guideLocal, guideBack, guideNext;
    private EditText guideKey;
    private int guidePage;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshLocal();
            renderSteps();
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

    private SharedPreferences prefs() { return AiConfig.prefs(this); }

    private void build() {
        binding = true;
        AiConfig cfg = AiConfig.load(this);
        useLocal = cfg.local;
        charging = AiConfig.MODE_CHARGING.equals(cfg.mode);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = CourseUi.column(this);
        scroll.addView(root);
        setContentView(scroll);
        Ui.systemBars(this, scroll);
        root.addView(Ui.topBar(this, "AI 识别"));

        // ---------- 这是做什么的 ----------
        LinearLayout intro = Ui.card(root);
        Ui.heading(intro, "给本机规则查漏补缺",
                "楼外平时用本机规则从群消息里认出待办，又快又稳。遇到说法特别、信息不全的消息，规则会拿不准，"
                        + "AI 复核只看这一小部分。默认关闭，不开也能正常用。");
        addPoints(intro);
        intro.addView(safetyNote(), topMargin(16));
        CourseUi.gap(intro, 14);
        intro.addView(CourseUi.action(this, "用向导一步步设置", false, () -> showGuide(0)), CourseUi.row(dp(48)));

        // ---------- 设置进度 ----------
        LinearLayout state = Ui.card(root);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(CourseUi.text(this, "设置进度", 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        stateTag = Ui.tag(this, "", Ui.SUB, Ui.FILL);
        head.addView(stateTag);
        state.addView(head);
        status = CourseUi.text(this, "", 12, Ui.SUB, false);
        status.setLineSpacing(dp(3), 1);
        status.setPadding(0, dp(6), 0, dp(4));
        state.addView(status);
        steps = CourseUi.column(this);
        state.addView(steps);
        CourseUi.gap(state, 4);
        state.addView(Ui.hairline(this));
        CourseUi.gap(state, 4);
        enabled = Ui.toggle(state, "开启 AI 复核", "打开后拿不准的消息自动排队复核；关掉立刻回到只用本机规则。", cfg.enabled);
        enabled.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            boolean saved = save();
            renderSteps();
            if (!saved) return;
            if (on) showEnabledFeedback();
            else android.widget.Toast.makeText(this, "AI 复核已关闭，回到只用本机规则识别", android.widget.Toast.LENGTH_SHORT).show();
        });

        // ---------- 用哪个模型 ----------
        which = Ui.card(root);
        Ui.heading(which, "用哪个模型复核", "两种二选一，随时可以换。");
        localChoice = new Ui.Choice(which, "本机小模型", "隐私最好", LOCAL_DESC, () -> chooseBackend(true));
        cloudChoice = new Ui.Choice(which, "云端接口", "最快", CLOUD_DESC, () -> chooseBackend(false));
        if (!LocalLlm.supported()) localChoice.disable(localUnsupported());

        // ---------- 本机小模型 ----------
        localBox = Ui.card(root);
        Ui.heading(localBox, "本机小模型",
                "推荐 " + LocalLlmCore.RECOMMENDED_NAME + "，约 1 GB，只下载一次，存放在楼外的专属目录，卸载时一并删除。"
                        + "也可以导入任意 llama.cpp 格式（.gguf）的模型，建议不超过 1 GB。复核在后台进行，一批约半分钟到一分钟。");
        localStatus = infoBox("");
        localBox.addView(localStatus, topMargin(12));
        mobileData = Ui.toggle(localBox, "允许用移动数据下载", "关掉时只在连上 Wi-Fi 后下载，约 1 GB 流量。", false);
        CourseUi.gap(localBox, 6);
        localBox.addView(CourseUi.action(this, "下载推荐模型", true, this::download), CourseUi.row(dp(48)));
        CourseUi.gap(localBox, 10);
        localBox.addView(CourseUi.action(this, "从文件导入 .gguf", false, this::pickModel), CourseUi.row(dp(48)));
        CourseUi.gap(localBox, 10);
        localBox.addView(CourseUi.action(this, "删除模型 / 取消下载", false, () -> {
            try { LocalModelStore.delete(this); }
            catch (RuntimeException e) { sayLocal(e.getMessage()); return; }
            refreshLocal();
            refresh();
        }), CourseUi.row(dp(48)));
        localUrl = Ui.field(localBox, "下载地址（一般不用改）", LocalLlmCore.RECOMMENDED_URL, LocalModelStore.downloadUrl(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        // ---------- 云端接口 ----------
        api = Ui.card(root);
        Ui.heading(api, "云端接口",
                "选一个服务商自动填好地址和模型，再粘贴你的 API Key。拿不准的消息原文会发送到这个接口；"
                        + "密钥用 Android 系统密钥库加密保存在本机。");
        api.addView(presetRow(null), topMargin(12));
        base = Ui.field(api, "接口地址（OpenAI 兼容）", AiConfig.DEFAULT_BASE, cfg.base,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        key = Ui.field(api, "API Key", "sk-…", cfg.key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        model = Ui.field(api, "文字模型（复核用）", AiConfig.DEFAULT_MODEL, cfg.model, InputType.TYPE_CLASS_TEXT);
        vision = Ui.field(api, "图片模型（截图识别用，需支持看图）", AiConfig.DEFAULT_VISION, cfg.vision, InputType.TYPE_CLASS_TEXT);
        TextView visionTip = CourseUi.text(this, "图片模型可留空。DeepSeek 目前不支持看图，选它时截图识别不可用，文字复核照常。", 12, Ui.MUTE, false);
        visionTip.setLineSpacing(dp(2), 1);
        visionTip.setPadding(0, dp(8), 0, dp(14));
        api.addView(visionTip);
        LinearLayout apiActions = new LinearLayout(this);
        apiActions.addView(CourseUi.action(this, "保存", true, this::save), weight(1, 0));
        apiActions.addView(CourseUi.action(this, "测试连接", false, this::test), weight(1, 10));
        api.addView(apiActions);

        // ---------- 什么时候复核 ----------
        LinearLayout when = Ui.card(root);
        Ui.heading(when, "什么时候复核", null);
        nowChoice = new Ui.Choice(when, "有网就复核", null, "约 20 秒攒一批，结果最快。", () -> chooseMode(false));
        chargeChoice = new Ui.Choice(when, "只在充电时", "省电", "充电时批量处理，更省电省流量；用本机模型时推荐。", () -> chooseMode(true));

        // ---------- 截图识别 ----------
        LinearLayout shot = Ui.card(root);
        Ui.heading(shot, "截图识别",
                "通知只显示“[图片]”时，楼外读不到内容。截下群聊或通知页面，在相册里“分享 → 楼外”，"
                        + "或者点下面的按钮选图，楼外会逐条转写并出卡。需要云端接口并填写图片模型。");
        CourseUi.gap(shot, 14);
        shot.addView(CourseUi.action(this, "选一张截图识别", false,
                () -> startActivity(new Intent(this, ShotActivity.class).putExtra(ShotActivity.EXTRA_PICK, true))),
                CourseUi.row(dp(48)));

        // ---------- 队列与额度（默认收起） ----------
        LinearLayout adv = Ui.card(root);
        LinearLayout advHead = new LinearLayout(this);
        advHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout advWords = CourseUi.column(this);
        advWords.addView(CourseUi.text(this, "队列与额度", 16, Ui.TEXT, true));
        TextView advDesc = CourseUi.text(this, "排队中的消息、失败重试、每日请求上限", 12, Ui.SUB, false);
        advDesc.setPadding(0, dp(3), 0, 0);
        advWords.addView(advDesc);
        advHead.addView(advWords, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageDrawable(CourseUi.glyph(this, "down"));
        advHead.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        adv.addView(advHead);
        advanced = CourseUi.column(this);
        advanced.setVisibility(View.GONE);
        adv.addView(advanced);
        advHead.setOnClickListener(v -> {
            boolean open = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(open ? 180 : 0).setDuration(160).start();
        });
        CourseUi.gap(advanced, 14);
        advanced.addView(CourseUi.action(this, "立即处理排队中的消息", false, () -> {
            if (!save()) return;
            AiJob.runNow(this);
            say("已开始处理，联网后几秒内完成。\n" + AiConfig.summary(this));
        }), CourseUi.row(dp(48)));
        CourseUi.gap(advanced, 10);
        advanced.addView(CourseUi.action(this, "重试失败的请求", false, () -> {
            try {
                int count = AiQueue.retryFailed(this);
                AiJob.schedule(this);
                say("已重新排队 " + count + " 条。过期或与当前事项不符的结果不会写回。\n" + AiConfig.summary(this));
            } catch (RuntimeException error) { say("请求未重新排队，请先检查队列存档"); }
        }), CourseUi.row(dp(48)));
        CourseUi.gap(advanced, 10);
        advanced.addView(CourseUi.action(this, "清空队列", false, () -> {
            try { AiQueue.clear(this); refresh(); }
            catch (RuntimeException error) { say("队列未清空，原文件仍保留，请稍后重试"); }
        }), CourseUi.row(dp(48)));
        dailyLimit = Ui.field(advanced, "每日 AI 请求上限（云端）", "30", String.valueOf(cfg.dailyLimit), InputType.TYPE_CLASS_NUMBER);
        TextView limitTip = CourseUi.text(this,
                "范围 1–500，文字批次、图片和连接测试均计数，失败请求也计数。达到上限后保留队列，次日继续。"
                        + "token 数仅统计服务端已返回的用量。本机模型不受这个上限限制。", 12, Ui.MUTE, false);
        limitTip.setLineSpacing(dp(2), 1);
        limitTip.setPadding(0, dp(8), 0, 0);
        advanced.addView(limitTip);

        CourseUi.gap(root, 28);
        renderChoices();
        showBackend();
        binding = false;
        refresh();

        if (!cfg.enabled && !prefs().getBoolean(GUIDE_SEEN, false)) {
            ui.post(() -> { if (!isFinishing() && !isDestroyed()) showGuide(0); });
        }
    }

    // ---------- 文案 ----------

    private static final String LOCAL_DESC =
            "消息只在手机里处理：不联网、不花钱、没有次数上限。需要先下载约 1 GB 模型；复核较慢，不支持截图识别。";
    private static final String CLOUD_DESC =
            "把拿不准的消息原文发给你填写的接口（通义千问、DeepSeek 等）。速度快、能识别截图；需要自己申请 API Key，按服务商价格计费。";

    private String localUnsupported() {
        return "这台手机用不了：" + LocalLlm.unavailableReason() + "。请选云端接口。";
    }

    private void addPoints(LinearLayout parent) {
        Ui.point(parent, "找", Ui.ACCENT, Ui.ACCENT_BG, "找回漏掉的待办",
                "规则没把握、或者带着动作和时间却没出卡的消息，交给 AI 再判断一次。");
        Ui.point(parent, "补", Ui.TEAL, Ui.TEAL_BG, "补全缺的信息",
                "卡片缺事项名、时间写得含糊时，补上标题、截止时间、地点和提交方式。");
        Ui.point(parent, "图", Ui.VIOLET, Ui.VIOLET_BG, "读懂截图",
                "通知只显示“[图片]”时，分享截图给楼外转写成文字再出卡（需要云端接口）。");
    }

    private TextView safetyNote() {
        return infoBox("放心的地方：AI 只能补原文里写了的内容，编造的时间、地点写不进卡片；"
                + "不会覆盖你亲手改过的标题和时间；它认为不是待办的卡会移到「已移除的卡片」，可以恢复。");
    }

    /** 浅底圆角的说明块。 */
    private TextView infoBox(String text) {
        TextView t = CourseUi.text(this, text, 12, Ui.SUB, false);
        t.setLineSpacing(dp(3), 1);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(Ui.round(this, Ui.dark ? Ui.FILL : Ui.BG, 14));
        return t;
    }

    private LinearLayout presetRow(Runnable after) {
        LinearLayout presets = new LinearLayout(this);
        presets.addView(CourseUi.action(this, "通义千问", false, () -> {
            preset(AiConfig.DEFAULT_BASE, "qwen-plus", "qwen-vl-plus");
            say("已按通义千问填好接口地址和模型，接下来粘贴 API Key。");
            if (after != null) after.run();
        }), weight(1, 0));
        presets.addView(CourseUi.action(this, "DeepSeek", false, () -> {
            preset("https://api.deepseek.com", "deepseek-chat", "");
            say("已按 DeepSeek 填好接口地址和模型，接下来粘贴 API Key。DeepSeek 不支持截图识别。");
            if (after != null) after.run();
        }), weight(1, 10));
        return presets;
    }

    // ---------- 状态 ----------

    private void chooseBackend(boolean local) {
        useLocal = local;
        prefs().edit().putBoolean(BACKEND_CHOSEN, true).apply();
        renderChoices();
        showBackend();
        save();
        renderSteps();
    }

    private void chooseMode(boolean onlyCharging) {
        charging = onlyCharging;
        renderChoices();
        save();
    }

    private void renderChoices() {
        localChoice.select(useLocal);
        cloudChoice.select(!useLocal);
        nowChoice.select(!charging);
        chargeChoice.select(charging);
    }

    private void showBackend() {
        localBox.setVisibility(useLocal ? View.VISIBLE : View.GONE);
        api.setVisibility(useLocal ? View.GONE : View.VISIBLE);
        refreshLocal();
    }

    /** 测试通过时记下的配置指纹：换了模型、接口或密钥后需要重新测试。 */
    private String signature(AiConfig cfg) {
        if (cfg.local) return "local|" + LocalModelStore.name(this) + "|" + LocalModelStore.size(this);
        return "cloud|" + cfg.base + "|" + cfg.model + "|" + Integer.toHexString(cfg.key.hashCode());
    }

    private void renderSteps() {
        if (steps == null) return;
        steps.removeAllViews();
        AiConfig cfg = AiConfig.load(this);
        boolean chosen = cfg.enabled || prefs().getBoolean(BACKEND_CHOSEN, false);
        boolean localOk = LocalLlm.supported();
        boolean prepared = cfg.local ? localOk && LocalModelStore.present(this)
                : !cfg.key.isEmpty() && cfg.base.startsWith("https://");
        boolean tested = prepared && signature(cfg).equals(prefs().getString(TEST_OK, ""));
        boolean on = cfg.enabled;

        Ui.step(steps, 1, "选择复核方式", chosen ? (cfg.local ? "本机小模型" : "云端接口") : "本机更私密，云端更快",
                chosen, "去选择", () -> scrollTo(which));
        String prep;
        if (cfg.local) {
            if (!localOk) prep = "这台手机用不了本机模型，请改选云端接口";
            else if (prepared) prep = "模型已就绪：" + LocalModelStore.name(this);
            else if (LocalModelStore.downloading(this)) prep = "正在下载，完成后自动打勾";
            else prep = "下载推荐模型，或导入 .gguf 文件";
        } else {
            prep = prepared ? "已填写 API Key" : "选服务商并粘贴 API Key";
        }
        Ui.step(steps, 2, cfg.local ? "准备本机模型" : "填写云端接口", prep, prepared,
                cfg.local && !localOk ? null : "去准备", () -> scrollTo(cfg.local ? localBox : api));
        Ui.step(steps, 3, "测试一次", tested ? "上次测试通过" : "确认模型能正常回答", tested,
                prepared ? "测试" : null, this::test);
        Ui.step(steps, 4, "打开 AI 复核", on ? "已开启" : "打开下面的开关后自动生效", on,
                "开启", () -> enabled.setChecked(true));

        String label;
        int fg, bg;
        if (cfg.ready() && tested) { label = "运行中"; fg = Ui.GREEN; bg = Ui.GREEN_BG; }
        else if (cfg.ready()) { label = "已开启"; fg = Ui.ACCENT; bg = Ui.ACCENT_BG; }
        else if (on) { label = "还差一步"; fg = Ui.AMBER_TX; bg = Ui.AMBER_BG; }
        else { label = "未开启"; fg = Ui.SUB; bg = Ui.FILL; }
        stateTag.setText(label);
        stateTag.setTextColor(fg);
        stateTag.setBackground(Ui.round(this, bg, 99));
    }

    private void scrollTo(View v) {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(12))));
    }

    /** 状态文字同时写到页面和打开着的向导上。 */
    private void say(String text) {
        if (status != null) status.setText(text);
        if (guideStatus != null) {
            guideStatus.setText(text);
            guideStatus.setVisibility(View.VISIBLE);
        }
    }

    private void sayLocal(String text) {
        if (localStatus != null) localStatus.setText(text);
        if (guideLocal != null) guideLocal.setText(text);
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
        sayLocal(line);
    }

    private void refresh() {
        if (status == null) return;
        status.setText(AiConfig.summary(this) + AiQueue.failureSummary(this));
        renderSteps();
    }

    // ---------- 动作 ----------

    private void download() {
        String why = LocalModelStore.startDownload(this, localUrl.getText().toString(), mobileData.isChecked());
        if (why != null) { sayLocal(why); return; }
        sayLocal(mobileData.isChecked() ? "已开始下载，可在通知栏查看进度" : "已开始下载，连上 Wi-Fi 时进行，可在通知栏查看进度");
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 1500);
    }

    private void pickModel() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(pick, PICK_MODEL); }
        catch (RuntimeException e) { sayLocal("这台手机没有可用的文件选择器"); }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MODEL || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
            sayLocal("请选择 .gguf 格式的模型文件");
            return;
        }
        sayLocal("正在导入 " + name + "，约 1 GB 需要十几秒…");
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
                if (text != null) sayLocal(text);
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

    private void preset(String b, String m, String v) {
        base.setText(b);
        model.setText(m);
        vision.setText(v);
    }

    private boolean save() {
        if (binding) return false;
        try {
            int limit = Integer.parseInt(dailyLimit.getText().toString().trim());
            if (limit < 1 || limit > 500) throw new IllegalArgumentException("每日请求上限应为 1–500");
            AiConfig.save(this, enabled.isChecked(), useLocal ? AiConfig.BACKEND_LOCAL : AiConfig.BACKEND_CLOUD,
                    base.getText().toString(), key.getText().toString(),
                    model.getText().toString(), vision.getText().toString(),
                    charging ? AiConfig.MODE_CHARGING : AiConfig.MODE_NOW, limit);
        } catch (RuntimeException e) {
            say(e instanceof NumberFormatException ? "请填写有效的每日请求上限" : e.getMessage());
            return false;
        }
        AiConfig cfg = AiConfig.load(this);
        if (cfg.enabled && !cfg.local && !cfg.base.startsWith("https://")) {
            say("接口地址必须是 https:// 开头");
            renderSteps();
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
        if (!cfg.base.startsWith("https://")) { say("接口地址必须是 https:// 开头"); return; }
        if (cfg.key.isEmpty()) {
            say("先填写 API Key");
            return;
        }
        say("正在测试…");
        io.execute(() -> {
            String msg;
            boolean ok;
            try {
                new AiClient(cfg).ping();
                ok = true;
                msg = "连接成功：" + cfg.model + " 可用" + (cfg.enabled ? "" : "（记得打开「开启 AI 复核」）");
            } catch (Exception e) {
                ok = false;
                msg = "连接失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            finishTest(cfg, ok, msg);
        });
    }

    private void testLocal(AiConfig cfg) {
        if (!LocalLlm.supported()) { say("本机模型不可用：" + LocalLlm.unavailableReason()); return; }
        if (!LocalModelStore.present(this)) { say("先下载或导入模型文件"); return; }
        say("正在加载本机模型并试跑，首次约 5~20 秒…");
        io.execute(() -> {
            String msg;
            boolean ok;
            long t0 = System.currentTimeMillis();
            try (AiClient client = new AiClient(cfg)) {
                client.ping();
                ok = true;
                msg = "本机模型可用：" + LocalModelStore.name(this) + "，用时 " + (System.currentTimeMillis() - t0) / 1000.0 + " 秒"
                        + (cfg.enabled ? "" : "（记得打开「开启 AI 复核」）");
            } catch (Exception e) {
                ok = false;
                msg = "本机模型试跑失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            finishTest(cfg, ok, msg);
        });
    }

    private void finishTest(AiConfig cfg, boolean ok, String msg) {
        if (ok) prefs().edit().putString(TEST_OK, signature(cfg)).apply();
        else prefs().edit().remove(TEST_OK).apply();
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            renderSteps();
            say(msg);
        });
    }

    // ---------- 开启后的反馈 ----------

    /** 还差什么才能真正开始复核；已就绪时返回 null。 */
    private String missingStep(AiConfig cfg) {
        if (cfg.ready()) return null;
        if (cfg.local) {
            if (!LocalLlm.supported()) return "这台手机用不了本机模型，请改选云端接口。";
            return LocalModelStore.downloading(this) ? "本机模型还在下载，下完会自动开始复核。" : "还需要下载或导入本机模型。";
        }
        if (cfg.key.isEmpty()) return "还需要填写 API Key。";
        return "接口地址必须是 https:// 开头。";
    }

    /**
     * 打开开关后明确告诉用户：已经开了、用的是哪种方式、接下来在哪里能看到它在工作。
     * 向导里开启时不另弹面板，结果直接写在向导上。
     */
    private void showEnabledFeedback() {
        AiConfig cfg = AiConfig.load(this);
        String missing = missingStep(cfg);
        boolean ready = missing == null;
        String how = cfg.local ? "本机小模型 " + LocalModelStore.name(this) + "，消息不出手机"
                : "云端接口 " + cfg.model;
        String when = AiConfig.MODE_CHARGING.equals(cfg.mode) ? "充电时批量复核" : "有网就复核，约 20 秒攒一批";
        if (guide != null && guide.isShowing()) {
            say(ready ? "✓ AI 复核已开启：" + how + "，" + when + "。" : "已开启，还差一步：" + missing);
            return;
        }
        LinearLayout body = CourseUi.column(this);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView mark = new ImageView(this);
        int tone = ready ? Ui.GREEN : Ui.AMBER_TX;
        mark.setImageDrawable(new Ui.CheckCircle(this, tone, tone, 56, true));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(56), dp(56));
        mp.topMargin = dp(8);
        body.addView(mark, mp);
        mark.setScaleX(0.6f);
        mark.setScaleY(0.6f);
        mark.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        TextView title = CourseUi.text(this, ready ? "AI 复核已开启" : "已开启，还差一步", 20, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        body.addView(title, topMargin(14));
        TextView sub = CourseUi.text(this, ready ? "正在使用" + how + "。\n" + when + "。" : missing, 13, Ui.SUB, false);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(dp(3), 1);
        body.addView(sub, topMargin(6));
        LinearLayout list = CourseUi.column(this);
        body.addView(list, topMargin(10));
        if (ready) {
            Ui.point(list, "1", Ui.ACCENT, Ui.ACCENT_BG, "拿不准的消息自动排队",
                    "新消息先按本机规则出卡，拿不准的会交给 AI 再看一眼，不用你做任何操作。");
            Ui.point(list, "2", Ui.ACCENT, Ui.ACCENT_BG, "复核过的卡带「AI 已复核」",
                    "首页卡片上能直接看到哪些被 AI 看过、补全过。");
            Ui.point(list, "3", Ui.ACCENT, Ui.ACCENT_BG, "首页顶部显示「AI 复核中」",
                    "随时点它回到这里调整或关闭。");
        }
        LinearLayout footer = CourseUi.column(this);
        footer.setPadding(0, dp(14), 0, 0);
        Dialog[] holder = new Dialog[1];
        int queued = AiQueue.size(this);
        if (ready && queued > 0) {
            footer.addView(CourseUi.action(this, "立即复核排队中的 " + queued + " 条", false, () -> {
                AiJob.runNow(this);
                say("已开始处理，联网后几秒内完成。\n" + AiConfig.summary(this));
                if (holder[0] != null) holder[0].dismiss();
            }), CourseUi.row(dp(48)));
            CourseUi.gap(footer, 10);
        }
        footer.addView(CourseUi.action(this, ready ? "知道了" : "去完成设置", true, () -> {
            if (holder[0] != null) holder[0].dismiss();
            if (!ready) showGuide(2);
        }), CourseUi.row(dp(48)));
        holder[0] = Ui.sheet(this, body, footer);
    }

    // ---------- 设置向导 ----------

    private void showGuide(int page) {
        prefs().edit().putBoolean(GUIDE_SEEN, true).apply();
        guidePage = page;
        if (guide != null && guide.isShowing()) { fillGuide(); return; }
        guideBody = CourseUi.column(this);
        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(0, dp(12), 0, 0);
        guideBack = CourseUi.action(this, "", false, () -> {
            leaveGuidePage();
            if (guidePage == 0) { if (guide != null) guide.dismiss(); }
            else { guidePage--; fillGuide(); }
        });
        guideNext = CourseUi.action(this, "", true, () -> {
            leaveGuidePage();
            if (guidePage >= 3) {
                if (guide != null) guide.dismiss();
                // 走完向导且已开启：用同一张「已开启」面板收尾，让用户确切知道 AI 已经在工作。
                if (enabled.isChecked()) ui.post(this::showEnabledFeedback);
            }
            else { guidePage++; fillGuide(); }
        });
        footer.addView(guideBack, weight(1, 0));
        footer.addView(guideNext, weight(1.4f, 10));
        guide = Ui.sheet(this, guideBody, footer);
        guide.setOnDismissListener(d -> {
            leaveGuidePage();
            guide = null;
            guideBody = null;
            guideStatus = null;
            guideLocal = null;
            guideKey = null;
            refresh();
        });
        fillGuide();
    }

    /** 离开向导某一页时把那一页填的内容写回设置页并保存。 */
    private void leaveGuidePage() {
        if (guideKey == null) return;
        String typed = guideKey.getText().toString();
        if (!typed.equals(key.getText().toString())) key.setText(typed);
        save();
    }

    private void fillGuide() {
        if (guideBody == null) return;
        guideBody.removeAllViews();
        guideStatus = null;
        guideLocal = null;
        guideKey = null;
        guideBody.addView(Ui.sectionLabel(this, "设置向导 · 第 " + (guidePage + 1) + " / 4 步"));
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(0, dp(10), 0, dp(16));
        for (int i = 0; i < 4; i++) {
            View seg = new View(this);
            seg.setBackground(Ui.round(this, i <= guidePage ? Ui.ACCENT : Ui.FILL, 3));
            bar.addView(seg, weight(1, i == 0 ? 0 : 4, 4));
        }
        guideBody.addView(bar);
        switch (guidePage) {
            case 0: guideIntro(); break;
            case 1: guideChoose(); break;
            case 2: guidePrepare(); break;
            default: guideFinish(); break;
        }
        guideBack.setText(guidePage == 0 ? "以后再说" : "上一步");
        guideNext.setText(guidePage == 0 ? "开始设置" : guidePage == 3 ? "完成" : "下一步");
    }

    private void guideTitle(String title, String desc) {
        guideBody.addView(CourseUi.text(this, title, 20, Ui.TEXT, true));
        if (desc == null) return;
        TextView d = CourseUi.text(this, desc, 13, Ui.SUB, false);
        d.setLineSpacing(dp(3), 1);
        d.setPadding(0, dp(8), 0, 0);
        guideBody.addView(d);
    }

    private TextView guideResult() {
        TextView t = infoBox("");
        t.setTextColor(Ui.TEXT);
        t.setVisibility(View.GONE);
        guideBody.addView(t, topMargin(12));
        return t;
    }

    private void guideIntro() {
        guideTitle("先认识一下 AI 复核",
                "楼外平时用本机规则认待办，又快又稳；碰到说法特别、信息不全的消息会拿不准。"
                        + "AI 复核只处理这一小部分，帮规则查漏补缺。");
        addPoints(guideBody);
        guideBody.addView(infoBox("放心的地方：AI 只能补原文里写了的内容，编造的时间、地点写不进卡片；"
                + "不会覆盖你亲手改过的标题和时间；它认为不是待办的卡会移到「已移除的卡片」，可以恢复。"
                + "不开也完全能用，关掉马上回到只用本机规则。"), topMargin(16));
    }

    private void guideChoose() {
        boolean supported = LocalLlm.supported();
        guideTitle("选一种复核方式", "两种二选一，之后随时能在设置页换。");
        Ui.Choice local = new Ui.Choice(guideBody, "本机小模型", "隐私最好", LOCAL_DESC, () -> { chooseBackend(true); fillGuide(); });
        Ui.Choice cloud = new Ui.Choice(guideBody, "云端接口", "最快", CLOUD_DESC, () -> { chooseBackend(false); fillGuide(); });
        local.select(useLocal);
        cloud.select(!useLocal);
        if (!supported) local.disable(localUnsupported());
        TextView tip = CourseUi.text(this, supported
                ? "拿不准选哪个？在意隐私、不想申请密钥就选本机；想要快、要用截图识别就选云端。"
                : "这台手机不支持本机模型，建议直接用云端接口。", 12, Ui.MUTE, false);
        tip.setLineSpacing(dp(2), 1);
        tip.setPadding(0, dp(12), 0, 0);
        guideBody.addView(tip);
    }

    private void guidePrepare() {
        if (useLocal) {
            guideTitle("准备本机模型",
                    "推荐模型约 1 GB，只需下载一次。下载在后台进行，可以先关掉向导，下完回到这里继续。");
            if (!LocalLlm.supported()) {
                guideBody.addView(infoBox(localUnsupported()), topMargin(12));
                return;
            }
            guideLocal = infoBox("");
            guideLocal.setTextColor(Ui.TEXT);
            guideBody.addView(guideLocal, topMargin(12));
            Switch data = Ui.toggle(guideBody, "允许用移动数据下载", "关掉时只在连上 Wi-Fi 后下载。", mobileData.isChecked());
            data.setOnCheckedChangeListener((v, on) -> mobileData.setChecked(on));
            CourseUi.gap(guideBody, 6);
            guideBody.addView(CourseUi.action(this, "下载推荐模型", true, this::download), CourseUi.row(dp(48)));
            CourseUi.gap(guideBody, 10);
            guideBody.addView(CourseUi.action(this, "从文件导入 .gguf", false, this::pickModel), CourseUi.row(dp(48)));
            refreshLocal();
        } else {
            guideTitle("填写云端接口",
                    "先选服务商，楼外会自动填好接口地址和模型名；再粘贴你在服务商控制台创建的 API Key。");
            guideBody.addView(presetRow(null), topMargin(14));
            guideKey = Ui.field(guideBody, "API Key", "sk-…", key.getText().toString(),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            TextView tip = CourseUi.text(this,
                    "通义千问的密钥在阿里云百炼控制台创建，DeepSeek 的在 DeepSeek 开放平台创建，按服务商价格计费。"
                            + "楼外每天最多发 " + dailyLimit.getText().toString().trim() + " 次请求，可在「队列与额度」里调整。"
                            + "拿不准的消息原文会发到这个接口，密钥加密保存在本机。", 12, Ui.MUTE, false);
            tip.setLineSpacing(dp(2), 1);
            tip.setPadding(0, dp(8), 0, 0);
            guideBody.addView(tip);
            guideStatus = guideResult();
        }
    }

    private void guideFinish() {
        guideTitle("试一下，然后开启",
                "楼外会发一条测试请求，确认模型能正常回答。打开后，拿不准的消息会自动排队处理。");
        TextView label = Ui.sectionLabel(this, "什么时候复核");
        label.setPadding(0, dp(16), 0, 0);
        guideBody.addView(label);
        Ui.Choice now = new Ui.Choice(guideBody, "有网就复核", null, "约 20 秒攒一批，结果最快。", () -> { chooseMode(false); fillGuide(); });
        Ui.Choice charge = new Ui.Choice(guideBody, "只在充电时", "省电", "更省电省流量；用本机模型时推荐。", () -> { chooseMode(true); fillGuide(); });
        now.select(!charging);
        charge.select(charging);
        CourseUi.gap(guideBody, 16);
        guideBody.addView(CourseUi.action(this, enabled.isChecked() ? "再测试一次" : "测试并开启", true, () -> {
            if (!enabled.isChecked()) enabled.setChecked(true);
            test();
        }), CourseUi.row(dp(48)));
        guideStatus = guideResult();
    }

    // ---------- 布局小工具 ----------

    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams weight(float w, int leftMargin) {
        return weight(w, leftMargin, 48);
    }

    private LinearLayout.LayoutParams weight(float w, int leftMargin, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(height), w);
        p.leftMargin = dp(leftMargin);
        return p;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        refreshLocal();
        if (LocalModelStore.downloading(this)) { ui.removeCallbacks(poll); ui.postDelayed(poll, 2000); }
    }

    @Override
    public void onPause() {
        ui.removeCallbacks(poll);
        // 离开页面时保存，免得填完密钥直接返回就丢了。
        save();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (guide != null && guide.isShowing()) guide.dismiss();
        io.shutdown();
        super.onDestroy();
    }
}
