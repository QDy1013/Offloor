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
    private boolean stopped;
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
        if (stopped) return;
        stop();
        if (!host.isFinishing() && !host.isDestroyed()) listener.finished(error);
    }
    void stop() {
        if (stopped) return; stopped=true;
        if (batch!=null) { batch.stop(); batch=null; }
        if (stage!=null && stage.getParent() instanceof ViewGroup) ((ViewGroup)stage.getParent()).removeView(stage);
        // A late write can still finish, but the page accepts only this query's result token.
    }
}
