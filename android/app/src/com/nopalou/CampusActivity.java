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
    private LinearLayout list;
    @Override public void onCreate(Bundle b) { super.onCreate(b); Ui.apply(this); detail=getIntent().getStringExtra("module"); render(); }
    @Override public void onResume() {
        super.onResume(); io.execute(() -> {
            CampusData d=null; boolean broken=false;
            try { d=CampusStore.load(getApplicationContext()); } catch (Exception e) { broken=true; }
            CampusData result=d; boolean bad=broken;
            runOnUiThread(() -> { if (isDestroyed()) return; data=result; loaded=true; render(); if (bad) toast("校园存档无法读取，请重新同步"); });
        });
    }
    private int dp(float n) { return Ui.dp(this,n); }
    private TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this,s,size,color,bold); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout root=CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this,root);
        CampusServices.Service service=CampusServices.byId(detail);
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(16),dp(12),dp(8),dp(10)); root.addView(head);
        if (service!=null) head.addView(CourseUi.icon(this,"prev","返回校园",this::finish));
        head.addView(text(service==null?"我的校园":service.title,26,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        head.addView(CourseUi.icon(this,"sync",service==null?"全部同步":"同步此项",() -> { if (service==null) syncAll(); else open(service); }));
        if (service==null) head.addView(CourseUi.icon(this,"more","校园设置",this::settings));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
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
        LinearLayout hero=card();
        int saved=0; for (CampusServices.Service s:CampusServices.ALL) if (data!=null && CampusData.count(data.module(s.id))>0) saved++;
        hero.addView(text("西北工业大学",12,Ui.ACCENT,true)); CourseUi.gap(hero,10);
        hero.addView(text(saved==0?"把校园信息，放到一起":saved+" 项校园服务已有本机数据",22,Ui.TEXT,true));
        CourseUi.gap(hero,10); TextView sub=text("一次翱翔门户登录，自动完成各校园服务的统一认证并同步信息。宿舍选择、校方安全验证或校园网要求会单独提示。",12,Ui.SUB,false); sub.setLineSpacing(dp(4),1); hero.addView(sub);
        CourseUi.gap(hero,18); hero.addView(CourseUi.action(this,"全部同步",true,this::syncAll),CourseUi.row(dp(48)));
        section("我的学业"); for (CampusServices.Service s:CampusServices.ALL) if (s.academic) moduleCard(s);
        section("校园生活"); for (CampusServices.Service s:CampusServices.ALL) if (!s.academic && !s.id.equals("notices") && !s.id.equals("calendar")) moduleCard(s);
        section("公共信息"); for (CampusServices.Service s:CampusServices.ALL) if (s.id.equals("notices") || s.id.equals("calendar")) moduleCard(s);
        note("数据保存在本机。页面没有读到内容时会保留上次成功同步的数据，并显示本次结果。预约、缴费等操作请在校方页面完成。");
    }
    private void moduleCard(CampusServices.Service s) {
        JSONObject m=data==null?null:data.module(s.id); int count=CampusData.count(m);
        LinearLayout c=card(); c.setBackground(Ui.ripple(this,Ui.round(this,Ui.CARD,18),Ui.press(),18)); c.setFocusable(true);
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); c.addView(row);
        row.addView(text(s.title,16,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        String state=m==null?"":m.optString("state");
        row.addView(Ui.tag(this,CampusServices.status(state),"ok".equals(state)?Ui.ACCENT:Ui.SUB,"ok".equals(state)?Ui.ACCENT_BG:Ui.FILL));
        CourseUi.gap(c,9); c.addView(text(s.detail,12,Ui.SUB,false));
        if (count>0) {
            CourseUi.gap(c,10); JSONArray fields=m.optJSONArray("fields");
            for (int i=0; fields!=null && i<Math.min(2,fields.length()); i++) {
                JSONObject f=fields.optJSONObject(i); if (f!=null) { TextView v=text(f.optString("label")+"  "+f.optString("value"),13,Ui.TEXT,false); v.setMaxLines(2); c.addView(v); CourseUi.gap(c,5); }
            }
            c.addView(text(count+" 条信息 · "+time(m.optLong("updated"))+("ok".equals(state)?"":" · 显示上次数据"),11,Ui.MUTE,false));
        }
        c.setOnClickListener(v -> startActivity(new Intent(this,CampusActivity.class).putExtra("module",s.id)));
    }
    private void renderDetail(CampusServices.Service s) {
        JSONObject m=data==null?null:data.module(s.id); int count=CampusData.count(m);
        LinearLayout intro=card(); intro.addView(text(s.detail,15,Ui.TEXT,true)); CourseUi.gap(intro,10);
        intro.addView(text(m==null?"尚未同步":CampusServices.status(m.optString("state"))+" · "+time(m.optLong("updated")),12,Ui.SUB,false));
        if (count>0 && !"ok".equals(m.optString("state"))) { CourseUi.gap(intro,8); intro.addView(text("以下为上次成功读取的数据，本次没有覆盖。",12,Ui.SUB,false)); }
        CourseUi.gap(intro,16); intro.addView(CourseUi.action(this,s.id.equals("electric")?"查询 / 选择宿舍":"打开校方页面并同步",true,() -> open(s)),CourseUi.row(dp(48)));
        if (count==0) { note("应用会复用翱翔门户登录状态进入此服务，并读取已加载的字段和表格。若入口或页面尚未适配，会如实提示；需要校内网络的服务，请先连接校园网。"); return; }
        JSONArray fields=m.optJSONArray("fields");
        if (fields!=null && fields.length()>0) {
            LinearLayout c=card();
            for (int i=0;i<fields.length();i++) { JSONObject f=fields.optJSONObject(i); if (f==null) continue;
                TextView label=text(f.optString("label"),12,Ui.SUB,false); c.addView(label); CourseUi.gap(c,5);
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
        CourseUi.gap(c,10); c.addView(text(rows.length()+" 条记录"+(t.optBoolean("truncated")?" · 页面超过200条，仅保留前200条":""),11,Ui.MUTE,false));
    }
    private void cell(LinearLayout row,String value,boolean header) {
        TextView t=text(value,13,header?Ui.SUB:Ui.TEXT,header); t.setPadding(dp(12),dp(12),dp(12),dp(12)); t.setMinHeight(dp(44)); t.setTextIsSelectable(!header);
        row.addView(t,new LinearLayout.LayoutParams(dp(150),-2));
    }
    private void open(CampusServices.Service s) { startActivity(new Intent(this,CampusWebActivity.class).putExtra("module",s.id)); }
    private void syncAll() { startActivityForResult(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu").putExtra("reuse_session",true),41); }
    @Override protected void onActivityResult(int request,int result,Intent value) {
        super.onActivityResult(request,result,value);
        if (request==41 && result==RESULT_OK) startActivity(new Intent(this,CampusWebActivity.class).putExtra("all",true));
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
    @Override public void onDestroy() { io.shutdown(); super.onDestroy(); }
}
