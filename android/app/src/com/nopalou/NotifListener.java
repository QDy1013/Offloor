package com.nopalou;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 数据源：通知监听（方案书第 5 节主数据源）。
 * 用户授权后可以读到其他 App 发出的通知，不需要打开群。
 *
 * 除了新到的通知，还会"扫历史"：服务连上时、以及用户点「扫描历史」时，
 * 把通知栏里还挂着的所有通知重新过一遍，包括微信/QQ 会话通知里折叠的多条旧消息
 * （MessagingStyle 的 messages 和 historic messages）。已经处理过的消息按编号跳过，不会重复出卡。
 *
 * 限制：免打扰群不发通知；用户清掉的通知扫不到；关掉"显示消息详情"后没有正文。
 *
 * 这里只负责从通知里把字段取出来，具体怎么切分发送者和正文交给 NotifParser。
 */
public class NotifListener extends NotificationListenerService {

    /** 会话标题的键。API 28+ 才有 EXTRA_CONVERSATION_TITLE 常量，这里直接用字符串，避免版本问题。 */
    private static final String KEY_CONVERSATION_TITLE = "android.conversationTitle";

    /** MessagingStyle 多条消息数组的键。 */
    private static final String KEY_MESSAGES = "android.messages";

    /** 回放器包名和它冒充平台时用的字段：只认回放器，其他 App 写了也无效。 */
    private static final String REPLAY_PKG = "com.nopalou.replay";
    private static final String KEY_AS_PACKAGE = "nopalou.asPackage";

    /** MessagingStyle 里更早的历史消息（EXTRA_HISTORIC_MESSAGES），部分聊天软件会放在这里。 */
    private static final String KEY_HISTORIC = "android.messages.historic";

    /** 当前连上的服务实例；界面点「扫描历史」时通过它读通知栏。没连上时为 null。 */
    private static volatile NotifListener instance;

    /** 一次扫描的结果，界面据此给出反馈。 */
    public static final class ScanResult {
        /** 读到的通知条数（白名单来源）。 */
        public int notifications;
        /** 从这些通知里拿到的消息条数（含以前处理过的）。 */
        public int messages;
        /** 其中第一次处理的新消息条数。 */
        public int fresh;
        /** 扫描前后的卡片数，差值就是新增事项。 */
        public int cardsBefore, cardsAfter;
        /** 新增卡片的编号，界面用来高亮。 */
        public final List<String> newCardIds = new ArrayList<>();

        public int added() { return Math.max(0, cardsAfter - cardsBefore); }
    }

    /** 最近一次扫描结果；服务连上时的自动扫描也记在这里，界面回到前台时可以提示。 */
    public static volatile ScanResult lastScan;
    public static volatile long lastScanAt;

    /** 服务是否连上、可以扫描。 */
    public static boolean canScan() {
        return instance != null;
    }

