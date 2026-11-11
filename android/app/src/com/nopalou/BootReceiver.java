package com.nopalou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：vivo 等系统重启后会悄悄把通知监听解绑，
 * 这里在开机、锁屏开机完成、应用更新后主动请求重新绑定，
 * 并把卡片提前读进引擎，第一条消息进来时状态已就绪。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        try {
            NotifRecovery.request(context, false);
        } catch (RuntimeException ignored) {
        }

        // 顺便登记看门狗：只在还没有这个任务时才会真正登记，不会重置已有周期。
        try {
            WatchdogJob.schedule(context);
            CampusRefreshJob.schedule(context);
        } catch (Exception ignored) {
        }

        try {
            ReminderReceiver.refresh(context, EngineHolder.get(context));
        } catch (Exception ignored) {
        }
    }
}
