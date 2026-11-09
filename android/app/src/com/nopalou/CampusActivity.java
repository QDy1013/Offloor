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
    private boolean busWeekend;
    private CampusClassroomRefresh classroomRefresh;
    private boolean roomAttempted,roomLoading;
    private String roomCampus="长安校区",roomDate="",roomToken="",roomError="";
    private int roomStart,roomEnd,roomLimit=40;
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
    @Override public void onCreate(Bundle b) { super.onCreate(b); Ui.apply(this); detail=getIntent().getStringExtra("module"); busWeekend=b==null?CampusBusSchedule.weekend(System.currentTimeMillis()):b.getBoolean("bus_weekend"); render(); }
    @Override public void onSaveInstanceState(Bundle b) { b.putBoolean("bus_weekend",busWeekend); super.onSaveInstanceState(b); }
    @Override public void onResume() {
        super.onResume(); resumed=true;
        if ("classroom".equals(detail)) {
            stopClassroomQuery(); roomDate=CampusClassroomQuery.today(System.currentTimeMillis());
            int[] range=CampusClassroomQuery.defaultRange(System.currentTimeMillis()); roomStart=range[0]; roomEnd=range[1];
            roomCampus=getSharedPreferences("classroom-live",MODE_PRIVATE).getString("campus","长安校区");
            if (!"友谊校区".equals(roomCampus)) roomCampus="长安校区";
            roomAttempted=false; roomToken=""; roomError=""; roomLoading=true;
        }
        CampusStore.observe(changed); CampusAutoRefresh.maybeRefresh(this); reload(); updates.postDelayed(clock,60_000);
    }
    private void reload() {
        if (isDestroyed() || io.isShutdown()) return;
        io.execute(() -> {
            CampusData d=null; boolean broken=false;
            try { d=CampusStore.load(getApplicationContext()); } catch (Exception e) { broken=true; }
            CampusData result=d; boolean bad=broken;
            runOnUiThread(() -> { if (isDestroyed() || isFinishing()) return; int y=scroll==null?0:scroll.getScrollY(); data=result; loaded=true;
                if ("classroom".equals(detail) && resumed && !roomAttempted) startClassroomQuery(false);
                render(); if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,y)); if (bad) toast("校园存档无法读取"); });
        });
    }
    private int dp(float n) { return Ui.dp(this,n); }
    private TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this,s,size,color,bold); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout root=CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this,root);
        CampusServices.Service service=CampusServices.selectedService(detail);
        LinearLayout head=new LinearLayout(this); head.setBaselineAligned(false); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(20),dp(8),dp(8),dp(8)); root.addView(head);
        if (service!=null) head.addView(CourseUi.icon(this,"prev","返回校园",this::finish));
        head.addView(text(service==null?"我的校园":service.title,24,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        if (service==null) head.addView(CourseUi.icon(this,"more","校园设置",this::settings));
        CampusSyncBanner.add(this,root);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        list=CourseUi.column(this); list.setPadding(dp(18),dp(4),dp(18),dp(28)); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
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
        boolean linked=data!=null && !data.owner.isEmpty();
        if (!linked) {
            LinearLayout login=card(); login.addView(text("连接校园账号",18,Ui.TEXT,true)); CourseUi.gap(login,6);
            login.addView(text("登录后自动同步余额、电量与校园服务",12,Ui.SUB,false)); CourseUi.gap(login,14);
            login.addView(CourseUi.action(this,"登录校园账号",true,() -> startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"))),CourseUi.row(dp(48)));
        } else {
            String attention=CampusSilentRefresh.attention(this);
            if (!attention.isEmpty()) {
                LinearLayout warn=card(); warn.setBackground(Ui.round(this,Ui.AMBER_BG,18));
                warn.addView(text("login".equals(attention)?"自动登录暂未完成":"需要完成校方验证",14,Ui.AMBER_TX,true));
                CourseUi.gap(warn,6); warn.addView(text("已保存的信息仍可查看",12,Ui.SUB,false)); CourseUi.gap(warn,10);
                warn.addView(CourseUi.action(this,"login".equals(attention)?"继续登录":"完成验证",false,() -> startActivity(new Intent(this,CampusWebActivity.class).putExtra("all",true).putExtra("automatic",true))),CourseUi.row(dp(44)));
            }
        }
        accountOverview();
        section("校园服务");
        moduleCard(CampusServices.byId("classroom"));
        moduleCard(CampusServices.byId("bus"));
        TextView foot=text("数据来自校方系统 · 点开查看详情与更新时间",11,Ui.MUTE,false);
        foot.setPadding(dp(4),dp(6),dp(4),dp(10)); list.addView(foot);
    }
    private void accountOverview() {
        LinearLayout account=card(); account.setPadding(dp(8),dp(18),dp(8),dp(12)); account.setBackground(Ui.heroSurface(this,24));
        TextView caption=text("校园账户",14,Ui.ON_HERO,true); caption.setPadding(dp(12),0,dp(12),dp(14)); account.addView(caption);
        LinearLayout values=new LinearLayout(this); values.setBaselineAligned(false); account.addView(values);
        String[] ids={"network","card","electric"};
        for (String id:ids) {
            JSONObject module=data==null?null:data.module(id); boolean present=CampusData.count(module)>0;
            CampusServices.Service service=CampusServices.byId(id);
            CampusSummary summary=CampusSummary.of(id,module,System.currentTimeMillis());
            LinearLayout item=CourseUi.column(this); item.setPadding(dp(10),dp(10),dp(6),dp(12));
            item.setBackground(Ui.ripple(this,null,Ui.withAlpha(android.graphics.Color.WHITE,24),16));
            item.addView(text(service.title,12,Ui.HERO_SUB,false)); CourseUi.gap(item,9);
            TextView value=text(summary.value,present?20:13,Ui.ON_HERO,true); value.setMinHeight(dp(28)); value.setMaxLines(2); value.setEllipsize(android.text.TextUtils.TruncateAt.END); item.addView(value);
            CourseUi.gap(item,9);
            String freshness=present?new SimpleDateFormat("M/d HH:mm",Locale.CHINA).format(new Date(module.optLong("updated"))):"等待同步";
            if (present && !"ok".equals(module.optString("state"))) freshness="上次数据";
            item.addView(text(freshness,10,Ui.HERO_SUB,false));
            item.setContentDescription(service.title+"，"+summary.value+"，查看详情"); item.setFocusable(true);
            item.setOnClickListener(v -> openModule(id));
            values.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        }
    }
    private void openModule(String id) { startActivity(new Intent(this,CampusActivity.class).putExtra("module",id)); }
    private void moduleCard(CampusServices.Service s) {
        JSONObject module=data==null?null:data.module(s.id);
        CampusSummary summary="classroom".equals(s.id)?new CampusSummary("选择校区和时段，打开即查今天","每次打开重新查询"):CampusSummary.of(s.id,module,System.currentTimeMillis());
        LinearLayout c=card(); c.setPadding(dp(14),dp(16),dp(14),dp(16));
        c.setBackground(Ui.ripple(this,Ui.round(this,Ui.CARD,20),Ui.press(),20)); c.setFocusable(true); Ui.pressScale(c,.985f);
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); c.addView(row);
        int color=s.id.equals("classroom")?Ui.ACCENT:Ui.TEAL, fill=s.id.equals("classroom")?Ui.ACCENT_BG:Ui.TEAL_BG;
        android.widget.ImageView icon=new android.widget.ImageView(this); icon.setImageDrawable(new CampusServiceIcon(s.id,color));
        icon.setPadding(dp(11),dp(11),dp(11),dp(11)); icon.setBackground(Ui.round(this,fill,14));
        row.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout body=CourseUi.column(this); LinearLayout.LayoutParams bodyParams=new LinearLayout.LayoutParams(0,-2,1); bodyParams.leftMargin=dp(14); row.addView(body,bodyParams);
        body.addView(text(s.title,15,Ui.TEXT,true)); CourseUi.gap(body,5);
        TextView value=text(summary.value,12,Ui.SUB,false); value.setMaxLines(2); value.setEllipsize(android.text.TextUtils.TruncateAt.END); body.addView(value);
        if (!summary.note.isEmpty()) { CourseUi.gap(body,4); TextView note=text(summary.note,11,Ui.MUTE,false); note.setMaxLines(2); note.setEllipsize(android.text.TextUtils.TruncateAt.END); body.addView(note); }
        TextView arrow=text("›",24,Ui.MUTE,false); arrow.setPadding(dp(10),0,0,0); row.addView(arrow);
        c.setOnClickListener(v -> openModule(s.id));
    }
    private void renderDetail(CampusServices.Service s) {
        JSONObject m=data==null?null:data.module(s.id); int count=CampusData.count(m);
        if ("bus".equals(s.id)) { renderBus(m); return; }
        if ("classroom".equals(s.id)) { renderClassroom(m); return; }
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
    private void stopClassroomQuery() { if (classroomRefresh!=null) { classroomRefresh.stop(); classroomRefresh=null; } }
    private void startClassroomQuery(boolean repaint) {
        roomAttempted=true;
        if (data==null || data.owner.isEmpty()) { roomLoading=false; roomError="请先登录校园账号"; return; }
        CampusClassroomQuery query;
        try { roomDate=CampusClassroomQuery.today(System.currentTimeMillis()); query=new CampusClassroomQuery(roomCampus,roomDate,roomStart,roomEnd); }
        catch (Exception e) { roomLoading=false; toast("结束时间需要晚于开始时间"); return; }
        stopClassroomQuery(); roomToken=java.util.UUID.randomUUID().toString(); String token=roomToken;
        roomLoading=true; roomError=""; roomLimit=40;
        CampusClassroomRefresh reader=new CampusClassroomRefresh(this,token,error -> {
            if (!token.equals(roomToken)) return;
            roomLoading=false; roomError=error; classroomRefresh=null; reload();
        });
        classroomRefresh=reader; reader.start(data,query);
        if (repaint) render();
    }
    private void roomOptionsChanged() { stopClassroomQuery(); roomAttempted=true; roomLoading=false; roomToken=""; roomError=""; render(); }
    private void chooseRoomTime(boolean start) {
        int minute=start?roomStart:roomEnd;
        new android.app.TimePickerDialog(this,(view,hour,value) -> {
            if (start) roomStart=hour*60+value; else roomEnd=hour*60+value;
            roomOptionsChanged();
        },minute/60,minute%60,true).show();
    }
    private void renderClassroom(JSONObject module) {
        LinearLayout query=card(); query.addView(text("今天 · "+CampusClassroomQuery.today(System.currentTimeMillis()),13,Ui.SUB,true)); CourseUi.gap(query,12);
        TextView campus=CourseUi.action(this,roomCampus+"  ▾",false,() -> new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择校区").setItems(new String[]{"长安校区","友谊校区"},(d,i) -> {
            roomCampus=i==0?"长安校区":"友谊校区"; getSharedPreferences("classroom-live",MODE_PRIVATE).edit().putString("campus",roomCampus).apply(); roomOptionsChanged();
        }).show());
        campus.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,12),Ui.press(),12)); query.addView(campus,CourseUi.row(dp(44))); CourseUi.gap(query,10);
        LinearLayout times=new LinearLayout(this); query.addView(times);
        TextView start=CourseUi.action(this,"开始 "+CampusClassroomQuery.time(roomStart),false,() -> chooseRoomTime(true));
        TextView end=CourseUi.action(this,"结束 "+CampusClassroomQuery.time(roomEnd),false,() -> chooseRoomTime(false));
        start.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,12),Ui.press(),12)); end.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,12),Ui.press(),12));
        times.addView(start,new LinearLayout.LayoutParams(0,dp(44),1)); LinearLayout.LayoutParams endParams=new LinearLayout.LayoutParams(0,dp(44),1); endParams.leftMargin=dp(10); times.addView(end,endParams);
        CourseUi.gap(query,14); query.addView(CourseUi.action(this,roomLoading?"重新查询此时段":"查询此时段",true,() -> startClassroomQuery(true)),CourseUi.row(dp(48)));
        if (data==null || data.owner.isEmpty()) {
            note("登录校园账号后可查询当日的空闲教室。"); LinearLayout login=card(); login.addView(CourseUi.action(this,"登录校园账号",true,() -> startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"))),CourseUi.row(dp(48))); return;
        }
        if (roomLoading) {
            android.widget.ProgressBar bar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); bar.setIndeterminate(true);
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT)); list.addView(bar,CourseUi.row(dp(4)));
            note("正在重新查询今天所选时段，可以返回或继续操作。");
        }
        if (!roomError.isEmpty()) { note(roomError); return; }
        if (!CampusClassroomResult.current(module,roomToken,CampusClassroomQuery.today(System.currentTimeMillis()))) {
            if (!roomLoading) note("选择校区与时段后查询，旧结果不会作为本次结果展示。"); return;
        }
        java.util.List<CampusClassroomResult.Room> rooms=CampusClassroomResult.rooms(module);
        if (rooms.isEmpty()) { if (!roomLoading) note("该时段没有查到可展示的空闲教室。"); return; }
        section("找到 "+rooms.size()+" 间空闲教室"); note(CampusSummary.field(module,"查询条件"));
        LinearLayout result=card();
        for (int i=0;i<Math.min(roomLimit,rooms.size());i++) {
            CampusClassroomResult.Room room=rooms.get(i); result.addView(text(room.name,15,Ui.TEXT,true));
            if (!room.detail.isEmpty()) { CourseUi.gap(result,4); result.addView(text(room.detail,11,Ui.SUB,false)); }
            if (i<Math.min(roomLimit,rooms.size())-1) { CourseUi.gap(result,12); View line=new View(this); line.setBackgroundColor(Ui.LINE); result.addView(line,CourseUi.row(1)); CourseUi.gap(result,12); }
        }
        if (roomLimit<rooms.size()) result.addView(CourseUi.action(this,"继续查看教室",false,() -> { int y=scroll.getScrollY(); roomLimit+=40; render(); scroll.post(() -> scroll.scrollTo(0,y)); }),CourseUi.row(dp(48)));
        if (module.optBoolean("partial")) note("当前为本次已读取的部分结果。");
    }

    private void renderBus(JSONObject module) {
        LinearLayout controls=new LinearLayout(this); controls.setPadding(dp(4),dp(4),dp(4),dp(4)); controls.setBackground(Ui.round(this,Ui.FILL,16));
        LinearLayout.LayoutParams controlsParams=new LinearLayout.LayoutParams(-1,-2); controlsParams.bottomMargin=dp(16); list.addView(controls,controlsParams);
        String[] modes={"工作日","周末"};
        for (int i=0;i<2;i++) {
            final boolean weekend=i==1; boolean selected=weekend==busWeekend;
            TextView tab=text(modes[i],14,selected?Ui.ACCENT:Ui.SUB,selected); tab.setGravity(Gravity.CENTER); tab.setSelected(selected);
            tab.setBackground(Ui.ripple(this,selected?Ui.round(this,Ui.CARD,12):null,Ui.press(),12));
            controls.addView(tab,new LinearLayout.LayoutParams(0,dp(44),1));
            tab.setOnClickListener(v -> { if (busWeekend!=weekend) { busWeekend=weekend; render(); } });
        }
        java.util.List<CampusBusSchedule.Route> routes=CampusBusSchedule.routes(module,busWeekend);
        long now=System.currentTimeMillis(); boolean today=busWeekend==CampusBusSchedule.weekend(now); int minute=CampusBusSchedule.minute(now);
        if (routes.isEmpty()) { note("暂未读到这一天的校车班次，可查看校方原始公告。"); }
        for (CampusBusSchedule.Route route:routes) {
            LinearLayout c=card(); c.addView(text(route.name,18,Ui.TEXT,true)); CourseUi.gap(c,14);
            CampusBusSchedule.Departure next=today?route.next(minute):route.departures.get(0);
            LinearLayout nextRow=new LinearLayout(this); nextRow.setGravity(Gravity.CENTER_VERTICAL); c.addView(nextRow);
            nextRow.addView(text(next==null?"今天已无后续班次":today?"今天下一班":"首班",12,Ui.SUB,false),new LinearLayout.LayoutParams(0,-2,1));
            if (next!=null) nextRow.addView(text(next.time,26,Ui.ACCENT,true));
            java.util.LinkedHashSet<String> places=new java.util.LinkedHashSet<>();
            for (CampusBusSchedule.Departure departure:route.departures) if (!departure.place.isEmpty()) places.add(departure.place);
            if (!places.isEmpty()) { CourseUi.gap(c,8); TextView place=text("上车地点："+String.join(" / ",places),12,Ui.SUB,false); place.setLineSpacing(dp(3),1); c.addView(place); }
            CourseUi.gap(c,16); c.addView(text("全部发车时间",12,Ui.SUB,true)); CourseUi.gap(c,8);
            for (int first=0;first<route.departures.size();first+=4) {
                LinearLayout times=new LinearLayout(this); LinearLayout.LayoutParams timesParams=new LinearLayout.LayoutParams(-1,-2); timesParams.topMargin=dp(6); c.addView(times,timesParams);
                for (int j=first;j<first+4;j++) {
                    if (j>=route.departures.size()) { times.addView(new View(this),new LinearLayout.LayoutParams(0,dp(38),1)); continue; }
                    CampusBusSchedule.Departure departure=route.departures.get(j); boolean upcoming=today && departure==next;
                    TextView chip=text(departure.time,14,upcoming?Ui.ACCENT:today && departure.minute<minute?Ui.MUTE:Ui.TEXT,upcoming);
                    chip.setGravity(Gravity.CENTER); chip.setBackground(Ui.round(this,upcoming?Ui.ACCENT_BG:Ui.FILL,10));
                    if (upcoming) chip.setContentDescription(departure.time+"，今天下一班");
                    LinearLayout.LayoutParams chipParams=new LinearLayout.LayoutParams(0,dp(38),1); if (j>first) chipParams.leftMargin=dp(6); times.addView(chip,chipParams);
                }
            }
        }
        LinearLayout tips=card(); tips.addView(text("乘车提示",14,Ui.TEXT,true)); CourseUi.gap(tips,8);
        String reservation=CampusSummary.field(module,"学生乘车预约");
        if (!reservation.isEmpty()) { TextView text=text(reservation,12,Ui.SUB,false); text.setLineSpacing(dp(3),1); tips.addView(text); CourseUi.gap(tips,8); }
        String extra=busWeekend?CampusSummary.field(module,"周末学生校车"):"";
        if (!extra.isEmpty()) { TextView text=text(extra,12,Ui.SUB,false); text.setLineSpacing(dp(3),1); tips.addView(text); CourseUi.gap(tips,8); }
        tips.addView(text("学期内时刻表，节假日和寒暑假调整以校方通知为准。",12,Ui.SUB,false));
        String source=module==null?"":module.optString("source");
        if (CampusServices.readUrl(source)) { CourseUi.gap(tips,12); tips.addView(CourseUi.action(this,"查看校方原始公告",false,() -> startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(source)))),CourseUi.row(dp(44))); }
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
        new AlertDialog.Builder(this,Ui.dialogTheme()).setItems(new String[]{"立即更新","切换教务账号","清空本机校园数据","下载最新版"},(d,i) -> {
            if (i==0) {
                boolean started=CampusAutoRefresh.refreshNow(this);
                android.widget.Toast.makeText(this,started?"已开始在后台更新，可以照常使用":"正在后台更新中",android.widget.Toast.LENGTH_SHORT).show();
                updates.postDelayed(this::reload,1500);
            }
            else if (i==1) startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"));
            else if (i==3) startActivity(new Intent(this,UpdateActivity.class));
            else new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("清空本机校园数据？").setMessage("将删除此页面的同步存档和宿舍绑定，可重新同步。").setNegativeButton("取消",null).setPositiveButton("清空",(a,b) -> io.execute(() -> {
                CampusStore.clear(getApplicationContext()); runOnUiThread(() -> { data=null; render(); });
            })).show();
        }).show();
    }
    @Override public void finish() { super.finish(); AppNavigation.fade(this); }
    @Override public void onPause() { resumed=false; CampusStore.unobserve(changed); updates.removeCallbacksAndMessages(null); CampusAutoRefresh.pause(this); super.onPause(); }
    @Override public void onDestroy() { stopClassroomQuery(); CampusSilentRefresh.hostDestroyed(this); io.shutdown(); super.onDestroy(); }
}
