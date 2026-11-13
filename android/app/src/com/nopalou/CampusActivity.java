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
    private String busRoute="",busPlace="";
    private CampusBusRefresh busRefresh;
    private JSONArray busTrips;
    private String busDate=CampusClassroomQuery.today(System.currentTimeMillis()),busError="";
    private boolean busAttempted,busLoading;
    private long busUpdated;
    private CampusClassroomRefresh classroomRefresh;
    private boolean roomAttempted,roomLoading;
    private String roomCampus="长安校区",roomDate="",roomToken="",roomError="";
    private int roomStart,roomEnd,roomLimit=40;
    private final java.util.Set<String> selectedRoomLocations=new java.util.LinkedHashSet<>();
    private final java.util.Set<String> submittedRoomLocations=new java.util.LinkedHashSet<>();
    private final java.util.List<String> roomLocations=new java.util.ArrayList<>();
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
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); Ui.apply(this); detail=getIntent().getStringExtra("module");
        busWeekend=b==null?CampusBusSchedule.weekend(System.currentTimeMillis()):b.getBoolean("bus_weekend");
        busRoute=b==null?"":b.getString("bus_route",""); busPlace=b==null?"":b.getString("bus_place",""); render();
    }
    @Override public void onSaveInstanceState(Bundle b) {
        b.putBoolean("bus_weekend",busWeekend); b.putString("bus_route",busRoute); b.putString("bus_place",busPlace); super.onSaveInstanceState(b);
    }
    @Override public void onResume() {
        super.onResume(); resumed=true;
        if ("classroom".equals(detail) && roomDate.isEmpty()) {
            roomDate=CampusClassroomQuery.today(System.currentTimeMillis());
            int[] range=CampusClassroomQuery.defaultRange(System.currentTimeMillis()); roomStart=range[0]; roomEnd=range[1];
            roomCampus=getSharedPreferences("classroom-live",MODE_PRIVATE).getString("campus","长安校区");
            if (!"友谊校区".equals(roomCampus)) roomCampus="长安校区";
            roomAttempted=false; roomToken=""; roomError=""; selectedRoomLocations.clear(); submittedRoomLocations.clear(); roomLoading=false;
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
                if ("bus".equals(detail) && resumed && !busAttempted) startBusQuery();
                render(); if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,y)); if (bad) toast("校园存档无法读取"); });
        });
    }
    private int dp(float n) { return Ui.dp(this,n); }
    private TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this,s,size,color,bold); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout root=CourseUi.column(this); root.setBackgroundColor(Ui.BG);
        android.widget.FrameLayout frame=new android.widget.FrameLayout(this); frame.setBackgroundColor(Ui.BG);
        frame.addView(root,new android.widget.FrameLayout.LayoutParams(-1,-1));
        setContentView(frame); Ui.systemBars(this,frame);
        CampusServices.Service service=CampusServices.selectedService(detail);
        LinearLayout head=new LinearLayout(this); head.setBaselineAligned(false); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(20),dp(8),dp(8),dp(8)); root.addView(head);
        if (service!=null) head.addView(CourseUi.icon(this,"prev","返回校园",this::finish));
        head.addView(text(service==null?"我的校园":service.title,24,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        if (service==null) head.addView(CourseUi.icon(this,"more","校园设置",this::settings));
        CampusSyncBanner.add(this,head);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        list=CourseUi.column(this); list.setPadding(dp(18),dp(4),dp(18),dp(28)); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (!loaded) note("正在读取本机数据…");
        else if (service!=null) renderDetail(service);
        else renderOverview();
        if (service==null) {
            // 导航悬浮在页面上、背后没有底色；列表底部自动让出导航高度。
            View nav=AppNavigation.bar(this,AppNavigation.CAMPUS);
            frame.addView(nav,new android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            AppNavigation.reserve(nav,scroll);
        }
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
        moduleCard(CampusServices.byId("study"));
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
            CampusSummary summary=CampusSummary.of(id,module,System.currentTimeMillis(),CampusChanges.price(CampusChanges.prefs(this)));
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
        LinearLayout pay=new LinearLayout(this); pay.setGravity(Gravity.CENTER_VERTICAL);
        pay.setPadding(dp(10),dp(4),dp(10),0);
        pay.addView(payPill("去交电费",CampusPay.ELECTRIC),new LinearLayout.LayoutParams(0,dp(40),1));
        LinearLayout.LayoutParams rightParams=new LinearLayout.LayoutParams(0,dp(40),1); rightParams.leftMargin=dp(10);
        pay.addView(payPill("去交网费",CampusPay.NETWORK),rightParams);
        account.addView(pay);
    }

    /** 账户卡里的缴费入口：只把用户送到校方官方页面，钱不在 App 里扣。 */
    private TextView payPill(String label,final String id) {
        TextView view=text(label,13,Ui.ON_HERO,true);
        view.setGravity(Gravity.CENTER); view.setMinHeight(dp(40));
        view.setBackground(Ui.ripple(this,Ui.round(this,Ui.withAlpha(android.graphics.Color.WHITE,24),99),Ui.press(),99));
        view.setContentDescription(label+"，在校方官方页面完成缴纳");
        view.setOnClickListener(v -> startActivity(new Intent(this,CampusPayActivity.class).putExtra("module",id)));
        return view;
    }
    private void openModule(String id) {
        startActivity("study".equals(id)?new Intent(this,StudyRoomActivity.class):new Intent(this,CampusActivity.class).putExtra("module",id));
    }
    private void moduleCard(CampusServices.Service s) {
        JSONObject module=data==null?null:data.module(s.id);
        CampusSummary summary="study".equals(s.id)?new CampusSummary("查空闲、选时段与真实同行人","查看或取消本人预约 · 按需连接校方系统"):"classroom".equals(s.id)?new CampusSummary("选择时间和教学楼后点击查询","点击教室查看今天各节占用"):CampusSummary.of(s.id,module,System.currentTimeMillis(),CampusChanges.price(CampusChanges.prefs(this)));
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
        if ("electric".equals(s.id) && count>0) {
            double price=CampusChanges.price(CampusChanges.prefs(this));
            Double kwh=ChangeDiff.firstNumber(CampusSummary.field(m,"剩余电量|当前电量|电量余额"));
            String note=CampusBalance.electricNote(kwh,price);
            if (!note.isEmpty()) {
                CourseUi.gap(intro,12);
                intro.addView(text(CampusBalance.electricValue(kwh,price),26,Ui.TEXT,true));
                CourseUi.gap(intro,6);
                intro.addView(text(note+" · 仅供参考，以校方账单为准",12,Ui.SUB,false));
            }
        }
        if (CampusPay.known(s.id)) {
            CourseUi.gap(intro,14);
            intro.addView(CourseUi.action(this,CampusPay.title(s.id),true,
                    () -> startActivity(new Intent(this,CampusPayActivity.class).putExtra("module",s.id))),CourseUi.row(dp(48)));
            CourseUi.gap(intro,8);
            intro.addView(text("缴纳在校方官方页面完成 · 本应用不代收、不保存支付信息",11,Ui.MUTE,false));
        }
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
        roomAttempted=true; submittedRoomLocations.clear(); submittedRoomLocations.addAll(selectedRoomLocations);
        if (data==null || data.owner.isEmpty()) { roomLoading=false; roomError="请先登录校园账号"; return; }
        CampusClassroomQuery query;
        try { roomDate=CampusClassroomQuery.today(System.currentTimeMillis()); query=new CampusClassroomQuery(roomCampus,roomDate,roomStart,roomEnd); }
        catch (Exception e) { roomLoading=false; toast("结束时间需要晚于开始时间"); return; }
        stopClassroomQuery(); roomToken=java.util.UUID.randomUUID().toString(); String token=roomToken;
        roomLoading=true; roomError=""; roomLimit=40;
        CampusClassroomRefresh reader=new CampusClassroomRefresh(this,token,error -> {
            if (!token.equals(roomToken)) return;
            roomLoading=false; roomError=error; reload();
        });
        classroomRefresh=reader; reader.start(data,query);
        if (repaint) renderRoomPage();
    }
    private void roomOptionsChanged() {
        stopClassroomQuery(); roomAttempted=false; roomLoading=false; roomToken=""; roomError=""; renderRoomPage();
    }
    private void renderRoomPage() {
        int y=scroll==null?0:scroll.getScrollY(); render(); if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,y));
    }
    private void chooseRoomTime(boolean start) {
        int minute=start?roomStart:roomEnd;
        Ui.pickTime(this,start?"选择开始时间":"选择结束时间",minute/60,minute%60,
                new int[]{8*60,10*60,12*60,14*60,16*60,18*60,20*60,22*60},
                (hour,value) -> {
                    if (start) roomStart=hour*60+value; else roomEnd=hour*60+value;
                    roomOptionsChanged();
                });
    }
    private void renderClassroom(JSONObject module) {
        boolean current=CampusClassroomResult.current(module,roomToken,CampusClassroomQuery.today(System.currentTimeMillis()));
        java.util.List<CampusClassroomResult.Room> allRooms=current?CampusClassroomResult.rooms(module):java.util.Collections.emptyList();
        if (roomLocations.isEmpty()) {
            if ("长安校区".equals(roomCampus)) for (String building:new String[]{"教学东楼A座","教学东楼B座","教学东楼C座","教学东楼D座","教学西楼A座","教学西楼B座","教学西楼C座","教学西楼D座"}) roomLocations.add(building);
            else { roomLocations.add("诚字楼"); roomLocations.add("研究生西馆"); }
        }
        java.util.List<CampusClassroomResult.Room> knownRooms=CampusClassroomResult.rooms(module);
        for (String place:CampusClassroomResult.locations(knownRooms)) if (!roomLocations.contains(place) && knownRooms.stream().anyMatch(r -> r.location.equals(place) && r.detail.contains(roomCampus))) roomLocations.add(place);
        java.util.List<String> locations=new java.util.ArrayList<>(roomLocations);
        selectedRoomLocations.retainAll(locations);
        if (selectedRoomLocations.isEmpty() && !locations.isEmpty()) selectedRoomLocations.add(locations.get(0));
        String locationLabel=selectedRoomLocations.isEmpty()?"选择教学楼"
                :(selectedRoomLocations.size()==1?selectedRoomLocations.iterator().next()
                :selectedRoomLocations.iterator().next()+" 等"+selectedRoomLocations.size()+"处");
        LinearLayout query=card(); query.addView(text("今天 · "+CampusClassroomQuery.today(System.currentTimeMillis()),13,Ui.SUB,true)); CourseUi.gap(query,12);
        TextView campus=CourseUi.action(this,roomCampus+"  ▾",false,() -> new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择校区").setItems(new String[]{"长安校区","友谊校区"},(d,i) -> {
            roomCampus=i==0?"长安校区":"友谊校区"; selectedRoomLocations.clear(); submittedRoomLocations.clear(); roomLocations.clear(); getSharedPreferences("classroom-live",MODE_PRIVATE).edit().putString("campus",roomCampus).apply(); roomOptionsChanged();
        }).show());
        LinearLayout places=new LinearLayout(this); query.addView(places);
        campus.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,12),Ui.press(),12)); places.addView(campus,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView location=CourseUi.action(this,locationLabel+"  ▾",false,() -> {
            String[] choices=locations.toArray(new String[0]);
            boolean[] checked=new boolean[choices.length];
            for (int i=0;i<choices.length;i++) checked[i]=selectedRoomLocations.contains(choices[i]);
            new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择教学楼（可多选）")
                    .setMultiChoiceItems(choices,checked,(dialog,which,isChecked) -> checked[which]=isChecked)
                    .setNegativeButton("取消",null)
                    .setPositiveButton("确定",(dialog,which) -> {
                        java.util.Set<String> next=new java.util.LinkedHashSet<>();
                        for (int i=0;i<choices.length;i++) if (checked[i]) next.add(choices[i]);
                        if (next.isEmpty()) { toast("请至少选择一个教学楼"); return; }
                        selectedRoomLocations.clear(); selectedRoomLocations.addAll(next);
                        roomOptionsChanged();
                    }).show();
        });
        location.setSingleLine(true); location.setEllipsize(android.text.TextUtils.TruncateAt.END); location.setContentDescription("教学楼筛选，"+locationLabel);
        location.setEnabled(!locations.isEmpty()); location.setAlpha(locations.isEmpty()?.5f:1f);
        location.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,12),Ui.press(),12));
        LinearLayout.LayoutParams locationParams=new LinearLayout.LayoutParams(0,dp(44),1); locationParams.leftMargin=dp(10); places.addView(location,locationParams); CourseUi.gap(query,10);
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
        if (!current) {
            if (!roomLoading) note("选择校区与时段后查询，旧结果不会作为本次结果展示。"); return;
        }
        java.util.List<CampusClassroomResult.Room> rooms=CampusClassroomResult.atLocations(allRooms,submittedRoomLocations);
        if (rooms.isEmpty()) { if (!roomLoading) note("所选教学楼在本次结果中暂无可展示的空闲教室。"); return; }
        section("找到 "+rooms.size()+" 间空闲教室"); note(CampusSummary.field(module,"查询条件")+(submittedRoomLocations.isEmpty()?"":" · "+String.join("、",submittedRoomLocations)));
        LinearLayout result=card();
        for (int i=0;i<Math.min(roomLimit,rooms.size());i++) {
            CampusClassroomResult.Room room=rooms.get(i);
            LinearLayout item=CourseUi.column(this); item.setPadding(dp(4),dp(8),dp(4),dp(8));
            item.setBackground(Ui.ripple(this,null,Ui.press(),12));
            item.addView(text(room.name+"  ›",15,Ui.TEXT,true));
            if (!room.detail.isEmpty()) { CourseUi.gap(item,4); item.addView(text(room.detail,11,Ui.SUB,false)); }
            CourseUi.gap(item,4); item.addView(text("查看今天各节占用",11,Ui.ACCENT,false));
            item.setFocusable(true); item.setEnabled(!roomLoading);
            item.setContentDescription(room.name+"，查看今天各节占用"); item.setOnClickListener(v -> showRoomDay(room)); result.addView(item);
            if (i<Math.min(roomLimit,rooms.size())-1) { CourseUi.gap(result,12); View line=new View(this); line.setBackgroundColor(Ui.LINE); result.addView(line,CourseUi.row(1)); CourseUi.gap(result,12); }
        }
        if (roomLimit<rooms.size()) result.addView(CourseUi.action(this,"继续查看教室",false,() -> { int y=scroll.getScrollY(); roomLimit+=40; render(); scroll.post(() -> scroll.scrollTo(0,y)); }),CourseUi.row(dp(48)));
        if (module.optBoolean("partial")) note("当前为本次已读取的部分结果。");
    }

    private void showRoomDay(CampusClassroomResult.Room room) {
        if (classroomRefresh==null) { toast("请重新查询教室后查看"); return; }
        LinearLayout body=CourseUi.column(this); body.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView pane=new ScrollView(this); pane.addView(body);
        body.addView(text("正在读取今天各节占用…",13,Ui.SUB,false));
        AlertDialog dialog=new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle(room.name+" · 今天").setView(pane).setPositiveButton("关闭",null).create(); dialog.show();
        classroomRefresh.day(room.name,CampusClassroomQuery.today(System.currentTimeMillis()),(result,error) -> {
            if (!dialog.isShowing() || isDestroyed()) return;
            body.removeAllViews();
            if (!error.isEmpty()) { body.addView(text(error,13,Ui.SUB,false)); return; }
            JSONArray items=result.optJSONArray("items");
            for (int i=0;items!=null && i<items.length();i++) {
                JSONObject item=items.optJSONObject(i); if (item==null) continue;
                String state=item.optString("state"); boolean free="free".equals(state),occupied="occupied".equals(state);
                LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(10),0,dp(10));
                LinearLayout label=CourseUi.column(this); label.addView(text(item.optString("label"),14,Ui.TEXT,true));
                label.addView(text(item.optString("time"),12,Ui.SUB,false)); row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                row.addView(text(free?"空闲":occupied?"占用":"未查到",14,free?Ui.TEAL:occupied?Ui.AMBER_TX:Ui.MUTE,true)); body.addView(row);
            }
            CourseUi.gap(body,10); body.addView(text("占用包含上课或预约；未查到的节次不视为空闲。数据以校方系统为准。",11,Ui.SUB,false));
        });
    }

    private void startBusQuery() {
        ticketBusy=false;
        if (busRefresh!=null) busRefresh.stop();
        busAttempted=true; busLoading=true; busError=""; busTrips=null; busUpdated=0;
        busRefresh=new CampusBusRefresh(this,(trips,error) -> {
            busLoading=false; busTrips=trips; busError=error;
            if (trips!=null) busUpdated=System.currentTimeMillis();
            render();
        });
        busRefresh.start(data,busDate);
    }
    private boolean busLinked() { return data!=null && !data.owner.isEmpty(); }
    private void chooseBusDate() {
        java.util.Calendar day=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        String[] days=new String[3];
        for (int i=0;i<days.length;i++) { days[i]=CampusClassroomQuery.today(day.getTimeInMillis()); day.add(java.util.Calendar.DAY_OF_MONTH,1); }
        new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择乘车日期").setItems(days,(d,which) -> { busDate=days[which]; startBusQuery(); render(); }).show();
    }
    private String busDateLabel() {
        try {
            SimpleDateFormat in=new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);
            in.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            SimpleDateFormat out=new SimpleDateFormat("M月d日 EEE",Locale.CHINA);
            out.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            return out.format(in.parse(busDate));
        } catch (Exception e) { return busDate; }
    }
    /** 小号胶囊按钮：标题行的刷新、日期、行内购票都复用它。 */
    private TextView smallAction(String label,int color,int fill,boolean enabled,Runnable click) {
        TextView v=text(label,12,color,true);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(12),dp(6),dp(12),dp(6)); v.setMinHeight(dp(28));
        v.setBackground(Ui.ripple(this,Ui.round(this,fill,99),Ui.press(),99));
        v.setFocusable(true); v.setEnabled(enabled); v.setAlpha(enabled?1f:.5f);
        v.setOnClickListener(x -> { if (v.isEnabled()) click.run(); });
        return v;
    }
    private TextView banner(String message,int color,int fill) {
        TextView v=text(message,12,color,false); v.setLineSpacing(dp(3),1);
        v.setPadding(dp(14),dp(11),dp(14),dp(11)); v.setBackground(Ui.round(this,fill,14));
        return v;
    }
    private TextView remainingTag(JSONObject trip,boolean open) {
        if (trip.isNull("remaining")) return Ui.tag(this,"余位待读",Ui.SUB,Ui.FILL);
        int remaining=trip.optInt("remaining");
        if (remaining<=0) return Ui.tag(this,"已满",Ui.RED,Ui.RED_BG);
        if (!open) return Ui.tag(this,"已停售",Ui.MUTE,Ui.FILL);
        if (remaining<=5) return Ui.tag(this,"余 "+remaining+" 位",Ui.ORANGE,Ui.ORANGE_BG);
        return Ui.tag(this,"余 "+remaining+" 位",Ui.GREEN,Ui.GREEN_BG);
    }
    /** 截止时间只留当天的时分，避免在行内被截断。 */
    private static String busDeadline(String deadline) {
        if (deadline.isEmpty()) return "";
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(\\d{2}:\\d{2})").matcher(deadline);
        return m.find()?m.group(1):deadline.trim();
    }
    private String bookedText(JSONObject trip) {
        if (trip.isNull("booked")) return "已订人数待读";
        String booked=String.valueOf(trip.optInt("booked"));
        return trip.isNull("capacity")?"已订 "+booked+" 人":"已订 "+booked+" / "+trip.optInt("capacity")+" 人";
    }
    private View busTripRow(JSONObject trip) {
        LinearLayout row=CourseUi.column(this); row.setPadding(dp(14),dp(13),dp(14),dp(13));
        row.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,16),Ui.press(),16));
        boolean open=trip.optBoolean("open");
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setBaselineAligned(false);
        top.addView(text(trip.optString("time"),20,Ui.TEXT,true));
        TextView route=text(trip.optString("route"),13,Ui.SUB,true);
        route.setSingleLine(true); route.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams routeParams=new LinearLayout.LayoutParams(0,-2,1); routeParams.leftMargin=dp(10); top.addView(route,routeParams);
        top.addView(remainingTag(trip,open)); row.addView(top);
        CourseUi.gap(row,7);
        LinearLayout meta=new LinearLayout(this); meta.setGravity(Gravity.CENTER_VERTICAL); meta.setBaselineAligned(false);
        String deadline=busDeadline(trip.optString("deadline"));
        TextView info=text(bookedText(trip)+(deadline.isEmpty()?"":" · 截止 "+deadline),11,Ui.SUB,false);
        info.setSingleLine(true); info.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        if (!trip.optString("id").isEmpty()) {
            TextView action=smallAction(open?"购票":"查看订单",open?Ui.ON_ACCENT:Ui.TEXT,open?Ui.ACCENT:Ui.CARD,true,() -> ticketQuote(trip));
            LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-2,-2); actionParams.leftMargin=dp(8); meta.addView(action,actionParams);
        }
        row.addView(meta);
        String note=trip.optString("note");
        if (!note.isEmpty()) {
            CourseUi.gap(row,7);
            TextView noteView=text(note,11,Ui.MUTE,false); noteView.setMaxLines(2); noteView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(noteView);
        }
        return row;
    }
    private void renderBusLive() {
        LinearLayout live=card();
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setBaselineAligned(false);
        head.addView(text("实时余位与已订人数",17,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        if (busLinked()) head.addView(smallAction("刷新",Ui.ACCENT,Ui.ACCENT_BG,!busLoading,() -> { startBusQuery(); render(); }));
        live.addView(head);
        if (!busLinked()) {
            CourseUi.gap(live,10);
            TextView hint=text("连接校园账号后，可查看各班次的实时余位、已订人数并购买车票。",12,Ui.SUB,false);
            hint.setLineSpacing(dp(3),1); live.addView(hint);
            CourseUi.gap(live,14);
            live.addView(CourseUi.action(this,"连接校园账号",true,() -> startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"))),CourseUi.row(dp(48)));
            return;
        }
        CourseUi.gap(live,12);
        LinearLayout dateRow=new LinearLayout(this); dateRow.setGravity(Gravity.CENTER_VERTICAL); dateRow.setBaselineAligned(false);
        TextView date=smallAction(busDateLabel()+"  ▾",Ui.TEXT,Ui.FILL,true,this::chooseBusDate);
        date.setTextSize(13); date.setContentDescription("选择乘车日期"); dateRow.addView(date);
        if (busUpdated>0) {
            TextView updated=text("更新于 "+new SimpleDateFormat("HH:mm",Locale.CHINA).format(new Date(busUpdated)),11,Ui.MUTE,false);
            updated.setGravity(Gravity.END); dateRow.addView(updated,new LinearLayout.LayoutParams(0,-2,1));
        }
        live.addView(dateRow);
        if (busLoading) {
            CourseUi.gap(live,14);
            android.widget.ProgressBar bar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true); bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            live.addView(bar,CourseUi.row(dp(3)));
            CourseUi.gap(live,10);
            live.addView(text("正在向校方查询 "+busDateLabel()+" 的实时班次…",12,Ui.SUB,false));
            return;
        }
        if (!busError.isEmpty()) {
            CourseUi.gap(live,12); live.addView(banner(busError,Ui.RED,Ui.RED_BG));
            CourseUi.gap(live,12);
            live.addView(CourseUi.action(this,"重试",false,() -> { startBusQuery(); render(); }),CourseUi.row(dp(44)));
            return;
        }
        if (busTrips==null || busTrips.length()==0) {
            CourseUi.gap(live,12);
            live.addView(banner("校方暂未返回这一天的可查询班次。",Ui.SUB,Ui.FILL));
            CourseUi.gap(live,10);
            live.addView(text("可以换一天再查，或到下方参考时刻表查看固定发车时间。",11,Ui.MUTE,false));
            return;
        }
        java.util.LinkedHashMap<String,java.util.ArrayList<JSONObject>> groups=new java.util.LinkedHashMap<>();
        for (int i=0;i<busTrips.length();i++) {
            JSONObject trip=busTrips.optJSONObject(i); if (trip==null) continue;
            String route=trip.optString("route");
            java.util.ArrayList<JSONObject> trips=groups.get(route);
            if (trips==null) { trips=new java.util.ArrayList<>(); groups.put(route,trips); }
            trips.add(trip);
        }
        boolean firstGroup=true;
        for (java.util.Map.Entry<String,java.util.ArrayList<JSONObject>> group:groups.entrySet()) {
            CourseUi.gap(live,firstGroup?14:20);
            LinearLayout groupHead=new LinearLayout(this); groupHead.setGravity(Gravity.CENTER_VERTICAL); groupHead.setBaselineAligned(false);
            View bar=new View(this); bar.setBackground(Ui.round(this,Ui.ACCENT,2));
            groupHead.addView(bar,new LinearLayout.LayoutParams(dp(3),dp(13)));
            String name=group.getKey().isEmpty()?"其他班次":group.getKey();
            TextView label=text(name,12,Ui.TEXT,true); label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1); labelParams.leftMargin=dp(8); groupHead.addView(label,labelParams);
            groupHead.addView(text(group.getValue().size()+" 班",11,Ui.MUTE,false));
            live.addView(groupHead);
            for (JSONObject trip:group.getValue()) {
                LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);
                rowParams.topMargin=dp(8); live.addView(busTripRow(trip),rowParams);
            }
            firstGroup=false;
        }
        CourseUi.gap(live,8);
        live.addView(text("余位与人数随预约实时变化，购票结果以校方系统为准。",11,Ui.MUTE,false));
    }
    private boolean ticketBusy;
    private void ticketQuote(JSONObject trip) {
        if (ticketBusy || busRefresh==null) return;
        ticketBusy=true; toast("正在查询票价和订单…");
        busRefresh.ticket("quote",trip,"",false,(result,error) -> {
            ticketBusy=false;
            if (result==null) { toast(error); return; }
            if (result.optBoolean("paid")) { new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("购票成功").setMessage(busDate+" "+trip.optString("time")+" "+trip.optString("route")+"\n订单："+result.optString("order")+"\n状态："+result.optString("status")).setPositiveButton("知道了",null).show(); return; }
            if (result.optBoolean("payable")) { ticketPrepare(trip,result.optString("order"),0); return; }
            if (!result.optBoolean("canSubmit") || !trip.optBoolean("open")) { toast("当前不可购票，订单状态："+result.optString("status","未下单")); return; }
            LinearLayout body=CourseUi.column(this); body.setPadding(dp(24),dp(12),dp(24),dp(12));
            body.addView(text(busDate+" "+trip.optString("time")+"\n"+trip.optString("route")+"\n应付：¥"+result.optString("fee"),16,Ui.TEXT,true));
            android.widget.CheckBox agreement=new android.widget.CheckBox(this); agreement.setText("我已阅读并同意预约须知"); body.addView(agreement);
            String notice=result.optString("notice");
            android.app.AlertDialog dialog=new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("确认购票").setView(body)
                .setNegativeButton("取消",null).setPositiveButton("确认下单",null).create();
            if (notice.startsWith("https://")) body.addView(CourseUi.action(this,"阅读预约须知",false,() -> showTicketNotice(notice)),CourseUi.row(dp(44)));
            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                agreement.setOnCheckedChangeListener((button,checked) -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(checked));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    dialog.dismiss(); ticketBusy=true;
                    busRefresh.ticket("submit",trip,result.optString("fee"),true,(order,err) -> {
                        ticketBusy=false;
                        if (order==null) { new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("订单结果未确认").setMessage(err).setPositiveButton("查询订单",(a,b) -> ticketQuote(trip)).setNegativeButton("稍后查询",null).show(); return; }
                        if (order.optBoolean("paid")) { ticketQuote(trip); return; }
                        if (!order.optString("order").isEmpty()) ticketPrepare(trip,order.optString("order"),0);
                        else { toast("订单状态已变化，请重新查询"); ticketQuote(trip); }
                    });
                });
            }); dialog.show();
        });
    }
    private void ticketPrepare(JSONObject trip,String order,int attempt) {
        if (ticketBusy || busRefresh==null || !resumed) return;
        ticketBusy=true;
        busRefresh.ticket("prepare",trip,order,false,(result,error) -> {
            ticketBusy=false;
            if (result==null) { toast(error); return; }
            if (result.optBoolean("paid")) { ticketQuote(trip); return; }
            if (result.optBoolean("preparing") && attempt<10) {
                updates.postDelayed(() -> ticketPrepare(trip,order,attempt+1),2000); return;
            }
            String prepay=result.optString("prepay");
            if (prepay.isEmpty()) { toast("支付单尚未就绪，请稍后点击查看订单继续"); return; }
            new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("订单待支付").setMessage("订单："+order+"\n应付：¥"+result.optString("fee")+"\n在应用内的校方收银台完成支付后，将查询校方订单结果。")
                .setNegativeButton("稍后支付",null).setPositiveButton("去支付",(d,w) -> busRefresh.openCashier(order,prepay,() -> ticketQuote(trip),(r,e) -> toast(e))).show();
        });
    }
    private void showTicketNotice(String url) {
        android.webkit.WebView notice=new android.webkit.WebView(this);
        notice.getSettings().setAllowFileAccess(false); notice.getSettings().setAllowContentAccess(false);
        notice.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v,android.webkit.WebResourceRequest r) { return !"https".equals(r.getUrl().getScheme()); }
        });
        new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("预约须知").setView(notice).setPositiveButton("关闭",null).setOnDismissListener(d -> notice.destroy()).show();
        notice.loadUrl(url);
    }
    private void renderBus(JSONObject module) {
        renderBusLive();
        section("学期内参考时刻表");
        LinearLayout filterCard=card(); filterCard.setPadding(dp(10),dp(10),dp(10),dp(10));
        LinearLayout controls=new LinearLayout(this); controls.setPadding(dp(4),dp(4),dp(4),dp(4)); controls.setBackground(Ui.round(this,Ui.FILL,14));
        filterCard.addView(controls);
        String[] modes={"工作日","周末"};
        for (int i=0;i<2;i++) {
            final boolean weekend=i==1; boolean selected=weekend==busWeekend;
            TextView tab=text(modes[i],14,selected?Ui.ACCENT:Ui.SUB,selected); tab.setGravity(Gravity.CENTER); tab.setSelected(selected);
            tab.setBackground(Ui.ripple(this,selected?Ui.round(this,Ui.CARD,11):null,Ui.press(),11));
            controls.addView(tab,new LinearLayout.LayoutParams(0,dp(42),1));
            tab.setOnClickListener(v -> { if (busWeekend!=weekend) { busWeekend=weekend; busRoute=""; busPlace=""; render(); } });
        }
        java.util.List<CampusBusSchedule.Route> available=CampusBusSchedule.routes(module,busWeekend);
        java.util.List<String> routeNames=CampusBusSchedule.routeNames(available);
        if (!busRoute.isEmpty() && !routeNames.contains(busRoute)) busRoute="";
        java.util.List<String> availablePlaces=CampusBusSchedule.places(CampusBusSchedule.filter(available,busRoute,""));
        if (!busPlace.isEmpty() && !availablePlaces.contains(busPlace)) busPlace="";
        CourseUi.gap(filterCard,10);
        LinearLayout filters=new LinearLayout(this); filterCard.addView(filters);
        TextView routeFilter=CourseUi.action(this,busRoute.isEmpty()?"全部路线":"路线 · "+busRoute+"  ▾",false,() -> {
            String[] choices=new String[routeNames.size()+1]; choices[0]="全部路线";
            for (int i=0;i<routeNames.size();i++) choices[i+1]=routeNames.get(i);
            int selected=busRoute.isEmpty()?0:routeNames.indexOf(busRoute)+1;
            new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择路线方向").setSingleChoiceItems(choices,selected,(dialog,which) -> {
                busRoute=which==0?"":routeNames.get(which-1); busPlace=""; dialog.dismiss(); render();
            }).setNegativeButton("取消",null).show();
        });
        routeFilter.setSingleLine(true); routeFilter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        routeFilter.setContentDescription("路线筛选，"+(busRoute.isEmpty()?"全部路线":busRoute));
        routeFilter.setEnabled(!routeNames.isEmpty()); routeFilter.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,12),Ui.press(),12));
        filters.addView(routeFilter,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView placeFilter=CourseUi.action(this,busPlace.isEmpty()?"全部上车点":"上车点 · "+busPlace+"  ▾",false,() -> {
            String[] choices=new String[availablePlaces.size()+1]; choices[0]="全部上车点";
            for (int i=0;i<availablePlaces.size();i++) choices[i+1]=availablePlaces.get(i);
            int selected=busPlace.isEmpty()?0:availablePlaces.indexOf(busPlace)+1;
            new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择上车地点").setSingleChoiceItems(choices,selected,(dialog,which) -> {
                busPlace=which==0?"":availablePlaces.get(which-1); dialog.dismiss(); render();
            }).setNegativeButton("取消",null).show();
        });
        placeFilter.setSingleLine(true); placeFilter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        placeFilter.setContentDescription("上车点筛选，"+(busPlace.isEmpty()?"全部上车点":busPlace));
        placeFilter.setEnabled(!availablePlaces.isEmpty()); placeFilter.setAlpha(availablePlaces.isEmpty()?.5f:1f);
        placeFilter.setBackground(Ui.ripple(this,Ui.round(this,Ui.FILL,12),Ui.press(),12));
        LinearLayout.LayoutParams placeFilterParams=new LinearLayout.LayoutParams(0,dp(44),1); placeFilterParams.leftMargin=dp(8); filters.addView(placeFilter,placeFilterParams);
        java.util.List<CampusBusSchedule.Route> routes=CampusBusSchedule.filter(available,busRoute,busPlace);
        long now=System.currentTimeMillis(); boolean today=busWeekend==CampusBusSchedule.weekend(now); int minute=CampusBusSchedule.minute(now);
        if (available.isEmpty()) { note("暂未读到这一天的校车班次，可查看校方原始公告。"); }
        else if (routes.isEmpty()) { note("没有符合当前路线和上车点的班次。"); }
        for (CampusBusSchedule.Route route:routes) {
            LinearLayout c=card(); c.setPadding(dp(18),dp(16),dp(18),dp(18));
            CampusBusSchedule.Departure next=today?route.next(minute):route.departures.get(0);
            java.util.LinkedHashSet<String> places=new java.util.LinkedHashSet<>();
            for (CampusBusSchedule.Departure departure:route.departures) if (!departure.place.isEmpty()) places.add(departure.place);
            LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setBaselineAligned(false);
            LinearLayout left=CourseUi.column(this); left.setPadding(0,0,dp(12),0);
            TextView name=text(route.name,17,Ui.TEXT,true); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); left.addView(name);
            if (!places.isEmpty()) { CourseUi.gap(left,5); TextView place=text("上车 · "+String.join(" / ",places),11,Ui.SUB,false); place.setMaxLines(2); place.setEllipsize(android.text.TextUtils.TruncateAt.END); left.addView(place); }
            head.addView(left,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout right=CourseUi.column(this); right.setGravity(Gravity.END);
            TextView caption=text(next==null?"今天已无后续班次":today?"今天下一班":"首班",10,next==null?Ui.MUTE:Ui.SUB,false);
            caption.setGravity(Gravity.END); caption.setSingleLine(true); right.addView(caption);
            if (next!=null) { CourseUi.gap(right,3); TextView big=text(next.time,26,Ui.ACCENT,true); big.setGravity(Gravity.END); right.addView(big); }
            head.addView(right); c.addView(head);
            CourseUi.gap(c,14); c.addView(Ui.hairline(this)); CourseUi.gap(c,12);
            c.addView(text("全部发车时间",11,Ui.SUB,true));
            for (int first=0;first<route.departures.size();first+=4) {
                LinearLayout times=new LinearLayout(this); LinearLayout.LayoutParams timesParams=new LinearLayout.LayoutParams(-1,-2); timesParams.topMargin=dp(6); c.addView(times,timesParams);
                for (int j=first;j<first+4;j++) {
                    if (j>=route.departures.size()) { times.addView(new View(this),new LinearLayout.LayoutParams(0,dp(36),1)); continue; }
                    CampusBusSchedule.Departure departure=route.departures.get(j); boolean upcoming=today && departure==next;
                    TextView chip=text(departure.time,13,upcoming?Ui.ACCENT:today && departure.minute<minute?Ui.MUTE:Ui.TEXT,upcoming);
                    chip.setGravity(Gravity.CENTER); chip.setBackground(Ui.round(this,upcoming?Ui.ACCENT_BG:Ui.FILL,10));
                    if (upcoming) chip.setContentDescription(departure.time+"，今天下一班");
                    LinearLayout.LayoutParams chipParams=new LinearLayout.LayoutParams(0,dp(36),1); if (j>first) chipParams.leftMargin=dp(6); times.addView(chip,chipParams);
                }
            }
        }
        LinearLayout tips=card(); tips.addView(text("乘车提示",14,Ui.TEXT,true)); CourseUi.gap(tips,8);
        String reservation=CampusSummary.field(module,"学生乘车预约");
        if (!reservation.isEmpty()) { TextView text=text(reservation,12,Ui.SUB,false); text.setLineSpacing(dp(3),1); tips.addView(text); CourseUi.gap(tips,8); }
        String extra=busWeekend?CampusSummary.field(module,"周末学生校车"):"";
        if (!extra.isEmpty()) { TextView text=text(extra,12,Ui.SUB,false); text.setLineSpacing(dp(3),1); tips.addView(text); CourseUi.gap(tips,8); }
        tips.addView(text("学期内时刻表，节假日和寒暑假调整以校方通知为准。",12,Ui.SUB,false));
        CourseUi.gap(tips,12);
        tips.addView(text("选择上方班次购票，核对票价并同意预约须知后下单；支付结果以校方订单状态为准。",12,Ui.SUB,false));
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
    @Override public void onPause() { resumed=false; if (busRefresh!=null) busRefresh.stop(); busAttempted=false; busLoading=false; busTrips=null; ticketBusy=false; CampusStore.unobserve(changed); updates.removeCallbacksAndMessages(null); CampusAutoRefresh.pause(this); super.onPause(); }
    @Override public void onDestroy() { if (busRefresh!=null) busRefresh.stop(); stopClassroomQuery(); CampusSilentRefresh.hostDestroyed(this); io.shutdown(); super.onDestroy(); }
}
