package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校园信息（网费、电费、一卡通、空闲教室、成绩考试…）的后台自动更新：不开新页面、不转圈。
 * 浏览器放在当前窗口内容的“下面”（DecorView 的第 0 个子视图），看不见、也不接收触摸；
 * 页面照常使用，已读到的每一项保存后立刻显示。
 *
 * 流程与可见的自动读取一致：复用翱翔门户会话 → 读门户首页（网费、一卡通余额和服务入口）
 * → 三路并行读取各项（CampusBatch）。会话过期时使用本机加密凭据续登；
 * 登录失败或遇到安全验证时暂停并记下 attention，等待用户处理。
 */
final class CampusSilentRefresh {
    static final String PREFS = "campus-auto";
    static final String ATTENTION = "attention";       // "" / "login" / "challenge"
    private static CampusSilentRefresh current;

    private final Context host;
    private final Runnable completion;
    private boolean loginAttempted, sessionRecoveryAttempted;
    private String loginJs;
    private final Context app;
    private final String run;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final CampusAuth auth = new CampusAuth();
    private final Set<String> handoffs = new HashSet<>();
    private FrameLayout stage;
    private WebView web;
    private CampusBatch batch;
    private CampusData saved;
    private String owner = "", campusJs, ssoJs, autoJs;
    private boolean stopped, probing, catalogTried, portalRecovered, portalRetried, booting;
    private long bootStarted, authDeadline, portalChanged, lastBeat;
    private String portalSnapshot = "";
    private int generation;

    static boolean running() { return current != null; }

