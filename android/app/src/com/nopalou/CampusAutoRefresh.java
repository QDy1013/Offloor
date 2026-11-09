package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** One automatic batch after login, and automatic refresh on app entry when due. */
final class CampusAutoRefresh {
    private static final Object LOCK=new Object();
    private static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static boolean pending;
    private static String liveRun;
    private static final android.os.Handler TIMER=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Map<Activity,Runnable> POLLS=new java.util.WeakHashMap<>();
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("campus-auto",Context.MODE_PRIVATE); }
    private static long version(Context c) {
        try { android.content.pm.PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0); return android.os.Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode; } catch (Exception e) { return 0; }
    }
    static void afterLogin(Activity a) {
        String entry=a.getIntent().getStringExtra("course_url");
        SharedPreferences.Editor edit=prefs(a).edit().putBoolean("after_login_pending",true);
        if (CampusServices.readUrl(entry)) edit.putString("academic_entry",entry);
        edit.apply();
        a.startActivity(new Intent(a,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        a.finish();
    }
    /** 校园页“立即更新”：不等 6 小时，马上在后台跑一轮。已经在跑就不重复开。 */
    static boolean refreshNow(Activity a) {
        if (CampusSilentRefresh.running()) return false;
        launch(a,true); return true;
    }
    static void maybeRefresh(Activity a) {
        CampusRefreshJob.schedule(a);
        pause(a);
        java.lang.ref.WeakReference<Activity> ref=new java.lang.ref.WeakReference<>(a);
        Runnable poll=new Runnable() { public void run() {
            Activity current=ref.get();
            if (current==null || current.isFinishing() || current.isDestroyed()) return;
            launch(current,false); TIMER.postDelayed(this,60_000);
        } };
        POLLS.put(a,poll); TIMER.postDelayed(poll,60_000); launch(a,prefs(a).getBoolean("after_login_pending",false));
    }
    static void pause(Activity a) { Runnable poll=POLLS.remove(a); if (poll!=null) TIMER.removeCallbacks(poll); }
    private static void launch(Activity a,boolean force) {
        synchronized (LOCK) { if (pending && !force) return; pending=true; }
        Context context=a.getApplicationContext();
        IO.execute(() -> {
            CampusData data=null;
            try { data=CampusStore.load(context); } catch (Exception ignored) { }
            String owner=data==null?"":data.owner;
            a.runOnUiThread(() -> {
                synchronized (LOCK) {
                    pending=false;
                    if (a.isFinishing() || a.isDestroyed()) return;
                    if (!force && !POLLS.containsKey(a)) return;
                    if (owner.isEmpty()) return;
                    SharedPreferences p=prefs(context); long now=System.currentTimeMillis();
                    // WebView work dies with its process; a persisted lease alone is not
                    // evidence of a running reader after an update or a system process kill.
                    if (p.getBoolean("active",false) && !p.getString("run","").equals(liveRun)) p.edit().putBoolean("active",false).putLong("next",0).apply();
                    boolean same=owner.equals(p.getString("owner",""));
                    // 自动续登失败或安全验证等用户处理，不反复尝试密码
                    if (!force && !CampusSilentRefresh.attention(context).isEmpty()) return;
                    if (CampusSilentRefresh.running()) {
                        p.edit().remove("after_login_pending").apply(); return;
                    }
                    if (!force && same && !CampusAutoPolicy.due(now,p.getLong("started",0),p.getBoolean("active",false),p.getLong("next",0),p.getLong("version",0),version(context))) return;
                    String token=java.util.UUID.randomUUID().toString();
                    String entry=p.getString("academic_entry",NwpuImportActivity.COURSE_URL);
                    if (!CampusServices.readUrl(entry)) entry=NwpuImportActivity.COURSE_URL;
                    p.edit().remove("after_login_pending").putString("owner",owner).putString("run",token).putBoolean("active",true).putLong("started",now).putString("academic_entry",entry).putInt("done",0).putInt("total",0).putString("sync_phase","正在连接校园服务").putString("sync_result","").apply();
                    liveRun=token;
                    // 后台更新：不打开新页面、不转圈，浏览器挂在常驻的待办页（没有时挂在当前页）下面。
                    Activity host=MainActivity.live();
                    CampusSilentRefresh.start(host!=null?host:a,token);
                }
            });
        });
    }
    static String attach(Context c,String owner,String requested) {
        synchronized (LOCK) {
            SharedPreferences p=prefs(c);
            if (requested!=null && requested.equals(p.getString("run","")) && owner.equals(p.getString("owner",""))) { liveRun=requested; p.edit().putBoolean("active",true).putLong("started",System.currentTimeMillis()).apply(); return requested; }
            long now=System.currentTimeMillis();
            if (p.getBoolean("active",false) && p.getString("run","").equals(liveRun) && owner.equals(p.getString("owner","")) && now>=p.getLong("started",0) && now-p.getLong("started",0)<CampusAutoPolicy.LEASE) return null;
            String token=java.util.UUID.randomUUID().toString();
            p.edit().putString("owner",owner).putString("run",token).putBoolean("active",true).putLong("started",now).putInt("done",0).putInt("total",0).putString("sync_phase","正在连接校园服务").putString("sync_result","").apply(); liveRun=token; return token;
        }
    }
    static boolean owns(Context c,String run,String owner) {
        SharedPreferences p=prefs(c); return run!=null && p.getBoolean("active",false) && run.equals(p.getString("run","")) && owner.equals(p.getString("owner",""));
    }
    static void rebind(Context c,String run,String owner) {
        synchronized (LOCK) { SharedPreferences p=prefs(c); if (run!=null && run.equals(p.getString("run",""))) p.edit().putString("owner",owner).putLong("started",System.currentTimeMillis()).apply(); }
    }
    static void heartbeat(Context c,String run,String owner) {
        synchronized (LOCK) { if (owns(c,run,owner)) prefs(c).edit().putLong("started",System.currentTimeMillis()).apply(); }
    }
    static void finish(Context c,String run,String owner,boolean completed,boolean incomplete) {
        synchronized (LOCK) {
            if (!owns(c,run,owner)) return;
            long delay=!completed?CampusAutoPolicy.CANCEL_INTERVAL:incomplete?CampusAutoPolicy.RETRY_INTERVAL:CampusAutoPolicy.SUCCESS_INTERVAL;
            SharedPreferences.Editor e=prefs(c).edit();
            if (completed) e.remove(CampusSilentRefresh.ATTENTION);     // 读通过了一轮：之前的“需要重新登录”作废
            e.putString("sync_result",completed?(incomplete?"partial":"complete"):"interrupted");
            e.putBoolean("active",false).putLong("version",version(c)).putLong("next",System.currentTimeMillis()+delay).putLong("finished",System.currentTimeMillis()).apply();
        }
    }
}
