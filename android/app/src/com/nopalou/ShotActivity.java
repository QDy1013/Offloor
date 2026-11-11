package com.nopalou;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 截图识别 / 分享入口：相册“分享 → 楼外”（一张或多张图、或一段文字），也可以从设置页选图。
 * 识别在后台线程做，结果当场写进待办。
 */
public class ShotActivity extends Activity {
    static final String EXTRA_PICK = "pick";
    private static final int PICK = 41;
    static final int MAX_IMAGES = 5;

    private TextView headline, detail;
    private ProgressBar progress;
    private LinearLayout buttons;
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.apply(this);
        build();
        if (b != null) return;               // 旋转重建：不重复识别
        Intent in = getIntent();
        if (in.getBooleanExtra(EXTRA_PICK, false)) {
            pick();
            return;
        }
        handle(in);
    }

    private int dp(float n) { return Ui.dp(this, n); }

    private void build() {
        LinearLayout root = CourseUi.column(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        Ui.systemBars(this, root);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(4), dp(8), dp(12), dp(8));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bar);
        bar.addView(CourseUi.icon(this, "prev", "返回", this::finish));
        bar.addView(CourseUi.text(this, "截图识别", 21, Ui.TEXT, true));
        LinearLayout card = CourseUi.column(this);
        card.setPadding(dp(24), dp(28), dp(24), dp(28));
        Ui.cardSurface(card, 22);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(-1, -2);
        c.setMargins(dp(16), dp(24), dp(16), 0);
        root.addView(card, c);
        headline = CourseUi.text(this, "准备识别…", 19, Ui.TEXT, true);
        card.addView(headline);
        CourseUi.gap(card, 10);
        detail = CourseUi.text(this, "", 13, Ui.SUB, false);
        detail.setLineSpacing(dp(4), 1);
        card.addView(detail);
        CourseUi.gap(card, 18);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        card.addView(progress, CourseUi.row(dp(8)));
        CourseUi.gap(card, 18);
        buttons = CourseUi.column(this);
        card.addView(buttons);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, "选择截图"), PICK);
        } catch (Exception e) {
            done("打不开相册", "系统里没有可用的图片选择器。", false);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        if (result != RESULT_OK || data == null) { finish(); return; }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        images(uris);
    }

    private void handle(Intent in) {
        String action = in.getAction();
        String type = in.getType() == null ? "" : in.getType();
        if (Intent.ACTION_SEND.equals(action) && type.startsWith("text/")) {
            CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            text(t == null ? "" : t.toString());
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) { done("没有收到内容", "请分享一张截图或一段文字给楼外。", false); return; }
        images(uris);
    }

    private void text(String value) {
        if (value.trim().isEmpty()) { done("没有收到文字", "", false); return; }
        headline.setText("正在识别文字…");
        io.execute(() -> {
            try {
                AiImages.Result r = AiImages.recognizeText(this, value, System.currentTimeMillis());
                runOnUiThread(() -> done(r.added > 0 ? "新增 " + r.added + " 张待办" : "没有找到需要你做的事",
                        "共 " + r.messages + " 段文字" + (!AiConfig.ready(this) ? "，按本机规则识别。" : AiConfig.load(this).local ? "，已按规则出卡，本机模型稍后在后台复核。" : "，已经过 AI 复核。"), true));
            } catch (Exception e) {
                runOnUiThread(() -> done("识别失败", message(e), false));
            }
        });
    }

    private void images(List<Uri> uris) {
        if (!AiConfig.load(this).visionReady()) {
            AiConfig cfg = AiConfig.load(this);
            done("截图识别需要先开启 AI 识别", cfg.enabled && cfg.local
                    ? "本机小模型不支持看图。截图识别需要在“AI 识别”里切换到云端接口并填写图片模型；复制的文字仍可直接分享给楼外。"
                    : "在“AI 识别”里填写接口、密钥和图片模型（例如通义千问 qwen-vl-plus），之后再分享截图即可。", false);
            buttons.addView(CourseUi.action(this, "去设置 AI 识别", true,
                    () -> { startActivity(new Intent(this, AiActivity.class)); finish(); }), CourseUi.row(dp(48)));
            return;
        }
        final List<Uri> list = uris.size() > MAX_IMAGES ? uris.subList(0, MAX_IMAGES) : uris;
        headline.setText(list.size() > 1 ? "正在识别 " + list.size() + " 张截图…" : "正在识别截图…");
        detail.setText("图片会发送到你在“AI 识别”里填写的接口，通常 5~20 秒。");
        io.execute(() -> {
            int msgs = 0, added = 0, failed = 0;
            String group = "";
            String lastError = null;
            for (int i = 0; i < list.size(); i++) {
                final int n = i + 1;
                if (list.size() > 1) runOnUiThread(() -> headline.setText("正在识别第 " + n + "/" + list.size() + " 张…"));
                try {
                    byte[] jpeg = AiImages.loadJpeg(this, list.get(i));
                    AiImages.Result r = AiImages.recognize(this, jpeg, null, "截图", AiImages.PKG_SCREENSHOT,
                            "", System.currentTimeMillis(), true);
                    msgs += r.messages;
                    added += r.added;
                    group = r.group;
                } catch (Exception e) {
                    failed++;
                    lastError = message(e);
                }
            }
            final int fm = msgs, fa = added, ff = failed;
            final String fg = group, fe = lastError;
            runOnUiThread(() -> {
                if (ff == list.size()) { done("识别失败", fe, false); return; }
                done(fa > 0 ? "新增 " + fa + " 张待办" : fm == 0 ? "截图里没有读到消息" : "没有找到需要你做的事",
                        (fg.isEmpty() ? "" : "「" + fg + "」") + "读到 " + fm + " 条消息"
                                + (ff > 0 ? "，" + ff + " 张没识别成功（" + fe + "）" : "") + "。", true);
            });
        });
    }

    private void done(String title, String body, boolean ok) {
        if (isDestroyed()) return;
        headline.setText(title);
        detail.setText(body == null ? "" : body);
        progress.setIndeterminate(false);
        progress.setProgress(ok ? 100 : 0);
        buttons.removeAllViews();
        buttons.addView(CourseUi.action(this, "查看待办", ok, () -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }), CourseUi.row(dp(48)));
        CourseUi.gap(buttons, 10);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override
    public void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
