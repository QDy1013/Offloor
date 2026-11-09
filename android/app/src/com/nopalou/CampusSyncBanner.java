package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Shared, non-blocking sync status. Each page listens only while its banner is attached. */
final class CampusSyncBanner extends LinearLayout implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final SharedPreferences prefs;
    private final TextView title,detail,count;
    private final ProgressBar progress;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable redraw=this::refresh;

    static void add(Activity host,LinearLayout parent) {
        CampusSyncBanner banner=new CampusSyncBanner(host);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(Ui.dp(host,16),Ui.dp(host,6),Ui.dp(host,16),Ui.dp(host,10));
        parent.addView(banner,params);
    }
    CampusSyncBanner(Context context) {
        super(context); prefs=context.getSharedPreferences("campus-auto",Context.MODE_PRIVATE);
        setOrientation(VERTICAL); setPadding(dp(14),dp(12),dp(14),dp(12));
        setBackground(Ui.round(context,Ui.ACCENT_BG,16));
        LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); addView(row);
        title=CourseUi.text(context,"信息同步中",13,Ui.ACCENT,true);
        row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        count=CourseUi.text(context,"",12,Ui.ACCENT,true); row.addView(count);
        detail=CourseUi.text(context,"",11,Ui.SUB,false); detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(-1,-2); textParams.topMargin=dp(4); addView(detail,textParams);
        progress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        progress.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.withAlpha(Ui.ACCENT,35)));
        LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,dp(4)); barParams.topMargin=dp(9); addView(progress,barParams);
        setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        refresh();
    }
    private int dp(float value) { return Ui.dp(getContext(),value); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(this); refresh();
    }
    @Override protected void onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this); handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow();
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences preferences,String key) {
        handler.removeCallbacks(redraw); handler.postDelayed(redraw,60);
    }
    private void refresh() {
        handler.removeCallbacks(redraw);
        boolean active=prefs.getBoolean("active",false) && CampusSilentRefresh.running();
        boolean pending=prefs.getBoolean("after_login_pending",false);
        long finished=prefs.getLong("finished",0), now=System.currentTimeMillis();
        String result=prefs.getString("sync_result","");
        boolean recent=!result.isEmpty() && CampusSyncState.visible(false,false,finished,now);
        setVisibility(active || pending || recent?View.VISIBLE:View.GONE);
        if (getVisibility()!=View.VISIBLE) return;
        int total=prefs.getInt("total",0), done=Math.max(0,Math.min(total,prefs.getInt("done",0)));
        if (active || pending) {
            title.setText("校园信息同步中");
            count.setText(total>0 && !pending?done+" / "+total:"");
            String phase=prefs.getString("sync_phase","");
            detail.setText(pending?"正在准备，可以继续使用":(phase.isEmpty()?"后台读取中，可以继续使用":phase+" · 可继续使用"));
            progress.setIndeterminate(total<=0 || pending);
            if (total>0) progress.setProgress(CampusSyncState.percent(done,total,false));
        } else {
            boolean complete="complete".equals(result);
            title.setText(complete?"校园信息已同步":"部分信息暂未同步");
            String attention=prefs.getString(CampusSilentRefresh.ATTENTION,"");
            detail.setText(!attention.isEmpty()?"已保留读到的信息，请到校园页完成登录或验证":complete?"课表、成绩及校园信息已更新":"已保留读到的信息，稍后自动重试");
            count.setText(complete?"完成":""); progress.setIndeterminate(false);
            progress.setProgress(CampusSyncState.percent(done,total,complete));
            handler.postDelayed(redraw,Math.max(100,6000-(now-finished)));
        }
    }
}
