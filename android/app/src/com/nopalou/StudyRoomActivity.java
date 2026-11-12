package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-demand native room reservations. Writes always require explicit confirmation. */
public class StudyRoomActivity extends Activity {
    private StudyRoomSession session;
    private CampusData campus;
    private boolean ready,busy,mine,active,reconciled;
    private android.content.SharedPreferences writeState() { return getSharedPreferences("study_room_writes",MODE_PRIVATE); }
    private boolean pendingWrite() { return campus!=null && writeState().getBoolean(campus.owner,false); }
    private boolean markWrite(boolean pending) { return campus!=null && writeState().edit().putBoolean(campus.owner,pending).commit(); }
    private int page=1,epoch;
    private String date=CampusClassroomQuery.today(System.currentTimeMillis()),error="",message="";
    private JSONArray rows;
    private LinearLayout body;
    private AlertDialog form;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private interface Result { void accept(JSONObject result); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Ui.apply(this);
        if (state!=null) { date=state.getString("date",date); mine=state.getBoolean("mine"); }
    }
    @Override public void onSaveInstanceState(Bundle state) { state.putString("date",date); state.putBoolean("mine",mine); super.onSaveInstanceState(state); }
    @Override public void onStart() {
        super.onStart(); active=true; render(); int token=++epoch;
        io.execute(() -> {
            CampusData loaded=null; try { loaded=CampusStore.load(getApplicationContext()); } catch (Exception ignored) { }
            CampusData value=loaded;
            runOnUiThread(() -> { if (!active || token!=epoch) return; campus=value;
                if (pendingWrite()) { mine=true; message="上次请求结果尚未核对，请先查询我的预约，勿重复操作。"; }
                connect(); });
        });
    }
    @Override public void onStop() {
        active=false; ++epoch; ready=false; busy=false; rows=null; reconciled=false;
        if (form!=null) { form.dismiss(); form=null; }
        if (session!=null) { session.stop(); session=null; }
        super.onStop();
    }
    @Override public void onDestroy() { io.shutdownNow(); super.onDestroy(); }
    private int dp(int n) { return Ui.dp(this,n); }
    private TextView text(String value,int size,int color,boolean bold) { return CourseUi.text(this,value,size,color,bold); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    private LinearLayout card() {
        LinearLayout c=CourseUi.column(this); c.setPadding(dp(16),dp(16),dp(16),dp(16)); Ui.cardSurface(c,18);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(12); body.addView(c,p); return c;
    }
    private TextView action(LinearLayout target,String label,Runnable run) {
        TextView button=CourseUi.action(this,label,false,run); button.setEnabled(!busy); target.addView(button,CourseUi.row(dp(48))); return button;
    }
    private void render() {
        if (!active || isFinishing() || isDestroyed()) return;
        LinearLayout root=CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this,root);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(12),dp(8),dp(18),dp(8));
        header.addView(CourseUi.icon(this,"prev","返回校园",this::finish)); header.addView(text("研修室",24,Ui.TEXT,true)); root.addView(header);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        body=CourseUi.column(this); body.setPadding(dp(18),dp(8),dp(18),dp(24)); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout intro=card(); intro.addView(text("西工大图书馆研修室",17,Ui.TEXT,true)); CourseUi.gap(intro,8);
        intro.addView(text("使用已连接的校园账号；可能需要校园网。仅在手动确认后提交或取消，不后台抢约。",12,Ui.SUB,false));
        if (busy) { CourseUi.gap(intro,10); intro.addView(text("正在连接或读取校方数据…",13,Ui.ACCENT,false)); }
        if (!error.isEmpty()) { CourseUi.gap(intro,10); intro.addView(text(error,13,Ui.AMBER_TX,false)); }
        if (!message.isEmpty()) { CourseUi.gap(intro,10); intro.addView(text(message,13,Ui.SUB,false)); }
        if (!ready) {
            action(intro,"重新连接",this::connect);
            if (session!=null) action(intro,"打开校方页面完成验证",() -> session.openOfficial(this::connect));
            action(intro,"连接或更换校园账号",() -> startActivity(new Intent(this,NwpuImportActivity.class).putExtra("school_id","nwpu")));
            return;
        }
        LinearLayout controls=card();
        action(controls,mine?"查看可预约房间":"我的预约",() -> { mine=!mine; page=1; rows=null; query(); });
        if (!mine) action(controls,"日期："+date,this::chooseDate);
        else controls.addView(text("最近 7 天至未来 30 天 · 包含本人参与的研修室预约",12,Ui.SUB,false));
        action(controls,"刷新校方数据",this::query);
        if (pendingWrite()) {
            controls.addView(text("上次预约或取消的结果尚未核对。请先查我的预约，确认校方状态后再进行下一次操作。",13,Ui.AMBER_TX,false));
            if (mine && reconciled) action(controls,"我已核对预约，解除重复操作保护",() -> {
                new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("已核对校方预约状态？")
                    .setMessage("请确认上次提交或取消的结果；有疑问请稍后刷新，不要重复提交。")
                    .setNegativeButton("继续核对",null).setPositiveButton("已核对",(d,w) -> {
                        if (!active || busy) return;
                        if (markWrite(false)) { message="已核对上次操作结果"; render(); }
                        else toast("保护状态未能保存，请稍后重试");
                    }).show();
            });
        }
        if (rows!=null && rows.length()==0) body.addView(text(mine?"本页没有本人的研修室预约":"本页未返回研修室，不能据此判断其他日期或房间是否空闲",13,Ui.SUB,false));
        for (int i=0;rows!=null && i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if (row==null) continue;
            LinearLayout c=card(); c.addView(text(row.optString("name","研修室"),17,Ui.TEXT,true)); CourseUi.gap(c,6);
            c.addView(text(row.optString("location"),12,Ui.SUB,false)); CourseUi.gap(c,8);
            if (mine) {
                c.addView(text(row.optString("begin")+" — "+row.optString("end"),13,Ui.TEXT,false));
                c.addView(text(row.optString("title")+" · "+row.optString("status"),12,Ui.SUB,false));
                if (row.optBoolean("canCancel")) action(c,"取消本人发起的预约",() -> confirmCancel(row));
            } else {
                c.addView(text("人数（含本人）："+(row.isNull("min")?"待确认":row.optString("min"))+"–"+(row.isNull("max")?"待确认":row.optString("max")),12,Ui.SUB,false));
                JSONArray periods=row.optJSONArray("periods"); StringBuilder open=new StringBuilder("开放时间：");
                for (int j=0;periods!=null && j<periods.length();j++) { JSONObject p=periods.optJSONObject(j); if (p!=null) open.append(p.optString("start")).append("–").append(p.optString("end")).append("  "); }
                c.addView(text(open.toString(),12,Ui.SUB,false));
                JSONArray occupied=row.optJSONArray("occupied");
                if (occupied==null) c.addView(text("占用信息尚未读全，暂不能预约",12,Ui.AMBER_TX,false));
                else if (occupied.length()==0) c.addView(text("本次查询未返回占用；提交前会再次核对",12,Ui.SUB,false));
                else for (int j=0;j<occupied.length();j++) { JSONObject p=occupied.optJSONObject(j); if (p!=null) c.addView(text("已占用："+p.optString("start")+" — "+p.optString("end"),12,Ui.SUB,false)); }
                JSONObject rule=row.optJSONObject("rules");
                if (rule!=null) c.addView(text("时长 "+rule.optString("minMinutes")+"–"+rule.optString("maxMinutes")+" 分钟 · 间隔 "+rule.optString("interval")+" 分钟",12,Ui.SUB,false));
                if (row.optBoolean("available")) action(c,"选择时段并预约",() -> booking(row));
            }
        }
        if (rows!=null) { LinearLayout pages=card(); pages.addView(text("第 "+page+" 页 · 校方每页最多 40 条",12,Ui.SUB,false));
            if (page>1) action(pages,"上一页",() -> { --page; rows=null; query(); });
            // Personal filtering can make a non-final page empty; permit explicit paging.
            action(pages,"下一页",() -> { ++page; rows=null; query(); });
        }
    }
    private void connect() {
        if (busy || !active) return;
        if (session!=null) session.stop();
        ready=false; rows=null; reconciled=false; error=""; busy=true; int token=++epoch;
        session=new StudyRoomSession(this); render();
        session.start(campus,(result,problem) -> {
            if (!active || token!=epoch) return;
            busy=false; error=problem; ready=problem.isEmpty(); render(); if (ready) query();
        });
    }
    private void call(String method,JSONObject args,Result done) {
        if (busy || !ready || session==null) return;
        busy=true; error=""; render(); int token=epoch;
        session.run(method,args,(result,problem) -> {
            if (!active || token!=epoch) return;
            busy=false; ready=session!=null && session.isReady();
            if (!problem.isEmpty()) { error=problem; render(); toast(problem); return; }
            done.accept(result); render();
        });
    }
    private static JSONObject args(Object... pairs) {
        JSONObject out=new JSONObject(); try { for (int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]); } catch (Exception e) { throw new IllegalArgumentException(e); } return out;
    }
    private void query() { call(mine?"mine":"query",args("date",date,"page",page),result -> {
        rows=result.optJSONArray(mine?"bookings":"rooms");
        if (mine && rows!=null) reconciled=true;
    }); }
    private void chooseDate() {
        String[] p=date.split("-");
        new android.app.DatePickerDialog(this,(view,y,m,d) -> { date=String.format(java.util.Locale.ROOT,"%04d-%02d-%02d",y,m+1,d); page=1; rows=null; query(); },Integer.parseInt(p[0]),Integer.parseInt(p[1])-1,Integer.parseInt(p[2])).show();
    }
    private EditText input(LinearLayout target,String label,int limit) {
        target.addView(text(label,12,Ui.SUB,false)); EditText value=new EditText(this); value.setSingleLine(true); value.setTextColor(Ui.TEXT);
        value.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limit)}); target.addView(value,CourseUi.row(dp(48))); return value;
    }
    private void chooseTime(TextView target) {
        String[] p=target.getText().toString().split(":");
        new android.app.TimePickerDialog(this,(view,h,m) -> target.setText(String.format(java.util.Locale.ROOT,"%02d:%02d",h,m)),Integer.parseInt(p[0]),Integer.parseInt(p[1]),true).show();
    }
    private void booking(JSONObject room) {
        call("captcha",new JSONObject(),config -> {
            if ("2".equals(config.optString("mode"))) { message=config.optString("message"); toast(message); session.openOfficial(this::connect); return; }
            LinearLayout fields=CourseUi.column(this); fields.setPadding(dp(18),dp(8),dp(18),dp(16)); ScrollView scroll=new ScrollView(this); scroll.addView(fields);
            fields.addView(text(room.optString("name")+" · "+date,17,Ui.TEXT,true));
            fields.addView(text(config.optString("notice"),12,Ui.SUB,false)); CourseUi.gap(fields,12);
            fields.addView(text("开始时间",12,Ui.SUB,false)); TextView start=CourseUi.action(this,"09:00",false,() -> {}); fields.addView(start,CourseUi.row(dp(48))); start.setOnClickListener(v -> chooseTime(start));
            fields.addView(text("结束时间",12,Ui.SUB,false)); TextView end=CourseUi.action(this,"10:00",false,() -> {}); fields.addView(end,CourseUi.row(dp(48))); end.setOnClickListener(v -> chooseTime(end));
            EditText title=input(fields,"研修主题",100),memo=input(fields,"备注（可选）",300);
            LinearLayout participants=CourseUi.column(this); fields.addView(participants);
            java.util.LinkedHashMap<String,JSONObject> selected=new java.util.LinkedHashMap<>();
            Runnable showMembers=() -> { participants.removeAllViews(); participants.addView(text("本人已计入人数；请添加真实同行人",12,Ui.SUB,false));
                for (JSONObject member:new java.util.ArrayList<>(selected.values())) {
                    TextView remove=CourseUi.action(this,member.optString("name")+" · "+member.optString("account")+"（点击移除）",false,() -> {});
                    participants.addView(remove,CourseUi.row(dp(48))); remove.setOnClickListener(v -> { selected.remove(member.optString("id")); participants.removeView(remove); });
                }
            }; showMembers.run();
            action(fields,"搜索并添加同行人",() -> searchMember(selected,showMembers));
            EditText captcha;
            if ("1".equals(config.optString("mode"))) {
                try {
                    String encoded=config.optString("image"); if (!encoded.startsWith("data:image/") || encoded.length()>700000) throw new IllegalArgumentException();
                    byte[] bytes=android.util.Base64.decode(encoded.substring(encoded.indexOf(',')+1),android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length); if (bitmap==null) throw new IllegalArgumentException();
                    android.widget.ImageView image=new android.widget.ImageView(this); image.setImageBitmap(bitmap); image.setContentDescription("校方图片验证码"); fields.addView(image,CourseUi.row(dp(64)));
                } catch (Exception e) { error="验证码图片无法读取，请重新选择房间"; return; }
                captcha=input(fields,"图片验证码（过期请关闭后重新选择房间）",12);
            } else captcha=null;
            CheckBox agreement=new CheckBox(this); agreement.setText("我已阅读预约须知，并确认参与者真实有效"); agreement.setTextColor(Ui.TEXT); fields.addView(agreement);
            AlertDialog dialog=new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("预约研修室").setView(scroll).setNegativeButton("返回",null).setPositiveButton("核对预约",null).create(); form=dialog;
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (busy) { toast("上一请求尚未完成，请稍候"); return; }
                if (!agreement.isChecked() || title.getText().toString().trim().isEmpty()) { toast("请填写主题并阅读同意预约须知"); return; }
                JSONArray ids=new JSONArray(); StringBuilder names=new StringBuilder("本人");
                for (JSONObject member:selected.values()) { ids.put(member.opt("id")); names.append("、").append(member.optString("name")).append("（").append(member.optString("account")).append("）"); }
                JSONObject payload=args("id",room.opt("id"),"date",date,"start",start.getText().toString(),"end",end.getText().toString(),"title",title.getText().toString(),"memo",memo.getText().toString(),"members",ids,"captcha",captcha==null?"":captcha.getText().toString(),"agreed",true,"confirmed",true);
                new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("确认提交预约？").setMessage(room.optString("name")+"\n"+date+" "+start.getText()+"–"+end.getText()+"\n主题："+title.getText()+"\n参与者："+names+"\n以校方最终受理结果为准。")
                    .setNegativeButton("返回修改",null).setPositiveButton("确认提交",(d2,w) -> { if (busy) return; dialog.dismiss(); form=null; write("submit",payload); }).show();
            })); dialog.show();
        });
    }
    private void searchMember(java.util.LinkedHashMap<String,JSONObject> selected,Runnable refresh) {
        LinearLayout content=CourseUi.column(this); content.setPadding(dp(18),dp(8),dp(18),dp(8)); EditText key=input(content,"姓名或学号（至少两个字符）",60);
        AlertDialog search=new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("搜索真实同行人").setView(content).setNegativeButton("返回",null).setPositiveButton("搜索",null).create();
        search.setOnShowListener(d -> search.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return; if (key.getText().toString().trim().length()<2) { toast("请输入至少两个字符"); return; }
            search.dismiss(); call("members",args("key",key.getText().toString()),result -> {
                JSONArray found=result.optJSONArray("members"); if (found==null || found.length()==0) { toast("未找到可添加的同行人"); return; }
                String[] labels=new String[found.length()]; for (int i=0;i<labels.length;i++) { JSONObject m=found.optJSONObject(i); labels[i]=m.optString("name")+" · "+m.optString("account"); }
                new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("选择同行人").setItems(labels,(dialog,index) -> { JSONObject member=found.optJSONObject(index); if (selected.size()>=30) { toast("最多添加 30 位同行人"); return; } selected.put(member.optString("id"),member); refresh.run(); }).setNegativeButton("返回",null).show();
            });
        })); search.show();
    }
    private void confirmCancel(JSONObject row) {
        new AlertDialog.Builder(this,Ui.dialogTheme()).setTitle("确认取消本人预约？").setMessage(row.optString("name")+"\n"+row.optString("begin")+" — "+row.optString("end"))
            .setNegativeButton("保留预约",null).setPositiveButton("确认取消",(d,w) -> write("cancel",args("uuid",row.optString("uuid"),"confirmed",true))).show();
    }
    private void write(String method,JSONObject payload) {
        if (!active || busy || !ready || session==null) return;
        if (pendingWrite()) { mine=true; rows=null; page=1; toast("请先核对上次操作结果，勿重复提交"); query(); return; }
        // Persist before dispatch, including process death and activity recreation.
        if (!markWrite(true)) { toast("无法保存操作保护状态，本次未提交"); return; }
        reconciled=false;
        call(method,payload,result -> {
            if (result.optBoolean("success")) markWrite(false);
            message=result.optString("message"); toast(message); mine=true; page=1; rows=null;
            // Do not retry a write, even if the server outcome is unknown.
            query();
        });
    }
}
