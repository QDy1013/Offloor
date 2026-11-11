package com.nopalou;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动读取自检的纯逻辑：根据权限、连接状态、电池和最近收消息时间给出问题列表。
 * 纯 Java，不依赖 android，方便在电脑上直接跑测试。
 */
public final class HealthCheck {

    /** 提醒：功能还能用，但需要用户处理一下。 */
    public static final int LEVEL_WARN = 1;

    /** 严重：现在已经收不到消息了。 */
    public static final int LEVEL_SEVERE = 2;

    /** 三天对应的毫秒数，超过它说明可能被免打扰挡住了。 */
    private static final long MUTE_LIMIT = 72L * 60L * 60L * 1000L;

    /** 工具类，不实例化。 */
    private HealthCheck() {
    }

    /** 一条自检问题。 */
    public static final class Issue {
        /** 严重程度：1 提醒，2 严重。 */
        public final int level;
        /** 给用户看的说明。 */
        public final String text;
        /** 给界面用的处理动作标记。 */
        public final String fix;

        /** 保存一条问题，字段对外只读。 */
        Issue(int level, String text, String fix) {
            this.level = level;
            this.text = text;
            this.fix = fix;
        }
    }

    /** 自检结果。 */
    public static final class Result {
        /** 所有问题里最高的等级，没有问题为 0。 */
        public final int level;
        /** 问题列表，顺序就是规则顺序，永不为 null。 */
        public final List<Issue> issues;

        /** 保存结果，字段对外只读。 */
        Result(int level, List<Issue> issues) {
            this.level = level;
            this.issues = issues;
        }

        /** 一句话总结：没有问题说正常，有问题说个数。 */
        public String summary() {
            // 没有问题时给正面结论。
            if (issues.isEmpty()) return "通知服务已连接";
            // 有问题时报出问题数量。
            return "发现 " + issues.size() + " 个问题";
        }
    }

    /**
     * 按固定顺序检查四项：
     * 1. 没有通知读取权限；2. 有权限但服务没连上；
     * 3. 电池没设成不限制；4. 连着但超过三天没收到消息。
     */
    public static Result evaluate(boolean accessEnabled, boolean connected, boolean batteryIgnored,
                                  long lastReceivedAt, long now) {
        // 问题列表按规则顺序追加，界面直接按顺序展示即可。
        List<Issue> issues = new ArrayList<Issue>();

        // 规则 1：权限没开，什么都收不到，最严重。
        if (!accessEnabled) {
            // 加入权限问题。
            issues.add(new Issue(LEVEL_SEVERE,
                    "通知读取权限没开，App 收不到群消息", "NOTIF_ACCESS"));
        }

        // 规则 2：授权不等于服务已绑定；恢复进度由界面单独显示。
        if (accessEnabled && !connected) {
            // 加入重连问题。
            issues.add(new Issue(LEVEL_SEVERE,
                    "权限已开，但系统还没有连上通知服务", "REBIND"));
        }

        // 规则 3：电池没设成不限制，后台可能被系统杀掉。
        if (!batteryIgnored) {
            // 加入电池问题。
            issues.add(new Issue(LEVEL_WARN,
                    "电池没有设成不限制，系统可能在后台把它关掉", "BATTERY"));
        }

        // 规则 4：服务连着，但超过 72 小时没收到消息，可能是群开了免打扰。
        // lastReceivedAt 为 0 表示还不知道上次时间，这种情况不报。
        if (connected && lastReceivedAt > 0L && now - lastReceivedAt > MUTE_LIMIT) {
            // 加入免打扰提醒。
            issues.add(new Issue(LEVEL_WARN,
                    "已经超过 3 天没收到群消息，请确认群没有开免打扰", "MUTE"));
        }

        // 等级取所有问题里最高的，初始为 0 表示正常。
        int level = 0;
        // 逐个比较，保留最大值。
        for (int i = 0; i < issues.size(); i++) {
            // 取出这条问题的等级。
            int l = issues.get(i).level;
            // 比当前最高还高就更新。
            if (l > level) level = l;
        }

        // 返回汇总结果，列表可以直接交给界面。
        return new Result(level, issues);
    }
}
