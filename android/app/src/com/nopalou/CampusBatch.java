package com.nopalou;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Three bounded browser lanes. One account/CAS session, no serial wait across unrelated sites. */
final class CampusBatch {
    interface Listener {
        void progress(int done,int total,int success,int failed,String active);
        void challenge(WebView web,boolean visible);
        void loginRequired();
        void complete(int success,int failed);
    }
    private final Activity activity;
    private final FrameLayout stage;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final String owner,run,scripts,autoScript,academicScripts;
    private final Listener listener;
    private final List<CampusServices.Service> waiting=new ArrayList<>();
    private final Set<String> doneIds=new HashSet<>(), hosts=new HashSet<>();
    private final List<Lane> lanes=new ArrayList<>();
    private CampusData saved;
    private final int total;
    private int success,failed,done;
    private boolean stopped,loginRequested;
    private Lane interactive;
    private long lastBeat;
    private final long wallStarted=System.currentTimeMillis();

    CampusBatch(Activity activity,FrameLayout stage,CampusData data,List<CampusServices.Service> services,String run,String campus,String sso,String auto,Listener listener) {
        this.activity=activity; this.stage=stage; this.saved=data; this.owner=data.owner; this.run=run; this.scripts=campus+"\n"+sso+"\n"+auto; this.autoScript=auto; this.listener=listener;
        String academic="";
        try {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            for (String file:new String[]{"nwpu-import.js","nwpu-academic.js"}) try (java.io.InputStream in=activity.getAssets().open(file)) { byte[] buffer=new byte[4096]; int n; while ((n=in.read(buffer))!=-1) out.write(buffer,0,n); out.write('\n'); }
            academic=out.toString("UTF-8");
        } catch (Exception ignored) { }
        academicScripts=academic;
        String entry=activity.getSharedPreferences("campus-auto",0).getString("academic_entry",NwpuImportActivity.COURSE_URL);
        waiting.add(new CampusServices.Service("_academic","全部学期成绩与考试","",CampusServices.readUrl(entry)?entry:NwpuImportActivity.COURSE_URL,true));
        waiting.addAll(services); total=waiting.size();
    }
    void start() { for (int i=0;i<3;i++) lanes.add(new Lane()); pump(); }
    void stop() {
        if (stopped) return; stopped=true; handler.removeCallbacksAndMessages(null);
        for (Lane lane:lanes) { lane.generation++; lane.web.stopLoading(); stage.removeView(lane.web); lane.web.destroy(); }
        io.shutdown();
    }
    private boolean alive() { return !stopped && !activity.isFinishing() && !activity.isDestroyed() && CampusAutoRefresh.owns(activity,run,owner); }
    private void pump() {
        if (!alive() || interactive!=null) return;
        for (Lane lane:lanes) if (lane.service==null) {
            int i=CampusSchedule.next(waiting,hosts,doneIds,saved);
            if (i>=0) {
                CampusServices.Service service=waiting.get(i); String host=CampusSchedule.host(service,saved);
                waiting.remove(i); hosts.add(host); lane.start(service,host);
            }
        }
        notifyProgress();
        if (done==total) listener.complete(success,failed);
    }
    private void notifyProgress() {
        StringBuilder active=new StringBuilder();
        for (Lane lane:lanes) if (lane.service!=null) { if (active.length()>0) active.append(" · "); active.append(lane.service.title); }
        listener.progress(done,total,success,failed,active.toString());
    }
    private void store(Lane lane,JSONObject module,boolean ok) {
        if (!alive() || lane.writing || lane.service==null) return;
        lane.writing=true; int generation=lane.generation; String id=lane.service.id;
        io.execute(() -> {
            CampusData next=null;
            try { if (!stopped && CampusAutoRefresh.owns(activity,run,owner)) next=CampusStore.update(activity.getApplicationContext(),new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put(id,module)).toString(),owner); } catch (Exception ignored) { }
            CampusData result=next;
            activity.runOnUiThread(() -> {
                if (!alive() || generation!=lane.generation) return;
                completeLane(lane,result,ok && result!=null,ok && module.optBoolean("partial"));
            });
        });
    }
    private void completeLane(Lane lane,CampusData result,boolean ok,boolean partial) {
        if (result!=null) saved=result;
        if (ok) success++; else failed++;
        if (ok && partial) failed++;
        done++; doneIds.add(lane.service.id); hosts.remove(lane.host); lane.service=null; lane.writing=false; lane.web.stopLoading(); pump();
    }
    private static JSONObject decode(String raw) throws Exception { Object value=new JSONTokener(raw).nextValue(); return value instanceof String?new JSONObject((String)value):new JSONObject(); }

    private final class Lane {
        final WebView web=new WebView(activity);
        final CampusAuth auth=new CampusAuth();
        final Runnable tick=this::inspect;
        CampusServices.Service service;
        CampusReadSession collection;
        String host="",lastPhase="";
        int generation;
        long started,deadline,loaded,lastPreview;
        boolean probing,writing,injected,portalRetry,electric,querying,profileSaved,academicInjected,electricRetry;
        int academicRevision;
        String academicPending;
        long academicPreview;
        final Set<String> handoffs=new HashSet<>();
        Lane() {
            stage.addView(web,0,new FrameLayout.LayoutParams(-1,-1));
            WebSettings settings=web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r) { return !CampusServices.allowed(r.getUrl().toString()); }
                @Override public boolean shouldOverrideUrlLoading(WebView v,String url) { return !CampusServices.allowed(url); }
                @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon) {
                    injected=false; academicInjected=false; loaded=SystemClock.elapsedRealtime(); probing=false;
                    if (CampusServices.allowed(url)) web.evaluateJavascript(autoScript+"\nNwpuAuto.install();",ignored -> {});
                }
                @Override public void onPageFinished(WebView v,String url) { injected=false; if (service!=null) schedule(20); }
                @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e) { if (r.isForMainFrame() && r.getUrl().toString().equals(v.getUrl())) fail("network"); }
                @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e) { if (r.isForMainFrame() && r.getUrl().toString().equals(v.getUrl()) && e.getStatusCode()>=400) fail("network"); }
                @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler h,android.net.http.SslError e) { h.cancel(); fail("network"); }
            });
        }
        void start(CampusServices.Service service,String host) {
            this.service=service; this.host=host; generation++; collection=new CampusReadSession(); auth.reset(); handoffs.clear();
            probing=false; writing=false; portalRetry=false; electric=false; electricRetry=false; querying=false; profileSaved=false; academicInjected=false; academicRevision=-1; academicPending=null; academicPreview=0; lastPhase="";
            started=SystemClock.elapsedRealtime(); lastPreview=0; deadline=started+15_000; navigate(saved.route(service));
        }
        void navigate(String url) { injected=false; loaded=SystemClock.elapsedRealtime(); web.loadUrl(url); schedule(60); }
        void schedule(long delay) { handler.removeCallbacks(tick); if (alive() && service!=null) handler.postDelayed(tick,delay); }
        void inspect() {
            if (!alive() || service==null || writing || probing || querying) return;
            if (interactive!=null && interactive!=this) { schedule(200); return; }
            if (System.currentTimeMillis()-lastBeat>60_000) { lastBeat=System.currentTimeMillis(); CampusAutoRefresh.heartbeat(activity,run,owner); }
            long now=SystemClock.elapsedRealtime();
            if (now-started>20*60_000 || collection.limited()) { finish(true); return; }
            String url=web.getUrl(); if (!CampusServices.allowed(url)) { timeout(); return; }
            int active=generation; probing=true;
            String prefix=injected?"":scripts+"\nNwpuAuto.install();"; injected=true;
            web.evaluateJavascript(prefix+"\nJSON.stringify({auth:NwpuSso.inspect(),read:NwpuAuto.inspect("+JSONObject.quote(service.id)+","+collection.visited()+","+JSONObject.quote(collection.context())+")})",raw -> {
                if (!alive() || active!=generation || service==null) return; probing=false;
                if (!url.equals(web.getUrl())) { schedule(40); return; }
                JSONObject result; try { result=decode(raw); } catch (Exception e) { injected=false; timeout(); return; }
                JSONObject login=result.optJSONObject("auth");
                if (login==null || !authenticate(login,url)) return;
                if (CampusServices.isPortal(url)) { portal(login); return; }
                if (service.id.equals("_academic")) { beginAcademic(); return; }
                if (service.id.equals("electric") && "yktapp.nwpu.edu.cn".equals(Uri.parse(url).getHost()) && !electric) {
                    electric=true; String room=activity.getSharedPreferences("campus-binding",0).getString("room","");
                    try { electric(room.isEmpty()?0:3,room.isEmpty()?new JSONObject():new JSONObject(room)); } catch (Exception e) { electric(0,new JSONObject()); } return;
                }
                JSONObject read=result.optJSONObject("read"); if (read==null) { timeout(); return; }
                read(read,url);
            });
        }
        boolean authenticate(JSONObject r,String url) {
            long now=SystemClock.elapsedRealtime(); String phase=r.optString("phase"); CampusAuth.Decision decision=auth.observe(phase,now);
            if (!phase.equals(lastPhase)) { lastPhase=phase; deadline=now+15_000; }
            if (decision==CampusAuth.Decision.CHALLENGE) { interactive=this; listener.challenge(web,true); schedule(150); return false; }
            if (interactive==this) { interactive=null; listener.challenge(web,false); pump(); }
            if (decision==CampusAuth.Decision.CENTRAL_EXPIRED) {
                if (!loginRequested) { loginRequested=true; listener.loginRequired(); } return false;
            }
            if (decision==CampusAuth.Decision.HANDOFF) {
                if (handoffs.add(phase+":"+url)) {
                    String target=r.optString("url");
                    if (CampusServices.readUrl(target)) navigate(target); else web.evaluateJavascript("NwpuSso.click('sso',"+JSONObject.quote("batch-"+generation)+")",ignored -> schedule(60));
                } else timeout();
                return false;
            }
            if (decision==CampusAuth.Decision.SERVICE_ENTRY) { retryPortal(); return false; }
            if (decision!=CampusAuth.Decision.READY) { timeout(); return false; }
            return true;
        }
        void retryPortal() { if (portalRetry) { fail("unavailable"); return; } portalRetry=true; handoffs.clear(); navigate(CampusServices.PORTAL_SSO); }
        void portal(JSONObject r) {
            JSONObject routes=r.optJSONObject("routes"); String target=routes==null?"":routes.optString(service.id);
            if (CampusServices.readUrl(target) && !target.equals(web.getUrl())) { navigate(target); return; }
            String key="portal:"+service.id;
            if (handoffs.add(key)) {
                int active=generation;
                String call=service.id.startsWith("portal_")?"NwpuSso.clickService("+JSONObject.quote(service.title):"NwpuSso.click("+JSONObject.quote(service.id);
                web.evaluateJavascript(call+","+JSONObject.quote("batch-app-"+generation)+")",clicked -> {
                    if (!alive() || active!=generation || service==null) return;
                    if (!"true".equals(clicked) && handoffs.add("catalog:"+service.id)) {
                        handoffs.remove(key); web.evaluateJavascript("NwpuSso.click('catalog',"+JSONObject.quote("batch-catalog-"+generation)+")",ignored -> schedule(100));
                    } else schedule(100);
                });
            } else timeout();
        }
        void read(JSONObject r,String url) {
            JSONObject fresh=r.optJSONObject("snapshot"), module=fresh==null || fresh.optJSONObject("modules")==null?null:fresh.optJSONObject("modules").optJSONObject(service.id);
            if (module==null) { timeout(); return; }
            if (r.optBoolean("settling") || !collection.stable(module,r.optInt("pending"))) { timeout(); return; }
            String state=module.optString("state"); if (state.equals("login")) { retryPortal(); return; }
            JSONArray actions=r.optJSONArray("actions"); JSONObject first=actions==null?null:actions.optJSONObject(0);
            boolean prepare=r.optBoolean("preparing");
            try { collection.remember(actions); if (!prepare && (state.equals("ok") || state.equals("empty"))) collection.add(module); } catch (Exception e) { collection.markIncomplete(); }
            preview();
            JSONObject routes=fresh.optJSONObject("routes"); String route=routes==null?"":routes.optString(service.id);
            if (CampusData.count(module)==0 && CampusServices.readUrl(route) && !route.equals(url) && collection.visit("page:"+route)) { deadline=SystemClock.elapsedRealtime()+15_000; navigate(route); return; }
            for (int i=0;actions!=null && i<actions.length();i++) {
                JSONObject action=actions.optJSONObject(i); if (action==null || !collection.visit(action.optString("key"))) continue;
                try {
                    if (action.optString("type").equals("detail") && CampusServices.readUrl(action.optString("url"))) collection.openDetail(url,action.optString("context"));
                    else if (action.optString("type").equals("navigate")) collection.openPage();
                } catch (Exception e) { collection.markIncomplete(); }
                deadline=SystemClock.elapsedRealtime()+15_000;
                web.evaluateJavascript("NwpuAuto.act("+JSONObject.quote(service.id)+","+JSONObject.quote(action.optString("key"))+")",ignored -> schedule(60)); return;
            }
            if (state.equals("ok") || state.equals("empty")) { if (!continuePage()) finish(collection.incomplete()); return; }
            // Unknown completed pages do not consume the old 45-second timeout for every app.
            if (SystemClock.elapsedRealtime()-loaded>2500 && r.optInt("pending")==0 && !r.optBoolean("settling")) { collection.markIncomplete(); if (!continuePage()) finish(true); }
            else timeout();
        }
        boolean continuePage() {
            JSONObject back=collection.back();
            if (back!=null) {
                String target=back.optString("url"); android.webkit.WebBackForwardList history=web.copyBackForwardList(); int at=history.getCurrentIndex()-1;
                if (at>=0 && target.equals(history.getItemAtIndex(at).getUrl())) web.goBack(); else navigate(target);
                loaded=SystemClock.elapsedRealtime(); deadline=loaded+15_000; schedule(60); return true;
            }
            JSONObject next=collection.nextPage();
            if (next!=null && collection.visit(next.optString("key"))) { collection.openPage(); deadline=SystemClock.elapsedRealtime()+15_000; navigate(next.optString("url")); return true; }
            return false;
        }
        void timeout() {
            if (SystemClock.elapsedRealtime()>deadline) { collection.markIncomplete(); if (!continuePage()) finish(true); }
            else schedule(100);
        }
        void preview() {
            if (!collection.hasResult() || SystemClock.elapsedRealtime()-lastPreview<1000) return;
            try {
                JSONObject module=collection.result(); if (CampusData.count(module)==0) return;
                lastPreview=SystemClock.elapsedRealtime(); module.put("partial",true); String id=service.id; int active=generation;
                io.execute(() -> {
                    CampusData updated=null;
                    try { if (!stopped && CampusAutoRefresh.owns(activity,run,owner)) updated=CampusStore.update(activity.getApplicationContext(),new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put(id,module)).toString(),owner); } catch (Exception ignored) { }
                    CampusData result=updated; activity.runOnUiThread(() -> { if (alive() && active==generation && result!=null) saved=result; });
                });
            } catch (Exception ignored) { }
        }
        void finish(boolean partial) {
            if (collection!=null && collection.hasResult()) {
                try { JSONObject result=collection.result(); result.put("partial",partial); store(this,result,true); } catch (Exception e) { fail("unsupported"); }
            } else fail("unavailable");
        }
        void fail(String reason) {
            if (service==null || stopped || writing) return;
            if (service.id.equals("_academic")) { saveAcademic(true); return; }
            if (service.id.equals("profile") && saved.module("profile")!=null && "ok".equals(saved.module("profile").optString("state")) && saved.module("profile").optLong("updated")>=wallStarted-30_000) { store(this,saved.module("profile"),true); return; }
            try { store(this,new JSONObject().put("state",reason).put("source",service.entry).put("fields",new JSONArray()).put("tables",new JSONArray()).put("links",new JSONArray()),false); } catch (Exception ignored) { }
        }
        void beginAcademic() {
            if (academicScripts.isEmpty()) { fail("unsupported"); return; }
            int active=generation; String token=java.util.UUID.randomUUID().toString(); querying=true;
            String code=(academicInjected?"":academicScripts)+"\nJSON.stringify((function(){try{var sid=String(NwpuImport.context(window,document).student);NwpuAcademic.start("+JSONObject.quote(token)+",sid);NwpuCampus.profileStart("+JSONObject.quote(token)+",sid);return {ready:true,identity:sid};}catch(e){return {ready:false};}})())";
            academicInjected=true;
            web.evaluateJavascript(code,raw -> {
                if (!alive() || active!=generation || service==null) return;
                JSONObject r; try { r=decode(raw); } catch (Exception e) { r=new JSONObject(); }
                if (!r.optBoolean("ready")) { querying=false; timeout(); return; }
                try { if (!owner.equals(CampusData.accountKey(r.optString("identity")))) { listener.loginRequired(); return; } } catch (Exception e) { querying=false; fail("unavailable"); return; }
                pollAcademic(active,token,SystemClock.elapsedRealtime()+180_000,-1);
            });
        }
        void pollAcademic(int active,String token,long limit,int previous) {
            if (!alive() || active!=generation || service==null) return;
            if (System.currentTimeMillis()-lastBeat>60_000) { lastBeat=System.currentTimeMillis(); CampusAutoRefresh.heartbeat(activity,run,owner); }
            if (SystemClock.elapsedRealtime()>limit) { saveAcademic(true); return; }
            web.evaluateJavascript("(function(){var a=window.__louAcademicResult||{};return JSON.stringify({academic:a.revision==="+academicRevision+"?{token:a.token,status:a.status,revision:a.revision}:a,profile:"+(profileSaved?"null":"window.__louProfileResult||{}")+"});})()",raw -> {
                if (!alive() || active!=generation || service==null) return;
                JSONObject both; try { both=decode(raw); } catch (Exception e) { both=new JSONObject(); }
                JSONObject a=both.optJSONObject("academic"), p=both.optJSONObject("profile");
                if (!profileSaved && p!=null && token.equals(p.optString("token")) && "ok".equals(p.optString("status")) && p.optJSONObject("data")!=null) {
                    profileSaved=true; String profile=p.optJSONObject("data").toString();
                    io.execute(() -> { try { if (!stopped && CampusAutoRefresh.owns(activity,run,owner)) { CampusData data=CampusStore.update(activity.getApplicationContext(),profile,owner); activity.runOnUiThread(() -> { if (alive() && active==generation) saved=data; }); } } catch (Exception ignored) { } });
                }
                int finished=previous; long nextLimit=limit;
                if (a!=null && token.equals(a.optString("token"))) {
                    academicRevision=a.optInt("revision",-1);
                    JSONObject data=a.optJSONObject("data"); if (data!=null) {
                        academicPending=data.toString(); JSONObject progress=data.optJSONObject("gradeProgress");
                        if (progress!=null) { finished=progress.optInt("finished"); if (finished>previous) nextLimit=Math.max(limit,SystemClock.elapsedRealtime()+90_000); }
                        if (SystemClock.elapsedRealtime()-academicPreview>1000) { academicPreview=SystemClock.elapsedRealtime(); saveAcademicPreview(academicPending); }
                    }
                    if ("ok".equals(a.optString("status")) || "error".equals(a.optString("status"))) { saveAcademic(!"ok".equals(a.optString("status"))); return; }
                }
                final int count=finished; final long next=nextLimit;
                handler.postDelayed(() -> pollAcademic(active,token,next,count),80);
            });
        }
        void saveAcademicPreview(String value) {
            io.execute(() -> { try { if (!stopped) CampusStore.updateAcademic(activity.getApplicationContext(),value,owner,run); } catch (Exception ignored) { } });
        }
        void saveAcademic(boolean incomplete) {
            if (writing || service==null) return; writing=true; querying=false;
            int active=generation; String value=academicPending;
            io.execute(() -> {
                boolean ok=false, partial=incomplete;
                try {
                    if (value!=null && !stopped && CampusAutoRefresh.owns(activity,run,owner)) {
                        Academic data=CampusStore.updateAcademic(activity.getApplicationContext(),value,owner,run); ok=true;
                        for (String part:Academic.PARTS) partial|=data.incomplete(part);
                    }
                } catch (Exception ignored) { }
                final boolean result=ok, rest=partial;
                activity.runOnUiThread(() -> { if (alive() && active==generation) completeLane(this,null,result,rest); });
            });
        }
        void electric(int level,JSONObject binding) {
            querying=true; int active=generation; String token=java.util.UUID.randomUUID().toString();
            web.evaluateJavascript("NwpuCampus.electricStart("+JSONObject.quote(token)+","+level+","+binding+")",ignored -> pollElectric(active,token,level,binding,SystemClock.elapsedRealtime()+13_000));
        }
        void pollElectric(int active,String token,int level,JSONObject binding,long limit) {
            if (!alive() || generation!=active || service==null) return;
            if (SystemClock.elapsedRealtime()>limit) { querying=false; fail("network"); return; }
            web.evaluateJavascript("JSON.stringify(window.__louElectricResult||{})",raw -> {
                if (!alive() || active!=generation || service==null) return;
                JSONObject r; try { r=decode(raw); } catch (Exception e) { r=new JSONObject(); }
                if (!token.equals(r.optString("token")) || r.optString("status").equals("loading")) { handler.postDelayed(() -> pollElectric(active,token,level,binding,limit),80); return; }
                querying=false;
                if (!r.optString("status").equals("ok")) {
                    if (level==3 && !electricRetry) { electricRetry=true; activity.getSharedPreferences("campus-binding",0).edit().remove("room").apply(); electric(0,new JSONObject()); return; }
                    fail("unavailable"); return;
                }
                try {
                    if (level==3) { activity.getSharedPreferences("campus-binding",0).edit().putString("room",binding.toString()).apply(); store(this,r.getJSONObject("data"),true); }
                    else {
                        JSONObject option=CampusBindings.match(saved,r.optJSONArray("data"),level);
                        if (option==null) { fail("binding"); return; }
                        binding.put(new String[]{"campus","building","room"}[level],option.getString("value")); electric(level+1,binding);
                    }
                } catch (Exception e) { fail("unsupported"); }
            });
        }
    }
}
