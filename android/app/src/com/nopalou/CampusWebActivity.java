package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.List;

/** One shared CAS login, first-party service handoffs, and bounded page extraction. */
public class CampusWebActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView title,status,primary;
    private String script,ssoScript,autoScript,owner="",lastSnapshot="",run;
    private View cover,actions;
    private CampusReadSession collection=new CampusReadSession();
    private CampusData saved;
    private CampusServices.Service service;
    private final List<CampusServices.Service> queue=new ArrayList<>();
    private int index,generation,success,unavailable;
    private boolean all,automatic,completed,writing,probing,electricBusy,electricRequested,electricRetry;
    private int restoredIndex=-1;
    private long due,authDeadline,bootstrapStarted;
    private boolean bootstrapping,resumeService,portalRetried,keepPortalRetry,catalogTried,portalActivated,centralExpired,authPaused,challengeActive;
    private final CampusAuth auth=new CampusAuth();
    private final java.util.Set<String> handoffs=new java.util.HashSet<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b); Ui.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try (java.io.InputStream in=getAssets().open("nwpu-campus.js"); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n; while ((n=in.read(buffer))!=-1) out.write(buffer,0,n); script=out.toString("UTF-8");
        } catch (Exception e) { toast("校园同步组件无法加载"); finish(); return; }
        try (java.io.InputStream in=getAssets().open("nwpu-sso.js"); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n; while ((n=in.read(buffer))!=-1) out.write(buffer,0,n); ssoScript=out.toString("UTF-8");
        } catch (Exception e) { toast("统一认证组件无法加载"); finish(); return; }
        try (java.io.InputStream in=getAssets().open("nwpu-auto.js"); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n; while ((n=in.read(buffer))!=-1) out.write(buffer,0,n); autoScript=out.toString("UTF-8");
        } catch (Exception e) { toast("自动读取组件无法加载"); finish(); return; }
        all=getIntent().getBooleanExtra("all",false); automatic=getIntent().getBooleanExtra("automatic",all);
        if (b!=null) { restoredIndex=b.getInt("index",0); success=b.getInt("success",0); unavailable=b.getInt("unavailable",0); }
        else if (getIntent().getBooleanExtra("academic_incomplete",false)) unavailable=1;
        build();
        if (all) {
            for (CampusServices.Service s:CampusServices.ALL) if (!s.id.equals("profile")) queue.add(s);
        } else {
            CampusServices.Service s=CampusServices.byId(getIntent().getStringExtra("module")); queue.add(s==null?CampusServices.ALL[0]:s);
        }
        io.execute(() -> {
            try { saved=CampusStore.load(getApplicationContext()); owner=saved==null?"":saved.owner; } catch (Exception ignored) { }
            runOnUiThread(() -> { if (!isDestroyed()) {
                if (automatic) {
                    if (owner.isEmpty()) { finish(); return; }
                    run=CampusAutoRefresh.attach(this,owner,getIntent().getStringExtra("run"));
                    if (run==null) { finish(); return; }
                    getIntent().putExtra("run",run);
                }
                service=queue.get(0);
                if (restoredIndex>=0) index=Math.min(restoredIndex,queue.size());
                if (automatic && b==null && !getIntent().getBooleanExtra("fresh_login",false)) {
                    authPaused=true;
                    startActivityForResult(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu").putExtra("reuse_session",true).putExtra("auth_only",true).putExtra("automatic_refresh",true),42); return;
                }
                if (all || (!service.id.equals("notices") && !service.id.equals("calendar"))) beginBootstrap(false); else loadNext();
            } });
        });
    }
    private int dp(float n) { return Ui.dp(this,n); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private TextView text(String s,float size,int color,boolean bold) { return CourseUi.text(this,s,size,color,bold); }
    private void build() {
        LinearLayout root=CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this,root);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(4),dp(4),dp(8),dp(4)); root.addView(bar);
        bar.addView(CourseUi.icon(this,"prev","返回校园",this::finish));
        title=text("校园服务",18,Ui.TEXT,true); bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        status=text("正在打开校方页面…",12,Ui.SUB,false); status.setPadding(dp(16),dp(8),dp(16),dp(12)); status.setLineSpacing(dp(3),1); root.addView(status);
        FrameLayout stage=new FrameLayout(this); root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        web=new WebView(this); stage.addView(web,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout waiting=CourseUi.column(this); waiting.setGravity(Gravity.CENTER); waiting.setBackgroundColor(Ui.BG); waiting.setClickable(true);
        waiting.addView(new ProgressBar(this),new LinearLayout.LayoutParams(dp(36),dp(36))); CourseUi.gap(waiting,24);
        waiting.addView(text("正在自动读取校园信息",20,Ui.TEXT,true)); CourseUi.gap(waiting,12);
        waiting.addView(text("无需逐项打开或点击同步",13,Ui.SUB,false)); cover=waiting; stage.addView(waiting,new FrameLayout.LayoutParams(-1,-1));
        cover.setVisibility(automatic?View.VISIBLE:View.GONE);
        WebSettings ws=web.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true); ws.setAllowFileAccess(false); ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); ws.setSupportZoom(true); ws.setBuiltInZoomControls(true); ws.setDisplayZoomControls(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false); CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r) { return !navigate(r.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url) { return !navigate(url); }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon) { probing=false; }
            @Override public void onPageFinished(WebView v,String url) { CookieManager.getInstance().flush(); if (service!=null) schedule(800); }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e) { h.cancel(); failure("network"); }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e) { if (r.isForMainFrame()) failure("network"); }
        });
        LinearLayout controls=new LinearLayout(this); controls.setPadding(dp(12),dp(10),dp(12),dp(12)); root.addView(controls); actions=controls;
        primary=CourseUi.action(this,"继续登录",true,this::primaryAction);
        controls.addView(primary,new LinearLayout.LayoutParams(-1,dp(48)));
        actions.setVisibility(View.GONE);
    }
    private boolean navigate(String url) {
        if (CampusServices.allowed(url)) return true;
        toast("此链接需要在校方指定的浏览器或应用中打开"); return false;
    }
    private void loadNext() {
        if (isFinishing() || isDestroyed()) return;
        generation++; probing=false; electricBusy=false; electricRequested=false; electricRetry=false; lastSnapshot=""; handler.removeCallbacksAndMessages(null);
        bootstrapping=false; centralExpired=false; challengeActive=false; portalActivated=false; catalogTried=false;
        if (!keepPortalRetry) { portalRetried=false; collection=new CampusReadSession(); } keepPortalRetry=false;
        actions.setVisibility(View.GONE); if (automatic) cover.setVisibility(View.VISIBLE);
        auth.reset(); handoffs.clear(); authDeadline=android.os.SystemClock.elapsedRealtime()+45_000;
        if (index>=queue.size()) {
            completed=true;
            CampusAutoRefresh.finish(this,run,owner,true,unavailable>0);
            toast("自动读取完成："+success+" 项已更新"+(unavailable>0?"，其余项目稍后自动重试":""));
            startActivity(new Intent(this,CampusActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish(); return;
        }
        service=queue.get(index); title.setText(service.title+(all?" · "+(index+1)+"/"+queue.size():""));
        status.setText(all?"正在通过统一认证进入各校园服务，无需重复输入账号密码。":"正在使用翱翔门户登录状态打开“"+service.title+"”，读到内容后会自动保存。");
        due=android.os.SystemClock.elapsedRealtime()+45_000;
        String initial=!all && index==0?getIntent().getStringExtra("url"):null;
        String entry=initial!=null && CampusServices.readUrl(initial)?initial:saved==null?service.entry:saved.route(service);
        web.loadUrl(entry); schedule(1200);
    }
    private void schedule(long delay) { handler.removeCallbacks(tick); if (!isFinishing() && !isDestroyed()) handler.postDelayed(tick,delay); }
    private final Runnable tick=() -> inspect(false);
    private JSONObject result(String value) throws Exception { Object raw=new JSONTokener(value).nextValue(); return raw instanceof String?new JSONObject((String)raw):new JSONObject(); }
    private void inspect(boolean manual) {
        if (automatic && !CampusAutoRefresh.owns(this,run,owner)) { finish(); return; }
        if (web==null || service==null || writing || probing || electricBusy || authPaused || isFinishing() || isDestroyed()) return;
        String url=web.getUrl(); if (url==null || !CampusServices.allowed(url)) { schedule(1200); return; }
        final int active=generation; probing=true;
        web.evaluateJavascript(script+"\n"+ssoScript+"\n"+autoScript+"\nNwpuAuto.install();JSON.stringify(NwpuSso.inspect())",value -> {
            if (active!=generation || isDestroyed()) return; probing=false;
            if (!url.equals(web.getUrl())) { schedule(600); return; }
            JSONObject r; try { r=result(value); } catch (Exception e) { timeout(); return; }
            if (!authenticationReady(r,active)) return;
            if (bootstrapping) { scanPortal(r,active); return; }
            if ("ecampus.nwpu.edu.cn".equals(Uri.parse(url).getHost()) && !service.id.equals("notices") && !service.id.equals("calendar")) {
                openPortalService(r,active); return;
            }
            readPage(manual,active,url);
        });
    }
    private void readPage(boolean manual,int active,String url) {
        if (service.id.equals("electric") && Uri.parse(url).getHost().equals("yktapp.nwpu.edu.cn") && !electricRequested) {
            electricRequested=true; String stored=getSharedPreferences("campus-binding",MODE_PRIVATE).getString("room","");
            if (!stored.isEmpty()) { try { queryElectric(3,new JSONObject(stored),false); return; } catch (Exception ignored) { } }
            if (all) { queryElectric(0,new JSONObject(),false); return; }
            if (getIntent().getBooleanExtra("bind",false)) { bindElectric(); return; }
        }
        final String id=service.id; probing=true;
        web.evaluateJavascript("JSON.stringify(NwpuAuto.inspect("+JSONObject.quote(id)+","+collection.visited()+"))",value -> {
            if (active!=generation || isDestroyed()) return; probing=false;
            if (!url.equals(web.getUrl())) { schedule(800); return; }
            JSONObject fresh,m,inspection;
            try { inspection=result(value); fresh=inspection.getJSONObject("snapshot"); m=fresh.getJSONObject("modules").getJSONObject(id); } catch (Exception e) { timeout(); return; }
            if (inspection.optBoolean("settling") || !collection.stable(m,inspection.optInt("pending"))) { timeout(); return; }
            String state=m.optString("state");
            if ("login".equals(state)) { if (!portalRetried) { portalRetried=true; beginBootstrap(true); } else failure("unavailable"); return; }
            if ("ok".equals(state) || "empty".equals(state)) { try { collection.add(m); } catch (Exception ignored) { } }
            // Follow a labelled school link once; never construct or enumerate private endpoints.
            JSONObject routes=fresh.optJSONObject("routes"); String route=routes==null?"":routes.optString(id);
            if (!route.isEmpty() && CampusServices.readUrl(route) && !route.equals(url) && CampusData.count(m)==0 && collection.visit("page:"+route)) {
                if (saved==null || !route.equals(saved.routes.optString(id))) saveRoutes(fresh.toString(),active);
                web.loadUrl(route); schedule(1000); return;
            }
            JSONArray candidates=inspection.optJSONArray("actions");
            if (candidates!=null) for (int i=0;i<candidates.length();i++) {
                JSONObject action=candidates.optJSONObject(i); if (action==null || !collection.visit(action.optString("key"))) continue;
                status.setText("正在自动读取“"+service.title+"” · "+action.optString("label"));
                web.evaluateJavascript("NwpuAuto.act("+JSONObject.quote(id)+","+JSONObject.quote(action.optString("key"))+")",ignored -> schedule(1000)); return;
            }
            if (collection.hasResult()) finishCollected(active,collection.actions()>=40); else timeout();
        });
    }
    private void timeout() {
        if (bootstrapping) { if (android.os.SystemClock.elapsedRealtime()-bootstrapStarted>30_000) finishBootstrap(new JSONObject(),generation); else schedule(1000); return; }
        if (all && android.os.SystemClock.elapsedRealtime()>due) { if (collection.hasResult()) finishCollected(generation,true); else failure("unavailable"); } else schedule(800);
    }
    private void finishCollected(int active,boolean partial) {
        try {
            JSONObject m=collection.result(); if (partial) m.put("partial",true);
            JSONObject fresh=new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put(service.id,m));
            String current=m.toString();
            if (all || !current.equals(lastSnapshot)) { lastSnapshot=current; if (partial) unavailable++; persist(fresh.toString(),active,true); }
        } catch (Exception e) { failure("unsupported"); }
    }
    private void primaryAction() {
        if (centralExpired) {
            authPaused=true; handler.removeCallbacksAndMessages(null);
            startActivityForResult(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu").putExtra("auth_only",true),42);
        } else if (bootstrapping || challengeActive) inspect(true);
        else if (service!=null && service.id.equals("electric")) bindElectric(); else inspect(true);
    }
    private void beginBootstrap(boolean resume) {
        generation++; handler.removeCallbacksAndMessages(null); probing=false; electricBusy=false; centralExpired=false; challengeActive=false;
        bootstrapping=true; resumeService=resume; catalogTried=false; auth.reset(); handoffs.clear();
        bootstrapStarted=android.os.SystemClock.elapsedRealtime(); authDeadline=bootstrapStarted+45_000;
        title.setText("统一认证"); actions.setVisibility(View.GONE); status.setText("正在复用翱翔门户会话，加载本人可用的校园服务入口…");
        web.loadUrl(CampusServices.PORTAL_SSO); schedule(700);
    }
    private boolean authenticationReady(JSONObject r,int active) {
        long now=android.os.SystemClock.elapsedRealtime(); String phase=r.optString("phase");
        CampusAuth.Decision decision=auth.observe(phase,now);
        if (decision==CampusAuth.Decision.CHALLENGE) {
            challengeActive=true; authDeadline=now+300_000;
            cover.setVisibility(View.GONE);
            status.setText("请完成校方统一认证的安全验证，通过后会自动继续同步。"); schedule(700); return false;
        }
        if (decision==CampusAuth.Decision.CENTRAL_EXPIRED) {
            if (!centralExpired) { centralExpired=true; primary.setText("重新统一登录"); primaryAction(); }
            status.setText("校方登录状态已过期，登录后自动继续。"); return false;
        }
        if (decision==CampusAuth.Decision.WAIT) {
            status.setText("正在等待统一认证完成跳转…");
            if (now>authDeadline) { if (bootstrapping) finishBootstrap(new JSONObject(),active); else failure("unavailable"); }
            else schedule(600); return false;
        }
        if (decision==CampusAuth.Decision.HANDOFF) {
            String target=r.optString("url"), key=phase+":"+web.getUrl();
            if (handoffs.add(key) && handoffs.size()<=4) {
                status.setText("正在通过学校提供的统一登录入口建立服务会话…");
                if ("sso_link".equals(phase) && CampusServices.readUrl(target)) web.loadUrl(target);
                else web.evaluateJavascript("NwpuSso.click('sso',"+JSONObject.quote("handoff-"+active)+")",ignored -> {});
                schedule(800);
            } else if (now>authDeadline) { if (bootstrapping) finishBootstrap(new JSONObject(),active); else failure("unavailable"); }
            else schedule(800);
            return false;
        }
        if (decision==CampusAuth.Decision.SERVICE_ENTRY) {
            if (!portalRetried) { portalRetried=true; beginBootstrap(true); }
            else { status.setText("这个服务的统一认证入口尚未适配，将稍后自动重试。"); if (all) failure("unavailable"); else schedule(1500); }
            return false;
        }
        if (decision!=CampusAuth.Decision.READY) { timeout(); return false; }
        if (centralExpired || challengeActive) {
            centralExpired=false; challengeActive=false; due=now+45_000; authDeadline=now+45_000;
            if (automatic) cover.setVisibility(View.VISIBLE);
        }
        return true;
    }
    private void scanPortal(JSONObject r,int active) {
        String url=web.getUrl();
        if (url==null || !"ecampus.nwpu.edu.cn".equals(Uri.parse(url).getHost())) { timeout(); return; }
        if (!catalogTried) {
            catalogTried=true; web.evaluateJavascript("NwpuSso.click('catalog',"+JSONObject.quote("catalog-"+active)+")",ignored -> schedule(900)); return;
        }
        if (android.os.SystemClock.elapsedRealtime()-bootstrapStarted<6000) { schedule(800); return; }
        JSONObject routes=r.optJSONObject("routes"); finishBootstrap(routes==null?new JSONObject():routes,active);
    }
    private void finishBootstrap(JSONObject routes,int active) {
        if (!bootstrapping || active!=generation || isDestroyed()) return;
        writing=true;
        io.execute(() -> {
            CampusData next=null;
            try {
                JSONObject fresh=new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject()).put("routes",routes);
                next=CampusStore.update(getApplicationContext(),fresh.toString(),owner);
            } catch (Exception ignored) { }
            CampusData result=next; runOnUiThread(() -> {
                writing=false; if (active!=generation || isDestroyed()) return; if (result!=null) saved=result;
                bootstrapping=false; keepPortalRetry=resumeService; loadNext();
            });
        });
    }
    private void openPortalService(JSONObject r,int active) {
        JSONObject routes=r.optJSONObject("routes"); String target=routes==null?"":routes.optString(service.id);
        if (CampusServices.readUrl(target) && !target.equals(web.getUrl())) {
            try { saveRoutes(new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject()).put("routes",routes).toString(),active); } catch (Exception ignored) { }
            web.loadUrl(target); schedule(700); return;
        }
        if (!portalActivated) {
            portalActivated=true; web.evaluateJavascript("NwpuSso.click("+JSONObject.quote(service.id)+","+JSONObject.quote("service-"+active)+")",ignored -> schedule(1000)); return;
        }
        if (!catalogTried) { catalogTried=true; web.evaluateJavascript("NwpuSso.click('catalog',"+JSONObject.quote("service-catalog-"+active)+")",ignored -> { portalActivated=false; schedule(1000); }); return; }
        timeout();
    }
    @Override protected void onActivityResult(int request,int result,Intent value) {
        super.onActivityResult(request,result,value);
        if (request!=42) return; authPaused=false;
        if (result!=RESULT_OK) { finish(); return; }
        io.execute(() -> {
            try { saved=CampusStore.load(getApplicationContext()); owner=saved==null?"":saved.owner; } catch (Exception ignored) { }
            runOnUiThread(() -> { if (!isDestroyed()) { CampusAutoRefresh.rebind(this,run,owner); index=0; success=0; unavailable=value!=null && value.getBooleanExtra("academic_incomplete",false)?1:0; beginBootstrap(false); } });
        });
    }
    private void saveRoutes(String fresh,int active) {
        io.execute(() -> { try { CampusData d=CampusStore.update(getApplicationContext(),fresh,owner); runOnUiThread(() -> { if (active==generation) saved=d; }); } catch (Exception ignored) { } });
    }
    private void persist(String fresh,int active,boolean countSuccess) {
        writing=true;
        io.execute(() -> {
            CampusData d=null; try { d=CampusStore.update(getApplicationContext(),fresh,owner); } catch (Exception ignored) { }
            CampusData result=d;
            runOnUiThread(() -> {
                writing=false; if (active!=generation || isDestroyed()) return;
                if (result==null) { status.setText("本次数据没有保存，账号可能已切换或页面格式已变化。"); if (all) { unavailable++; index++; loadNext(); } return; }
                saved=result; status.setText("已保存“"+service.title+"”的 "+CampusData.count(saved.module(service.id))+" 条信息，可返回校园页离线查看。");
                if (all) { if (countSuccess) success++; index++; loadNext(); }
            });
        });
    }
    private void failure(String state) {
        if (service==null || writing || isDestroyed()) return;
        try {
            JSONObject m=new JSONObject().put("state",state).put("source",service.entry).put("fields",new JSONArray()).put("tables",new JSONArray()).put("links",new JSONArray());
            JSONObject fresh=new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put(service.id,m));
            final int active=generation; writing=true;
            io.execute(() -> {
                CampusData d=null; try { d=CampusStore.update(getApplicationContext(),fresh.toString(),owner); } catch (Exception ignored) { }
                CampusData result=d; runOnUiThread(() -> {
                    writing=false; if (active!=generation || isDestroyed()) return; if (result!=null) saved=result;
                    status.setText(CampusServices.status(state)+"。上次成功读取的数据已保留。");
                    if (all) { unavailable++; index++; loadNext(); }
                });
            });
        } catch (Exception ignored) { writing=false; }
    }
    private void bindElectric() {
        if (writing || electricBusy) return;
        if (web.getUrl()==null || !"yktapp.nwpu.edu.cn".equals(Uri.parse(web.getUrl()).getHost())) {
            status.setText("正在打开一卡通，加载后自动显示本人宿舍选项。"); web.loadUrl("https://yktapp.nwpu.edu.cn/"); return;
        }
        String stored=getSharedPreferences("campus-binding",MODE_PRIVATE).getString("room","");
        if (!stored.isEmpty()) new AlertDialog.Builder(this,Ui.dialogTheme()).setItems(new String[]{"查询已绑定宿舍","重新选择宿舍"},(d,i) -> {
            if (i==0) { try { queryElectric(3,new JSONObject(stored),false); } catch (Exception e) { queryElectric(0,new JSONObject(),true); } }
            else queryElectric(0,new JSONObject(),true);
        }).show();
        else queryElectric(0,new JSONObject(),true);
    }
    private void queryElectric(int level,JSONObject binding,boolean choosing) {
        if (electricBusy || isDestroyed()) return;
        electricBusy=true; final int active=generation; final String token=java.util.UUID.randomUUID().toString();
        status.setText(level==3?"正在查询宿舍剩余电量…":"正在获取校方提供的宿舍选项…");
        web.evaluateJavascript(script+"\nNwpuCampus.electricStart("+JSONObject.quote(token)+","+level+","+binding+");",ignored ->
            pollElectric(active,token,level,binding,choosing,android.os.SystemClock.elapsedRealtime()+14_000));
    }
    private void pollElectric(int active,String token,int level,JSONObject binding,boolean choosing,long limit) {
        if (active!=generation || isDestroyed() || !electricBusy) return;
        if (android.os.SystemClock.elapsedRealtime()>limit) { electricBusy=false; failure("network"); return; }
        web.evaluateJavascript("JSON.stringify(window.__louElectricResult||{})",value -> {
            if (active!=generation || isDestroyed()) return;
            JSONObject r; try { r=result(value); } catch (Exception e) { r=new JSONObject(); }
            if (!token.equals(r.optString("token")) || "loading".equals(r.optString("status"))) { handler.postDelayed(() -> pollElectric(active,token,level,binding,choosing,limit),400); return; }
            electricBusy=false;
            if (!"ok".equals(r.optString("status"))) {
                if (!choosing && level==3 && !electricRetry) {
                    electricRetry=true; getSharedPreferences("campus-binding",MODE_PRIVATE).edit().remove("room").apply(); queryElectric(0,new JSONObject(),false); return;
                }
                status.setText("电量接口暂时没有返回可用数据，将稍后自动重试。"); if (all) failure("unavailable"); return;
            }
            if (level<3) {
                JSONArray options=r.optJSONArray("data"); if (options==null || options.length()==0) { failure("binding"); return; }
                if (!choosing) {
                    JSONObject match=CampusBindings.match(saved,options,level);
                    if (match==null) { failure("binding"); return; }
                    try { binding.put(new String[]{"campus","building","room"}[level],match.getString("value")); queryElectric(level+1,binding,false); } catch (Exception e) { failure("binding"); }
                    return;
                }
                String[] labels=new String[options.length()]; for (int i=0;i<labels.length;i++) labels[i]=options.optJSONObject(i).optString("name");
                new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle(new String[]{"选择自己的校区","选择自己的楼栋","选择自己的房间"}[level]).setItems(labels,(d,i) -> {
                    try { binding.put(new String[]{"campus","building","room"}[level],options.getJSONObject(i).getString("value")); queryElectric(level+1,binding,true); } catch (Exception e) { toast("宿舍选项发生变化，请重新选择"); }
                }).setNegativeButton("取消",null).show();
            } else {
                try {
                    JSONObject m=r.getJSONObject("data"), fresh=new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put("electric",m));
                    getSharedPreferences("campus-binding",MODE_PRIVATE).edit().putString("room",binding.toString()).apply();
                    persist(fresh.toString(),active,true);
                } catch (Exception e) { failure("unsupported"); }
            }
        });
    }
    @Override public void onSaveInstanceState(Bundle b) { b.putInt("index",index); b.putInt("success",success); b.putInt("unavailable",unavailable); super.onSaveInstanceState(b); }
    @Override public void onBackPressed() { if (!all && web!=null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override public void onDestroy() {
        if (automatic && !completed && !isChangingConfigurations()) CampusAutoRefresh.finish(this,run,owner,false,true);
        generation++; handler.removeCallbacksAndMessages(null); if (web!=null) { web.stopLoading(); ((android.view.ViewGroup)web.getParent()).removeView(web); web.destroy(); web=null; }
        io.shutdown(); super.onDestroy();
    }
}