    /**
     * 立刻扫描通知栏。必须在主线程调用（与 onNotificationPosted 同一线程，引擎不用加锁）。
     * 服务没连上返回 null。
     */
    public static ScanResult scanNow() {
        NotifListener l = instance;
        return l == null ? null : l.scanActive("手动");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 整个方法体都包在 try/catch(Throwable) 里：这里一抛异常，系统就会解绑服务，之后就再也收不到消息。
        try {
            int[] counts = new int[2];
            boolean fresh = handle(sbn, counts, false);
            if (!fresh && (sbn == null || !NotifFilter.accept(sbn.getPackageName()))) return;
            // 全部处理成功才存盘和记"收到"。
            finishBatch();
        } catch (Throwable t) {
            // 任何没被上面接住的异常都在这里落地，只记诊断日志，绝不向外抛。
            EngineHolder.diag(this, "出错", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 处理一条通知。counts[0] 累加拿到的消息条数，counts[1] 累加其中新处理的条数。
     * 返回 true 表示至少有一条新消息进了引擎（需要存盘）。
     * quiet 为 true 时（扫描）不写"跳过"类诊断日志，避免一次扫描把日志刷屏。
     */
    private boolean handle(StatusBarNotification sbn, int[] counts, boolean quiet) {
        // 空通知理论上不会出现，保护一下，避免下面取包名时空指针。
        if (sbn == null) {
            if (!quiet) EngineHolder.diag(this, "跳过", "通知对象为空");
            return false;
        }
        // 来源不在白名单时不记日志，否则别的 App 的通知会把诊断日志刷屏。
        if (!NotifFilter.accept(sbn.getPackageName())) return false;

        // 取引擎可能失败（模型没装好等），失败就跳过这条通知，不影响系统。
        Engine eng;
        try {
            eng = EngineHolder.get(this);
        } catch (Throwable e) {
            eng = null;
        }
        if (eng == null) {
            EngineHolder.diag(this, "跳过", "引擎不可用");
            return false;
        }
        Notification n = sbn.getNotification();
        if (n == null) {
            if (!quiet) EngineHolder.diag(this, "跳过", "没有通知对象");
            return false;
        }
        Bundle ex = n.extras;
        if (ex == null) {
            if (!quiet) EngineHolder.diag(this, "跳过", "没有 extras");
            return false;
        }

        // 系统生成的分组汇总通知只有"3条新消息"这类计数，不是真实消息。
        boolean isSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

        // 学习平台自己发的通知：不是群聊，不拆“发送者：正文”，整条交给平台解析器。
        // 回放器可以冒充平台（extras 里写 nopalou.asPackage），用来在真机上模拟平台通知。
        String platformPkg = sbn.getPackageName();
        if (REPLAY_PKG.equals(platformPkg)) {
            String as = ex.getString(KEY_AS_PACKAGE);
            if (as != null && Engine.PlatformNotice.isPlatform(as)) platformPkg = as;
        }
        if (Engine.PlatformNotice.isPlatform(platformPkg)) {
            return handlePlatform(eng, sbn, platformPkg, ex, isSummary, counts, quiet);
        }

        // 标题优先取会话标题：群名通常放在这里，而不是 EXTRA_TITLE。
        CharSequence titleCs = ex.getCharSequence(KEY_CONVERSATION_TITLE);
        if (titleCs == null || titleCs.toString().trim().isEmpty()) {
            titleCs = ex.getCharSequence(Notification.EXTRA_TITLE);
        }
        String title = titleCs == null ? "" : titleCs.toString().trim();

        // 正文优先取长文本（展开后的完整内容），没有再用短文本。
        CharSequence textCs = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (textCs == null) textCs = ex.getCharSequence(Notification.EXTRA_TEXT);
        String text = textCs == null ? "" : textCs.toString();

        // 多条消息（MessagingStyle）：先放历史消息，再放当前消息，保持时间从早到晚。
        String[] msgSenders = null;
        String[] msgTexts = null;
        long[] msgTimes = null;
        imageUris.clear();
        imageSenders.clear();
        imageTimes.clear();
        try {
            List<String> senders = new ArrayList<String>();
            List<String> texts = new ArrayList<String>();
            List<Long> times = new ArrayList<Long>();
            collectMessages(ex.getParcelableArray(KEY_HISTORIC), senders, texts, times);
            collectMessages(ex.getParcelableArray(KEY_MESSAGES), senders, texts, times);
            if (!texts.isEmpty()) {
                msgSenders = senders.toArray(new String[senders.size()]);
                msgTexts = texts.toArray(new String[texts.size()]);
                msgTimes = new long[times.size()];
                for (int i = 0; i < times.size(); i++) msgTimes[i] = times.get(i);
            }
        } catch (Exception e) {
            // 解析多条消息出错就当没有多条消息，继续走单条逻辑。
            msgSenders = null;
            msgTexts = null;
            msgTimes = null;
        }

        List<NotifParser.Parsed> parsed = NotifParser.parse(title, text,
                msgSenders, msgTexts, msgTimes, isSummary);

        // 群名：标题为空说明通知没给群名，用包名兜底。
        String group = title.isEmpty() ? sbn.getPackageName() : title;
        String gid = NotifFilter.groupId(sbn.getPackageName(), group);

        // 只有“[图片]”“[语音]”的消息：正文读不到，交给引擎决定要不要出“去看一下”的提示卡；
        // 通知里如果直接带了图片（MessagingStyle 的图片消息），顺手送去截图识别。
        int media = 0;
        for (NotifParser.Parsed p : NotifParser.media(title, text, msgSenders, msgTexts, msgTimes, isSummary)) {
            long sentAt = p.time > 0 ? p.time : sbn.getPostTime();
            if (sentAt <= 0) sentAt = System.currentTimeMillis();
            String id = NotifFilter.messageId("m", sbn.getPackageName(), gid, p.sender, p.body, sentAt);
            if (wasSeen(this, id)) continue;
            String placeholder = p.body.replace("[有全体消息]", "");
            if (eng.noteMedia(new Engine.Msg(id, gid, group, p.sender, p.body, sbn.getPackageName(), sentAt), placeholder)) media++;
            markSeen(this, id);
        }
        for (int i = 0; i < imageUris.size(); i++) {
            long at = imageTimes.get(i) > 0 ? imageTimes.get(i) : sbn.getPostTime();
            // 会话通知每次都带上前面的旧消息：同一张图只识别一次
            if (wasSeen(this, NotifFilter.messageId("i", sbn.getPackageName(), gid, imageSenders.get(i), imageUris.get(i).toString(), at))) continue;
            AiImages.fromNotification(this, imageUris.get(i), gid, group, imageSenders.get(i),
                    sbn.getPackageName(), at);
        }
        imageUris.clear();
        imageSenders.clear();
        imageTimes.clear();

        if (parsed == null || parsed.isEmpty()) {
            if (!quiet) {
                EngineHolder.diag(this, media > 0 ? "收到" : "跳过", media > 0 ? group + " · 图片/语音提示 " + media + "条"
                        : isSummary ? "汇总通知" : "没有可用消息（图片/表情/计数/空）");
            }
            return media > 0;
        }

        int fresh = media;
        for (NotifParser.Parsed p : parsed) {
            long sentAt = p.time > 0 ? p.time : sbn.getPostTime();
            if (sentAt <= 0) sentAt = System.currentTimeMillis();
            // 消息编号：同一群同一发送者同一正文同一时间视为同一条，避免重复出卡。
            String id = NotifFilter.messageId("n", sbn.getPackageName(), gid, p.sender, p.body, sentAt);
            counts[0]++;
            // 会话通知每次都会带上前面几条旧消息，扫描也会读到处理过的消息：处理过的直接跳过。
            if (!eng.processNotification(new Engine.Msg(id, gid, group, p.sender, p.body, sbn.getPackageName(), sentAt), false)) continue;
            fresh++;
        }
        counts[1] += fresh;
        if (fresh == 0) return false;

        EngineHolder.lastReceivedAt = System.currentTimeMillis();
        EngineHolder.lastReceivedGroup = group;
        if (!quiet) EngineHolder.diag(this, "收到", group + " · " + fresh + "条");
        return true;
    }

    /** 把 MessagingStyle 的消息数组追加到三个同长列表里；非 Bundle 元素跳过。 */
    private void collectMessages(Parcelable[] raw, List<String> senders, List<String> texts, List<Long> times) {
        if (raw == null) return;
        for (Parcelable p : raw) {
            if (!(p instanceof Bundle)) continue;
            Bundle b = (Bundle) p;
            CharSequence t = b.getCharSequence("text");
            CharSequence s = b.getCharSequence("sender");
            senders.add(s == null ? null : s.toString());
            texts.add(t == null ? null : t.toString());
            times.add(b.getLong("time"));
            // MessagingStyle 的图片消息：type 是 image/*，uri 指向图片（系统会给监听者读权限）。
            String type = b.getString("type");
            Object uri = b.get("uri");
            if (type != null && type.startsWith("image/") && uri instanceof android.net.Uri) {
                imageUris.add((android.net.Uri) uri);
                imageSenders.add(s == null ? "" : s.toString());
                imageTimes.add(b.getLong("time"));
            }
        }
    }

    /** 本条通知里带的图片，handle 用完即清空（监听回调都在主线程，不会并发）。 */
    private final List<android.net.Uri> imageUris = new ArrayList<>();
    private final List<String> imageSenders = new ArrayList<>();
    private final List<Long> imageTimes = new ArrayList<>();

    /** 一批通知处理完：落盘"最近一次收到"和卡片。 */
    private void finishBatch() {
        EngineHolder.persistLast(this);
        if (!EngineHolder.saveIfChanged(this)) {
            EngineHolder.diag(this, "出错", "卡片保存失败，等待下次通知重试");
            return; // 卡片尚未落盘时不能先提交去重记录，否则重启后无法重新扫描这条通知。
        }
        // 通知编号与卡片在同一快照中提交；备份恢复时两者一并回退。
    }

    /**
     * 学习平台通知：标题 + 正文（以及收件箱样式里的多行）逐条交给引擎。
     * 每条原文都记进诊断日志（类别“平台”），用来收集真实样本、校准解析规则；只存本机。
     */
    private boolean handlePlatform(Engine eng, StatusBarNotification sbn, String pkg, Bundle ex, boolean isSummary,
                                   int[] counts, boolean quiet) {
        String app = Engine.PlatformNotice.appName(pkg);
        if (isSummary) {
            if (!quiet) EngineHolder.diag(this, "跳过", app + " 汇总通知");
            return false;
        }
        CharSequence titleCs = ex.getCharSequence(Notification.EXTRA_TITLE);
        String title = titleCs == null ? "" : titleCs.toString().trim();
        List<String> bodies = new ArrayList<String>();
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 1) {
            for (CharSequence l : lines) {
                if (l != null && !l.toString().trim().isEmpty()) bodies.add(l.toString().trim());
            }
        }
        if (bodies.isEmpty()) {
            CharSequence textCs = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (textCs == null) textCs = ex.getCharSequence(Notification.EXTRA_TEXT);
            String text = textCs == null ? "" : textCs.toString().trim();
            if (!text.isEmpty() || !title.isEmpty()) bodies.add(text);
        }
        if (bodies.isEmpty()) {
            if (!quiet) EngineHolder.diag(this, "跳过", app + " 通知没有文字");
            return false;
        }
        int before = eng.all().size();
        int fresh = 0;
        for (int i = 0; i < bodies.size(); i++) {
            String body = bodies.get(i);
            String id = NotifFilter.messageId("p", pkg, title, app, body, sbn.getPostTime());
            counts[0]++;
            EngineHolder.diag(this, "平台", app + " · 收到文字 " + body.length() + " 字");
            long at = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
            if (!eng.processNotification(new Engine.Msg(id, NotifFilter.groupId(pkg, title), title, app, body, pkg, at), true)) continue;
            fresh++;
        }
        counts[1] += fresh;
        if (fresh == 0) return false;
        EngineHolder.lastReceivedAt = System.currentTimeMillis();
        EngineHolder.lastReceivedGroup = app;
        if (!quiet) {
            EngineHolder.diag(this, "收到", app + " · " + fresh + "条 · 卡片 " + before + "→" + eng.all().size());
        }
        return true;
    }

    /**
     * 扫描通知栏里所有还挂着的通知，按发布时间从早到晚处理（更正、撤销类消息要排在原消息之后）。
     * 任何异常都吞掉并记日志，返回已经统计到的部分结果。
     */
    ScanResult scanActive(String why) {
        ScanResult r = new ScanResult();
        Engine eng = null;
        try {
            eng = EngineHolder.get(this);
        } catch (Throwable ignored) {
        }
        java.util.Set<String> beforeIds = new java.util.HashSet<>();
        if (eng != null) {
            for (Engine.Card c : eng.all()) beforeIds.add(c.id);
            r.cardsBefore = eng.all().size();
        }
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                StatusBarNotification[] sorted = Arrays.copyOf(active, active.length);
                Arrays.sort(sorted, new Comparator<StatusBarNotification>() {
                    @Override
                    public int compare(StatusBarNotification a, StatusBarNotification b) {
                        long ta = a == null ? 0 : a.getPostTime();
                        long tb = b == null ? 0 : b.getPostTime();
                        return Long.compare(ta, tb);
                    }
                });
                int[] counts = new int[2];
                boolean changed = false;
                for (StatusBarNotification sbn : sorted) {
                    if (sbn == null || !NotifFilter.accept(sbn.getPackageName())) continue;
                    r.notifications++;
                    try {
                        changed |= handle(sbn, counts, true);
                    } catch (Throwable t) {
                        EngineHolder.diag(this, "出错", "扫描单条：" + t.getClass().getSimpleName());
                    }
                }
                r.messages = counts[0];
                r.fresh = counts[1];
                if (changed || (eng != null && eng.hasStateChanges())) finishBatch();
            }
        } catch (Throwable t) {
            EngineHolder.diag(this, "出错", "扫描通知栏：" + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        if (eng != null) {
            r.cardsAfter = eng.all().size();
            for (Engine.Card c : eng.all()) {
                if (!beforeIds.contains(c.id)) r.newCardIds.add(c.id);
            }
        }
        lastScan = r;
        lastScanAt = System.currentTimeMillis();
        EngineHolder.diag(this, "扫描", why + " · 通知 " + r.notifications + " · 消息 " + r.messages
                + "（新 " + r.fresh + "）· 新增事项 " + r.newCardIds.size());
        EngineHolder.refreshCards();
        return r;
    }

    /** 编号随引擎快照保存，不能与卡片使用不同的恢复版本。 */
    static synchronized boolean wasSeen(Context ctx, String id) {
        Engine engine = EngineHolder.get(ctx);
        return engine != null && engine.notificationSeen(id);
    }
    static synchronized boolean markSeen(Context ctx, String id) {
        Engine engine = EngineHolder.get(ctx);
        return engine != null && engine.markNotification(id);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        EngineHolder.listenerConnected = true;
        NotifRecovery.connected(this);
        EngineHolder.refreshCards();
        EngineHolder.diag(this, "连接", "通知服务已连接");
        // 连上后把通知栏里已有的消息扫一遍：App 被杀、重启、刚授权期间到达的消息都能补上。
        // 放到下一轮消息循环，不在系统回调里做重活。
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (instance == NotifListener.this) {
                    try {
                        scanActive("连接后自动");
                    } catch (Throwable t) {
                        EngineHolder.diag(NotifListener.this, "出错", "自动扫描：" + t.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // Ignore a late callback from an instance that has already been replaced.
        if (instance != null && instance != this) return;
        if (instance == this) instance = null;
        EngineHolder.listenerConnected = false;
        EngineHolder.refreshCards();
        EngineHolder.diag(this, "断开", "通知服务已断开");
        NotifRecovery.request(this, false);
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
            EngineHolder.listenerConnected = false;
            EngineHolder.refreshCards();
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 撤回/删除不作为待办来源；原文在卡片里已经留存
    }
}
