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
    private boolean busAttempted,busLoading,busHidePast=true;
    private long busUpdated;
    private int busTab;
    private java.util.List<CampusBusTicket.Ticket> busTickets=java.util.Collections.emptyList();
    private String busTicketOwner="";
    private boolean busTicketsScanning,busTicketsScanPendingAll=true;
    private int busTicketWriteEpoch,busScanEpoch;
    private CampusClassroomRefresh classroomRefresh;
    private boolean roomAttempted,roomLoading;
    private String roomCampus="长安校区",roomDate="",roomToken="",roomError="";
    private String busLiveRoute="";
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
        busRoute=b==null?"":b.getString("bus_route",""); busPlace=b==null?"":b.getString("bus_place","");
        busTab=b==null?getIntent().getIntExtra("bus_tab",0):b.getInt("bus_tab",0);
        if (busTab!=0 && busTab!=1) busTab=0;
        render();
    }
    @Override public void onSaveInstanceState(Bundle b) {
        b.putBoolean("bus_weekend",busWeekend); b.putString("bus_route",busRoute); b.putString("bus_place",busPlace); b.putInt("bus_tab",busTab); super.onSaveInstanceState(b);
    }
    @Override public void onResume() {
        super.onResume(); resumed=true;
        if ("classroom".equals(detail) && roomDate.isEmpty()) {
            roomDate=CampusClassroomQuery.today(System.currentTimeMillis());
            int[] range=CampusClassroomQuery.defaultRange(System.currentTimeMillis()); roomStart=range[0]; roomEnd=range[1];
            roomCampus=getSharedPreferences("classroom-live",MODE_PRIVATE).getString("campus","长安校区");
            Intent in=getIntent();
            int preStart=in.getIntExtra("prefill_start",-1), preEnd=in.getIntExtra("prefill_end",-1);
            if (preStart>=0 && preEnd>preStart && preEnd<=1439) {
                roomStart=preStart; roomEnd=preEnd;
                in.removeExtra("prefill_start"); in.removeExtra("prefill_end");
            }
            String preCampus=in.getStringExtra("prefill_campus");
            if ("长安校区".equals(preCampus) || "友谊校区".equals(preCampus)) {
                roomCampus=preCampus;
                in.removeExtra("prefill_campus");
            }
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
                loadBusTicketsForOwner();
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
        LinearLayout head=new LinearLayout(this); head.setBaselineAligned(false); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(20),dp(12),dp(10),dp(6)); root.addView(head);
        if (service!=null) head.addView(CourseUi.icon(this,"prev","返回校园",this::finish));
        TextView titleView=text(service==null?"校园":service.title,service==null?26:21,Ui.TEXT,false);
        titleView.setTypeface(Ui.editorialBold());
        head.addView(titleView,new LinearLayout.LayoutParams(0,-2,1));
        CampusSyncBanner.add(this,head);
        if (service==null) head.addView(CourseUi.icon(this,"more","校园设置",this::settings));
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        list=CourseUi.column(this); list.setPadding(dp(18),dp(4),dp(18),dp(28)); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (!loaded) note("正在读取本机数据…");
        else if (service!=null) renderDetail(service);
        else renderOverview();
        if (service==null) {
            View nav=AppNavigation.bar(this,AppNavigation.CAMPUS);
            frame.addView(nav,new android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            AppNavigation.reserve(nav,scroll);
        } else if ("bus".equals(service.id)) {
            View busNav=busNavBar();
            frame.addView(busNav,new android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            AppNavigation.reserve(busNav,scroll);
        }
    }
    private LinearLayout card() {
        LinearLayout c=CourseUi.column(this); c.setPadding(dp(18),dp(16),dp(18),dp(16)); Ui.cardSurface(c,14);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(12); list.addView(c,p); return c;
    }
    private void note(String s) { TextView v=text(s,12,Ui.SUB,false); v.setLineSpacing(dp(4),1); v.setPadding(dp(4),dp(8),dp(4),dp(14)); list.addView(v); }
    private void section(String s) {
        TextView v=Ui.sectionLabel(this,s); v.setPadding(dp(4),dp(16),0,dp(8)); list.addView(v);
    }
    private static String time(long t) { return t<=0?"尚未同步":new SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(new Date(t)); }
    private void renderOverview() {
        boolean linked=data!=null && !data.owner.isEmpty();
        if (!linked) {
            LinearLayout login=card(); login.addView(text("连接校园账号",17,Ui.TEXT,true)); CourseUi.gap(login,6);
            login.addView(text("登录后自动同步余额、电量与校园服务",12,Ui.SUB,false)); CourseUi.gap(login,12);
            login.addView(CourseUi.action(this,"登录校园账号",true,() -> startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"))),CourseUi.row(dp(46)));
        } else {
            String attention=CampusSilentRefresh.attention(this);
            if (!attention.isEmpty()) {
                LinearLayout warn=card(); warn.setBackground(Ui.roundStroke(this,Ui.AMBER_BG,Ui.withAlpha(Ui.AMBER_TX,75),14,0.75f));
                warn.addView(text("login".equals(attention)?"自动登录暂未完成":"需要完成校方验证",14,Ui.AMBER_TX,true));
                CourseUi.gap(warn,4); warn.addView(text("已保存的信息仍可查看",12,Ui.SUB,false)); CourseUi.gap(warn,10);
                warn.addView(CourseUi.action(this,"login".equals(attention)?"继续登录":"完成验证",false,() -> startActivity(new Intent(this,CampusWebActivity.class).putExtra("all",true).putExtra("automatic",true))),CourseUi.row(dp(44)));
            }
        }
        accountOverview();
        section("常用服务");
        LinearLayout group=Ui.group(this);
        list.addView(group,new LinearLayout.LayoutParams(-1,-2));
        moduleRow(group,CampusServices.byId("classroom"),false);
        moduleRow(group,CampusServices.byId("study"),true);
        moduleRow(group,CampusServices.byId("bus"),true);
        TextView foot=text("数据来自校方系统 · 点按查看明细与更新时间",11,Ui.MUTE,false);
        foot.setPadding(dp(4),dp(12),dp(4),dp(10)); list.addView(foot);
    }
    private void accountOverview() {
        LinearLayout account=card(); account.setPadding(dp(14),dp(14),dp(14),dp(12));
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4),0,dp(4),dp(10));
        head.addView(text("账户概况",12,Ui.MUTE,true),new LinearLayout.LayoutParams(0,-2,1));
        head.addView(payLink("电费充值 ›",CampusPay.ELECTRIC));
        TextView sep=text(" · ",12,Ui.MUTE,false); head.addView(sep);
        head.addView(payLink("网费充值 ›",CampusPay.NETWORK));
        account.addView(head);
        account.addView(Ui.hairline(this));
        CourseUi.gap(account,8);

        LinearLayout values=new LinearLayout(this); values.setBaselineAligned(false); values.setGravity(Gravity.CENTER_VERTICAL); account.addView(values);
        String[] ids={"network","card","electric"};
        android.content.SharedPreferences alertPrefs=CampusChanges.prefs(this);
        double elecPrice=CampusChanges.price(alertPrefs);
        for (int idx=0;idx<ids.length;idx++) {
            String id=ids[idx];
            if (idx>0) {
                View vRule=new View(this); vRule.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(1,dp(42));
                values.addView(vRule,rp);
            }
            JSONObject module=data==null?null:data.module(id); boolean present=CampusData.count(module)>0;
            CampusServices.Service service=CampusServices.byId(id);
            CampusSummary summary=CampusSummary.of(id,module,System.currentTimeMillis(),elecPrice);
            boolean hasBalance=present && !"余额暂未读到".equals(summary.value) && !"电费暂未读到".equals(summary.value);
            LinearLayout item=CourseUi.column(this); item.setPadding(dp(8),dp(8),dp(6),dp(6));
            item.setBackground(Ui.ripple(this,null,Ui.press(),10));
            item.addView(text(service.title,11,Ui.MUTE,false)); CourseUi.gap(item,6);
            TextView value=text(summary.value,hasBalance?18:13,Ui.TEXT,hasBalance);
            if (hasBalance) value.setTypeface(Ui.editorialBold());
            value.setMinHeight(dp(24)); value.setMaxLines(2); value.setEllipsize(android.text.TextUtils.TruncateAt.END); item.addView(value);
            CourseUi.gap(item,5);
            boolean lowAlert=false;
            if (present) {
                if ("electric".equals(id)) {
                    Double kwh=ChangeDiff.firstNumber(CampusSummary.field(module,"剩余电量|当前电量|电量余额"));
                    lowAlert=CampusBalance.electricLow(kwh,elecPrice,CampusChanges.electricThreshold(alertPrefs));
                } else if ("network".equals(id)) {
                    Double yuan=ChangeDiff.firstNumber(CampusSummary.field(module,"账户余额|可用金额|剩余金额|余额|当前余额"));
                    lowAlert=CampusBalance.networkLow(yuan,CampusChanges.networkThreshold(alertPrefs));
                }
            }
            if (lowAlert) {
                item.addView(text("余额偏低",11,Ui.ORANGE,true));
            } else {
                String freshness=hasBalance?new SimpleDateFormat("M/d HH:mm",Locale.CHINA).format(new Date(module.optLong("updated"))):"等待同步";
                if (hasBalance && !"ok".equals(module.optString("state"))) freshness="上次数据";
                item.addView(text(freshness,10,Ui.MUTE,false));
            }
            item.setContentDescription(service.title+"，"+summary.value+"，查看详情"); item.setFocusable(true);
            item.setOnClickListener(v -> openModule(id));
            values.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        }
    }

    /** 账户卡右上角的轻量官方缴费跳转链接，不铺占底部按钮面积。 */
    private TextView payLink(String label,final String id) {
        TextView view=text(label,12,Ui.ACCENT,true);
        view.setPadding(dp(4),dp(4),dp(4),dp(4));
        view.setBackground(Ui.ripple(this,null,Ui.press(),8));
        view.setContentDescription(label+"，在校方官方页面完成缴纳");
        view.setOnClickListener(v -> startActivity(new Intent(this,CampusPayActivity.class).putExtra("module",id)));
        return view;
    }
    private void openModule(String id) {
        startActivity("study".equals(id)?new Intent(this,StudyRoomActivity.class):new Intent(this,CampusActivity.class).putExtra("module",id));
    }
    private void moduleRow(LinearLayout group,CampusServices.Service s,boolean divider) {
        if (divider) {
            View rule=new View(this); rule.setBackgroundColor(Ui.LINE);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,1); rp.leftMargin=dp(56);
            group.addView(rule,rp);
        }
        String hint="study".equals(s.id)?"查空闲并按需预约时段"
                :"classroom".equals(s.id)?"按时段和教学楼查空闲教室"
                :"班次时刻与实时下一班";
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),dp(14),dp(14),dp(14));
        row.setBackground(Ui.ripple(this,null,Ui.press(),0));
        row.setFocusable(true);
        android.widget.ImageView icon=new android.widget.ImageView(this);
        icon.setImageDrawable(new CampusServiceIcon(s.id,s.id.equals("classroom")?Ui.ACCENT:Ui.SUB));
        icon.setPadding(dp(8),dp(8),dp(8),dp(8));
        icon.setBackground(Ui.round(this,Ui.FILL,10));
        row.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        LinearLayout body=CourseUi.column(this); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1); bp.leftMargin=dp(12); row.addView(body,bp);
        body.addView(text(s.title,15,Ui.TEXT,true));
        TextView sub=text(hint,12,Ui.MUTE,false); sub.setPadding(0,dp(3),0,0); body.addView(sub);
        android.widget.ImageView chev=new android.widget.ImageView(this);
        chev.setImageDrawable(Ui.chevron(this,Ui.RING));
        row.addView(chev,new LinearLayout.LayoutParams(dp(16),dp(16)));
        row.setContentDescription(s.title+"，"+hint);
        row.setOnClickListener(v -> openModule(s.id));
        group.addView(row,new LinearLayout.LayoutParams(-1,-2));
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
            android.content.SharedPreferences prefs=CampusChanges.prefs(this);
            double price=CampusChanges.price(prefs);
            Double kwh=ChangeDiff.firstNumber(CampusSummary.field(m,"剩余电量|当前电量|电量余额"));
            String note=CampusBalance.electricNote(kwh,price);
            if (!note.isEmpty()) {
                CourseUi.gap(intro,12);
                intro.addView(text(CampusBalance.electricValue(kwh,price),26,Ui.TEXT,true));
                CourseUi.gap(intro,6);
                intro.addView(text(note+" · 仅供参考，以校方账单为准",12,Ui.SUB,false));
                double threshold=CampusChanges.electricThreshold(prefs);
                if (CampusBalance.electricLow(kwh,price,threshold)) {
                    CourseUi.gap(intro,10);
                    intro.addView(Ui.tag(this,CampusBalance.electricAlert(kwh,price,threshold),Ui.ORANGE,Ui.ORANGE_BG),new LinearLayout.LayoutParams(-2,-2));
                }
            }
        }
        if ("network".equals(s.id) && count>0) {
            Double yuan=ChangeDiff.firstNumber(CampusSummary.field(m,"账户余额|可用金额|剩余金额|余额|当前余额"));
            if (yuan!=null) {
                CourseUi.gap(intro,12);
                intro.addView(text(CampusBalance.networkValue(yuan),26,Ui.TEXT,true));
                double threshold=CampusChanges.networkThreshold(CampusChanges.prefs(this));
                if (CampusBalance.networkLow(yuan,threshold)) {
                    CourseUi.gap(intro,10);
                    intro.addView(Ui.tag(this,CampusBalance.networkAlert(yuan,threshold),Ui.ORANGE,Ui.ORANGE_BG),new LinearLayout.LayoutParams(-2,-2));
                }
            }
        }
        if ("card".equals(s.id) && count>0) {
            Double yuan=ChangeDiff.firstNumber(CampusSummary.field(m,"卡余额|校园卡余额|可用金额|剩余金额|卡内金额|主钱包|余额|当前余额"));
            if (yuan!=null) {
                CourseUi.gap(intro,12);
                intro.addView(text(CampusBalance.networkValue(yuan),26,Ui.TEXT,true));
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
    private void showLocationPicker(java.util.List<String> locations) {
        if (locations.isEmpty()) return;
        java.util.Set<String> draft=new java.util.LinkedHashSet<>(selectedRoomLocations);
        LinearLayout body=CourseUi.column(this);
        body.setPadding(0,dp(4),0,dp(16));
        LinearLayout header=new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout titleCol=CourseUi.column(this);
        titleCol.addView(text("选择教学楼",20,Ui.TEXT,true));
        TextView countHint=text("",13,Ui.SUB,false);
        countHint.setPadding(0,dp(4),0,0);
        titleCol.addView(countHint);
        header.addView(titleCol,new LinearLayout.LayoutParams(0,-2,1f));
        TextView selectAll=text("全选",13,Ui.ACCENT,true);
        selectAll.setPadding(dp(12),dp(6),dp(12),dp(6));
        selectAll.setBackground(Ui.ripple(this,Ui.round(this,Ui.ACCENT_BG,999),Ui.press(),999));
        header.addView(selectAll,new LinearLayout.LayoutParams(-2,-2));
        body.addView(header,new LinearLayout.LayoutParams(-1,-2));
        CourseUi.gap(body,16);

        java.util.List<LinearLayout> rows=new java.util.ArrayList<>();
        java.util.List<TextView> labels=new java.util.ArrayList<>();
        java.util.List<Ui.CheckCircle> checks=new java.util.ArrayList<>();
        Runnable sync=() -> {
            countHint.setText(draft.isEmpty()?"请至少选择 1 处教学楼":"可多选 · 已选 "+draft.size()+" 处");
            countHint.setTextColor(draft.isEmpty()?Ui.RED:Ui.SUB);
            boolean all=draft.size()==locations.size();
            selectAll.setAlpha(all?0.45f:1f);
            selectAll.setEnabled(!all);
            for (int i=0;i<locations.size();i++) {
                boolean on=draft.contains(locations.get(i));
                LinearLayout row=rows.get(i);
                android.graphics.drawable.GradientDrawable bg=Ui.round(this,on?Ui.ACCENT_BG:Ui.FILL,14);
                if (on) bg.setStroke(Math.max(1,dp(1)),Ui.ACCENT);
                row.setBackground(Ui.ripple(this,bg,Ui.press(),14));
                labels.get(i).setTextColor(on?Ui.ACCENT:Ui.TEXT);
                checks.get(i).setProgress(on?1f:0f);
            }
        };

        for (int i=0;i<locations.size();i++) {
            String place=locations.get(i);
            LinearLayout row=new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16),dp(13),dp(14),dp(13));
            TextView label=text(place,15,Ui.TEXT,true);
            row.addView(label,new LinearLayout.LayoutParams(0,-2,1f));
            Ui.CheckCircle circle=new Ui.CheckCircle(this,Ui.RING,Ui.ACCENT,20f,draft.contains(place));
            android.widget.ImageView icon=new android.widget.ImageView(this);
            icon.setImageDrawable(circle);
            row.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));
            row.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                if (draft.contains(place)) draft.remove(place); else draft.add(place);
                sync.run();
            });
            rows.add(row); labels.add(label); checks.add(circle);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            if (i>0) lp.topMargin=dp(8);
            body.addView(row,lp);
        }
        selectAll.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            draft.addAll(locations); sync.run();
        });
        sync.run();

        LinearLayout footer=CourseUi.column(this);
        footer.addView(Ui.hairline(this));
        CourseUi.gap(footer,12);
        LinearLayout actions=new LinearLayout(this);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView cancel=CourseUi.action(this,"取消",false,() -> {});
        actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1f));
        TextView confirm=CourseUi.action(this,"确定",true,() -> {});
        LinearLayout.LayoutParams confirmLp=new LinearLayout.LayoutParams(0,-2,2f);
        confirmLp.leftMargin=dp(12);
        actions.addView(confirm,confirmLp);
        footer.addView(actions,new LinearLayout.LayoutParams(-1,-2));

        android.app.Dialog dialog=Ui.sheet(this,body,footer);
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            if (draft.isEmpty()) { toast("请至少选择一个教学楼"); return; }
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            selectedRoomLocations.clear(); selectedRoomLocations.addAll(draft);
            dialog.dismiss(); roomOptionsChanged();
        });
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
        LinearLayout query=card(); query.setPadding(0,0,0,0);
        // 第1行：今天日期 + 校区双切换滑块
        LinearLayout rowCampus=new LinearLayout(this); rowCampus.setGravity(Gravity.CENTER_VERTICAL);
        rowCampus.setPadding(dp(16),dp(11),dp(12),dp(11));
        LinearLayout campusTitle=CourseUi.column(this);
        campusTitle.addView(text("校区",12,Ui.MUTE,false));
        campusTitle.addView(text("今天 · "+CampusClassroomQuery.today(System.currentTimeMillis()),11,Ui.SUB,false));
        rowCampus.addView(campusTitle,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout seg=new LinearLayout(this); seg.setPadding(dp(2),dp(2),dp(2),dp(2));
        seg.setBackground(Ui.round(this,Ui.FILL,9));
        for (String cName:new String[]{"长安校区","友谊校区"}) {
            boolean on=cName.equals(roomCampus);
            TextView tab=text(cName.replace("校区",""),12,on?Ui.TEXT:Ui.SUB,on);
            tab.setGravity(Gravity.CENTER); tab.setPadding(dp(12),dp(5),dp(12),dp(5));
            tab.setBackground(Ui.ripple(this,on?Ui.roundStroke(this,Ui.CARD,Ui.LINE,7,0.75f):null,Ui.press(),7));
            tab.setOnClickListener(v -> {
                if (cName.equals(roomCampus)) return;
                roomCampus=cName; selectedRoomLocations.clear(); submittedRoomLocations.clear(); roomLocations.clear();
                getSharedPreferences("classroom-live",MODE_PRIVATE).edit().putString("campus",roomCampus).apply();
                roomOptionsChanged();
            });
            seg.addView(tab);
        }
        rowCampus.addView(seg); query.addView(rowCampus);
        query.addView(Ui.hairline(this));

        // 第2行：教学楼多选（整行轻触即选，不再用胖按钮）
        LinearLayout rowLoc=new LinearLayout(this); rowLoc.setGravity(Gravity.CENTER_VERTICAL);
        rowLoc.setPadding(dp(16),dp(13),dp(14),dp(13));
        rowLoc.setBackground(Ui.ripple(this,null,Ui.press(),0));
        rowLoc.setFocusable(true);
        rowLoc.setContentDescription("教学楼筛选，"+locationLabel);
        rowLoc.setEnabled(!locations.isEmpty());
        rowLoc.addView(text("教学楼",14,Ui.SUB,false),new LinearLayout.LayoutParams(dp(64),-2));
        TextView locVal=text(locationLabel,14,locations.isEmpty()?Ui.MUTE:Ui.TEXT,true);
        locVal.setGravity(Gravity.END); locVal.setSingleLine(true); locVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rowLoc.addView(locVal,new LinearLayout.LayoutParams(0,-2,1));
        android.widget.ImageView locChev=new android.widget.ImageView(this); locChev.setImageDrawable(Ui.chevron(this,Ui.RING));
        LinearLayout.LayoutParams lcLp=new LinearLayout.LayoutParams(dp(15),dp(15)); lcLp.leftMargin=dp(6);
        rowLoc.addView(locChev,lcLp);
        rowLoc.setOnClickListener(v -> showLocationPicker(locations));
        query.addView(rowLoc);
        query.addView(Ui.hairline(this));

        // 第3行：起止时间 + 极简 1h / 2h 快捷字链
        LinearLayout rowTime=new LinearLayout(this); rowTime.setGravity(Gravity.CENTER_VERTICAL);
        rowTime.setPadding(dp(16),dp(11),dp(12),dp(11));
        rowTime.addView(text("时段",14,Ui.SUB,false),new LinearLayout.LayoutParams(dp(64),-2));
        LinearLayout timeLinks=new LinearLayout(this); timeLinks.setGravity(Gravity.CENTER_VERTICAL);
        TextView start=text(CampusClassroomQuery.time(roomStart),15,Ui.TEXT,true);
        start.setPadding(dp(6),dp(5),dp(6),dp(5)); start.setBackground(Ui.ripple(this,null,Ui.press(),8));
        start.setOnClickListener(v -> chooseRoomTime(true));
        timeLinks.addView(start);
        timeLinks.addView(text(" – ",14,Ui.MUTE,false));
        TextView end=text(CampusClassroomQuery.time(roomEnd),15,Ui.TEXT,true);
        end.setPadding(dp(6),dp(5),dp(6),dp(5)); end.setBackground(Ui.ripple(this,null,Ui.press(),8));
        end.setOnClickListener(v -> chooseRoomTime(false));
        timeLinks.addView(end);
        rowTime.addView(timeLinks,new LinearLayout.LayoutParams(0,-2,1));
        for (int h=1;h<=2;h++) {
            final int hrs=h;
            TextView q=text(h+"小时",12,Ui.ACCENT,true);
            q.setPadding(dp(9),dp(5),dp(9),dp(5));
            q.setBackground(Ui.ripple(this,Ui.roundStroke(this,Ui.CARD,Ui.LINE,8,0.75f),Ui.press(),8));
            q.setOnClickListener(v -> quickRoomPreset(hrs));
            LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-2,-2);
            if (h==2) qp.leftMargin=dp(6);
            rowTime.addView(q,qp);
        }
        query.addView(rowTime);

        LinearLayout actionWrap=CourseUi.column(this);
        actionWrap.setPadding(dp(12),dp(2),dp(12),dp(12));
        TextView submit=CourseUi.action(this,roomLoading?"重新查询此时段":"查询此时段",true,() -> startClassroomQuery(true));
        submit.setMinHeight(dp(42));
        actionWrap.addView(submit,new LinearLayout.LayoutParams(-1,dp(42)));
        query.addView(actionWrap);
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
        LinearLayout roomHead=new LinearLayout(this); roomHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rhl=new LinearLayout.LayoutParams(-1,-2); rhl.topMargin=dp(18); rhl.bottomMargin=dp(8);
        TextView rhTitle=text("找到 "+rooms.size()+" 间空闲教室",14,Ui.SUB,true); rhTitle.setPadding(dp(4),0,0,0);
        roomHead.addView(rhTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView copyRooms=smallAction("复制列表",Ui.ACCENT,Ui.ACCENT_BG,true,() -> {
            StringBuilder sb=new StringBuilder();
            String cond=CampusSummary.field(module,"查询条件");
            if (!cond.isEmpty()) sb.append(cond).append("\n");
            for (int i=0;i<rooms.size();i++) {
                if (i>0) sb.append("\n");
                sb.append(rooms.get(i).name);
            }
            try {
                android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("空闲教室",sb.toString()));
                toast("已复制空闲教室列表");
            } catch (RuntimeException e) { toast("复制失败"); }
        });
        roomHead.addView(copyRooms);
        list.addView(roomHead,rhl);
        note(CampusSummary.field(module,"查询条件")+(submittedRoomLocations.isEmpty()?"":" · "+String.join("、",submittedRoomLocations)));
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

    private void quickRoomPreset(int hours) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        int nowMin = Math.min(1438, cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE));
        roomStart = nowMin;
        roomEnd = Math.min(1439, nowMin + hours * 60);
        startClassroomQuery(true);
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

    private void renderKeepingScroll() {
        int y=scroll==null?0:scroll.getScrollY();
        render();
        if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,y));
    }
    private String currentOwner() { return data==null?"":data.owner; }
    private void loadBusTicketsForOwner() {
        String owner=currentOwner();
        if (owner.equals(busTicketOwner)) return;
        busTicketOwner=owner; busTicketsScanPendingAll=true;
        if (owner.isEmpty()) { busTickets=java.util.Collections.emptyList(); return; }
        String today=CampusClassroomQuery.today(System.currentTimeMillis());
        String raw=getSharedPreferences("campus-bus-tickets",MODE_PRIVATE).getString("snapshot","");
        busTickets=CampusBusTicket.decodeStorage(raw,owner,today);
    }
    private void saveBusTickets() {
        String owner=currentOwner();
        if (owner.isEmpty() || !owner.equals(busTicketOwner)) return;
        String encoded=CampusBusTicket.encodeStorage(owner,busTickets);
        if (!encoded.isEmpty()) getSharedPreferences("campus-bus-tickets",MODE_PRIVATE).edit().putString("snapshot",encoded).apply();
    }
    private void syncSingleBusTicket(JSONObject trip,String targetDate,JSONObject quoteResult) {
        loadBusTicketsForOwner();
        if (currentOwner().isEmpty()) return;
        busTicketWriteEpoch++;
        String today=CampusClassroomQuery.today(System.currentTimeMillis());
        busTickets=CampusBusTicket.upsertFromQuote(busTickets,trip,targetDate,quoteResult,today);
        saveBusTickets();
        renderKeepingScroll();
    }
    private void startBusOrderScan(JSONArray currentTrips,String queriedDate) {
        if (busRefresh==null || isDestroyed() || isFinishing() || currentOwner().isEmpty()) return;
        loadBusTicketsForOwner();
        final String ownerSnapshot=currentOwner();
        final int startWriteEpoch=busTicketWriteEpoch;
        final int scanId=++busScanEpoch;
        final String[] threeDays=CampusBusTicket.threeDaysFrom(System.currentTimeMillis());
        final String[] targetDates=busTicketsScanPendingAll?threeDays:new String[]{queriedDate};
        final CampusBusRefresh runner=busRefresh;
        busTicketsScanning=true;
        renderKeepingScroll();
        runner.scanOrders(targetDates,queriedDate,currentTrips,(results,extraTickets) -> {
            if (isDestroyed() || isFinishing() || busRefresh!=runner || scanId!=busScanEpoch) return;
            busTicketsScanning=false;
            if (!ownerSnapshot.equals(currentOwner())) return;
            if (startWriteEpoch!=busTicketWriteEpoch) { renderKeepingScroll(); return; }
            String today=CampusClassroomQuery.today(System.currentTimeMillis());
            busTickets=CampusBusTicket.mergeScanResults(busTickets,results,extraTickets,today);
            if (results!=null && results.length()>0) busTicketsScanPendingAll=false;
            saveBusTickets();
            renderKeepingScroll();
        });
    }
    private void openBusPortal() {
        if (busRefresh==null) busRefresh=new CampusBusRefresh(this,(t,e) -> { });
        busRefresh.openPortal(currentOwner(),() -> {
            if (isDestroyed() || isFinishing() || !busLinked()) return;
            busTicketsScanPendingAll=true; startBusQuery(); render();
        });
    }
    private void startBusQuery() {
        ticketBusy=false; busTicketsScanning=false;
        loadBusTicketsForOwner();
        if (busRefresh!=null) busRefresh.stop();
        busAttempted=true; busLoading=true; busError=""; busTrips=null; busUpdated=0;
        final String queriedDate=busDate;
        busRefresh=new CampusBusRefresh(this,(trips,error) -> {
            busLoading=false; busTrips=trips; busError=error;
            if (trips!=null) { busUpdated=System.currentTimeMillis(); startBusOrderScan(trips,queriedDate); }
            renderKeepingScroll();
        });
        busRefresh.start(data,queriedDate);
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
    private static int nowMinutesShanghai() {
        java.util.Calendar cal=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return cal.get(java.util.Calendar.HOUR_OF_DAY)*60+cal.get(java.util.Calendar.MINUTE);
    }
    private static int busClockMinutes(String value) {
        if (value==null) return -1;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(value);
        if (!m.find()) return -1;
        int h=Integer.parseInt(m.group(1)),min=Integer.parseInt(m.group(2));
        return h>=0 && h<=23 && min>=0 && min<=59 ? h*60+min : -1;
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
            CampusBusTicket.Ticket owned=CampusBusTicket.findForTrip(busTickets,busDate,trip);
            String label; int fg; int bg;
            if (owned!=null) {
                if (owned.payable) { label="待支付"; fg=Ui.ON_ACCENT; bg=Ui.ORANGE; }
                else if (owned.paid && "已核验".equals(owned.status)) { label="已核验"; fg=Ui.SUB; bg=Ui.CARD; }
                else if (owned.paid) { label="已购 · "+(owned.status.isEmpty()?"待核验":owned.status); fg=Ui.GREEN; bg=Ui.GREEN_BG; }
                else { label=owned.status; fg=Ui.ACCENT; bg=Ui.ACCENT_BG; }
            } else {
                label=open?"购票":"查看订单"; fg=open?Ui.ON_ACCENT:Ui.TEXT; bg=open?Ui.ACCENT:Ui.CARD;
            }
            TextView action=smallAction(label,fg,bg,true,() -> {
                if (owned!=null && owned.hasQrCode()) showBusQr(owned);
                else ticketQuote(trip,busDate);
            });
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
        if (busLinked()) head.addView(smallAction("刷新",Ui.ACCENT,Ui.ACCENT_BG,!busLoading,() -> { busTicketsScanPendingAll=true; startBusQuery(); render(); }));
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
        boolean isBusToday=busDate.equals(CampusClassroomQuery.today(System.currentTimeMillis()));
        if (isBusToday) {
            TextView pastToggle=smallAction(busHidePast?"只看未发车":"全部班次",busHidePast?Ui.ON_ACCENT:Ui.TEXT,busHidePast?Ui.ACCENT:Ui.FILL,true,() -> { busHidePast=!busHidePast; render(); });
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2); tp.leftMargin=dp(8); dateRow.addView(pastToggle,tp);
        }
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
        int nowMins=isBusToday&&busHidePast?nowMinutesShanghai():-1;
        java.util.ArrayList<JSONObject> visibleTrips=new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> allRoutes=new java.util.LinkedHashSet<>();
        int openCount=0;
        String nextOpenTime="";
        for (int i=0;i<busTrips.length();i++) {
            JSONObject trip=busTrips.optJSONObject(i); if (trip==null) continue;
            if (nowMins>=0) {
                int tm=busClockMinutes(trip.optString("time"));
                if (tm>=0 && tm<nowMins) continue;
            }
            visibleTrips.add(trip);
            String routeName=trip.optString("route");
            allRoutes.add(routeName.isEmpty()?"其他班次":routeName);
            if (trip.optBoolean("open")) {
                openCount++;
                if (nextOpenTime.isEmpty()) nextOpenTime=trip.optString("time");
            }
        }
        if (visibleTrips.isEmpty() && isBusToday && busHidePast) {
            CourseUi.gap(live,12);
            live.addView(banner("今天的班次已全部发车。",Ui.SUB,Ui.FILL));
            CourseUi.gap(live,10);
            live.addView(CourseUi.action(this,"显示今日全部班次",false,() -> { busHidePast=false; render(); }),CourseUi.row(dp(42)));
            return;
        }
        if (!busLiveRoute.isEmpty() && !allRoutes.contains(busLiveRoute)) busLiveRoute="";
        CourseUi.gap(live,10);
        String busSummary=(isBusToday&&busHidePast?"未发车 ":"共 ")+visibleTrips.size()+" 班 · 可购 "+openCount+" 班"
                +(nextOpenTime.isEmpty()?"":" · 最近可购 "+nextOpenTime);
        live.addView(text(busSummary,11,openCount>0?Ui.GREEN:Ui.SUB,true));
        if (allRoutes.size()>1) {
            CourseUi.gap(live,10);
            android.widget.HorizontalScrollView routeScroll=new android.widget.HorizontalScrollView(this);
            routeScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout routeRow=new LinearLayout(this);
            java.util.ArrayList<String> routeTabs=new java.util.ArrayList<>();
            routeTabs.add("");
            routeTabs.addAll(allRoutes);
            for (int ri=0;ri<routeTabs.size();ri++) {
                String rKey=routeTabs.get(ri);
                String rLabel=rKey.isEmpty()?"全部路线":rKey;
                boolean selected=rKey.equals(busLiveRoute);
                TextView chip=smallAction(rLabel,selected?Ui.ON_ACCENT:Ui.TEXT,selected?Ui.ACCENT:Ui.FILL,true,() -> { busLiveRoute=rKey; render(); });
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
                if (ri>0) cp.leftMargin=dp(8);
                routeRow.addView(chip,cp);
            }
            routeScroll.addView(routeRow);
            live.addView(routeScroll);
        }
        java.util.LinkedHashMap<String,java.util.ArrayList<JSONObject>> groups=new java.util.LinkedHashMap<>();
        for (JSONObject trip:visibleTrips) {
            String route=trip.optString("route");
            String routeKey=route.isEmpty()?"其他班次":route;
            if (!busLiveRoute.isEmpty() && !busLiveRoute.equals(routeKey)) continue;
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
    private void ticketQuote(JSONObject trip,String targetDate) {
        if (ticketBusy) return;
        if (busLoading) { toast("正在连接校车系统，请稍候…"); return; }
        if (busRefresh==null) { toast("请先刷新连接校车系统，或点右上角「校方订单」"); return; }
        String queryDate=(targetDate==null || targetDate.isEmpty())?busDate:targetDate;
        ticketBusy=true; toast("正在查询票价和订单…");
        busRefresh.ticket("quote",trip,queryDate,"",false,(result,error) -> {
            ticketBusy=false;
            if (result==null) { toast(error); return; }
            syncSingleBusTicket(trip,queryDate,result);
            if (result.optBoolean("paid")) {
                CampusBusTicket.Ticket latest=CampusBusTicket.findForTrip(busTickets,queryDate,trip);
                if (latest==null || !latest.hasQrCode()) startBusOrderScan(busTrips,queryDate);
                AlertDialog.Builder ok=new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("购票成功")
                        .setMessage(queryDate+" "+trip.optString("time")+" "+trip.optString("route")+"\n订单："+result.optString("order")+"\n状态："+result.optString("status"))
                        .setNeutralButton("查看校方订单",(d,w) -> openBusPortal());
                if (latest!=null && latest.hasQrCode()) ok.setNegativeButton("知道了",null).setPositiveButton("出示乘车码",(d,w) -> showBusQr(latest));
                else ok.setNegativeButton("知道了",null).setPositiveButton("去我的车票",(d,w) -> { busTab=1; render(); if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,0)); });
                ok.show(); return;
            }
            if (result.optBoolean("payable")) { ticketPrepare(trip,queryDate,result.optString("order"),0); return; }
            if (!result.optBoolean("canSubmit") || !trip.optBoolean("open")) { toast("当前不可购票，订单状态："+result.optString("status","未下单")); return; }
            LinearLayout body=CourseUi.column(this); body.setPadding(dp(24),dp(12),dp(24),dp(12));
            body.addView(text(queryDate+" "+trip.optString("time")+"\n"+trip.optString("route")+"\n应付：¥"+result.optString("fee"),16,Ui.TEXT,true));
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
                    busRefresh.ticket("submit",trip,queryDate,result.optString("fee"),true,(order,err) -> {
                        ticketBusy=false;
                        if (order==null) { new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("订单结果未确认").setMessage(err).setPositiveButton("查询订单",(a,b) -> ticketQuote(trip,queryDate)).setNegativeButton("稍后查询",null).show(); return; }
                        syncSingleBusTicket(trip,queryDate,order);
                        if (order.optBoolean("paid")) { ticketQuote(trip,queryDate); return; }
                        if (!order.optString("order").isEmpty()) ticketPrepare(trip,queryDate,order.optString("order"),0);
                        else { toast("订单状态已变化，请重新查询"); ticketQuote(trip,queryDate); }
                    });
                });
            }); dialog.show();
        });
    }
    private void ticketPrepare(JSONObject trip,String targetDate,String order,int attempt) {
        if (ticketBusy || busRefresh==null || !resumed) return;
        String queryDate=(targetDate==null || targetDate.isEmpty())?busDate:targetDate;
        ticketBusy=true;
        busRefresh.ticket("prepare",trip,queryDate,order,false,(result,error) -> {
            ticketBusy=false;
            if (result==null) { toast(error); return; }
            syncSingleBusTicket(trip,queryDate,result);
            if (result.optBoolean("paid")) { ticketQuote(trip,queryDate); return; }
            if (result.optBoolean("preparing") && attempt<10) {
                updates.postDelayed(() -> ticketPrepare(trip,queryDate,order,attempt+1),2000); return;
            }
            String prepay=result.optString("prepay");
            if (prepay.isEmpty()) { toast("支付单尚未就绪，请稍后点击查看订单继续"); return; }
            new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("订单待支付").setMessage("订单："+order+"\n应付：¥"+result.optString("fee")+"\n在应用内的校方收银台完成支付后，将查询校方订单结果。")
                .setNegativeButton("稍后支付",null).setPositiveButton("去支付",(d,w) -> busRefresh.openCashier(order,prepay,() -> ticketQuote(trip,queryDate),(r,e) -> toast(e))).show();
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
    private void showBusQr(CampusBusTicket.Ticket ticket) {
        Runnable verify=() -> {
            if (!ticket.date.isEmpty() && !ticket.id.isEmpty()) ticketQuote(ticket.toTripJson(),ticket.date);
            else { busTicketsScanPendingAll=true; startBusQuery(); render(); }
        };
        BusQrDialog.show(this,ticket,verify,this::openBusPortal);
    }
    private void renderBusTicketsPage() {
        if (!busLinked()) {
            LinearLayout login=card();
            login.addView(text("我的车票",17,Ui.TEXT,true));
            CourseUi.gap(login,10);
            TextView hint=text("连接校园账号后，自动汇总你购买的车票，支持直接出示乘车核验二维码与继续支付。",12,Ui.SUB,false);
            hint.setLineSpacing(dp(3),1); login.addView(hint);
            CourseUi.gap(login,14);
            login.addView(CourseUi.action(this,"连接校园账号",true,() -> startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"))),CourseUi.row(dp(48)));
            return;
        }
        loadBusTicketsForOwner();
        LinearLayout topCard=card();
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setBaselineAligned(false);
        String heading="我的车票";
        if (!busTickets.isEmpty()) {
            int payCount=0;
            for (CampusBusTicket.Ticket t:busTickets) if (t.payable) payCount++;
            heading=payCount>0?"我的车票 · 待支付 "+payCount+" 张":"我的车票 · "+busTickets.size()+" 张";
        }
        head.addView(text(heading,17,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView refreshBtn=smallAction("刷新",Ui.ACCENT,Ui.ACCENT_BG,!busLoading && !busTicketsScanning,() -> { busTicketsScanPendingAll=true; startBusQuery(); render(); });
        head.addView(refreshBtn);
        TextView portalBtn=smallAction("校方订单",Ui.TEXT,Ui.FILL,true,this::openBusPortal);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2); pp.leftMargin=dp(6);
        head.addView(portalBtn,pp);
        topCard.addView(head);
        if (busTicketsScanning || busLoading) {
            CourseUi.gap(topCard,12);
            android.widget.ProgressBar bar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true); bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            topCard.addView(bar,CourseUi.row(dp(3)));
            CourseUi.gap(topCard,8);
            topCard.addView(text("正在向校方核对车票与乘车核验码…",12,Ui.SUB,false));
        } else if (!busError.isEmpty() && busTickets.isEmpty()) {
            CourseUi.gap(topCard,12);
            topCard.addView(banner(busError,Ui.RED,Ui.RED_BG));
        } else if (busTickets.isEmpty()) {
            CourseUi.gap(topCard,12);
            topCard.addView(text("当前暂无有效车票，去「买票」页挑一班合适的车吧。",12,Ui.SUB,false));
            CourseUi.gap(topCard,12);
            topCard.addView(CourseUi.action(this,"去买票",true,() -> { busTab=0; render(); if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,0)); }),CourseUi.row(dp(44)));
        }

        if (!busTickets.isEmpty()) {
            String[] threeDays=CampusBusTicket.threeDaysFrom(System.currentTimeMillis());
            for (CampusBusTicket.Ticket t:busTickets) {
                LinearLayout ticketCard=card();
                LinearLayout rowTop=new LinearLayout(this); rowTop.setGravity(Gravity.CENTER_VERTICAL); rowTop.setBaselineAligned(false);
                rowTop.addView(text(CampusBusTicket.friendlyDate(t.date,threeDays)+" "+t.time,19,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
                boolean verified="已核验".equals(t.status);
                TextView badge;
                if (t.payable) badge=Ui.tag(this,"待支付",Ui.ORANGE,Ui.ORANGE_BG);
                else if (t.paid && verified) badge=Ui.tag(this,"已核验",Ui.SUB,Ui.FILL);
                else if (t.paid) badge=Ui.tag(this,t.status.isEmpty()?"待核验":t.status,Ui.GREEN,Ui.GREEN_BG);
                else badge=Ui.tag(this,t.status,Ui.ACCENT,Ui.ACCENT_BG);
                rowTop.addView(badge);
                ticketCard.addView(rowTop);

                StringBuilder routeLine=new StringBuilder();
                String r=t.displayRoute();
                if (!r.isEmpty()) routeLine.append(r);
                if (!t.fee.isEmpty()) { if (routeLine.length()>0) routeLine.append(" · "); routeLine.append("¥").append(t.fee); }
                if (routeLine.length()>0) {
                    CourseUi.gap(ticketCard,6);
                    ticketCard.addView(text(routeLine.toString(),14,Ui.TEXT,false));
                }
                if (!t.place.isEmpty()) {
                    CourseUi.gap(ticketCard,4);
                    ticketCard.addView(text("上车地点："+t.place,12,Ui.SUB,false));
                }
                if (t.payable && t.payRemainMin>=0) {
                    CourseUi.gap(ticketCard,4);
                    ticketCard.addView(text("剩余支付时间约 "+t.payRemainMin+" 分钟，请及时完成付款",12,Ui.ORANGE,true));
                }
                String refText=!t.qrCode.isEmpty()?("核验码 "+t.qrCode):(!t.order.isEmpty()?"单号 "+t.order:"");
                if (!refText.isEmpty()) {
                    CourseUi.gap(ticketCard,6);
                    TextView ref=text(refText,11,Ui.MUTE,false);
                    ref.setSingleLine(true); ref.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    ticketCard.addView(ref);
                }

                CourseUi.gap(ticketCard,14);
                LinearLayout btns=new LinearLayout(this); btns.setBaselineAligned(false);
                if (t.hasQrCode()) {
                    TextView verifyBtn=CourseUi.action(this,"实时复核",false,() -> {
                        if (!t.date.isEmpty() && !t.id.isEmpty()) ticketQuote(t.toTripJson(),t.date);
                        else { busTicketsScanPendingAll=true; startBusQuery(); render(); }
                    });
                    verifyBtn.setMinHeight(dp(42));
                    btns.addView(verifyBtn,new LinearLayout.LayoutParams(0,dp(42),1));
                    View spacer=new View(this);
                    btns.addView(spacer,new LinearLayout.LayoutParams(dp(10),1));
                    TextView qrBtn=CourseUi.action(this,"出示乘车码",true,() -> showBusQr(t));
                    qrBtn.setMinHeight(dp(42));
                    btns.addView(qrBtn,new LinearLayout.LayoutParams(0,dp(42),1.4f));
                } else if (t.paid) {
                    TextView portal=CourseUi.action(this,"校方订单",false,this::openBusPortal);
                    portal.setMinHeight(dp(42));
                    btns.addView(portal,new LinearLayout.LayoutParams(0,dp(42),1));
                    btns.addView(new View(this),new LinearLayout.LayoutParams(dp(10),1));
                    TextView syncBtn=CourseUi.action(this,"获取乘车码",true,() -> startBusOrderScan(busTrips,busDate));
                    syncBtn.setMinHeight(dp(42));
                    btns.addView(syncBtn,new LinearLayout.LayoutParams(0,dp(42),1.4f));
                } else if (t.payable) {
                    Runnable payNow=() -> {
                        if (!t.date.isEmpty() && !t.id.isEmpty()) ticketQuote(t.toTripJson(),t.date);
                        else if (!t.order.isEmpty() && !t.prepay.isEmpty() && busRefresh!=null) busRefresh.openCashier(t.order,t.prepay,() -> { busTicketsScanPendingAll=true; startBusQuery(); },(res,err) -> toast(err));
                        else openBusPortal();
                    };
                    TextView payBtn=CourseUi.action(this,"立即支付",true,payNow);
                    payBtn.setMinHeight(dp(42));
                    btns.addView(payBtn,new LinearLayout.LayoutParams(-1,dp(42)));
                } else {
                    TextView detailBtn=CourseUi.action(this,"查看状态",false,() -> {
                        if (!t.date.isEmpty() && !t.id.isEmpty()) ticketQuote(t.toTripJson(),t.date);
                        else openBusPortal();
                    });
                    detailBtn.setMinHeight(dp(42));
                    btns.addView(detailBtn,new LinearLayout.LayoutParams(-1,dp(42)));
                }
                ticketCard.addView(btns);
            }
            note("点「出示乘车码」可亮屏出示官方同款乘车二维码；如需退票或查历史取消订单请点上方「校方订单」。");
        }
    }
    private View busNavBar() {
        LinearLayout shell=new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(Ui.BG);
        shell.addView(Ui.hairline(this),new LinearLayout.LayoutParams(-1,1));
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(4),dp(12),dp(6)); row.setMinimumHeight(dp(52));
        shell.addView(row,new LinearLayout.LayoutParams(-1,-2));
        int count=busTickets.size();
        String[] labels={"买票",count>0?"我的车票 ("+count+")":"我的车票"};
        for (int i=0;i<2;i++) {
            final int tabIdx=i; final boolean selected=busTab==tabIdx;
            LinearLayout tab=new LinearLayout(this); tab.setGravity(Gravity.CENTER); tab.setOrientation(LinearLayout.VERTICAL);
            tab.setPadding(0,dp(4),0,dp(4)); tab.setMinimumHeight(dp(48));
            tab.setSelected(selected); tab.setFocusable(true);
            tab.setBackground(Ui.ripple(this,null,Ui.press(),12)); Ui.pressScale(tab,0.97f);
            int tint=selected?Ui.ACCENT:Ui.MUTE;
            android.widget.ImageView icon=new android.widget.ImageView(this);
            icon.setImageDrawable(busNavGlyph(tabIdx,tint));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView lbl=text(labels[i],11,tint,selected);
            lbl.setSingleLine(true); lbl.setGravity(Gravity.CENTER); lbl.setIncludeFontPadding(false);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(3);
            tab.addView(lbl,lp);
            tab.setOnClickListener(v -> {
                if (busTab==tabIdx) return;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                busTab=tabIdx; render();
                if (scroll!=null) scroll.post(() -> scroll.scrollTo(0,0));
            });
            LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(0,-2,1);
            if (i>0) tlp.leftMargin=dp(4);
            row.addView(tab,tlp);
        }
        return shell;
    }
    private android.graphics.drawable.Drawable busNavGlyph(final int idx,final int color) {
        return new android.graphics.drawable.Drawable() {
            final android.graphics.Paint p=new android.graphics.Paint(3);
            @Override public void draw(android.graphics.Canvas c) {
                c.save(); c.translate(getBounds().left,getBounds().top);
                c.scale(getBounds().width()/24f,getBounds().height()/24f);
                p.setColor(color); p.setStrokeWidth(1.85f); p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                p.setStrokeJoin(android.graphics.Paint.Join.ROUND); p.setStyle(android.graphics.Paint.Style.STROKE);
                if (idx==0) {
                    c.drawRoundRect(new android.graphics.RectF(5,4.2f,19,17.2f),2.8f,2.8f,p);
                    c.drawLine(5,11,19,11,p);
                    c.drawLine(7.5f,17.2f,7.5f,19.5f,p); c.drawLine(16.5f,17.2f,16.5f,19.5f,p);
                    c.drawLine(8.2f,14.2f,9.2f,14.2f,p); c.drawLine(14.8f,14.2f,15.8f,14.2f,p);
                } else {
                    c.drawRoundRect(new android.graphics.RectF(3.5f,5.5f,20.5f,18.5f),2.4f,2.4f,p);
                    c.drawLine(8.5f,5.5f,8.5f,18.5f,p);
                    c.drawLine(12,10,17,10,p); c.drawLine(12,14,15.5f,14,p);
                }
                c.restore();
            }
            @Override public void setAlpha(int a) { p.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    private void renderBus(JSONObject module) {
        if (busTab==1) {
            renderBusTicketsPage();
            return;
        }
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
        routeFilter.setTextSize(13); routeFilter.setMinHeight(dp(36));
        routeFilter.setEnabled(!routeNames.isEmpty()); routeFilter.setBackground(Ui.ripple(this,Ui.roundStroke(this,Ui.CARD,Ui.LINE,9,0.75f),Ui.press(),9));
        filters.addView(routeFilter,new LinearLayout.LayoutParams(0,dp(36),1));
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
        placeFilter.setTextSize(13); placeFilter.setMinHeight(dp(36));
        placeFilter.setEnabled(!availablePlaces.isEmpty()); placeFilter.setAlpha(availablePlaces.isEmpty()?.5f:1f);
        placeFilter.setBackground(Ui.ripple(this,Ui.roundStroke(this,Ui.CARD,Ui.LINE,9,0.75f),Ui.press(),9));
        LinearLayout.LayoutParams placeFilterParams=new LinearLayout.LayoutParams(0,dp(36),1); placeFilterParams.leftMargin=dp(8); filters.addView(placeFilter,placeFilterParams);
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
        new AlertDialog.Builder(this,Ui.dialogTheme()).setItems(new String[]{"立即更新","复制同步自检","切换教务账号","清空本机校园数据","下载最新版"},(d,i) -> {
            if (i==0) {
                boolean started=CampusAutoRefresh.refreshNow(this);
                android.widget.Toast.makeText(this,started?"已开始在后台更新，可以照常使用":"正在后台更新中",android.widget.Toast.LENGTH_SHORT).show();
                updates.postDelayed(this::reload,1500);
            }
            else if (i==1) copySyncDiag();
            else if (i==2) startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu"));
            else if (i==4) startActivity(new Intent(this,UpdateActivity.class));
            else new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("清空本机校园数据？").setMessage("将删除此页面的同步存档和宿舍绑定，可重新同步。").setNegativeButton("取消",null).setPositiveButton("清空",(a,b) -> io.execute(() -> {
                CampusStore.clear(getApplicationContext()); runOnUiThread(() -> { data=null; render(); });
            })).show();
        }).show();
    }
    private void copySyncDiag() {
        String ver="未知";
        try { ver=getPackageManager().getPackageInfo(getPackageName(),0).versionName; } catch (Exception ignored) { }
        android.content.SharedPreferences p=getSharedPreferences("campus-auto",MODE_PRIVATE);
        String text=CampusDiag.report(data,ver,android.os.Build.MODEL+" / Android "+android.os.Build.VERSION.RELEASE,
                p.getString("sync_phase",""),p.getString("sync_result",""),
                p.getInt("done",0),p.getInt("total",0),CampusSilentRefresh.attention(this));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (cm!=null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Offloor 校园同步自检",text));
        toast("已复制脱敏同步自检信息（不含账号、密码和余额）");
    }
    @Override public void finish() { super.finish(); AppNavigation.fade(this); }
    @Override public void onPause() {
        resumed=false;
        if (busRefresh==null || !busRefresh.inCashier()) {
            if (busRefresh!=null) busRefresh.stop();
            busAttempted=false; busLoading=false; busTicketsScanning=false; busTrips=null; ticketBusy=false;
        }
        CampusStore.unobserve(changed); updates.removeCallbacksAndMessages(null); CampusAutoRefresh.pause(this); super.onPause();
    }
    @Override public void onDestroy() { if (busRefresh!=null) busRefresh.stop(); stopClassroomQuery(); CampusSilentRefresh.hostDestroyed(this); io.shutdown(); super.onDestroy(); }
}