    static String attention(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ATTENTION, "");
    }

    static void clearAttention(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ATTENTION).apply();
    }

    /** 在 host 里后台跑一轮。run 是 CampusAutoRefresh 发的租约令牌。 */
    static void start(Activity host, String run) {
        if (current != null || host == null || host.isFinishing() || host.isDestroyed()) return;
        current = new CampusSilentRefresh(host, run);
        current.begin();
    }

    private CampusSilentRefresh(Activity host, String run) {
        this(host, run, null);
    }

    private CampusSilentRefresh(Context host, String run, Runnable completion) {
        this.host = host;
        this.app = host.getApplicationContext();
        this.run = run;
        this.completion = completion;
    }

    static boolean startBackground(Context context, String run, Runnable completion) {
        if (current != null) return false;
        current = new CampusSilentRefresh(context, run, completion);
        current.begin();
        return true;
    }

    static void cancelAll() { if (current != null) current.end(false, true); }
    static void stopBackground(Context context) {
        if (current != null && current.host == context) current.end(false, true);
    }

    private static String asset(Context c, String name) throws Exception {
        try (java.io.InputStream in = c.getAssets().open(name); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void begin() {
        try {
            loginJs = asset(app, "nwpu-login.js");
            campusJs = asset(app, "nwpu-campus.js");
            ssoJs = asset(app, "nwpu-sso.js");
            autoJs = asset(app, "nwpu-auto.js");
        } catch (Exception e) {
            end(false, true);
            return;
        }
        // 放在 DecorView 最底层：内容区（不透明背景）盖在上面；setContentView 也不会把它移走。
        ViewGroup decor = host instanceof Activity ? (ViewGroup) ((Activity)host).getWindow().getDecorView() : null;
        stage = new FrameLayout(host) {
            @Override public boolean dispatchTouchEvent(MotionEvent e) { return false; }
        };
        stage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        stage.setFocusable(false);
        if (decor != null) decor.addView(stage, 0, new FrameLayout.LayoutParams(-1, -1));
        web = new WebView(host);
        stage.addView(web, new FrameLayout.LayoutParams(-1, -1));
        if (decor == null) {
            int size=View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY);
            stage.measure(size,size); stage.layout(0,0,1080,1080);
        }
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setOffscreenPreRaster(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !CampusServices.allowed(r.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !CampusServices.allowed(url); }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) { probing = false; }
            @Override public void onPageFinished(WebView v, String url) { CookieManager.getInstance().flush(); if (booting) schedule(30); }
            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) { h.cancel(); end(false, true); }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) { if (r.isForMainFrame()) end(false, true); }
        });
        io.execute(() -> {
            try { saved = CampusStore.load(app); owner = saved == null ? "" : saved.owner; } catch (Exception ignored) { }
            handler.post(() -> {
                if (stopped) return;
                if (owner.isEmpty() || !CampusAutoRefresh.owns(app, run, owner)) { end(false, false); return; }
                bootstrap();
            });
        });
    }

    // ---------- 门户首页：会话、网费/一卡通余额、各服务入口 ----------

    private void bootstrap() {
        generation++;
        booting = true;
        catalogTried = false;
        portalRecovered = false;
        auth.reset();
        handoffs.clear();
        bootStarted = SystemClock.elapsedRealtime();
        authDeadline = bootStarted + 45_000;
        portalSnapshot = "";
        portalChanged = bootStarted;
        web.loadUrl(CampusServices.PORTAL_SSO);
        schedule(50);
    }

    private final Runnable tick = this::inspect;

    private void schedule(long delay) {
        handler.removeCallbacks(tick);
        if (!stopped) handler.postDelayed(tick, delay);
    }

    private boolean hostAlive() { return !(host instanceof Activity) || (!((Activity)host).isFinishing() && !((Activity)host).isDestroyed()); }

    private void inspect() {
        if (stopped || !booting) return;
        if (!hostAlive() || !CampusAutoRefresh.owns(app, run, owner)) { end(false, false); return; }
        long now = SystemClock.elapsedRealtime();
        if (System.currentTimeMillis() - lastBeat > 60_000) { lastBeat = System.currentTimeMillis(); CampusAutoRefresh.heartbeat(app, run, owner); }
        if (now - bootStarted > 60_000) {
            if (loginAttempted) needsUser("login"); else end(false, true);
            return;
        }
        if (probing) return;
        String url = web.getUrl();
        if (url == null || !CampusServices.allowed(url)) { schedule(1200); return; }
        final int active = generation;
        probing = true;
        web.evaluateJavascript(campusJs + "\n" + ssoJs + "\n" + autoJs + "\nNwpuAuto.install();NwpuAuto.focus();JSON.stringify(NwpuSso.inspect())", value -> {
            if (stopped || active != generation) return;
            probing = false;
            if (!url.equals(web.getUrl())) { schedule(600); return; }
            JSONObject r;
            try {
                Object raw = new JSONTokener(value).nextValue();
                r = raw instanceof String ? new JSONObject((String) raw) : new JSONObject();
            } catch (Exception e) { schedule(1000); return; }
            String phase = r.optString("phase");
            long t = SystemClock.elapsedRealtime();
            switch (auth.observe(phase, t)) {
                case CHALLENGE: needsUser("challenge"); return;
                case CENTRAL_EXPIRED: renewLogin(active); return;
                case WAIT:
                    if (t > authDeadline) finishBootstrap(new JSONObject(), null, active); else schedule(600);
                    return;
                case HANDOFF: {
                    String target = r.optString("url"), key = phase + ":" + web.getUrl();
                    if (handoffs.add(key) && handoffs.size() <= 4) {
                        if ("sso_link".equals(phase) && CampusServices.readUrl(target)) web.loadUrl(target);
                        else web.evaluateJavascript("NwpuSso.click('sso'," + JSONObject.quote("handoff-" + active) + ")", ignored -> { });
                        schedule(800);
                    } else if (t > authDeadline) finishBootstrap(new JSONObject(), null, active);
                    else schedule(800);
                    return;
                }
                case SERVICE_ENTRY:
                    if (!portalRetried) { portalRetried = true; bootstrap(); } else finishBootstrap(new JSONObject(), null, active);
                    return;
                case READY:
                    scanPortal(r, active);
                    return;
                default:
                    schedule(1000);
            }
        });
    }

    private void scanPortal(JSONObject r, int active) {
        String url = web.getUrl();
        if (url == null || !"ecampus.nwpu.edu.cn".equals(Uri.parse(url).getHost())) { schedule(1000); return; }
        if (r.optBoolean("portalError") && !portalRecovered) { portalRecovered = true; web.loadUrl(CampusServices.PORTAL); schedule(100); return; }
        if (!catalogTried) {
            catalogTried = true;
            web.evaluateJavascript("NwpuSso.click('catalog'," + JSONObject.quote("catalog-" + active) + ")", ignored -> schedule(80));
            return;
        }
        long now = SystemClock.elapsedRealtime();
        String snapshot = String.valueOf(r.optJSONArray("apps")) + r.optJSONObject("routes") + r.optJSONObject("portalModules");
        if (!snapshot.equals(portalSnapshot)) { portalSnapshot = snapshot; portalChanged = now; }
        boolean empty = (r.optJSONArray("apps") == null || r.optJSONArray("apps").length() == 0)
                && (r.optJSONObject("routes") == null || r.optJSONObject("routes").length() == 0);
        if ((empty || r.optBoolean("busy") || now - portalChanged < 200) && now - bootStarted < 5000) { schedule(80); return; }
        JSONObject routes = r.optJSONObject("routes");
        finishBootstrap(routes == null ? new JSONObject() : routes, r.optJSONObject("portalModules"), active);
    }

    private void finishBootstrap(JSONObject routes, JSONObject modules, int active) {
        if (!booting || active != generation || stopped) return;
        booting = false;
        handler.removeCallbacks(tick);
        io.execute(() -> {
            CampusData next = null;
            try {
                JSONObject fresh = new JSONObject().put("version", 1).put("school", "西北工业大学")
                        .put("modules", modules == null ? new JSONObject() : modules).put("routes", routes);
                next = CampusStore.update(app, fresh.toString(), owner);
            } catch (Exception ignored) { }
            CampusData result = next;
            handler.post(() -> {
                if (stopped) return;
                if (result != null) saved = result;
                startBatch();
            });
        });
    }

    // ---------- 各项并行读取 ----------

    private void startBatch() {
        if (stopped || saved == null || !hostAlive()) { end(false, true); return; }
        web.stopLoading();
        List<CampusServices.Service> queue = new ArrayList<>();
        for (CampusServices.Service s : CampusServices.CAMPUS) queue.add(s);
        batch = new CampusBatch(host, stage, saved, queue, run, campusJs, ssoJs, autoJs, new CampusBatch.Listener() {
            public void progress(int done, int total, int ok, int failed, String active) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("done", done).putInt("total", total).apply();
            }
            public void challenge(WebView view, boolean visible) { if (visible) needsUser("challenge"); }
            public void loginRequired() {
                if (sessionRecoveryAttempted || loginAttempted) { needsUser("login"); return; }
                sessionRecoveryAttempted=true;
                if (batch != null) { batch.stop(); batch = null; }
                bootstrap();
            }
            public void complete(int ok, int failed) { end(true, failed > 0); }
        });
        batch.start();
    }

    private void renewLogin(int active) {
        CampusCredentials.Login login = CampusCredentials.load(app);
        if (login == null || !owner.equals(login.owner)) { needsUser("login"); return; }
        String url=web.getUrl();
        android.net.Uri uri=url==null?android.net.Uri.EMPTY:android.net.Uri.parse(url);
        if (!NwpuImportActivity.navigationAllowed(uri) || !"uis.nwpu.edu.cn".equals(uri.getHost()) || !"/cas/login".equals(uri.getPath())) { needsUser("login"); return; }
        probing=true;
        web.evaluateJavascript(loginJs+"\nJSON.stringify(NwpuLogin.inspect())", value -> {
            if (stopped || active!=generation) return;
            probing=false;
            if (!url.equals(web.getUrl())) { schedule(500); return; }
            try {
                Object raw=new JSONTokener(value).nextValue();
                JSONObject result=raw instanceof String?new JSONObject((String)raw):new JSONObject();
                String phase=result.optString("phase");
                if ("challenge".equals(phase)) { needsUser("challenge"); return; }
                if ("error".equals(phase)) { needsUser("login"); return; }
                if ("ready".equals(phase)) {
                    if (loginAttempted) { needsUser("login"); return; }
                    loginAttempted=true;
                    web.evaluateJavascript("JSON.stringify(NwpuLogin.submit("+JSONObject.quote(login.account)+","+JSONObject.quote(login.password)+"))", ignored -> {
                        if (!stopped && active==generation) schedule(600);
                    });
                    return;
                }
                // A submitted form may wait for school authentication; never resubmit it.
                if (SystemClock.elapsedRealtime()>authDeadline) { needsUser("login"); return; }
            } catch (Exception e) { needsUser("login"); return; }
            schedule(600);
        });
    }

    /** 登录失败或安全验证：停下并提示用户，避免反复尝试凭据。 */
    private void needsUser(String kind) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ATTENTION, kind).apply();
        EngineHolder.diag(app, "跳过", "校园后台更新：" + ("login".equals(kind) ? "登录已过期" : "需要安全验证"));
        end(false, true);
    }

    private void end(boolean completed, boolean incomplete) {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (batch != null) { batch.stop(); batch = null; }
        if (web != null) { web.stopLoading(); if (stage != null) stage.removeView(web); web.destroy(); web = null; }
        if (stage != null && stage.getParent() instanceof ViewGroup) ((ViewGroup) stage.getParent()).removeView(stage);
        io.shutdown();
        if (!owner.isEmpty()) CampusAutoRefresh.finish(app, run, owner, completed, incomplete);
        CampusStore.signalChanged();
        if (current == this) current = null;
        if (completion != null) completion.run();
    }

    /** host 要销毁了：本轮作废，稍后自动重试。 */
    static void hostDestroyed(Activity a) {
        if (current != null && current.host == a) current.end(false, true);
    }
}
