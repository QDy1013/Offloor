package com.nopalou;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 应用内缴费页：只承载校方官方页面，用户自己完成缴纳。
 * 不注入脚本、不自动点击、不读取或提交任何支付字段。
 */
public final class CampusPayActivity extends android.app.Activity {
    private WebView web;
    private TextView status;
    private String errorStatus = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String id = getIntent().getStringExtra("module");
        if (!CampusPay.known(id)) { toast("缴费入口不可用"); finish(); return; }
        String entryUrl = CampusPay.url(id);
        LinearLayout root = CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this, root);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4)); root.addView(bar);
        bar.addView(CourseUi.icon(this, "prev", "返回", this::finish));
        bar.addView(CourseUi.text(this, CampusPay.title(id), 18, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(CourseUi.icon(this, "sync", "刷新页面", () -> {
            if (web == null) return;
            errorStatus = "";
            String cur = web.getUrl();
            if (cur == null || cur.isEmpty() || "about:blank".equalsIgnoreCase(cur)) web.loadUrl(entryUrl);
            else web.reload();
        }));
        LinearLayout note = CourseUi.column(this);
        note.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        note.setBackground(Ui.round(this, Ui.FILL, 14));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.leftMargin = Ui.dp(this, 14); noteParams.rightMargin = Ui.dp(this, 14); noteParams.bottomMargin = Ui.dp(this, 10);
        root.addView(note, noteParams);
        TextView hint = CourseUi.text(this, CampusPay.note(id), 12, Ui.SUB, false);
        hint.setLineSpacing(Ui.dp(this, 3), 1f); note.addView(hint);
        status = CourseUi.text(this, "正在打开校方页面…", 12, Ui.SUB, false);
        status.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 10)); root.addView(status);
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this); stage.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        ProgressBar loading = new ProgressBar(this);
        android.widget.FrameLayout.LayoutParams loadingParams = new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        loadingParams.gravity = Gravity.CENTER; stage.addView(loading, loadingParams);
        PayWebSupport.configureSettings(web);
        PayWebSupport.AllowStay allowStay = CampusServices::allowed;
        Runnable onExternal = () -> {
            loading.setVisibility(View.GONE);
            status.setText("已拉起外部支付或浏览器，完成后可返回");
        };
        web.setWebChromeClient(PayWebSupport.chromeClient(this, web, allowStay, entryUrl, onExternal, progress -> {
            if (progress >= 95 && loading.getVisibility() == View.VISIBLE && errorStatus.isEmpty()) {
                loading.setVisibility(View.GONE);
            }
        }));
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return PayWebSupport.shouldOverride(CampusPayActivity.this, v, r, allowStay, entryUrl, onExternal);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return PayWebSupport.shouldOverrideUrl(CampusPayActivity.this, v, url, null, allowStay, entryUrl, onExternal);
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                errorStatus = "";
                loading.setVisibility(View.VISIBLE);
                status.setText("正在加载校方页面…");
            }
            @Override public void onPageFinished(WebView v, String url) {
                loading.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if (errorStatus.isEmpty()) status.setText("校方页面已加载 · 缴费在校方页面完成");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, android.net.http.SslError e) {
                h.cancel(); loading.setVisibility(View.GONE);
                errorStatus = "校方页面证书校验未通过，已停止加载";
                status.setText(errorStatus);
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r == null || !r.isForMainFrame()) return;
                int code = e == null ? 0 : e.getStatusCode();
                if (code >= 400) {
                    loading.setVisibility(View.GONE);
                    errorStatus = "校方页面返回错误（HTTP " + code + "），请确认校园网连接或点击右上角刷新重试";
                    status.setText(errorStatus);
                }
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r == null || !r.isForMainFrame()) return;
                loading.setVisibility(View.GONE);
                errorStatus = "校方页面打不开；校园网或校方维护时可能不可用，稍后再试";
                status.setText(errorStatus);
            }
        });
        web.loadUrl(entryUrl);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
