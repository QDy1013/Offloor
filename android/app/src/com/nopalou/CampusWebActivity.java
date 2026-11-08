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
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
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
    private String script,ssoScript,owner="",lastSnapshot="";
    private CampusData saved;
    private CampusServices.Service service;
    private final List<CampusServices.Service> queue=new ArrayList<>();
    private int index,generation,success,unavailable;
    private boolean all,writing,probing,electricBusy,electricRequested;
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
        build(); all=getIntent().getBooleanExtra("all",false);
        if (all) {
            for (CampusServices.Service s:CampusServices.ALL) if (!s.id.equals("profile")) queue.add(s);
        } else {
            CampusServices.Service s=CampusServices.byId(getIntent().getStringExtra("module")); queue.add(s==null?CampusServices.ALL[0]:s);
        }
        io.execute(() -> {
            try { saved=CampusStore.load(getApplicationContext()); owner=saved==null?"":saved.owner; } catch (Exception ignored) { }
            runOnUiThread(() -> { if (!isDestroyed()) {
                service=queue.get(0);
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
        TextView choose=CourseUi.action(this,"服务",false,this::choose); bar.addView(choose,new LinearLayout.LayoutParams(dp(66),dp(44)));
        status=text("正在打开校方页面…",12,Ui.SUB,false); status.setPadding(dp(16),dp(8),dp(16),dp(12)); status.setLineSpacing(dp(3),1); root.addView(status);
        web=new WebView(this); root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
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
        LinearLayout actions=new LinearLayout(this); actions.setPadding(dp(12),dp(10),dp(12),dp(12)); root.addView(actions);
        primary=CourseUi.action(this,"读取此页",true,this::primaryAction);
        actions.addView(primary,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView portal=CourseUi.action(this,"翱翔门户",false,() -> beginBootstrap(true));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); p.leftMargin=dp(8); actions.addView(portal,p);
        TextView next=CourseUi.action(this,"下一项",false,() -> { if (all) { unavailable++; index++; loadNext(); } else choose(); });
        LinearLayout.LayoutParams n=new LinearLayout.LayoutParams(dp(80),dp(48)); n.leftMargin=dp(8); actions.addView(next,n);
    }
    private boolean navigate(String url) {
        if (CampusServices.allowed(url)) return true;
        toast("此链接需要在校方指定的浏览器或应用中打开"); return false;
    }
    private void choose() {
        all=false; bootstrapping=false; handler.removeCallbacksAndMessages(null); generation++; probing=false; electricBusy=false;
        String[] labels=new String[CampusServices.ALL.length]; for (int i=0;i<labels.length;i++) labels[i]=CampusServices.ALL[i].title;
        new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择校园服务").setItems(labels,(d,i) -> {
            queue.clear(); queue.add(CampusServices.ALL[i]); index=0; loadNext();
        }).setNegativeButton("取消",(d,w) -> schedule(800)).show();
    }
    private void loadNext() {
        if (isFinishing() || isDestroyed()) return;
        generation++; probing=false; electricBusy=false; electricRequested=false; lastSnapshot=""; handler.removeCallbacksAndMessages(null);
        bootstrapping=false; centralExpired=false; challengeActive=false; portalActivated=false; catalogTried=false;
        if (!keepPortalRetry) portalRetried=false; keepPortalRetry=false;
        auth.reset(); handoffs.clear(); authDeadline=android.os.SystemClock.elapsedRealtime()+45_000;
        if (index>=queue.size()) {
            status.setText("同步结束："+success+" 项读到数据，"+unavailable+" 项需检查入口、页面或网络。原有数据已保留。");
            new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("校园同步已结束").setMessage(status.getText()).setPositiveButton("查看校园信息",(d,w) -> finish()).setNegativeButton("留在校方页面",(d,w) -> { all=false; }).show(); return;
        }
        service=queue.get(index); title.setText(service.title+(all?" · "+(index+1)+"/"+queue.size():""));
        primary.setText(service.id.equals("electric")?"选择宿舍":"读取此页");
        status.setText(all?"正在通过统一认证进入各校园服务，无需重复输入账号密码。":"正在使用翱翔门户登录状态打开“"+service.title+"”，读到内容后会自动保存。");
        due=android.os.SystemClock.elapsedRealtime()+16_000;
        String initial=!all && index==0?getIntent().getStringExtra("url"):null;
        String entry=initial!=null && CampusServices.readUrl(initial)?initial:saved==null?service.entry:saved.route(service);
        web.loadUrl(entry); schedule(1200);
    }
    private void schedule(long delay) { handler.removeCallbacks(tick); if (!isFinishing() && !isDestroyed()) handler.postDelayed(tick,delay); }
    private final Runnable tick=() -> inspect(false);
    private JSONObject result(String value) throws Exception { Object raw=new JSONTokener(value).nextValue(); return raw instanceof String?new JSONObject((String)raw):new JSONObject(); }
    private void inspect(boolean manual) {
        if (web==null || service==null || writing || probing || electricBusy || authPaused || isFinishing() || isDestroyed()) return;
        String url=web.getUrl(); if (url==null || !CampusServices.allowed(url)) { schedule(1200); return; }
        final int active=generation; probing=true;
        web.evaluateJavascript(script+"\n"+ssoScript+"\nJSON.stringify(NwpuSso.inspect())",value -> {
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
            if (all) { failure("binding"); return; }
        }
        final String id=service.id; probing=true;
        web.evaluateJavascript(script+"\nJSON.stringify(NwpuCampus.capture("+JSONObject.quote(id)+"))",value -> {
            if (active!=generation || isDestroyed()) return; probing=false;
            if (!url.equals(web.getUrl())) { schedule(800); return; }
            JSONObject fresh,m;
            try { fresh=result(value); m=fresh.getJSONObject("modules").getJSONObject(id); } catch (Exception e) { if (manual) toast("页面暂时无法读取，请加载完成后重试"); timeout(); return; }
            // Follow a labelled school link once; never construct or enumerate private endpoints.
            JSONObject routes=fresh.optJSONObject("routes"); String route=routes==null?"":routes.optString(id);
            if (!route.isEmpty() && CampusServices.readUrl(route) && !route.equals(url) && CampusData.count(m)==0) {
                if (saved==null || !route.equals(saved.routes.optString(id))) saveRoutes(fresh.toString(),active);
                web.loadUrl(route); schedule(1000); return;
            }
            String state=m.optString("state");
            if ("ok".equals(state) && CampusData.count(m)>0) {
                String snapshot=m.toString();
                if (!snapshot.equals(lastSnapshot)) { lastSnapshot=snapshot; persist(fresh.toString(),active,true); }
                else if (manual) toast("此页内容已保存");
                if (!all) schedule(1600);
            } else if ("login".equals(state)) {
                // A service login page is not evidence of another account/password. Retry its
                // portal SSO entry before classifying the adapter as incomplete.
                if (!portalRetried) { portalRetried=true; beginBootstrap(true); } else failure("unavailable");
            }
            else { if (manual) status.setText(CampusServices.status(state)+"。请在校方页面打开具体信息，加载后再读取。"); timeout(); }
        });
    }
    private void timeout() {
        if (bootstrapping) { if (android.os.SystemClock.elapsedRealtime()-bootstrapStarted>30_000) finishBootstrap(new JSONObject(),generation); else schedule(1000); return; }
        if (all && android.os.SystemClock.elapsedRealtime()>due) failure("unavailable"); else schedule(1200);
    }
    private void primaryAction() {
        if (centralExpired) {
            authPaused=true; handler.removeCallbacksAndMessages(null);
            startActivityForResult(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"),42);
        } else if (bootstrapping || challengeActive) inspect(true);
        else if (service!=null && service.id.equals("electric")) bindElectric(); else inspect(true);
    }
    private void beginBootstrap(boolean resume) {
        generation++; handler.removeCallbacksAndMessages(null); probing=false; electricBusy=false; centralExpired=false; challengeActive=false;
        bootstrapping=true; resumeService=resume; catalogTried=false; auth.reset(); handoffs.clear();
        bootstrapStarted=android.os.SystemClock.elapsedRealtime(); authDeadline=bootstrapStarted+45_000;
        title.setText("统一认证"); primary.setText("读取此页"); status.setText("正在复用翱翔门户会话，加载本人可用的校园服务入口…");
        web.loadUrl(CampusServices.PORTAL_SSO); schedule(700);
    }
    private boolean authenticationReady(JSONObject r,int active) {
        long now=android.os.SystemClock.elapsedRealtime(); String phase=r.optString("phase");
        CampusAuth.Decision decision=auth.observe(phase,now);
        if (decision==CampusAuth.Decision.CHALLENGE) {
            challengeActive=true; authDeadline=now+300_000;
            status.setText("请完成校方统一认证的安全验证，通过后会自动继续同步。"); schedule(700); return false;
        }
        if (decision==CampusAuth.Decision.CENTRAL_EXPIRED) {
            if (!centralExpired) { centralExpired=true; primary.setText("重新统一登录"); }
            status.setText("翱翔门户统一登录已过期，请重新登录一次，之后继续同步所有服务。"); schedule(1000); return false;
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
            else { status.setText("这个服务的统一认证入口尚未适配，请从翱翔门户打开对应服务。"); if (all) failure("unavailable"); else schedule(1500); }
            return false;
        }
        if (decision!=CampusAuth.Decision.READY) { timeout(); return false; }
        if (centralExpired || challengeActive) {
            centralExpired=false; challengeActive=false; due=now+16_000; authDeadline=now+45_000;
            primary.setText(service.id.equals("electric")?"选择宿舍":"读取此页");
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
        if (result!=RESULT_OK) { schedule(1000); return; }
        io.execute(() -> {
            try { saved=CampusStore.load(getApplicationContext()); owner=saved==null?"":saved.owner; } catch (Exception ignored) { }
            runOnUiThread(() -> { if (!isDestroyed()) { index=0; success=0; unavailable=0; beginBootstrap(false); } });
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
            status.setText("正在打开一卡通，请加载完成后点击“选择宿舍”。"); web.loadUrl("https://yktapp.nwpu.edu.cn/"); return;
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
            if (!"ok".equals(r.optString("status"))) { status.setText("电量接口暂时没有返回可用数据。可在校方页面选择电费服务后重试。"); if (all) failure("unavailable"); return; }
            if (level<3) {
                JSONArray options=r.optJSONArray("data"); if (options==null || options.length()==0) { status.setText("校方暂未返回可选宿舍，请稍后重试。"); return; }
                String[] labels=new String[options.length()]; for (int i=0;i<labels.length;i++) labels[i]=options.optJSONObject(i).optString("name");
                new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle(new String[]{"选择自己的校区","选择自己的楼栋","选择自己的房间"}[level]).setItems(labels,(d,i) -> {
                    try { binding.put(new String[]{"campus","building","room"}[level],options.getJSONObject(i).getString("value")); queryElectric(level+1,binding,true); } catch (Exception e) { toast("宿舍选项发生变化，请重新选择"); }
                }).setNegativeButton("取消",null).show();
            } else {
                try {
                    JSONObject m=r.getJSONObject("data"), fresh=new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put("electric",m));
                    if (choosing) getSharedPreferences("campus-binding",MODE_PRIVATE).edit().putString("room",binding.toString()).apply();
                    persist(fresh.toString(),active,true);
                } catch (Exception e) { status.setText("电量数据格式暂不支持，原数据已保留。"); }
            }
        });
    }
    @Override public void onBackPressed() { if (!all && web!=null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override public void onDestroy() {
        generation++; handler.removeCallbacksAndMessages(null); if (web!=null) { web.stopLoading(); ((android.view.ViewGroup)web.getParent()).removeView(web); web.destroy(); web=null; }
        io.shutdown(); super.onDestroy();
    }
}
