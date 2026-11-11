package com.nopalou;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

/**
 * 看门狗：系统（尤其 vivo 这类 ROM）会在后台悄悄把通知监听解绑，
 * 用户完全看不出来，界面只显示"未连接"，之后一条群消息都收不到。
 * 这里用 JobScheduler 每 15 分钟醒一次，发现"权限还在但服务没连上"就请求重新绑定；
 * 与首页共用限次恢复，不停用通知服务组件；超时后保留真实的未连接状态。
 */
public class WatchdogJob extends JobService {

    /** 任务编号：全局唯一即可，避免和其它任务撞号。 */
    static final int JOB_ID = 7001;

    /** 检查的周期：15 分钟。系统允许的最小周期就是 15 分钟。 */
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    /** 连续未连接次数落在 SharedPreferences 里的文件名，进程被杀也不丢。 */
    private static final String PREFS = "watchdog";

    /** 连续未连接次数的键。 */
    private static final String KEY_MISS = "miss";

    /**
     * 登记周期任务。
     * 每次 App 启动、开机、应用更新都会调用一次，所以必须先查已经存在的任务：
     * JobScheduler 重复 schedule 同一个 id 会重置周期计时，等于永远等不到下一次执行。
     * 整个方法吞掉所有异常：登记失败只是少了看门狗，绝不能让开机或界面崩掉。
     */
    static void schedule(Context ctx) {
        try {
            // 没有上下文就无从登记。
            if (ctx == null) return;
            // 取系统的任务调度器，拿不到就直接放弃。
            JobScheduler scheduler = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            // 已经登记过（pending 里还有这个 id）就不再重复登记，保住原来的周期。
            if (scheduler.getPendingJob(JOB_ID) != null) return;
            // 周期 15 分钟、重启后保持：setPersisted 需要 RECEIVE_BOOT_COMPLETED 权限，清单里已有。
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, WatchdogJob.class))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build();
            // 真正提交给系统。
            scheduler.schedule(job);
        } catch (Throwable t) {
            // 部分 ROM 限制后台任务会直接抛异常，这里一律忽略，不影响主流程。
        }
    }

    /**
     * 本 App 的通知监听是否还被系统启用着。
     * 系统把已授权的监听服务存成一个扁平字符串（包名/服务名用冒号分隔），
     * 这里只判断里面有没有"本包名/"，有就说明授权还在，服务没连上是系统解绑而不是用户关权限。
     */
    static boolean accessEnabled(Context ctx) {
        return NotifRecovery.accessEnabled(ctx);
    }

    /**
     * 电池优化是否已经把本 App 放行。
     * 这里只作为诊断参考，不参与重连判断：没放行时系统可能随时杀掉进程。
     */
    static boolean batteryIgnored(Context ctx) {
        try {
            // 没有上下文查不了。
            if (ctx == null) return false;
            // 取电源管理器，拿不到就当没放行。
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            // 系统明确说"忽略本 App 的电池优化"才算放行。
            return pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            // 个别 ROM 没有这个接口或直接抛异常，按没放行处理。
            return false;
        }
    }

    /**
     * 每次任务被系统唤醒时执行一次。
     * 只有"权限还在、但服务没连上"才动手：权限被用户关掉时重启组件毫无意义，还会打扰用户。
     */
    @Override
    public boolean onStartJob(JobParameters p) {
        try {
            // 只有授权仍在、当前进程也确认没连上，才值得尝试恢复。
            if (accessEnabled(this) && !EngineHolder.listenerConnected) {
                // 取出连续未连接的计数，第一次是默认 0。
                SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                int miss = sp.getInt(KEY_MISS, 0);
                // 先自增再存回，保证进程中途被杀也能接着累计。
                miss = miss + 1;
                sp.edit().putInt(KEY_MISS, miss).apply();
                // Share the bounded recovery path; never disable the listener component to force a bind.
                EngineHolder.diag(this, "重连", "看门狗检查通知服务（第" + miss + "次）");
                NotifRecovery.request(this, true);
            } else {
                // 已经连上、或用户根本没开权限：把计数清零，下次出问题从第 1 次重新算。
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MISS, 0).apply();
            }
        } catch (Throwable t) {
            // 任何异常都只记诊断，绝不向外抛，否则任务会被系统标记为失败。
            EngineHolder.diag(this, "出错", "看门狗：" + t);
        }
        // 返回 false：本次检查已经同步做完，不需要系统保留唤醒锁等我们。
        return false;
    }

    /** 任务被系统提前取消时调用；这里没有半途的工作要收尾，直接返回 false 表示不用重调度。 */
    @Override
    public boolean onStopJob(JobParameters p) {
        return false;
    }
}
