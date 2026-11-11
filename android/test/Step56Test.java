package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** AI 补审：AI 开启前就出的待确认群消息卡，开启后要能补送复核；已复核、已完成、平台卡和时间确定的卡不送。 */
public final class Step56Test {
    static String root; static int checks; static long now = 1800000000000L;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    static Engine fresh() throws Exception { try (InputStream in = Files.newInputStream(Paths.get(root, "app/assets/model.tsv"))) { return new Engine(in); } }

    public static void main(String[] args) throws Exception {
        root = args[0];
        Engine e = fresh();
        // AI 没开：消息到达时没有 escalator，什么都不会送。
        e.process(new Engine.Msg("vague", "g", "大学物理课程群", "王老师", "下周把实验报告交到学习通", "com.tencent.mm", now));
        e.process(new Engine.Msg("sure", "g", "大学物理课程群", "王老师", "明天18点前交数据库作业到学习通", "com.tencent.mm", now + 1000));
        Engine.Card vague = null, sure = null;
        for (Engine.Card c : e.all()) {
            if ("vague".equals(c.sourceId)) vague = c;
            if ("sure".equals(c.sourceId)) sure = c;
        }
        check("时间含糊的消息出了待确认卡", vague != null && (vague.needsConfirm || vague.deadline == 0));
        check("时间明确的卡不是待确认", sure != null && !sure.needsConfirm && sure.deadline > 0);

        check("没装 escalator 时补审什么都不做", e.backfillReviews() == 0);

        List<Engine.Escalation> sent = new ArrayList<>();
        e.escalator = sent::add;
        int n = e.backfillReviews();
        final Engine.Card target = vague;
        check("开启后补送待确认卡", n == 1 && sent.size() == 1 && target.id.equals(sent.get(0).cardId));
        check("补审带原文和当前卡片快照", target.sourceText.equals(sent.get(0).msg.text)
                && Engine.aiCardState(target).equals(sent.get(0).cardState));

        sent.clear();
        vague.aiChecked = true;
        check("已复核过的卡不重复送", e.backfillReviews() == 0 && sent.isEmpty());

        vague.aiChecked = false;
        vague.done = true;
        check("已完成的卡不送", e.backfillReviews() == 0);
        vague.done = false;

        vague.deadlineUserSet = true;
        check("用户亲手改过时间的卡不送", e.backfillReviews() == 0);
        vague.deadlineUserSet = false;

        vague.platformKey = "school|x";
        check("教务系统和学习平台的卡不走 AI", e.backfillReviews() == 0);
        vague.platformKey = null;

        check("条件恢复后仍可补送", e.backfillReviews() == 1);
        System.out.println(checks);
    }
}
