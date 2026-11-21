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
            web.getSettings().setBlockNetworkImage(true);
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
    interface ScanListener { void finished(JSONArray results,JSONArray extraTickets); }
    private interface WebDialogLoader { void load(WebView web,android.widget.TextView status,android.app.Dialog dialog,PayWebSupport.AllowStay stayRule,Runnable onExternal); }
    private int operation, scanToken;
    private boolean busy;
    void ticket(String method,JSONObject trip,String targetDate,String expectedFee,boolean agreed,TicketListener callback) {
        if (stopped || busy || web==null || web.getUrl()==null || !web.getUrl().startsWith(CampusServices.BUS_RESERVATION)) { callback.finished(null,"会话已失效，请刷新班次后重试"); return; }
        CampusCredentials.Login login=CampusCredentials.load(host);
        if (login==null || !account.equals(login.account)) { callback.finished(null,"校园账号已变化，请重新查询"); return; }
        if (!java.util.Arrays.asList("quote","submit","prepare").contains(method)) return;
        String queryDate=(targetDate==null || targetDate.isEmpty())?this.date:targetDate;
        int token=++operation; busy=true;
        String args=trip.toString()+","+JSONObject.quote(queryDate)+","+JSONObject.quote(account);
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

    /** 在同一已登录的校车会话内后台汇总我的预约订单（GetMyAppointment + 按需班次日期匹配），不阻塞购票交互。 */
    void scanOrders(String[] dates,String currentDate,JSONArray currentTrips,ScanListener callback) {
        if (stopped || web==null || web.getUrl()==null || !web.getUrl().startsWith(CampusServices.BUS_RESERVATION) || dates==null || dates.length==0) {
            callback.finished(new JSONArray(),new JSONArray()); return;
        }
        CampusCredentials.Login login=CampusCredentials.load(host);
        if (login==null || account==null || !account.equals(login.account)) { callback.finished(new JSONArray(),new JSONArray()); return; }
        int token=++scanToken;
        JSONArray dateArr=new JSONArray();
        for (String d:dates) dateArr.put(d);
        String tripsLiteral=currentTrips==null?"null":currentTrips.toString();
        String js=script+";window.__louBusScan=null;NwpuBus.scanDates("+dateArr.toString()+","+JSONObject.quote(currentDate==null?"":currentDate)+","+tripsLiteral+","+JSONObject.quote(account)+")"
                +".then(function(r){window.__louBusScan=r;}).catch(function(){window.__louBusScan={results:[],extraTickets:[]};});";
        web.evaluateJavascript(js,null);
        long until=android.os.SystemClock.elapsedRealtime()+25_000;
        Runnable poll=new Runnable() { public void run() {
            if (stopped || token!=scanToken) return;
            web.evaluateJavascript("JSON.stringify(window.__louBusScan)",value -> {
                if (stopped || token!=scanToken) return;
                JSONObject result=parse(value);
                if (result.has("results")) {
                    JSONArray arr=result.optJSONArray("results");
                    JSONArray extra=result.optJSONArray("extraTickets");
                    callback.finished(arr==null?new JSONArray():arr,extra==null?new JSONArray():extra);
                } else if (android.os.SystemClock.elapsedRealtime()>until) {
                    callback.finished(new JSONArray(),new JSONArray());
                } else {
                    handler.postDelayed(this,400);
                }
            });
        }};
        handler.postDelayed(poll,350);
    }

    boolean inCashier() {
        return cashierDialog != null && cashierDialog.isShowing();
    }

    void openPortal(String expectedOwner,Runnable onClosed) {
        if (host.isFinishing() || host.isDestroyed() || cashierDialog!=null) return;
        showWebDialog("校方订单与乘车码","关闭并更新","","",
                true,true,onClosed,(pay,payStatus,dialog,stayRule,onExternal) -> {
                    payStatus.setText("正在通过翱翔门户进入校车系统…");
                    final String loginJs, campusJs, ssoJs;
                    try {
                        loginJs=CampusSilentRefresh.asset(host,"nwpu-login.js");
                        campusJs=CampusSilentRefresh.asset(host,"nwpu-campus.js");
                        ssoJs=CampusSilentRefresh.asset(host,"nwpu-sso.js");
                    } catch (Exception e) {
                        pay.loadUrl(CampusServices.PORTAL_SSO);
                        return;
                    }
                    final boolean[] loginSubmitted={false}, busClicked={false}, catalogClicked={false}, doneRouting={false};
                    final long until=android.os.SystemClock.elapsedRealtime()+22_000;
                    final Runnable[] step=new Runnable[1];
                    step[0]=() -> {
                        if (doneRouting[0] || !dialog.isShowing() || host.isFinishing() || host.isDestroyed()) return;
                        if (android.os.SystemClock.elapsedRealtime()>until) {
                            doneRouting[0]=true;
                            payStatus.setText("请在门户内点击「校车预约」查看；若无入口可使用微信官方渠道查看乘车码");
                            return;
                        }
                        String u=pay.getUrl();
                        String h=u==null?"":Uri.parse(u).getHost();
                        if ("hq-bus.nwpu.edu.cn".equals(h)) {
                            doneRouting[0]=true;
                            payStatus.setText("已进入校车官方系统，完成后点击右上角「关闭并更新」");
                            return;
                        }
                        if ("uis.nwpu.edu.cn".equals(h)) {
                            pay.evaluateJavascript(loginJs+";JSON.stringify(NwpuLogin.inspect())",val -> {
                                if (doneRouting[0] || !dialog.isShowing()) return;
                                String phase=parse(val).optString("phase");
                                if ("challenge".equals(phase)) {
                                    doneRouting[0]=true; payStatus.setText("请在下方完成安全验证后继续进入校车系统"); return;
                                }
                                if ("error".equals(phase)) {
                                    doneRouting[0]=true; payStatus.setText("请在下方核对账号密码并完成登录"); return;
                                }
                                if ("ready".equals(phase) && !loginSubmitted[0]) {
                                    CampusCredentials.Login login=CampusCredentials.load(host);
                                    if (login!=null && expectedOwner!=null && expectedOwner.equals(login.owner)) {
                                        loginSubmitted[0]=true;
                                        payStatus.setText("正在验证翱翔门户登录态…");
                                        pay.evaluateJavascript("NwpuLogin.submit("+JSONObject.quote(login.account)+","+JSONObject.quote(login.password)+")",null);
                                    } else {
                                        doneRouting[0]=true; payStatus.setText("请在下方登录翱翔门户后点击校车进入"); return;
                                    }
                                }
                                handler.postDelayed(step[0],700);
                            });
                            return;
                        }
                        if ("ecampus.nwpu.edu.cn".equals(h)) {
                            String probe="(function(){var ins=NwpuSso.inspect();var out={phase:ins.phase,route:(ins.routes&&ins.routes.bus)||''};"
                                    +"if(out.route && out.route.indexOf('hq-bus.nwpu.edu.cn')!==-1){return JSON.stringify(out);}"
                                    +"out.clicked=NwpuSso.click('bus','bus-portal');"
                                    +"if(!out.clicked && out.phase==='ready'){"
                                    +"var hit=Array.from(document.querySelectorAll('a,button,[role=\"button\"],.app-item,.application-item,.service-item')).find(function(e){"
                                    +"if(!e.getClientRects||!e.getClientRects().length||e.closest('header,footer,nav'))return false;"
                                    +"var t=(e.innerText||e.textContent||e.getAttribute('title')||'').replace(/\\s+/g,' ').trim();"
                                    +"var href=(e.getAttribute('href')||e.getAttribute('data-url')||'');"
                                    +"return t.length<=20&&/(?:校车|通勤车|班车)/.test(t)&&!/(?:通知|公告|时刻表|调整)/.test(t)&&!/xygg\\.nwpu\\.edu\\.cn/.test(href);});"
                                    +"if(hit){out.clicked=NwpuSso.activateElement(hit);}"
                                    +"}"
                                    +"return JSON.stringify(out);})()";
                            pay.evaluateJavascript(campusJs+";"+ssoJs+";"+probe,val -> {
                                if (doneRouting[0] || !dialog.isShowing()) return;
                                JSONObject res=parse(val);
                                String direct=res.optString("route");
                                if (!direct.isEmpty() && CampusServices.allowed(direct)) {
                                    busClicked[0]=true;
                                    payStatus.setText("正在跳转到校车系统…");
                                    pay.loadUrl(direct);
                                } else if (res.optBoolean("clicked")) {
                                    busClicked[0]=true;
                                    payStatus.setText("正在跳转到校车系统…");
                                } else if (!busClicked[0] && !catalogClicked[0] && "ready".equals(res.optString("phase"))) {
                                    catalogClicked[0]=true;
                                    payStatus.setText("正在门户应用中查找校车入口…");
                                    pay.evaluateJavascript("NwpuSso.click('catalog','bus-portal-cat')",null);
                                }
                                handler.postDelayed(step[0],700);
                            });
                            return;
                        }
                        handler.postDelayed(step[0],700);
                    };
                    pay.loadUrl(CampusServices.PORTAL_SSO);
                    handler.postDelayed(step[0],900);
                });
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
                if (result.has("html") || result.has("url")) {
                    busy=false;
                    showCashierDialog(result,returned);
                } else if (result.optBoolean("error") || android.os.SystemClock.elapsedRealtime()>until) {
                    busy=false; callback.finished(null,"收银台暂时无法打开，请查询订单后继续支付");
                } else handler.postDelayed(this,500);
            });
        }}; handler.postDelayed(poll,300);
    }

    private void showCashierDialog(JSONObject result,Runnable returned) {
        if (stopped) return;
        showWebDialog("校方收银台","查支付结果","正在打开校方收银台…",
                "请在下方收银台完成支付，完成后点击右上角「查支付结果」",
                true,false,returned,(pay,payStatus,dialog,stayHttps,onExternal) -> {
                    String payUrl=result.optString("url");
                    if (!payUrl.isEmpty()) {
                        if (CampusPay.route(payUrl,true)==CampusPay.Route.STAY) {
                            java.util.Map<String,String> extra=new java.util.HashMap<>();
                            extra.put("Referer",CampusServices.BUS_RESERVATION);
                            pay.loadUrl(payUrl,extra);
                        } else {
                            PayWebSupport.shouldOverrideUrl(host,pay,payUrl,null,stayHttps,CampusServices.BUS_RESERVATION,onExternal);
                        }
                    } else {
                        pay.loadDataWithBaseURL(CampusServices.BUS_RESERVATION,result.optString("html"),"text/html","UTF-8",null);
                    }
                });
    }

    private void showWebDialog(String titleText,String actionText,String initialStatus,String readyStatus,
            boolean allowExternalHttps,boolean allowAfterStop,Runnable onClosed,WebDialogLoader loader) {
        if ((!allowAfterStop && stopped) || host.isFinishing() || host.isDestroyed() || cashierDialog!=null) return;
        Ui.apply(host);
        android.app.Dialog dialog=new android.app.Dialog(host,android.R.style.Theme_Material_Light_NoActionBar);
        android.widget.LinearLayout root=CourseUi.column(host);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        android.widget.LinearLayout bar=new android.widget.LinearLayout(host);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(host,4),Ui.dp(host,6),Ui.dp(host,12),Ui.dp(host,6));
        bar.addView(CourseUi.icon(host,"prev",actionText,dialog::dismiss));
        bar.addView(CourseUi.text(host,titleText,18,Ui.TEXT,true),new android.widget.LinearLayout.LayoutParams(0,-2,1));
        android.widget.TextView checkBtn=CourseUi.text(host,actionText,13,Ui.ACCENT,true);
        checkBtn.setPadding(Ui.dp(host,12),Ui.dp(host,6),Ui.dp(host,12),Ui.dp(host,6));
        checkBtn.setBackground(Ui.ripple(host,Ui.round(host,Ui.ACCENT_BG,99),Ui.press(),99));
        checkBtn.setOnClickListener(v -> dialog.dismiss());
        bar.addView(checkBtn);
        root.addView(bar);

        android.widget.TextView payStatus=CourseUi.text(host,initialStatus,12,Ui.SUB,false);
        payStatus.setPadding(Ui.dp(host,16),0,Ui.dp(host,16),Ui.dp(host,8));
        root.addView(payStatus);

        FrameLayout payStage=new FrameLayout(host);
        root.addView(payStage,new android.widget.LinearLayout.LayoutParams(-1,0,1));
        WebView pay=new WebView(host);
        payStage.addView(pay,new FrameLayout.LayoutParams(-1,-1));
        android.widget.ProgressBar loading=new android.widget.ProgressBar(host);
        FrameLayout.LayoutParams loadingParams=new FrameLayout.LayoutParams(Ui.dp(host,36),Ui.dp(host,36));
        loadingParams.gravity=android.view.Gravity.CENTER;
        payStage.addView(loading,loadingParams);

        PayWebSupport.configureSettings(pay);
        PayWebSupport.AllowStay stayRule=target -> allowExternalHttps
                ? "https".equals(CampusPay.scheme(target))
                : CampusServices.allowed(target);
        final String[] pageErr={""};
        Runnable onExternal=() -> {
            loading.setVisibility(View.GONE);
            payStatus.setText("已拉起支付或外部应用，完成后请返回点击「"+actionText+"」");
        };
        pay.setWebChromeClient(PayWebSupport.chromeClient(host,pay,stayRule,CampusServices.BUS_RESERVATION,onExternal,progress -> {
            if (progress>=95 && loading.getVisibility()==View.VISIBLE && pageErr[0].isEmpty()) loading.setVisibility(View.GONE);
        }));
        pay.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v,android.webkit.WebResourceRequest r) {
                return PayWebSupport.shouldOverride(host,v,r,stayRule,CampusServices.BUS_RESERVATION,onExternal);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url) {
                return PayWebSupport.shouldOverrideUrl(host,v,url,null,stayRule,CampusServices.BUS_RESERVATION,onExternal);
            }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon) {
                pageErr[0]=""; loading.setVisibility(View.VISIBLE);
                if (!initialStatus.isEmpty()) payStatus.setText(initialStatus);
            }
            @Override public void onPageFinished(WebView v,String url) {
                loading.setVisibility(View.GONE);
                android.webkit.CookieManager.getInstance().flush();
                if (pageErr[0].isEmpty() && !readyStatus.isEmpty()) payStatus.setText(readyStatus);
            }
            @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler h,android.net.http.SslError e) {
                h.cancel(); loading.setVisibility(View.GONE);
                pageErr[0]="校方页面证书校验未通过，已停止加载";
                payStatus.setText(pageErr[0]);
            }
            @Override public void onReceivedHttpError(WebView v,android.webkit.WebResourceRequest r,android.webkit.WebResourceResponse e) {
                if (r==null || !r.isForMainFrame()) return;
                int code=e==null?0:e.getStatusCode();
                if (code>=400) {
                    loading.setVisibility(View.GONE);
                    pageErr[0]="页面返回错误（HTTP "+code+"），请稍后返回重试";
                    payStatus.setText(pageErr[0]);
                }
            }
            @Override public void onReceivedError(WebView v,android.webkit.WebResourceRequest r,android.webkit.WebResourceError e) {
                if (r==null || !r.isForMainFrame()) return;
                loading.setVisibility(View.GONE);
                pageErr[0]="网络连接失败，请确认校园网后重试";
                payStatus.setText(pageErr[0]);
            }
        });

        dialog.setContentView(root);
        dialog.setOnKeyListener((d,keyCode,event) -> {
            if (keyCode==android.view.KeyEvent.KEYCODE_BACK && event.getAction()==android.view.KeyEvent.ACTION_UP && pay.canGoBack()) {
                pay.goBack(); return true;
            }
            return false;
        });
        final boolean[] dismissingByStop={false};
        dialog.setOnDismissListener(d -> {
            cashierDialog=null;
            payStage.removeView(pay);
            pay.stopLoading(); pay.destroy();
            if (!dismissingByStop[0] && (allowAfterStop || !stopped) && !host.isFinishing() && !host.isDestroyed() && onClosed!=null) {
                onClosed.run();
            }
        });
        dialog.setOnCancelListener(null);
        cashierDialog=dialog;
        this.dismissFromStop=() -> dismissingByStop[0]=true;
        dialog.show();
        if (dialog.getWindow()!=null) {
            dialog.getWindow().setLayout(-1,-1);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setStatusBarColor(Ui.BG);
            dialog.getWindow().setNavigationBarColor(Ui.BG);
            if (!Ui.dark) root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT>=26?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        }

        loader.load(pay,payStatus,dialog,stayRule,onExternal);
    }
    private Runnable dismissFromStop;
    private void schedule() { if (!stopped) handler.postDelayed(tick,800); }
    private static JSONObject parse(String value) {
        try { Object raw=new JSONTokener(value).nextValue(); return new JSONObject(raw instanceof String?(String)raw:"{}"); }
        catch (Exception e) { return new JSONObject(); }
    }
    private void finish(JSONArray trips,String error) {
        if (stopped) return;
        stopBackground(); if (!host.isFinishing() && !host.isDestroyed()) listener.finished(trips,error);
    }
    private void stopBackground() {
        handler.removeCallbacksAndMessages(null);
        if (stopped) return;
        stopped=true;
        if (stage!=null) {
            if (web!=null) stage.removeView(web);
            if (stage.getParent() instanceof ViewGroup) ((ViewGroup)stage.getParent()).removeView(stage);
        }
        if (web!=null) { web.stopLoading(); web.destroy(); web=null; }
    }
    void stop() {
        if (dismissFromStop!=null) { dismissFromStop.run(); dismissFromStop=null; }
        if (cashierDialog!=null) { cashierDialog.dismiss(); cashierDialog=null; }
        stopBackground();
    }
}
