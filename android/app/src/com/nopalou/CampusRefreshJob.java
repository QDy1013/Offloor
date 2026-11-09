package com.nopalou;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Persisted, network-constrained refresh independent of an Activity. */
public class CampusRefreshJob extends JobService {
    static final int JOB_ID=7312;
    private boolean running;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    static void schedule(Context c) {
        try {
            if (CampusCredentials.load(c)==null) return;
            ComponentName service=new ComponentName(c,CampusRefreshJob.class);
            // The standalone timetable test variant deliberately omits background services.
            try { c.getPackageManager().getServiceInfo(service,0); }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) { return; }
            JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler!=null && scheduler.getPendingJob(JOB_ID)==null)
                scheduler.schedule(new JobInfo.Builder(JOB_ID,service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setPeriodic(15*60_000L).build());
        } catch (RuntimeException e) {
            // A rejected background job must never prevent opening the app or logging in.
            EngineHolder.diag(c,"出错","校园后台任务登记失败："+e.getClass().getSimpleName());
        }
    }
    static void cancel(Context c) {
        JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler!=null) scheduler.cancel(JOB_ID);
    }
    @Override public boolean onStartJob(JobParameters params) {
        if (getSharedPreferences("campus-auto",0).getBoolean("after_login_pending",false) || CampusCredentials.load(this)==null || !CampusSilentRefresh.attention(this).isEmpty() || CampusSilentRefresh.running()) return false;
        running=true;
        new Thread(() -> {
            CampusData data=null;
            try { data=CampusStore.load(this); } catch (Exception ignored) { }
            CampusData saved=data;
            handler.post(() -> {
                if (!running) return;
                if (saved==null || saved.owner.isEmpty()) { finish(params); return; }
                CampusCredentials.Login login=CampusCredentials.load(this);
                if (login==null || !saved.owner.equals(login.owner) || CampusSilentRefresh.running()) { finish(params); return; }
                android.content.SharedPreferences p=getSharedPreferences("campus-auto",0);
                if (System.currentTimeMillis()<p.getLong("next",0)) { finish(params); return; }
                String token=CampusAutoRefresh.attach(this,saved.owner,null);
                if (token==null) { finish(params); return; }
                if (!CampusSilentRefresh.startBackground(this,token,() -> finish(params))) {
                    CampusAutoRefresh.finish(this,token,saved.owner,false,true); finish(params);
                }
            });
        }, "campus-refresh").start();
        return true;
    }
    private void finish(JobParameters params) {
        if (!running) return;
        running=false; jobFinished(params,false);
    }
    @Override public boolean onStopJob(JobParameters params) {
        boolean wasRunning=running; running=false; handler.removeCallbacksAndMessages(null);
        if (wasRunning) CampusSilentRefresh.stopBackground(this);
        return true;
    }
}
