package com.nopalou;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台排队调度：通知来了先用本机规则即时出卡，拿不准的进 AiQueue，
 * 由这个 Job 在有网时（省电模式下还要求在充电）攒成批次交给模型复核。
 * 失败按系统退避重试；密钥错误则停下，等用户改设置后再触发。
 */
public class AiJob extends JobService {
    static final int JOB_ID = 7311;
    /** 攒批：第一条进队后等这么久再发，同一波群消息合成一次请求。 */
    static final long BATCH_DELAY_MS = 20_000L;
    static final int BATCH = 8;
    /** 单次 Job 最多发多少次请求，剩下的下次继续，避免一次占用太久。 */
    static final int MAX_CALLS = 8;

    private volatile boolean stopped;

    static void schedule(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            AiConfig cfg = AiConfig.load(app);
            if (!cfg.ready() || AiQueue.size(app) == 0) return;
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID) != null) return;      // 已经排着了：不重排，免得一直往后推
            js.schedule(build(app, cfg, BATCH_DELAY_MS));
        } catch (RuntimeException e) {
            EngineHolder.diag(ctx, "出错", "AI 复核排队失败：" + e.getClass().getSimpleName());
        }
    }

    /** 设置改了：按新条件重排（例如从“充电时”改成“有网即复核”）。 */
    static void reschedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getApplicationContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
        schedule(ctx);
    }

    /** 设置页“立即处理”：不等攒批、不等充电，只要有网。 */
    static void runNow(Context ctx) {
        Context app = ctx.getApplicationContext();
        AiConfig cfg = AiConfig.load(app);
        if (!cfg.ready()) return;
        JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(JOB_ID);
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(app, AiJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build());
    }

    private static JobInfo build(Context app, AiConfig cfg, long delay) {
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(app, AiJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true);
        if (AiConfig.MODE_CHARGING.equals(cfg.mode)) {
            b.setRequiresCharging(true);
        } else {
            b.setMinimumLatency(delay);
        }
        return b.build();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        Thread t = new Thread(() -> {
            boolean retry = false;
            try {
                retry = work();
            } catch (Throwable e) {
                EngineHolder.diag(this, "出错", "AI 复核：" + e.getClass().getSimpleName());
                retry = true;
            }
            boolean more = !retry && !stopped && AiQueue.size(this) > 0;
            jobFinished(params, retry);
            if (more) schedule(this);        // 本轮额度用完还有剩：排下一轮
        }, "nopalou-ai");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;     // 被系统打断（断网、拔电）：保留队列，稍后重试
    }

    /** @return true 表示遇到可重试的错误，交给系统退避重排 */
    private boolean work() throws Exception {
        Context app = getApplicationContext();
        AiConfig cfg = AiConfig.load(app);
        if (!cfg.ready()) return false;
        AiClient client = new AiClient(cfg);
        int calls = 0, applied = 0;
        if (!cfg.visionReady()) {
            // 图片模型被清空了：排着的图片做不了，丢掉，别挡住文字复核
            List<JSONObject> stale = AiQueue.peek(app, "img", AiQueue.MAX);
            if (!stale.isEmpty()) AiQueue.remove(app, stale);
        }
        while (!stopped && calls < MAX_CALLS) {
            // 先做图片：转写出的文字还会产生新的复核条目，同一轮里接着处理
            List<JSONObject> imgs = AiQueue.peek(app, "img", 1);
            if (!imgs.isEmpty()) {
                JSONObject o = imgs.get(0);
                calls++;
                try {
                    byte[] jpeg = readFile(new File(o.optString("path")));
                    AiImages.Result r = AiImages.recognize(app, jpeg, o.optString("groupId"), o.optString("groupName"),
                            o.optString("pkg"), o.optString("sender"), o.optLong("ts"), false);
                    EngineHolder.diag(app, "收到", r.group + " · 图片转写 " + r.messages + "条");
                    AiQueue.remove(app, imgs);
                } catch (java.io.FileNotFoundException gone) {
                    AiQueue.remove(app, imgs);
                } catch (AiClient.AuthException e) {
                    EngineHolder.diag(app, "出错", "AI 识别：" + e.getMessage());
                    return false;
                } catch (AiClient.BadRequestException e) {
                    EngineHolder.diag(app, "跳过", "图片识别被拒：" + e.getMessage());
                    AiQueue.remove(app, imgs);
                } catch (IOException e) {
                    AiQueue.failed(app, imgs);
                    return true;
                }
                continue;
            }
            List<JSONObject> batch = AiQueue.peek(app, "esc", BATCH);
            if (batch.isEmpty()) break;
            calls++;
            List<Engine.Escalation> items = new ArrayList<>();
            for (JSONObject o : batch) items.add(AiQueue.toEscalation(o));
            List<Engine.AiVerdict> verdicts;
            try {
                Engine.Profile profile = AiImages.onMain(() -> {
                    Engine e = EngineHolder.get(app);
                    return e == null ? null : e.getProfile();
                });
                verdicts = client.judge(items, profile, System.currentTimeMillis());
            } catch (AiClient.AuthException e) {
                EngineHolder.diag(app, "出错", "AI 复核：" + e.getMessage());
                return false;
            } catch (AiClient.BadRequestException e) {
                EngineHolder.diag(app, "跳过", "AI 复核被拒：" + e.getMessage());
                AiQueue.remove(app, batch);
                continue;
            } catch (IOException e) {
                AiQueue.failed(app, batch);
                return true;
            }
            final List<Engine.AiVerdict> vs = verdicts;
            Integer changed = AiImages.onMain(() -> {
                Engine e = EngineHolder.get(app);
                if (e == null) return 0;
                int n = 0;
                long now = System.currentTimeMillis();
                for (Engine.AiVerdict v : vs) if (e.applyAi(v, now)) n++;
                if (e.hasStateChanges()) EngineHolder.save(app);
                return n;
            });
            applied += changed == null ? 0 : changed;
            AiQueue.remove(app, batch);
        }
        if (calls > 0) EngineHolder.diag(app, "复核", "AI 复核 " + calls + " 批，改动 " + applied + " 张卡");
        return false;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
