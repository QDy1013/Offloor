package com.nopalou;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;

/** 无头接龙名单识别、星期词误撤销防护、以及 30 天过期已完成/已移除记录自动清理回归。 */
public final class Step65Test {
    private static int checks;

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    private static Engine newEngine(String androidDir) throws Exception {
        try (InputStream in = new FileInputStream(Paths.get(androidDir, "app", "assets", "model.tsv").toFile())) {
            Engine e = new Engine(in);
            e.setProfile(Engine.Profile.demo());
            return e;
        }
    }

    public static void main(String[] args) throws Exception {
        String androidDir = args.length > 0 ? args[0] : "android";

        // 1. 无 #接龙 头的纯编号人名名单接龙，与 3 条作业清单区分
        check("headerless 3-person roster is recognized as jielong",
                Engine.isJielong("周六聚餐统计\n1. 林晓\n2. 周若曦\n3. 王五"));
        check("numbered homework checklist is never mistaken for jielong",
                !Engine.isJielong("本周作业：\n1. 完成第三章习题\n2. 提交实验报告\n3. 预习第四章"));
        check("numbered requirement steps are never mistaken for jielong",
                !Engine.isJielong("答辩注意事项\n1. 提前十分钟到场\n2. 准备三份纸质报告\n3. 核对学号信息"));

        Engine jl = newEngine(androidDir);
        long t0 = StudyRoomPlan.at("2026-10-14", "12:00");
        jl.process(new Engine.Msg("m1", "g1", "软件工程大作业群", "组长-林晓",
                "周六聚餐统计\n1. 林晓\n2. 周若曦\n3. 王五", "com.tencent.mm", t0));
        check("headerless roster creates pending jielong card",
                jl.all().size() == 1 && "jielong".equals(jl.all().get(0).kind));
        jl.process(new Engine.Msg("m2", "g1", "软件工程大作业群", "周若曦",
                "周六聚餐统计\n1. 林晓\n2. 周若曦\n3. 王五\n4. 李承宇", "com.tencent.mm", t0 + 60_000L));
        check("joining headerless roster clears jielong reminder", jl.all().isEmpty());

        // 2. 常见星期/日期词不得把无关待办误撤销
        Engine rev = newEngine(androidDir);
        rev.process(new Engine.Msg("r1", "g2", "计科2301班群", "辅导员张老师",
                "周五下班 17:30 前未交体检回执的同学交到学工办", "com.tencent.mm", t0));
        check("receipt todo created before unrelated meeting cancellation",
                rev.all().size() == 1 && "r1".equals(rev.all().get(0).sourceId));
        rev.process(new Engine.Msg("r2", "g2", "计科2301班群", "班长陈宇",
                "本周五班会取消，各自安排自习", "com.tencent.mm", t0 + 3600_000L));
        check("cancelling Friday meeting never revokes Friday receipt todo",
                rev.all().size() == 1 && "r1".equals(rev.all().get(0).sourceId));

        // 3. 30 天过期已完成事项与已移除归档自动清理
        Engine prune = newEngine(androidDir);
        long day = 24L * 60 * 60 * 1000L;
        long now = StudyRoomPlan.at("2026-11-19", "10:00");

        Engine.Card oldDone = prune.addManualCard("35天前已完成的旧作业", now - 35 * day, "", "", now - 36 * day);
        oldDone.done = true;

        Engine.Card recentDone = prune.addManualCard("老消息但昨天才点完成", now - 40 * day, "", "", now - 40 * day);
        recentDone.done = true;
        recentDone.changes.action(now - 2 * day, "本人", "标记已完成", recentDone.displayTitle());

        Engine.Card oldActive = prune.addManualCard("45天前但尚未完成的事项", now - 45 * day, "", "", now - 45 * day);

        Engine.Card oldRemoved = prune.addManualCard("40天前被移除的事项", now - 40 * day, "", "", now - 41 * day);
        prune.dismissCard(oldRemoved, now - 35 * day);

        Engine.Card recentRemoved = prune.addManualCard("5天前刚移除的事项", now - 40 * day, "", "", now - 41 * day);
        prune.dismissCard(recentRemoved, now - 5 * day);

        int cleaned = prune.pruneExpired(now, Engine.DEFAULT_RETENTION_MS);
        check("prunes exactly old done card and old removed archive", cleaned == 2);
        check("active overdue card is never auto-deleted", prune.all().contains(oldActive));
        check("recently completed card is retained within 30 days", prune.all().contains(recentDone));
        check("old completed card is removed after 30 days", !prune.all().contains(oldDone));
        check("old removed archive is pruned while recent removed stays restorable",
                !prune.removedCards().contains(oldRemoved) && prune.removedCards().contains(recentRemoved));

        System.out.println(checks + " step65 checks passed");
    }
}
