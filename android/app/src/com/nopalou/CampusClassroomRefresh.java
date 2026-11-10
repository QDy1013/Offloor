package com.nopalou;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** A single current query, independent of the long academic sync and its lease. */
final class CampusClassroomRefresh {
    interface Listener { void finished(String error); }
    private final Activity host;
    private final String token;
    private final Listener listener;
    private CampusBatch batch;
    private FrameLayout stage;
    private boolean stopped,finished;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private int dayOperation;
    CampusClassroomRefresh(Activity host,String token,Listener listener) { this.host=host; this.token=token; this.listener=listener; }
    void start(CampusData data,CampusClassroomQuery query) {
        try {
            host.getSharedPreferences("classroom-live",0).edit().putString("query_token",token).apply();
            stage=new FrameLayout(host) { @Override public boolean dispatchTouchEvent(MotionEvent event) { return false; } };
            stage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ViewGroup decor=(ViewGroup)host.getWindow().getDecorView(); decor.addView(stage,0,new FrameLayout.LayoutParams(-1,-1));
            batch=new CampusBatch(host,stage,data,java.util.Collections.singletonList(CampusServices.byId("classroom")),token,
                    CampusSilentRefresh.asset(host,"nwpu-campus.js"),CampusSilentRefresh.asset(host,"nwpu-sso.js"),CampusSilentRefresh.asset(host,"nwpu-auto.js"),new CampusBatch.Listener() {
                public void progress(int done,int total,int success,int failed,String active) { }
                public void challenge(WebView view,boolean visible) { if (visible) finish("学校要求安全验证，请先到校园页完成验证后重试"); }
                public void loginRequired() { finish("校园登录状态需要更新，请先完成登录后重试"); }
                public void complete(int success,int failed) { finish(success>0 && failed==0?"":"本次未读到可靠结果，请稍后重试"); }
            },query);
            batch.start();
        } catch (Exception e) { finish("教室查询暂时无法启动，请稍后重试"); }
    }
    private void finish(String error) {
        if (stopped || finished) return;
        finished=true;
        if (!error.isEmpty()) stop();
        if (!host.isFinishing() && !host.isDestroyed()) listener.finished(error);
    }
    interface DayListener { void finished(org.json.JSONObject result,String error); }
    void day(String name,String date,DayListener callback) {
        WebView web=batch==null?null:batch.classroomWeb();
        if (stopped || !finished || web==null) { callback.finished(null,"查询会话已失效，请重新查询教室"); return; }
        final int operation=++dayOperation;
        try {
            String script=CampusSilentRefresh.asset(host,"nwpu-room-day.js");
            web.evaluateJavascript(script+";window.__louRoomDayOperation="+operation+";window.__louRoomDay=null;NwpuRoomDay.load("+org.json.JSONObject.quote(name)+","+org.json.JSONObject.quote(date)+").then(r=>{if(window.__louRoomDayOperation==="+operation+")window.__louRoomDay={result:r}}).catch(()=>{if(window.__louRoomDayOperation==="+operation+")window.__louRoomDay={error:true}});",null);
            long until=android.os.SystemClock.elapsedRealtime()+160_000;
            Runnable poll=new Runnable() { public void run() {
                if (stopped || operation!=dayOperation) return;
                web.evaluateJavascript("JSON.stringify(window.__louRoomDay)",value -> {
                    if (stopped || operation!=dayOperation) return;
                    org.json.JSONObject result=new org.json.JSONObject();
                    try { Object raw=new org.json.JSONTokener(value).nextValue(); if (raw instanceof String) result=new org.json.JSONObject((String)raw); } catch (Exception ignored) { }
                    if (result.has("result")) callback.finished(result.optJSONObject("result"),"");
                    else if (result.optBoolean("error") || android.os.SystemClock.elapsedRealtime()>until) callback.finished(null,"未读到今天的节次状态，请稍后重试");
                    else handler.postDelayed(this,400);
                });
            }};
            handler.postDelayed(poll,300);
        } catch (Exception e) { callback.finished(null,"教室详情暂时无法读取"); }
    }
    void stop() {
        if (stopped) return; stopped=true; handler.removeCallbacksAndMessages(null); dayOperation++;
        if (batch!=null) { batch.stop(); batch=null; }
        if (stage!=null && stage.getParent() instanceof ViewGroup) ((ViewGroup)stage.getParent()).removeView(stage);
        // A late write can still finish, but the page accepts only this query's result token.
    }
}
