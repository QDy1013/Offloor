package com.nopalou;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

/** Quiet metadata checks; downloads only begin after the user taps the banner. */
final class UpdateNotice {
    private static final long INTERVAL=6*60*60_000L;
    private static boolean checking;
    private static final java.util.List<java.lang.ref.WeakReference<Runnable>> listeners=new java.util.ArrayList<>();
    static UpdateCatalog available(Context c) {
        try {
            PackageInfo installed=c.getPackageManager().getPackageInfo(c.getPackageName(),UpdateActivity.signatureFlags());
            long code=UpdateActivity.code(installed);
            UpdateCatalog next=new UpdateCatalog(c.getSharedPreferences("update-notice",0).getString("catalog",""),c.getPackageName(),code);
            return next.shouldNotify(code,UpdateActivity.certificate(installed))?next:null;
        } catch (Exception e) { return null; }
    }
    static synchronized void check(Context context,Runnable listener) {
        listeners.removeIf(ref -> ref.get()==null || ref.get()==listener);
        listeners.add(new java.lang.ref.WeakReference<>(listener));
        if (checking) return;
        Context app=context.getApplicationContext();
        SharedPreferences p=app.getSharedPreferences("update-notice",0);
        long now=System.currentTimeMillis(),last=p.getLong("checked",0);
        long installed;
        try { installed=UpdateActivity.code(app.getPackageManager().getPackageInfo(app.getPackageName(),0)); }
        catch (Exception e) { return; }
        if (installed==p.getLong("installed",-1) && now>=last && now-last<INTERVAL) return;
        checking=true;
        new Thread(() -> {
            try {
                String raw=UpdateActivity.metadata();
                UpdateCatalog next=new UpdateCatalog(raw,app.getPackageName(),installed);
                PackageInfo info=app.getPackageManager().getPackageInfo(app.getPackageName(),UpdateActivity.signatureFlags());
                if (!next.certificate.equals(UpdateActivity.certificate(info))) throw new java.io.IOException();
                p.edit().putString("catalog",next.json.toString()).putLong("checked",System.currentTimeMillis()).putLong("installed",installed).apply();
            } catch (Exception ignored) {
                // Retry after 15 minutes on failure, retaining any previously verified notice.
                p.edit().putLong("checked",System.currentTimeMillis()-INTERVAL+15*60_000L).putLong("installed",installed).apply();
            } finally {
                new Handler(Looper.getMainLooper()).post(() -> {
                    java.util.List<Runnable> callbacks=new java.util.ArrayList<>();
                    synchronized (UpdateNotice.class) {
                        checking=false;
                        for (java.lang.ref.WeakReference<Runnable> ref:listeners) { Runnable callback=ref.get(); if (callback!=null) callbacks.add(callback); }
                        listeners.clear();
                    }
                    for (Runnable callback:callbacks) callback.run();
                });
            }
        },"offloor-update-check").start();
    }
}
