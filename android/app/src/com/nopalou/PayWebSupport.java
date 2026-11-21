package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Map;

/**
 * 校方缴费页与校车收银台共用的 WebView 能力：
 * 统一处理外跳支付应用（微信/支付宝/云闪付/intent://）、多窗口弹窗与 JS 对话框，
 * 不向校方或支付页面注入脚本，不读取任何表单或密码。
 */
final class PayWebSupport {
    interface AllowStay { boolean test(String url); }

    private PayWebSupport() { }

    static void configureSettings(WebView web) {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
    }

    static boolean shouldOverride(Activity activity, WebView view, WebResourceRequest req,
            AllowStay allowStay, String defaultReferer, Runnable onLaunchedExternal) {
        String url = req == null || req.getUrl() == null ? "" : req.getUrl().toString();
        Map<String, String> headers = req == null ? null : req.getRequestHeaders();
        return shouldOverrideUrl(activity, view, url, headers, allowStay, defaultReferer, onLaunchedExternal);
    }

    static boolean shouldOverrideUrl(Activity activity, WebView view, String url, Map<String, String> headers,
            AllowStay allowStay, String defaultReferer, Runnable onLaunchedExternal) {
        if ("javascript".equals(CampusPay.scheme(url))) {
            // 部分校方旧版缴费按钮使用 <a href="javascript:..."> 触发页内跳转，放行给当前页自行执行，但不拉起外部 Intent。
            return false;
        }
        boolean stay = allowStay.test(url);
        CampusPay.Route route = CampusPay.route(url, stay);
        switch (route) {
            case STAY: {
                if (CampusPay.needsPayReferer(url) && !hasReferer(headers)) {
                    String ref = pickReferer(view == null ? null : view.getUrl(), defaultReferer);
                    if (view != null && !ref.isEmpty()) {
                        Map<String, String> extra = new HashMap<>();
                        extra.put("Referer", ref);
                        view.loadUrl(url, extra);
                        return true;
                    }
                }
                return false;
            }
            case OPEN_INTENT:
                launchIntentScheme(activity, view, url, allowStay, defaultReferer, onLaunchedExternal);
                return true;
            case OPEN_URI:
                launchExternalUri(activity, url, onLaunchedExternal);
                return true;
            case IGNORE:
            default:
                return true;
        }
    }

    private static boolean hasReferer(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("referer".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String pickReferer(String currentUrl, String defaultReferer) {
        if (currentUrl != null && "https".equals(CampusPay.scheme(currentUrl))) return currentUrl;
        if (defaultReferer != null && "https".equals(CampusPay.scheme(defaultReferer))) return defaultReferer;
        return "";
    }

    private static void launchExternalUri(Activity activity, String url, Runnable onLaunchedExternal) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            if (onLaunchedExternal != null) onLaunchedExternal.run();
        } catch (Exception e) {
            Toast.makeText(activity, "未能打开" + CampusPay.payAppName(url) + "，请确认已安装后重试", Toast.LENGTH_LONG).show();
        }
    }

    private static void launchIntentScheme(Activity activity, WebView view, String url,
            AllowStay allowStay, String defaultReferer, Runnable onLaunchedExternal) {
        Intent parsed;
        try {
            parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        } catch (Exception e) {
            Toast.makeText(activity, "无法识别该跳转链接", Toast.LENGTH_LONG).show();
            return;
        }
        parsed.setComponent(null);
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo resolved = activity.getPackageManager().resolveActivity(parsed, 0);
        if (resolved != null && resolved.activityInfo != null
                && !activity.getPackageName().equals(resolved.activityInfo.packageName)) {
            try {
                activity.startActivity(parsed);
                if (onLaunchedExternal != null) onLaunchedExternal.run();
                return;
            } catch (Exception ignored) { }
        }
        String fallback = parsed.getStringExtra("browser_fallback_url");
        if (fallback != null && !fallback.trim().isEmpty()) {
            String fbScheme = CampusPay.scheme(fallback);
            if ("https".equals(fbScheme) || "http".equals(fbScheme)) {
                if (view != null && "https".equals(fbScheme) && allowStay.test(fallback)) {
                    String ref = pickReferer(view.getUrl(), defaultReferer);
                    if (CampusPay.needsPayReferer(fallback) && !ref.isEmpty()) {
                        Map<String, String> extra = new HashMap<>();
                        extra.put("Referer", ref);
                        view.loadUrl(fallback, extra);
                    } else {
                        view.loadUrl(fallback);
                    }
                } else {
                    launchExternalUri(activity, fallback, onLaunchedExternal);
                }
                return;
            }
        }
        Toast.makeText(activity, "未能打开" + CampusPay.payAppName(url) + "，请确认已安装后重试", Toast.LENGTH_LONG).show();
    }

