package com.nopalou;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 截图 / 图片消息识别：图片 → 视觉模型逐条转写 → 每条当成一条群消息走引擎（规则 → 复核）。
 * 用户主动分享的截图当场复核并给出结果；通知里带的图片进队列，后台慢慢做。
 */
final class AiImages {
    static final String PKG_SCREENSHOT = "screenshot";
    static final String PKG_SHARE = "share";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Pattern HM = Pattern.compile("(?:^|\\D)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)");

    private AiImages() { }

    static final class Result {
        int messages, added, reviewed;
        String group = "";
    }

    // ---------- 图片处理 ----------

    /** 读图并压成 JPEG：长截图保留清晰度（宽 ≤1280，高 ≤4000），其余最长边 ≤2000。 */
    static byte[] loadJpeg(Context c, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("图片打不开");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("不是可识别的图片");
        int w = bounds.outWidth, h = bounds.outHeight;
        boolean tall = h > w * 1.6;
        double scale = tall ? Math.min(1.0, Math.min(1280.0 / w, 4000.0 / h)) : Math.min(1.0, 2000.0 / Math.max(w, h));
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = 1;
        while (w / (opt.inSampleSize * 2) >= w * scale && h / (opt.inSampleSize * 2) >= h * scale) opt.inSampleSize *= 2;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opt);
        }
        if (bmp == null) throw new IOException("图片解码失败");
        int tw = Math.max(1, (int) Math.round(w * scale)), th = Math.max(1, (int) Math.round(h * scale));
        if (bmp.getWidth() > tw * 1.05 || bmp.getHeight() > th * 1.05) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, tw, th, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        bmp.recycle();
        return out.toByteArray();
    }

    /** 通知里带的图片：权限只在通知存在时有效，立刻读出存到缓存，再排队。 */
    static void fromNotification(Context ctx, Uri uri, String gid, String group, String sender, String pkg, long ts) {
        if (!AiConfig.load(ctx).visionReady()) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                byte[] jpeg = loadJpeg(app, uri);
                File dir = new File(app.getCacheDir(), "ai-img");
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                File f = new File(dir, "n" + Math.abs((gid + "|" + uri + "|" + ts).hashCode()) + ".jpg");
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(jpeg); }
                AiQueue.add(app, new JSONObject()
                        .put("type", "img").put("key", "img:" + f.getName()).put("path", f.getAbsolutePath())
                        .put("groupId", gid).put("groupName", group).put("sender", sender)
                        .put("pkg", pkg).put("ts", ts));
                AiJob.schedule(app);
            } catch (Exception e) {
                EngineHolder.diag(app, "跳过", "通知图片读取失败：" + e.getClass().getSimpleName());
            }
        });
    }

    // ---------- 识别 ----------

    /**
     * 在后台线程调用：转写图片，逐条送进引擎。
     * reviewNow=true（用户主动分享）时，本次产生的复核请求当场发给模型并写回；否则照常进队列。
     */
    static Result recognize(Context ctx, byte[] jpeg, String gid, String group, String pkg,
                            String sender, long ts, boolean reviewNow) throws Exception {
        AiConfig cfg = AiConfig.load(ctx);
        if (!cfg.ready()) throw new IOException("还没有配置 AI 识别接口");
        if (!cfg.visionReady()) throw new AiClient.AuthException("没有填写图片模型，截图识别不可用");
        AiClient client = new AiClient(cfg);
        AiClient.Shot shot = client.ocr(jpeg);
        return ingest(ctx, client, shot.messages, shot.group, gid, group, pkg, sender, ts, reviewNow);
    }

    /** 分享过来的纯文本：不需要模型也能走规则；配置了模型再当场复核。 */
    static Result recognizeText(Context ctx, String text, long now) throws Exception {
        List<String[]> lines = new ArrayList<>();
        for (String part : text.split("\\n\\s*\\n")) {
            String t = part.trim();
            if (!t.isEmpty()) lines.add(new String[]{"", "", t});
        }
        AiConfig cfg = AiConfig.load(ctx);
        return ingest(ctx, cfg.ready() ? new AiClient(cfg) : null, lines, "", null, "分享的文字", PKG_SHARE, "分享", now, true);
    }

    private static Result ingest(Context ctx, AiClient client, List<String[]> lines, String shotGroup,
                                 String gid, String group, String pkg, String sender, long ts,
                                 boolean reviewNow) throws Exception {
        final Context app = ctx.getApplicationContext();
        final Result r = new Result();
        final String groupName = gid != null ? group
                : !Engine.blank(shotGroup) ? shotGroup.trim() : Engine.blank(group) ? "截图" : group;
        final String groupId = gid != null ? gid : pkg + "|" + groupName;
        final boolean userShared = gid == null;
        r.group = groupName;
        r.messages = lines.size();
        final List<Engine.Escalation> collected = new ArrayList<>();
        onMain(() -> {
            Engine eng = EngineHolder.get(app);
            if (eng == null) throw new IOException("引擎不可用");
            if (userShared && Engine.MODE_NORMAL.equals(eng.groupMode(groupId))) {
                // 用户主动分享的内容，默认按“重点关注”处理：不被“与我无关的课程群”规则挡掉
                eng.setGroupMode(groupId, groupName, pkg, Engine.MODE_FOCUS, System.currentTimeMillis());
            }
            Engine.Escalator saved = eng.escalator;
            if (reviewNow) eng.escalator = collected::add;
            int before = eng.all().size();
            try {
                for (String[] line : lines) {
                    String who = Engine.blank(line[0]) ? (Engine.blank(sender) ? "群友" : sender) : line[0];
                    long at = messageTime(line[1], ts);
                    String id = "s" + Math.abs((groupId + "|" + who + "|" + line[2]).hashCode()) + "_" + (at / 60_000L);
                    eng.process(new Engine.Msg(id, groupId, groupName, who, line[2], pkg, at));
                }
            } finally {
                eng.escalator = saved;
            }
            r.added = Math.max(0, eng.all().size() - before);
            EngineHolder.save(app);
            return null;
        });
        if (reviewNow && client != null && !collected.isEmpty()) {
            List<Engine.AiVerdict> verdicts = new ArrayList<>();
            for (int i = 0; i < collected.size(); i += 8) {
                verdicts.addAll(client.judge(collected.subList(i, Math.min(collected.size(), i + 8)),
                        onMain(() -> EngineHolder.get(app).getProfile()), System.currentTimeMillis()));
            }
            r.reviewed = verdicts.size();
            onMain(() -> {
                Engine eng = EngineHolder.get(app);
                int before = eng.all().size();
                for (Engine.AiVerdict v : verdicts) eng.applyAi(v, System.currentTimeMillis());
                r.added += eng.all().size() - before;
                EngineHolder.save(app);
                return null;
            });
        } else if (reviewNow && !collected.isEmpty() && AiConfig.ready(app)) {
            for (Engine.Escalation e : collected) AiQueue.add(app, AiQueue.fromEscalation(e));
            AiJob.schedule(app);
        }
        return r;
    }

    /** 截图里的时间原文 → 毫秒：带日期的按日期算；只有“20:05”的取不晚于现在的那一次；看不出就用兜底时间。 */
    static long messageTime(String raw, long fallback) {
        if (Engine.blank(raw)) return fallback;
        long now = fallback > 0 ? fallback : System.currentTimeMillis();
        String v = raw.trim();
        Matcher m = HM.matcher(v);
        boolean onlyClock = m.find() && v.replaceAll("[0-9:：\\s上下午晚早]", "").isEmpty();
        if (onlyClock) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            int hour = Integer.parseInt(m.group(1));
            if ((v.contains("下午") || v.contains("晚")) && hour < 12) hour += 12;
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long t = c.getTimeInMillis();
            return t > now + 60_000L ? t - 86400_000L : t;
        }
        if (v.contains("昨天")) {
            long base = now - 86400_000L;
            return m.find(0) ? messageTime(m.group(1) + ":" + m.group(2), base) : base;
        }
        Engine.Deadline d = Engine.parseDeadline(v, now);
        if (d.ok() && d.value <= now + 60_000L && d.value > now - 60L * 86400_000L) return d.value;
        return fallback;
    }

    static <T> T onMain(Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.call();
        FutureTask<T> f = new FutureTask<>(task);
        new Handler(Looper.getMainLooper()).post(f);
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
}
