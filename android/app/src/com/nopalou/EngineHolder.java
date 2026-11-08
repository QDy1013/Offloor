package com.nopalou;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 引擎单例：界面、通知监听、读屏服务共用一个实例，状态和时间线才对得上。 */
public class EngineHolder {

    static final String TAG = "EngineHolder";
    static final String FILE_NAME = "cards.dat";

    /** 诊断日志落盘用的 SharedPreferences 文件名。 */
    static final String DIAG_PREFS = "diag";

    /** 诊断日志在 SharedPreferences 里的键。 */
    static final String DIAG_KEY = "log";

    /** 「最近一次自动收到」的时间在 diag 里的键，重启后要能读回来。 */
    static final String KEY_LAST_AT = "last_at";

    /** 「最近一次自动收到的群名」在 diag 里的键。 */
    static final String KEY_LAST_GROUP = "last_group";

    /** 是否已经从磁盘恢复过诊断日志；只在进程内第一次 loadDiag 时恢复，避免反复覆盖内存里的新日志。 */
    private static boolean diagLoaded = false;

    private static Engine engine;

    /**
     * 记一条诊断日志并落盘。
     * 先写内存的环形日志，再把整份 dump 覆盖写到 SharedPreferences（apply 异步提交）。
     * 任何异常都吞掉：诊断只是辅助，绝不能因为它让通知处理或界面崩掉。
     */
    static void diag(Context ctx, String kind, String detail) {
        try {
            // 内存日志始终要记，即使没有 ctx 也要留下痕迹。
            DiagLog.record(System.currentTimeMillis(), kind, detail);
            // 没有上下文就没法落盘，只保留内存记录。
            if (ctx == null) return;
            // 优先用 Application 上下文，避免持有 Activity 造成泄漏。
            Context app = ctx.getApplicationContext();
            // 极少数情况下拿不到 Application，就退回传进来的 ctx。
            if (app == null) app = ctx;
            // 取出诊断日志专用的 SharedPreferences。
            SharedPreferences sp = app.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE);
            // 整份 dump 覆盖写入，保证磁盘和内存一致；apply 异步提交，不阻塞通知线程。
            sp.edit().putString(DIAG_KEY, DiagLog.dump()).apply();
        } catch (Throwable t) {
            // 落盘失败、存储不可用等都直接忽略，绝不向外抛。
        }
    }

    /**
     * 进程内第一次调用时，从 SharedPreferences 恢复上次的诊断日志。
     * 之后的调用直接返回，避免把本次运行新产生的日志又用旧的覆盖回去。
     */
    static void loadDiag(Context ctx) {
        // 已经恢复过就直接结束，保证每个进程只执行一次。
        if (diagLoaded) return;
        // 先置位再干活，即使下面抛异常也不会反复重试。
        diagLoaded = true;
        try {
            // 没有上下文就没法读盘，内存日志保持空白状态。
            if (ctx == null) return;
            // 优先用 Application 上下文。
            Context app = ctx.getApplicationContext();
            // 拿不到 Application 就退回传进来的 ctx。
            if (app == null) app = ctx;
            // 打开同一个诊断日志文件。
            SharedPreferences sp = app.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE);
            // 读出上次保存的文本；没有就是 null，DiagLog.load 会按空日志处理。
            DiagLog.load(sp.getString(DIAG_KEY, null));
            // 顺便把「最近一次自动收到」的时间和群名读回来，重启后界面不用显示成空白。
            lastReceivedAt = sp.getLong(KEY_LAST_AT, 0L);
            lastReceivedGroup = sp.getString(KEY_LAST_GROUP, null);
        } catch (Throwable t) {
            // 读取或解析出错都吞掉，诊断日志缺失不影响主流程。
        }
    }

    /** 系统是否真的把通知监听服务连上了。主界面靠它显示"通知读取"状态。 */
    public static volatile boolean listenerConnected = false;

    /** 最近一次自动收到消息的时间（毫秒）；还没收到过就是 0，界面据此显示"最近一次自动收到"。 */
    public static volatile long lastReceivedAt = 0L;

    /** 最近一次自动收到消息所属的群名；还没收到过就是 null。 */
    public static volatile String lastReceivedGroup = null;

    /**
     * 把「最近一次自动收到」的时间和群名写进 diag 存档，重启后由 loadDiag 读回。
     * 用 apply 异步提交，异常全吞：这份记录只影响界面显示，不能影响通知处理。
     */
    static void persistLast(Context ctx) {
        try {
            // 没有上下文就没法落盘，内存里的值保持不动。
            if (ctx == null) return;
            // 优先用 Application 上下文，避免持有 Activity 造成泄漏。
            Context app = ctx.getApplicationContext();
            // 拿不到 Application 就退回传进来的 ctx。
            if (app == null) app = ctx;
            // 和诊断日志共用同一个 diag 文件，只是键不同。
            SharedPreferences sp = app.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE);
            // 两个键一起写，避免时间和群名对不上；apply 异步提交，不阻塞通知线程。
            sp.edit()
                    .putLong(KEY_LAST_AT, lastReceivedAt)
                    .putString(KEY_LAST_GROUP, lastReceivedGroup)
                    .apply();
        } catch (Throwable t) {
            // 落盘失败、存储不可用等都直接忽略，绝不向外抛。
        }
    }

    /** 连接状态变了，让界面重画一次；引擎或界面还没准备好就跳过，不在这里加载模型。 */
    public static void refreshCards() {
        final Engine.SelectedListener l = engine == null ? null : engine.listener;
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                l.onCardsChanged();
            }
        });
    }

    /** 第一次调用时建引擎并从 cards.dat 读回卡片；之后一直返回同一个实例。失败返回 null。 */
    public static synchronized Engine get(Context ctx) {
        if (engine != null) return engine;
        if (ctx == null) return null;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;

        // 每个进程启动后、建引擎之前先恢复上次的诊断日志，异常与跳过才有上下文可看。
        loadDiag(app);

        Engine e;
        try (InputStream model = app.getAssets().open("model.tsv")) {
            e = new Engine(model);
        } catch (Exception ex) {
            Log.w(TAG, "模型加载失败", ex);
            // 模型加载失败也要进诊断日志，界面上的自检才能说明白为什么收不到消息。
            diag(app, "出错", "模型加载失败：" + ex);
            return null;
        }

        // 档案必须先于任何消息进来之前装上，否则"跟我有关"的判断会用空档案。
        e.setProfile(ProfileStore.load(app));

        AtomicFile file = new AtomicFile(new File(app.getFilesDir(), FILE_NAME));
        try (InputStream in = file.openRead()) {
            e.loadState(in);
        } catch (FileNotFoundException firstRun) {
            // 首次启动还没有卡片存档。
        } catch (Exception ex) {
            Log.w(TAG, "读取卡片失败", ex);
        }
        // 拿不准的消息/卡片：开了 AI 识别就落盘排队，交给 AiJob 批量复核；没开就什么都不做。
        final Context appCtx = app;
        e.escalator = esc -> {
            if (!AiConfig.ready(appCtx)) return;
            try {
                AiQueue.add(appCtx, AiQueue.fromEscalation(esc));
                AiJob.schedule(appCtx);
            } catch (Exception ex) {
                diag(appCtx, "出错", "AI 复核排队失败");
            }
        };
        engine = e;
        return engine;
    }

    /** 写盘专用的单线程：界面上的完成、撤销、移除不再在主线程等磁盘。 */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "nopalou-save");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    /** 快照序号：在主线程按顺序取快照，写盘时旧快照不会覆盖新快照。取号不加锁，主线程不会等磁盘。 */
    private static final java.util.concurrent.atomic.AtomicLong snapshotSeq = new java.util.concurrent.atomic.AtomicLong();
    private static long writtenSeq;
    /** 只保护真正的文件写入。 */
    private static final Object WRITE_LOCK = new Object();
    private static final int WRITTEN = 1, SKIPPED = 0, FAILED = -1;

    /** 等后台写盘排空（最多 timeoutMs）：页面退到后台时调用，进程随后被杀也不丢刚才的操作。 */
    public static void flush(long timeoutMs) {
        try {
            WRITER.submit(() -> { }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    /**
     * 界面操作用的保存：在当前（主）线程把引擎序列化成内存快照（很快，不碰磁盘），
     * 写文件放到后台线程；成功后回主线程标记已保存并重排提醒。
     */
    public static void saveAsync(Context ctx) {
        if (engine == null || ctx == null) return;
        Context app0 = ctx.getApplicationContext();
        final Context app = app0 == null ? ctx : app0;
        final Engine e = engine;
        final long revision = e.stateRevision();
        final byte[] bytes;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(16 * 1024);
            e.saveState(buf);
            bytes = buf.toByteArray();
        } catch (Exception ex) {
            Log.w(TAG, "序列化卡片失败，改为同步保存", ex);
            save(ctx);
            return;
        }
        final long seq = snapshotSeq.incrementAndGet();
        WRITER.execute(() -> {
            int result = writeBytes(app, bytes, seq);
            new Handler(Looper.getMainLooper()).post(() -> {
                // 被更新快照跳过的旧快照不回写“已保存”，免得把已保存版本往回拨。
                if (result == WRITTEN && engine == e && revision >= markedRevision) {
                    markedRevision = revision;
                    e.markStateSaved(revision);
                }
                try {
                    ReminderReceiver.refresh(app, e);
                } catch (RuntimeException ex) {
                    Log.w(TAG, "卡片已保存，提醒调度失败", ex);
                    diag(app, "出错", "到期提醒调度失败");
                }
            });
        });
    }

    /** 写入一份快照；比它新的快照已经写过就跳过。 */
    private static int writeBytes(Context app, byte[] bytes, long seq) {
        synchronized (WRITE_LOCK) {
            if (seq <= writtenSeq) return SKIPPED;
            AtomicFile file = new AtomicFile(new File(app.getFilesDir(), FILE_NAME));
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                out.write(bytes);
                file.finishWrite(out);
                writtenSeq = seq;
                return WRITTEN;
            } catch (Exception ex) {
                if (out != null) file.failWrite(out);
                Log.w(TAG, "保存卡片失败", ex);
                return FAILED;
            }
        }
    }

    /** 主线程上最近一次标记为已保存的版本。 */
    private static long markedRevision = Long.MIN_VALUE;

    /** 写入成功后提交新存档，出错时由 AtomicFile 保留上一次存档。 */
    public static synchronized boolean save(Context ctx) {
        if (engine == null || ctx == null) return false;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;

        long revision = engine.stateRevision();
        byte[] bytes;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(16 * 1024);
            engine.saveState(buf);
            bytes = buf.toByteArray();
        } catch (Exception ex) {
            Log.w(TAG, "保存卡片失败", ex);
            return false;
        }
        long seq = snapshotSeq.incrementAndGet();
        // 与后台写盘共用序号：同步保存的是最新快照，排在它之前的后台快照会被跳过。
        if (writeBytes(app, bytes, seq) == FAILED) return false;
        if (revision >= markedRevision) markedRevision = revision;
        engine.markStateSaved(revision);
        try {
            ReminderReceiver.refresh(app, engine);
        } catch (RuntimeException ex) {
            Log.w(TAG, "卡片已保存，提醒调度失败", ex);
            diag(app, "出错", "到期提醒调度失败");
        }
        return true;
    }

    /** 自动通知只有改变卡片时才写盘。失败后的脏状态保留，下次通知会再次尝试。 */
    public static synchronized boolean saveIfChanged(Context ctx) {
        if (engine == null || ctx == null) return false;
        return !engine.hasStateChanges() || save(ctx);
    }
}
