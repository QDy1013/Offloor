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
    private static final android.os.Handler TIMER=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Map<Activity,Runnable> POLLS=new java.util.WeakHashMap<>();
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("campus-auto",Context.MODE_PRIVATE); }
    private static long version(Context c) {
        try { android.content.pm.PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0); return android.os.Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode; } catch (Exception e) { return 0; }
    }
    static void afterLogin(Activity a) { launch(a,true,true); }
    static void maybeRefresh(Activity a) {
        pause(a);
        java.lang.ref.WeakReference<Activity> ref=new java.lang.ref.WeakReference<>(a);
        Runnable poll=new Runnable() { public void run() {
            Activity current=ref.get();
            if (current==null || current.isFinishing() || current.isDestroyed()) return;
            launch(current,false,false); TIMER.postDelayed(this,60_000);
        } };
        POLLS.put(a,poll); TIMER.postDelayed(poll,60_000); launch(a,false,false);
    }
    static void pause(Activity a) { Runnable poll=POLLS.remove(a); if (poll!=null) TIMER.removeCallbacks(poll); }
    private static void launch(Activity a,boolean force,boolean closeLogin) {
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
                    if (owner.isEmpty()) { if (closeLogin) { a.startActivity(new Intent(a,CampusActivity.class)); a.finish(); } return; }
                    SharedPreferences p=prefs(context); long now=System.currentTimeMillis();
                    boolean same=owner.equals(p.getString("owner",""));
                    if (!force && same && !CampusAutoPolicy.due(now,p.getLong("started",0),p.getBoolean("active",false),p.getLong("next",0),p.getLong("version",0),version(context))) return;
                    String token=java.util.UUID.randomUUID().toString();
                    p.edit().putString("owner",owner).putString("run",token).putBoolean("active",true).putLong("started",now).apply();
                    a.startActivity(new Intent(a,CampusWebActivity.class).putExtra("all",true).putExtra("automatic",true).putExtra("fresh_login",closeLogin).putExtra("academic_incomplete",a.getIntent().getBooleanExtra("academic_incomplete",false)).putExtra("run",token));
                    if (closeLogin) a.finish();
                }
            });
        });
    }
    static String attach(Context c,String owner,String requested) {
        synchronized (LOCK) {
            SharedPreferences p=prefs(c);
            if (requested!=null && requested.equals(p.getString("run","")) && owner.equals(p.getString("owner",""))) return requested;
            long now=System.currentTimeMillis();
            if (p.getBoolean("active",false) && owner.equals(p.getString("owner","")) && now>=p.getLong("started",0) && now-p.getLong("started",0)<CampusAutoPolicy.LEASE) return null;
            String token=java.util.UUID.randomUUID().toString();
            p.edit().putString("owner",owner).putString("run",token).putBoolean("active",true).putLong("started",now).apply(); return token;
        }
    }
    static boolean owns(Context c,String run,String owner) {
        SharedPreferences p=prefs(c); return run!=null && p.getBoolean("active",false) && run.equals(p.getString("run","")) && owner.equals(p.getString("owner",""));
    }
    static void rebind(Context c,String run,String owner) {
        synchronized (LOCK) { SharedPreferences p=prefs(c); if (run!=null && run.equals(p.getString("run",""))) p.edit().putString("owner",owner).putLong("started",System.currentTimeMillis()).apply(); }
    }
    static void finish(Context c,String run,String owner,boolean completed,boolean incomplete) {
        synchronized (LOCK) {
            if (!owns(c,run,owner)) return;
            long delay=!completed?CampusAutoPolicy.CANCEL_INTERVAL:incomplete?CampusAutoPolicy.RETRY_INTERVAL:CampusAutoPolicy.SUCCESS_INTERVAL;
            prefs(c).edit().putBoolean("active",false).putLong("version",version(c)).putLong("next",System.currentTimeMillis()+delay).putLong("finished",System.currentTimeMillis()).apply();
        }
    }
}
