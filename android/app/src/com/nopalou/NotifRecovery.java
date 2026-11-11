package com.nopalou;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

/** One recovery operation shared by the UI, listener, boot receiver and watchdog. */
final class NotifRecovery {
    private static NotifReconnect recovery;

    static boolean accessEnabled(Context ctx) {
        if (ctx == null) return false;
        ComponentName listener = new ComponentName(ctx, NotifListener.class);
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) return manager.isNotificationListenerAccessGranted(listener);
            } catch (RuntimeException ignored) { }
        }
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            if (flat != null) for (String entry : flat.split(":")) {
                if (listener.equals(ComponentName.unflattenFromString(entry))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static NotifReconnect get(Context ctx) {
        if (recovery == null) {
            Context application = ctx.getApplicationContext();
            final Context app = application == null ? ctx : application;
            final Handler handler = new Handler(Looper.getMainLooper());
            recovery = new NotifReconnect(new NotifReconnect.Host() {
                public boolean accessEnabled() { return NotifRecovery.accessEnabled(app); }
                public boolean connected() { return EngineHolder.listenerConnected; }
                public void rebind() {
                    ComponentName listener = new ComponentName(app, NotifListener.class);
                    PackageManager manager = app.getPackageManager();
                    int enabled = manager.getComponentEnabledSetting(listener);
                    if (enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                            || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        manager.setComponentEnabledSetting(listener, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP);
                    }
                    NotificationListenerService.requestRebind(listener);
                }
                public void later(Runnable task, long delayMs) { handler.postDelayed(task, delayMs); }
                public void changed() { EngineHolder.refreshCards(); }
                public void log(String kind, String detail) { EngineHolder.diag(app, kind, detail); }
            });
        }
        return recovery;
    }

    static void request(Context ctx, boolean manual) { get(ctx).start(manual); }
    static void connected(Context ctx) { get(ctx).connected(); }
    static boolean running() { return recovery != null && recovery.running(); }
    static boolean failed() { return recovery != null && recovery.state() == NotifReconnect.State.FAILED; }
}
