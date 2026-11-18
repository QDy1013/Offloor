package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact sync progress indicator with a permanently reserved place in the page header. */
final class CampusSyncBanner extends FrameLayout implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final SharedPreferences prefs;
    private final Ring ring;
    private final TextView value;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable redraw=this::refresh;

    static void add(Activity host,LinearLayout parent) {
        CampusSyncBanner indicator=new CampusSyncBanner(host);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(Ui.dp(host,28),Ui.dp(host,28));
        params.leftMargin=Ui.dp(host,4);
        params.rightMargin=Ui.dp(host,2);
        parent.addView(indicator,params);
    }
    CampusSyncBanner(Context context) {
        super(context); prefs=context.getSharedPreferences("campus-auto",Context.MODE_PRIVATE);
        setFocusable(true); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ring=new Ring(context); addView(ring,new FrameLayout.LayoutParams(-1,-1));
        value=CourseUi.text(context,"",10,Ui.ACCENT,true); value.setGravity(Gravity.CENTER);
        addView(value,new FrameLayout.LayoutParams(-1,-1));
        setVisibility(View.GONE);
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
        boolean visible=active || pending || recent;
        setVisibility(visible?View.VISIBLE:View.GONE);
        if (!visible) return;
        int total=prefs.getInt("total",0), done=Math.max(0,Math.min(total,prefs.getInt("done",0)));
        boolean complete=!active && !pending && CampusSyncState.completed(result);
        boolean partial=complete && "partial".equals(result);
        int percent=CampusSyncState.percent(done,total,complete);
        boolean loading=active || pending;
        ring.setProgress(percent,active && (total<=0 || pending),loading || complete ? Ui.ACCENT : Ui.AMBER_TX);
        if (loading) {
            value.setText("");
        } else {
            value.setTextColor(complete ? Ui.GREEN : Ui.AMBER_TX);
            value.setText(complete ? "✓" : "!");
        }
        String title=active || pending?"校园信息同步中":partial?"校园信息已更新":complete?"校园信息已同步":"部分信息暂未同步";
        String phase=prefs.getString("sync_phase","");
        String attention=prefs.getString(CampusSilentRefresh.ATTENTION,"");
        String detail=pending?"正在准备，可以继续使用":active?(phase.isEmpty()?"后台读取中，可以继续使用":phase+"，可以继续使用"):
                !attention.isEmpty()?"已保留读到的信息，请到校园页完成登录或验证":partial?"部分信息待重试，其余内容已更新":complete?"课表、成绩及校园信息已更新":"已保留读到的信息，稍后自动重试";
        setContentDescription(title+"，"+detail+(active && total>0?"，"+done+" / "+total:""));
        if (!active && !pending) handler.postDelayed(redraw,Math.max(100,6000-(now-finished)));
    }

    private final class Ring extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        private int progress;
        private boolean indeterminate;
        private int color=Ui.ACCENT;
        Ring(Context context) { super(context); setContentDescription(null); }
        void setProgress(int value,boolean spinning,int tint) {
            progress=Math.max(0,Math.min(100,value)); indeterminate=spinning; color=tint; invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float diameter=Math.min(getWidth(),getHeight())-dp(10);
            if (diameter<=0) return;
            float left=(getWidth()-diameter)/2f, top=(getHeight()-diameter)/2f;
            float stroke=dp(1.6f);
            bounds.set(left,top,left+diameter,top+diameter);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Ui.withAlpha(Ui.RING,110)); canvas.drawArc(bounds,0,360,false,paint);
            paint.setColor(color);
            if (indeterminate) {
                float start=(SystemClock.uptimeMillis()/4f)%360f-90f;
                canvas.drawArc(bounds,start,88,false,paint); postInvalidateDelayed(24);
            } else if (progress>0) canvas.drawArc(bounds,-90,360f*progress/100f,false,paint);
        }
    }
}
