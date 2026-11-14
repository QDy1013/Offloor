package com.nopalou;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONObject;
import org.json.JSONTokener;

/** School tokens stay in the official origin. No background booking or write retries. */
final class StudyRoomSession {
    interface Callback { void finished(JSONObject result, String error); }
    static final String HOME="https://ic.nwpu.edu.cn/";
    static final String LOGIN="https://uis.nwpu.edu.cn/cas/login?service=https%3A%2F%2Fic.nwpu.edu.cn%2Fauthcenter%2FdoAuth%2F38441982db3d4e6fb14591063ee594a9";
    private final Activity host;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private FrameLayout stage;
    private WebView web;
    private String script,loginScript,account,owner;
    private boolean stopped,busy,submitted,ready,writing;
    boolean isReady() { return ready && !stopped; }
    private int generation,connectToken;
    private Callback pending;
    private long deadline,spaWaitUntil;
    private android.app.Dialog official;
    StudyRoomSession(Activity host) { this.host=host; }
    static boolean allowed(String value) {
        try {
            Uri u=Uri.parse(value);
            String h=u.getHost();
            return "https".equals(u.getScheme()) && u.getUserInfo()==null && (u.getPort()==-1 || u.getPort()==443)
                && h!=null && (h.equals("nwpu.edu.cn") || h.endsWith(".nwpu.edu.cn"));
        } catch (Exception e) { return false; }
    }
    private boolean validAccount() {
        CampusCredentials.Login login=CampusCredentials.load(host);
        return login!=null && account.equals(login.account) && owner.equals(login.owner);
    }
    void start(CampusData data,Callback callback) {
        CampusCredentials.Login login=CampusCredentials.load(host);
        if (login==null || data==null || !data.owner.equals(login.owner)) { callback.finished(null,"请先在校园页连接校园账号"); return; }
        account=login.account; owner=login.owner; pending=callback; busy=true;
        try {
            script=CampusSilentRefresh.asset(host,"nwpu-study-room.js");
            loginScript=CampusSilentRefresh.asset(host,"nwpu-login.js");
            stage=new FrameLayout(host) { @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { return false; } };
            stage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ((ViewGroup)host.getWindow().getDecorView()).addView(stage,0,new FrameLayout.LayoutParams(-1,-1));
            web=new WebView(host); stage.addView(web,new FrameLayout.LayoutParams(-1,-1));
            android.webkit.WebSettings ws=web.getSettings();
            ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false); ws.setAllowContentAccess(false);
            ws.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            ws.setUseWideViewPort(true); ws.setLoadWithOverviewMode(true);
            ws.setSupportZoom(true); ws.setBuiltInZoomControls(true); ws.setDisplayZoomControls(false);
            android.webkit.CookieManager.getInstance().setAcceptCookie(true);
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v,android.webkit.WebResourceRequest r) { return !allowed(r.getUrl().toString()); }
                @Override public boolean shouldOverrideUrlLoading(WebView v,String url) { return !allowed(url); }
                @Override public void onPageFinished(WebView v,String url) {
                    android.webkit.CookieManager.getInstance().flush();
                    if (!stopped && !ready && official==null && pending!=null) { ++connectToken; handler.removeCallbacks(connect); handler.post(connect); }
                }
                @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler h,android.net.http.SslError e) { h.cancel(); fail("校方连接证书校验失败"); }
                @Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest r,android.webkit.WebResourceError e) { if (r.isForMainFrame()) fail("校方系统连接失败，请重新连接"); }
            });
            deadline=SystemClock.elapsedRealtime()+25_000; spaWaitUntil=0;
            web.loadUrl(HOME); handler.postDelayed(connect,200);
        } catch (Exception e) { fail("研修室会话暂时无法启动"); }
    }
    private final Runnable connect=new Runnable() { public void run() {
        if (stopped || ready || web==null) return;
        final int token=connectToken;
        if (!validAccount()) { fail("校园账号已变化，请重新连接"); return; }
        if (SystemClock.elapsedRealtime()>deadline) { fail("连接尚未完成；请打开校方页面完成验证后重新连接"); return; }
        String url=web.getUrl();
        if (!allowed(url)) { handler.postDelayed(this,200); return; }
        if ("uis.nwpu.edu.cn".equals(Uri.parse(url).getHost())) {
            web.evaluateJavascript(loginScript+";JSON.stringify(NwpuLogin.inspect())",value -> {
                if (stopped || token!=connectToken) return;
                if (!url.equals(web.getUrl())) { handler.postDelayed(connect,200); return; }
                String phase=parse(value).optString("phase");
                if ("challenge".equals(phase)) { fail("校方要求安全验证，请打开校方页面完成验证后重新连接"); return; }
                if ("error".equals(phase)) { fail("校园登录未完成，请重新连接校园账号"); return; }
                if ("ready".equals(phase) && !submitted) {
                    CampusCredentials.Login login=CampusCredentials.load(host);
                    if (!validAccount() || login==null) { fail("校园账号已变化"); return; }
                    submitted=true;
                    web.evaluateJavascript("NwpuLogin.submit("+JSONObject.quote(login.account)+","+JSONObject.quote(login.password)+")",null);
                }
                handler.postDelayed(connect,200);
            });
        } else {
            String path=Uri.parse(url).getPath();
            if (path!=null && path.startsWith("/authcenter/")) { spaWaitUntil=0; handler.postDelayed(connect,200); return; }
            if (spaWaitUntil==0) spaWaitUntil=SystemClock.elapsedRealtime()+6_000;
            web.evaluateJavascript("(function(){try{var u=JSON.parse(sessionStorage.getItem('userInfo')||'{}');return u&&u.token?'ready':'wait';}catch(e){return 'wait';}})()",tokenState -> {
                if (stopped || token!=connectToken) return;
                if (!url.equals(web.getUrl())) { spaWaitUntil=0; handler.postDelayed(connect,200); return; }
                String state=parseString(tokenState);
                if (!"ready".equals(state) && SystemClock.elapsedRealtime()<spaWaitUntil) { handler.postDelayed(connect,200); return; }
                web.evaluateJavascript(script+";if(!window.__louStudyReady && !window.__louStudyConnecting){window.__louStudyConnecting=true;NwpuStudyRoom.run('ready',{},"+JSONObject.quote(account)+").then(function(r){window.__louStudyReady=r;window.__louStudyConnecting=false;});}JSON.stringify(window.__louStudyReady)",value -> {
                    if (stopped || token!=connectToken) return;
                    if (!url.equals(web.getUrl())) { handler.postDelayed(connect,200); return; }
                    JSONObject result=parse(value);
                    if (result.optBoolean("ready")) { ready=true; resolve(new JSONObject(),""); }
                    else if (!result.optString("error").isEmpty()) fail(result.optString("error"));
                    else handler.postDelayed(connect,200);
                });
            });
        }
    }};
    void run(String method,JSONObject args,Callback callback) {
        if (stopped || !ready || web==null || !allowed(web.getUrl()) || !HOME.equals(origin(web.getUrl())) || !validAccount()) { ready=false; callback.finished(null,"会话已失效，请重新连接"); return; }
        if (busy) { callback.finished(null,"上一请求尚未完成，请稍候"); return; }
        if (!java.util.Arrays.asList("query","mine","members","captcha","submit","cancel").contains(method)) { callback.finished(null,"不支持的操作"); return; }
        busy=true; pending=callback; int token=++generation;
        boolean write="submit".equals(method) || "cancel".equals(method); writing=write;
        String url=web.getUrl();
        web.evaluateJavascript("window.__louStudyResult=null;NwpuStudyRoom.run("+JSONObject.quote(method)+","+args.toString()+","+JSONObject.quote(account)+").then(function(r){window.__louStudyResult={result:r};});",null);
        long until=SystemClock.elapsedRealtime()+40_000;
        Runnable poll=new Runnable() { public void run() {
            if (stopped || token!=generation) return;
            if (!validAccount() || !url.equals(web.getUrl())) { ready=false; resolve(null,write?"请求结果未确认，请先重新连接并查询我的预约，勿重复操作":"会话发生变化，请重新连接"); return; }
            web.evaluateJavascript("JSON.stringify(window.__louStudyResult)",value -> {
                if (stopped || token!=generation) return;
                JSONObject result=parse(value);
                if (result.has("result")) {
                    JSONObject payload=result.optJSONObject("result");
                    resolve(payload,payload==null?"校方返回格式无法识别":payload.optString("error"));
                } else if (SystemClock.elapsedRealtime()>until) {
                    ready=false; ++generation;
                    resolve(null,write?"请求结果未确认，请重新连接并先查询我的预约，勿重复操作":"请求超时，请重新连接");
                } else handler.postDelayed(this,150);
            });
        }};
        handler.postDelayed(poll,100);
    }
    private static String origin(String url) { try { Uri u=Uri.parse(url); return u.getScheme()+"://"+u.getAuthority()+"/"; } catch (Exception e) { return ""; } }
    private static String parseString(String value) {
        try { Object raw=new JSONTokener(value).nextValue(); return raw instanceof String?(String)raw:""; }
        catch (Exception e) { return ""; }
    }
    private static JSONObject parse(String value) {
        try { Object raw=new JSONTokener(value).nextValue(); return raw instanceof String?new JSONObject((String)raw):new JSONObject(); }
        catch (Exception e) { return new JSONObject(); }
    }
    private void resolve(JSONObject result,String error) {
        Callback callback=pending; pending=null; busy=false; writing=false;
        if (callback!=null && !host.isFinishing() && !host.isDestroyed()) callback.finished(result,error);
    }
    private void fail(String error) {
        if (writing) error="请求结果未确认，请重新连接并先查询我的预约，勿重复操作";
        ready=false; ++generation; handler.removeCallbacksAndMessages(null); resolve(null,error);
    }
    void reconnect(CampusData data,Callback callback) {
        if (stopped || web==null) { start(data,callback); return; }
        CampusCredentials.Login login=CampusCredentials.load(host);
        if (login==null || data==null || !data.owner.equals(login.owner)) { callback.finished(null,"请先在校园页连接校园账号"); return; }
        account=login.account; owner=login.owner; pending=callback; busy=true; ready=false; submitted=false; writing=false;
        handler.removeCallbacksAndMessages(null);
        deadline=SystemClock.elapsedRealtime()+25_000; spaWaitUntil=0;
        web.evaluateJavascript("window.__louStudyReady=null;window.__louStudyConnecting=false;",null);
        String url=web.getUrl();
        String path=url==null?null:Uri.parse(url).getPath();
        if (!allowed(url) || (path!=null && path.startsWith("/authcenter/"))) web.loadUrl(HOME);
        handler.postDelayed(connect,150);
    }
    void openOfficial(Runnable returned) {
        if (web==null || official!=null || stopped) return;
        busy=false; ready=false; pending=null; ++generation; handler.removeCallbacksAndMessages(null);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup)web.getParent()).removeView(web);
        String url=web.getUrl();
        String path=url==null?null:Uri.parse(url).getPath();
        if (!allowed(url) || (path!=null && path.startsWith("/authcenter/"))) web.loadUrl(HOME);
        int theme=Ui.dark?android.R.style.Theme_DeviceDefault_NoActionBar:android.R.style.Theme_DeviceDefault_Light_NoActionBar;
        android.app.Dialog dialog=new android.app.Dialog(host,theme);
        android.widget.LinearLayout root=CourseUi.column(host);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        android.widget.LinearLayout bar=new android.widget.LinearLayout(host);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(host,12),Ui.dp(host,8),Ui.dp(host,12),Ui.dp(host,8));
        bar.addView(CourseUi.action(host,"返回研修室并重新连接",true,dialog::dismiss),new android.widget.LinearLayout.LayoutParams(-1,Ui.dp(host,44)));
        root.addView(bar,new android.widget.LinearLayout.LayoutParams(-1,-2));
        root.addView(Ui.hairline(host),new android.widget.LinearLayout.LayoutParams(-1,1));
        root.addView(web,new android.widget.LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(root,new ViewGroup.LayoutParams(-1,-1)); official=dialog;
        dialog.setOnDismissListener(d -> {
            official=null;
            if (web!=null) { if (web.getParent() instanceof ViewGroup) ((ViewGroup)web.getParent()).removeView(web); stage.addView(web,new FrameLayout.LayoutParams(-1,-1)); }
            if (!stopped) returned.run();
        });
        dialog.show(); dialog.getWindow().setLayout(-1,-1);
    }
    void stop() {
        if (stopped) return; stopped=true; ++generation; pending=null;
        handler.removeCallbacksAndMessages(null);
        if (official!=null) official.dismiss();
        if (web!=null) { web.stopLoading(); if (web.getParent() instanceof ViewGroup) ((ViewGroup)web.getParent()).removeView(web); web.destroy(); web=null; }
        if (stage!=null && stage.getParent() instanceof ViewGroup) ((ViewGroup)stage.getParent()).removeView(stage);
    }
}
