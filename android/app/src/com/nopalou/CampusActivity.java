package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Campus overview and offline detail pages. Each module shows its own freshness and result. */
public class CampusActivity extends Activity {
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private CampusData data;
    private String detail;
    private boolean loaded;
    private boolean resumed;
    private LinearLayout list;
    private ScrollView scroll;
    private final android.os.Handler updates=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable changed=() -> { if (resumed) { updates.removeCallbacks(this.refresh); updates.postDelayed(this.refresh,250); } };
    private final Runnable refresh=this::reload;
    private final Runnable clock=new Runnable() { public void run() {
        if (!resumed) return;
        int y=scroll==null?0:scroll.getScrollY(); render(); if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,y)); updates.postDelayed(this,60_000);
    } };
    @Override public void onCreate(Bundle b) { super.onCreate(b); Ui.apply(this); detail=getIntent().getStringExtra("module"); render(); }
    @Override public void onResume() {
        super.onResume(); resumed=true; CampusStore.observe(changed); CampusAutoRefresh.maybeRefresh(this); reload(); updates.postDelayed(clock,60_000);
    }
    private void reload() {
        if (isDestroyed() || io.isShutdown()) return;
        io.execute(() -> {
            CampusData d=null; boolean broken=false;
            try { d=CampusStore.load(getApplicationContext()); } catch (Exception e) { broken=true; }
            CampusData result=d; boolean bad=broken;
            runOnUiThread(() -> { if (isDestroyed() || isFinishing()) return; int y=scroll==null?0:scroll.getScrollY(); data=result; loaded=true; render(); if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,y)); if (bad) toast("校园存档无法读取"); });
        });
    }
    private int dp(float n) { return Ui.dp(this,n); }
    private TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this,s,size,color,bold); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout root=CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this,root);
        CampusServices.Service service=CampusServices.selectedService(detail);
        LinearLayout head=new LinearLayout(this); head.setBaselineAligned(false); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(16),dp(12),dp(8),dp(10)); root.addView(head);
        if (service!=null) head.addView(CourseUi.icon(this,"prev","返回校园",this::finish));
        head.addView(text(service==null?"我的校园":service.title,26,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        if (service==null) head.addView(CourseUi.icon(this,"more","校园设置",this::settings));
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        list=CourseUi.column(this); list.setPadding(dp(16),dp(4),dp(16),dp(28)); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (!loaded) note("正在读取本机数据…");
        else if (service!=null) renderDetail(service);
        else renderOverview();
        if (service==null) root.addView(AppNavigation.bar(this,AppNavigation.CAMPUS),new LinearLayout.LayoutParams(-1,-2));
    }
    private LinearLayout card() {
        LinearLayout c=CourseUi.column(this); c.setPadding(dp(18),dp(18),dp(18),dp(18)); Ui.cardSurface(c,18);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(12); list.addView(c,p); return c;
    }
    private void note(String s) { TextView v=text(s,12,Ui.SUB,false); v.setLineSpacing(dp(4),1); v.setPadding(dp(4),dp(8),dp(4),dp(14)); list.addView(v); }
    private void section(String s) { TextView v=text(s,16,Ui.TEXT,true); v.setPadding(dp(4),dp(20),0,dp(12)); list.addView(v); }
    private static String time(long t) { return t<=0?"尚未同步":new SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(new Date(t)); }
    private void renderOverview() {
        if (data==null || data.owner.isEmpty()) { LinearLayout login=card(); login.addView(CourseUi.action(this,"登录校园账号",true,() -> startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"))),CourseUi.row(dp(48))); }
        else {
            android.content.SharedPreferences auto=getSharedPreferences("campus-auto",MODE_PRIVATE);
            String attention=CampusSilentRefresh.attention(this);
            if (!attention.isEmpty()) {
                // 后台更新遇到了只能本人处理的情况：给一个入口，走原来的可见读取页（含登录与安全验证）
                LinearLayout warn=card();
                warn.addView(text("login".equals(attention)?"校园登录已过期，暂停自动更新":"校方要求安全验证，暂停自动更新",15,Ui.TEXT,true));
                TextView why=text("下面是上次读到的信息。重新登录一次后，会继续在后台自动更新。",12,Ui.SUB,false); why.setPadding(0,dp(6),0,dp(12)); warn.addView(why);
                warn.addView(CourseUi.action(this,"login".equals(attention)?"重新登录并更新":"去完成验证",true,() -> startActivity(new Intent(this,CampusWebActivity.class).putExtra("all",true).putExtra("automatic",true))),CourseUi.row(dp(46)));
            } else if (auto.getBoolean("active",false)) {
                int done=auto.getInt("done",0),total=auto.getInt("total",0);
                note("正在后台更新"+(total>0?" · "+done+"/"+total:"")+"，可以照常使用，读到的信息会直接显示");
            } else note("已开启自动更新");
        }
        for (CampusServices.Service s:CampusServices.CAMPUS) moduleCard(s);
    }
    private void moduleCard(CampusServices.Service s) {
        JSONObject m=data==null?null:data.module(s.id); int count=CampusData.count(m);
        LinearLayout c=card(); c.setBackground(Ui.ripple(this,Ui.round(this,Ui.CARD,18),Ui.press(),18)); c.setFocusable(true);
        LinearLayout row=new LinearLayout(this); row.setBaselineAligned(false); row.setGravity(Gravity.CENTER_VERTICAL); c.addView(row);
        row.addView(text(s.title,16,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        String state=m==null?"":m.optString("state");
        if (!"ok".equals(state) && !"empty".equals(state)) row.addView(Ui.tag(this,CampusServices.status(state),Ui.SUB,Ui.FILL));
        CampusSummary summary=CampusSummary.of(s.id,m,System.currentTimeMillis());
        CourseUi.gap(c,12); TextView main=text(summary.value,24,Ui.TEXT,true); main.setMaxLines(2); c.addView(main);
        if (!summary.note.isEmpty()) { CourseUi.gap(c,8); c.addView(text(summary.note,13,Ui.SUB,false)); }
        if (count>0) { CourseUi.gap(c,10); c.addView(text(time(m.optLong("updated"))+("ok".equals(state)?"":" · 上次数据"),11,Ui.MUTE,false)); }
        c.setOnClickListener(v -> startActivity(new Intent(this,CampusActivity.class).putExtra("module",s.id)));
    }
    private void renderDetail(CampusServices.Service s) {
        JSONObject m=data==null?null:data.module(s.id); int count=CampusData.count(m);
        LinearLayout intro=card(); intro.addView(text(s.detail,15,Ui.TEXT,true)); CourseUi.gap(intro,10);
        intro.addView(text(m==null?"尚未同步":CampusServices.status(m.optString("state"))+" · "+time(m.optLong("updated")),12,Ui.SUB,false));
        if (m!=null && m.optBoolean("partial")) { CourseUi.gap(intro,8); intro.addView(text("部分信息已读取，剩余内容会自动重试。",12,Ui.SUB,false)); }
        if (count>0 && !"ok".equals(m.optString("state"))) { CourseUi.gap(intro,8); intro.addView(text("以下为上次成功读取的数据，本次没有覆盖。",12,Ui.SUB,false)); }
        if (s.id.equals("electric") && m!=null && "binding".equals(m.optString("state"))) {
            CourseUi.gap(intro,16); intro.addView(CourseUi.action(this,"关联本人电表",false,() -> startActivity(new Intent(this,CampusWebActivity.class).putExtra("module","electric").putExtra("bind",true))),CourseUi.row(dp(48)));
        }
        if (count==0) { note("登录后会自动进入此服务并读取信息，未读到的项目会自动重试。需要校内网络的服务请连接校园网；若校方未提供本人数据，会显示具体状态。"); return; }
        JSONArray fields=m.optJSONArray("fields");
        if (fields!=null && fields.length()>0) {
            LinearLayout c=card();
            for (int i=0;i<fields.length();i++) { JSONObject f=fields.optJSONObject(i); if (f==null) continue;
                String group=f.optString("group");
                TextView label=text((group.isEmpty()?"":group+" · ")+f.optString("label"),12,Ui.SUB,false); c.addView(label); CourseUi.gap(c,5);
                TextView value=text(f.optString("value"),16,Ui.TEXT,true); value.setTextIsSelectable(true); c.addView(value); if (i<fields.length()-1) CourseUi.gap(c,18);
            }
        }
        JSONArray tables=m.optJSONArray("tables");
        for (int i=0;tables!=null && i<tables.length();i++) table(tables.optJSONObject(i));
        JSONArray links=m.optJSONArray("links");
        for (int i=0;links!=null && i<links.length();i++) {
            JSONObject link=links.optJSONObject(i); if (link==null) continue;
            LinearLayout c=card(); c.addView(text(link.optString("title"),15,Ui.TEXT,true)); c.setFocusable(true);
            c.setOnClickListener(v -> startActivity(new Intent(this,CampusWebActivity.class).putExtra("module",s.id).putExtra("url",link.optString("url"))));
        }
        String src=m.optString("source");
        try { note("来源："+new java.net.URI(src).getHost()+" · "+time(m.optLong("updated"))+"\n仅展示同步时已加载的内容，以校方系统为准。"); } catch (Exception ignored) { }
    }
    private void table(JSONObject t) {
        if (t==null) return;
        LinearLayout c=card(); String title=t.optString("title"); if (!title.isEmpty()) { c.addView(text(title,16,Ui.TEXT,true)); CourseUi.gap(c,12); }
        JSONArray columns=t.optJSONArray("columns"), rows=t.optJSONArray("rows"); if (columns==null || rows==null) return;
        HorizontalScrollView sc=new HorizontalScrollView(this); sc.setHorizontalScrollBarEnabled(true); LinearLayout grid=CourseUi.column(this); sc.addView(grid); c.addView(sc);
        LinearLayout header=new LinearLayout(this); grid.addView(header);
        for (int i=0;i<columns.length();i++) cell(header,columns.optString(i),true);
        int[] shown={0}; Runnable append=() -> {
            int end=Math.min(shown[0]+30,rows.length());
            for (int r=shown[0];r<end;r++) { LinearLayout row=new LinearLayout(this); row.setBackgroundColor(r%2==0?Ui.CARD:Ui.FILL); grid.addView(row); JSONArray values=rows.optJSONArray(r);
                for (int col=0;col<columns.length();col++) cell(row,values==null?"":values.optString(col),false);
            } shown[0]=end;
        }; append.run();
        if (rows.length()>30) { TextView more=CourseUi.action(this,"显示更多记录",false,() -> {}); CourseUi.gap(c,12); c.addView(more);
            more.setOnClickListener(v -> { append.run(); if (shown[0]>=rows.length()) more.setVisibility(View.GONE); }); }
        CourseUi.gap(c,10); c.addView(text(rows.length()+" 条记录"+(t.optBoolean("truncated")?" · 部分记录受读取上限限制":""),11,Ui.MUTE,false));
    }
    private void cell(LinearLayout row,String value,boolean header) {
        TextView t=text(value,13,header?Ui.SUB:Ui.TEXT,header); t.setPadding(dp(12),dp(12),dp(12),dp(12)); t.setMinHeight(dp(44)); t.setTextIsSelectable(!header);
        row.addView(t,new LinearLayout.LayoutParams(dp(150),-2));
    }
    private void settings() {
        new AlertDialog.Builder(this,Ui.dialogTheme()).setItems(new String[]{"切换教务账号","清空本机校园数据","下载最新版"},(d,i) -> {
            if (i==0) startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"));
            else if (i==2) startActivity(new Intent(this,UpdateActivity.class));
            else new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("清空本机校园数据？").setMessage("将删除此页面的同步存档和宿舍绑定，可重新同步。").setNegativeButton("取消",null).setPositiveButton("清空",(a,b) -> io.execute(() -> {
                CampusStore.clear(getApplicationContext()); runOnUiThread(() -> { data=null; render(); });
            })).show();
        }).show();
    }
    @Override public void finish() { super.finish(); AppNavigation.fade(this); }
    @Override public void onPause() { resumed=false; CampusStore.unobserve(changed); updates.removeCallbacksAndMessages(null); CampusAutoRefresh.pause(this); super.onPause(); }
    @Override public void onDestroy() { CampusSilentRefresh.hostDestroyed(this); io.shutdown(); super.onDestroy(); }
}
