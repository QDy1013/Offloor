package com.nopalou;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** The school browser runs behind the native page; only read-only trip data leaves it. */
final class CampusBusRefresh {
    interface Listener { void finished(JSONArray trips,String error); }
    private final Activity host;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WebView web;
    private android.app.Dialog cashierDialog;
    private FrameLayout stage;
    private boolean stopped,submitted,portalOpened,portalClicked;
    private String script,loginScript,campusScript,ssoScript,date,account;
    private long deadline;
    CampusBusRefresh(Activity host,Listener listener) { this.host=host; this.listener=listener; }
    void start(CampusData data,String date) {
        CampusCredentials.Login login=CampusCredentials.load(host);
        if (data==null || login==null || !data.owner.equals(login.owner)) { finish(null,"请先连接校园账号，再查询实时余位"); return; }
        this.account=login.account; this.date=date;
        try {
            script=CampusSilentRefresh.asset(host,"nwpu-bus.js");
            loginScript=CampusSilentRefresh.asset(host,"nwpu-login.js");
            campusScript=CampusSilentRefresh.asset(host,"nwpu-campus.js");
            ssoScript=CampusSilentRefresh.asset(host,"nwpu-sso.js");
            stage=new FrameLayout(host) { @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { return false; } }; stage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            stage.setFocusable(false);
            ((ViewGroup)host.getWindow().getDecorView()).addView(stage,0,new FrameLayout.LayoutParams(-1,-1));
            web=new WebView(host); stage.addView(web,new FrameLayout.LayoutParams(-1,-1));
            web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
            web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v,android.webkit.WebResourceRequest r) { return !CampusServices.allowed(r.getUrl().toString()); }
                @Override public boolean shouldOverrideUrlLoading(WebView v,String url) { return !CampusServices.allowed(url); }
                @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler h,android.net.http.SslError e) { h.cancel(); finish(null,"校方连接证书校验失败，请稍后重试"); }
                @Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest r,android.webkit.WebResourceError e) { if (r.isForMainFrame()) finish(null,"校车系统连接失败，请稍后重试"); }
            });
            deadline=android.os.SystemClock.elapsedRealtime()+60_000;
            web.loadUrl(CampusServices.BUS_RESERVATION); handler.postDelayed(tick,800);
        } catch (Exception e) { finish(null,"实时校车查询暂时无法启动"); }
    }
    private final Runnable tick=this::inspect;
    private void inspect() {
        if (stopped) return;
        if (android.os.SystemClock.elapsedRealtime()>deadline) { finish(null,"本次未读到实时人数，请稍后重试"); return; }
        String url=web.getUrl();
        if (!CampusServices.allowed(url)) { schedule(); return; }
        String hostName=Uri.parse(url).getHost();
        if ("hq-bus.nwpu.edu.cn".equals(hostName)) {
            web.evaluateJavascript(script+";NwpuBus.start("+JSONObject.quote(date)+","+JSONObject.quote(account)+");JSON.stringify(window.__louBus)", value -> {
                if (stopped) return;
                if (!url.equals(web.getUrl())) { schedule(); return; }
                JSONObject result=parse(value); String phase=result.optString("phase");
                if ("ready".equals(phase)) {
                    handler.removeCallbacksAndMessages(null);
                    listener.finished(result.optJSONArray("items"),"");
                }
                else if ("error".equals(phase)) {
                    if (!portalOpened) { portalOpened=true; web.loadUrl(CampusServices.PORTAL_SSO); schedule(); }
                    else finish(null,"校方暂未返回实时人数，请稍后重试或更新校园登录状态");
                } else schedule();
            });
        } else if ("uis.nwpu.edu.cn".equals(hostName)) {
            web.evaluateJavascript(loginScript+";JSON.stringify(NwpuLogin.inspect())", value -> {
                if (stopped) return;
                if (!url.equals(web.getUrl())) { schedule(); return; }
                String phase=parse(value).optString("phase");
                if ("challenge".equals(phase)) { finish(null,"学校要求安全验证，请在校园页完成验证后重试"); return; }
                if ("error".equals(phase)) { finish(null,"校园登录状态需要更新"); return; }
                if ("ready".equals(phase) && !submitted) {
                    CampusCredentials.Login login=CampusCredentials.load(host);
                    if (login==null || !account.equals(login.account)) { finish(null,"校园账号已变化，请重新查询"); return; }
                    submitted=true;
                    web.evaluateJavascript("NwpuLogin.submit("+JSONObject.quote(login.account)+","+JSONObject.quote(login.password)+")",null);
                }
                schedule();
            });
        } else if ("ecampus.nwpu.edu.cn".equals(hostName)) {
            web.evaluateJavascript(campusScript+";"+ssoScript+";JSON.stringify({phase:NwpuSso.inspect().phase,clicked:NwpuSso.click('bus','bus-live')})", value -> {
                if (stopped) return;
                JSONObject result=parse(value);
                if (result.optBoolean("clicked")) portalClicked=true;
                if (!portalClicked && "ready".equals(result.optString("phase"))) web.loadUrl(CampusServices.BUS_RESERVATION);
                schedule();
            });
        } else { schedule(); }
    }
    interface TicketListener { void finished(JSONObject result,String error); }
    private int operation;
    private boolean busy;
    void ticket(String method,JSONObject trip,String expectedFee,boolean agreed,TicketListener callback) {
        if (stopped || busy || web==null || web.getUrl()==null || !web.getUrl().startsWith(CampusServices.BUS_RESERVATION)) { callback.finished(null,"会话已失效，请刷新班次后重试"); return; }
        CampusCredentials.Login login=CampusCredentials.load(host);
        if (login==null || !account.equals(login.account)) { callback.finished(null,"校园账号已变化，请重新查询"); return; }
        if (!java.util.Arrays.asList("quote","submit","prepare").contains(method)) return;
        int token=++operation; busy=true;
        String args=trip.toString()+","+JSONObject.quote(date)+","+JSONObject.quote(account);
        if ("submit".equals(method)) args+=","+JSONObject.quote(expectedFee)+","+agreed;
        if ("prepare".equals(method)) args+=","+JSONObject.quote(expectedFee);
        String js="window.__louTicketResult=null;NwpuBus."+method+"("+args+").then(function(r){window.__louTicketResult={result:r}}).catch(function(){window.__louTicketResult={error:true}});";
        web.evaluateJavascript(js,null);
        long until=android.os.SystemClock.elapsedRealtime()+30_000;
        Runnable poll=new Runnable() { public void run() {
            if (stopped || token!=operation) return;
            web.evaluateJavascript("JSON.stringify(window.__louTicketResult)",value -> {
                if (stopped || token!=operation) return;
                JSONObject result=parse(value);
                if (result.has("result")) { busy=false; callback.finished(result.optJSONObject("result"),""); }
                else if (result.optBoolean("error") || android.os.SystemClock.elapsedRealtime()>until) {
                    busy=false; callback.finished(null,"请求未确认，请先查询订单状态，勿重复下单");
                } else handler.postDelayed(this,500);
            });
        }};
        handler.postDelayed(poll,300);
    }
    void openCashier(String order,String prepay,Runnable returned,TicketListener callback) {
        CampusCredentials.Login login=CampusCredentials.load(host);
        if (stopped || busy || web==null || login==null || !account.equals(login.account)) { callback.finished(null,"请先刷新订单状态"); return; }
        busy=true;
        String js="window.__louCashier=null;NwpuBus.cashier("+JSONObject.quote(account)+","+JSONObject.quote(order)+","+JSONObject.quote(prepay)+").then(function(r){window.__louCashier=r}).catch(function(){window.__louCashier={error:true}});";
        web.evaluateJavascript(js,null);
        long until=android.os.SystemClock.elapsedRealtime()+30_000;
        Runnable poll=new Runnable() { public void run() {
            if (stopped) return;
            web.evaluateJavascript("JSON.stringify(window.__louCashier)",value -> {
                if (stopped) return;
                JSONObject result=parse(value);
                if (result.has("html")) {
                    busy=false;
                    android.app.Dialog dialog=new android.app.Dialog(host,android.R.style.Theme_Material_Light_NoActionBar);
                    android.widget.LinearLayout root=new android.widget.LinearLayout(host); root.setOrientation(1);
                    android.widget.TextView close=new android.widget.TextView(host); close.setText("返回订单并查询支付结果"); close.setPadding(24,32,24,32); root.addView(close);
                    WebView pay=new WebView(host); root.addView(pay,new android.widget.LinearLayout.LayoutParams(-1,0,1));
                    pay.getSettings().setJavaScriptEnabled(true); pay.getSettings().setDomStorageEnabled(true);
                    pay.getSettings().setAllowFileAccess(false); pay.getSettings().setAllowContentAccess(false);
                    pay.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                    pay.setWebViewClient(new WebViewClient() {
                        @Override public boolean shouldOverrideUrlLoading(WebView v,android.webkit.WebResourceRequest r) { return !"https".equals(r.getUrl().getScheme()); }
                        @Override public boolean shouldOverrideUrlLoading(WebView v,String url) { return !"https".equals(Uri.parse(url).getScheme()); }
                    });
                    dialog.setContentView(root); close.setOnClickListener(v -> dialog.dismiss());
                    dialog.setOnDismissListener(d -> { cashierDialog=null; pay.stopLoading(); pay.destroy(); if (!stopped) returned.run(); });
                    cashierDialog=dialog;
                    dialog.show(); dialog.getWindow().setLayout(-1,-1);
                    pay.loadDataWithBaseURL(CampusServices.BUS_RESERVATION,result.optString("html"),"text/html","UTF-8",null);
                } else if (result.optBoolean("error") || android.os.SystemClock.elapsedRealtime()>until) {
                    busy=false; callback.finished(null,"收银台暂时无法打开，请查询订单后继续支付");
                } else handler.postDelayed(this,500);
            });
        }}; handler.postDelayed(poll,300);
    }
    private void schedule() { if (!stopped) handler.postDelayed(tick,800); }
    private static JSONObject parse(String value) {
        try { Object raw=new JSONTokener(value).nextValue(); return new JSONObject(raw instanceof String?(String)raw:"{}"); }
        catch (Exception e) { return new JSONObject(); }
    }
    private void finish(JSONArray trips,String error) {
        if (stopped) return;
        stop(); if (!host.isFinishing() && !host.isDestroyed()) listener.finished(trips,error);
    }
    void stop() {
        if (stopped) return; stopped=true; handler.removeCallbacksAndMessages(null);
        if (cashierDialog!=null) { cashierDialog.dismiss(); cashierDialog=null; }
        if (web!=null) { web.stopLoading(); web.destroy(); web=null; }
        if (stage!=null && stage.getParent() instanceof ViewGroup) ((ViewGroup)stage.getParent()).removeView(stage);
    }
}
