package com.nopalou;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String id = getIntent().getStringExtra("module");
        if (!CampusPay.known(id)) { toast("缴费入口不可用"); finish(); return; }
        LinearLayout root = CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this, root);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4)); root.addView(bar);
        bar.addView(CourseUi.icon(this, "prev", "返回", this::finish));
        bar.addView(CourseUi.text(this, CampusPay.title(id), 18, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
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
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !open(r.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !open(url); }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) { loading.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView v, String url) {
                loading.setVisibility(View.GONE); CookieManager.getInstance().flush();
                status.setText("校方页面已加载 · 缴费在校方页面完成");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, android.net.http.SslError e) {
                h.cancel(); loading.setVisibility(View.GONE); status.setText("校方页面证书校验未通过，已停止加载");
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                loading.setVisibility(View.GONE);
                status.setText("校方页面打不开；校园网或校方维护时可能不可用，稍后再试");
            }
        });
        web.loadUrl(CampusPay.url(id));
    }

    /** 只放行校方域名，别的一律交给系统浏览器，免得在校方页面里被引到别处输入密码。 */
    private boolean open(String url) {
        if (CampusServices.allowed(url)) return true;
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (Exception ignored) { }
        return false;
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