    static WebChromeClient chromeClient(Activity activity, WebView hostWeb, AllowStay allowStay,
            String defaultReferer, Runnable onLaunchedExternal, ProgressListener onProgress) {
        return new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                if (onProgress != null) onProgress.changed(newProgress);
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (!(resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport)) return false;
                if (activity.isFinishing() || activity.isDestroyed()) return false;
                WebView relay = new WebView(activity);
                WebSettings relaySettings = relay.getSettings();
                relaySettings.setJavaScriptEnabled(true);
                relaySettings.setAllowFileAccess(false);
                relaySettings.setAllowContentAccess(false);
                relaySettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                final Handler cleaner = new Handler(Looper.getMainLooper());
                final boolean[] done = {false};
                final Runnable cleanup = () -> {
                    if (done[0]) return;
                    done[0] = true;
                    relay.stopLoading();
                    relay.destroy();
                };
                cleaner.postDelayed(cleanup, 4000);
                relay.setWebViewClient(new WebViewClient() {
                    private boolean routeTarget(String target, Map<String, String> headers) {
                        if (done[0]) return true;
                        if (target == null || target.isEmpty() || "about:blank".equalsIgnoreCase(target)) return false;
                        cleaner.removeCallbacks(cleanup);
                        if (CampusPay.route(target, allowStay.test(target)) == CampusPay.Route.STAY) {
                            String ref = pickReferer(hostWeb.getUrl(), defaultReferer);
                            if (CampusPay.needsPayReferer(target) && !hasReferer(headers) && !ref.isEmpty()) {
                                Map<String, String> extra = new HashMap<>();
                                extra.put("Referer", ref);
                                hostWeb.loadUrl(target, extra);
                            } else {
                                hostWeb.loadUrl(target);
                            }
                        } else {
                            shouldOverrideUrl(activity, hostWeb, target, headers, allowStay, defaultReferer, onLaunchedExternal);
                        }
                        cleanup.run();
                        return true;
                    }

                    @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                        // 部分 Android WebView 对 WebViewTransport 的首跳不经 shouldOverrideUrlLoading 而直接走 onPageStarted
                        routeTarget(url, null);
                    }

                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        String target = req == null || req.getUrl() == null ? "" : req.getUrl().toString();
                        return routeTarget(target, req == null ? null : req.getRequestHeaders());
                    }

                    @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        return routeTarget(url, null);
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(relay);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (activity.isFinishing() || activity.isDestroyed()) { result.cancel(); return true; }
                boolean[] done = {false};
                new AlertDialog.Builder(activity, Ui.dialogTheme())
                        .setMessage(message == null ? "" : message)
                        .setPositiveButton("确定", (d, w) -> { if (!done[0]) { done[0] = true; result.confirm(); } })
                        .setOnCancelListener(d -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .setOnDismissListener(d -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .show();
                return true;
            }

            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (activity.isFinishing() || activity.isDestroyed()) { result.cancel(); return true; }
                boolean[] done = {false};
                new AlertDialog.Builder(activity, Ui.dialogTheme())
                        .setMessage(message == null ? "" : message)
                        .setPositiveButton("确定", (d, w) -> { if (!done[0]) { done[0] = true; result.confirm(); } })
                        .setNegativeButton("取消", (d, w) -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .setOnCancelListener(d -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .setOnDismissListener(d -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .show();
                return true;
            }

            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                if (activity.isFinishing() || activity.isDestroyed()) { result.cancel(); return true; }
                EditText input = new EditText(activity);
                if (defaultValue != null) input.setText(defaultValue);
                boolean[] done = {false};
                new AlertDialog.Builder(activity, Ui.dialogTheme())
                        .setMessage(message == null ? "" : message)
                        .setView(input)
                        .setPositiveButton("确定", (d, w) -> { if (!done[0]) { done[0] = true; result.confirm(input.getText().toString()); } })
                        .setNegativeButton("取消", (d, w) -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .setOnCancelListener(d -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .setOnDismissListener(d -> { if (!done[0]) { done[0] = true; result.cancel(); } })
                        .show();
                return true;
            }
        };
    }

    interface ProgressListener { void changed(int progress); }
}
