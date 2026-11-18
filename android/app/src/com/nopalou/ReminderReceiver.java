package com.nopalou;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用一个非精确闹钟唤醒，下次提醒按存档中的最新卡片重算。 */
public final class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "deadlines";
    private static final String SETTINGS = "deadline_settings";
    private static final String DELIVERED = "deadline_delivered";
    private static final int ALARM_ID = 8401;
    private static final int NOTIFICATION_ID = 8402;
    private static long scheduledAt = -1;

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void setEnabled(Context context, Engine engine, boolean enabled) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
        if (!enabled) deliveries(context).edit().clear().apply();
        refresh(context, engine);
    }

    private static SharedPreferences deliveries(Context context) {
        return context.getSharedPreferences(DELIVERED, Context.MODE_PRIVATE);
    }

    static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    static void refresh(Context context, Engine engine) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!isEnabled(context) || engine == null) {
            schedule(context, 0);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
            return;
        }

        long now = System.currentTimeMillis();
        SharedPreferences prefs = deliveries(context);
        Map<String, ?> delivered = prefs.getAll();
        Set<String> current = new HashSet<>();
        boolean activeNotice = false;
        for (Engine.Card card : engine.all()) {
            if (card.deadline > now && ReminderPlan.wasDelivered(card, delivered)) {
                current.add(card.id);
                if (!card.done && !card.needsConfirm) activeNotice = true;
            }
        }
        if (current.size() != delivered.size()) {
            SharedPreferences.Editor editor = prefs.edit();
            for (String id : delivered.keySet()) if (!current.contains(id)) editor.remove(id);
            editor.apply();
        }
        if (!activeNotice && manager != null) manager.cancel(NOTIFICATION_ID);
        schedule(context, canNotify(context) ? ReminderPlan.create(engine.all(), delivered, now).nextAt : 0);
    }

    private static void schedule(Context context, long nextAt) {
        if (nextAt == scheduledAt) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Intent intent = new Intent(context, ReminderReceiver.class);
        int flags = PendingIntent.FLAG_IMMUTABLE;
        if (nextAt == 0) {
            PendingIntent old = PendingIntent.getBroadcast(context, ALARM_ID, intent, flags | PendingIntent.FLAG_NO_CREATE);
            if (old != null) alarms.cancel(old);
        } else {
            PendingIntent alarm = PendingIntent.getBroadcast(context, ALARM_ID, intent, flags);
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(System.currentTimeMillis(), nextAt), alarm);
        }
        scheduledAt = nextAt;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        scheduledAt = -1;
        Engine engine = EngineHolder.get(context);
        if (engine != null && isEnabled(context) && canNotify(context)) {
            SharedPreferences prefs = deliveries(context);
            long now = System.currentTimeMillis();
            ReminderPlan plan = ReminderPlan.create(engine.all(), prefs.getAll(), now);
            if (!plan.due.isEmpty()) {
                try {
                    post(context, plan.due, now);
                } catch (SecurityException denied) {
                    schedule(context, 0);
                    return;
                }
                SharedPreferences.Editor editor = prefs.edit();
                for (Engine.Card card : plan.due) editor.putLong(card.id, card.deadline);
                editor.apply();
            }
        }
        refresh(context, engine);
    }

    private void post(Context context, List<Engine.Card> due, long now) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "待办到期提醒",
                    NotificationManager.IMPORTANCE_DEFAULT));
            builder = new Notification.Builder(context, CHANNEL);
            long expiresAt = now;
            for (Engine.Card card : due) expiresAt = Math.max(expiresAt, card.deadline);
            builder.setTimeoutAfter(expiresAt - now);
        } else {
            builder = new Notification.Builder(context);
        }
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTIFICATION_ID, builder.setSmallIcon(Ui.notifIcon(context, android.R.drawable.ic_lock_idle_alarm))
                .setColor(0xFFC96442)
                .setContentTitle("学习待办即将到期")
                .setContentText("打开 Offloor 查看")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build());
    }
}
