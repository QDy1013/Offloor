package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 日常使用前的回归：相似事项不误改，AI 不能用低把握结果确认时间。 */
public final class Step45Test {
    static int passed, failed, seq;
    static long now;
    static String root;
    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passed++; else failed++;
    }
    static Engine engine() throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get(root, "app/assets/model.tsv"))) {
            Engine e = new Engine(in);
            e.setProfile(Engine.Profile.demo());
            return e;
        }
    }
    static void send(Engine e, String who, String text, long at) {
        e.process(new Engine.Msg("daily-" + ++seq, "daily-group", "大学物理课程群", who, text,
                "com.tencent.mm", at));
    }
    static Engine.Card only(Engine e) {
        if (e.all().size() != 1) throw new AssertionError("铺垫应出一张卡，实际 " + e.all().size());
        return e.all().get(0);
    }
    static Engine.AiVerdict positive(Engine.Card c, long deadline) {
        Engine.AiVerdict v = new Engine.AiVerdict();
        v.cardId = c.id; v.todo = true; v.title = "提交实验报告";
        v.deadline = deadline; v.timeSure = true; v.confidence = .9;
        return v;
    }
    static Engine.AiVerdict reply(Engine.Escalation request, boolean todo) {
        Engine.AiVerdict v = new Engine.AiVerdict();
        v.cardId = request.cardId;
        v.msg = request.cardId == null ? request.msg : null;
        v.epoch = request.epoch; v.cardState = request.cardState;
        v.todo = todo; v.confidence = .95; v.title = "提交实验报告";
        v.deadline = now + 86400_000L; v.timeSure = true;
        return v;
    }
    static Engine roundTrip(Engine e) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        e.saveState(bytes);
        Engine restored = engine();
        restored.loadState(new ByteArrayInputStream(bytes.toByteArray()));
        return restored;
    }
    public static void main(String[] args) throws Exception {
        root = args[0];
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.NOVEMBER, 9, 10, 0, 0); cal.set(Calendar.MILLISECOND, 0);
        now = cal.getTimeInMillis();

        Engine ambiguous = engine();
        send(ambiguous, "王老师", "周五18点前交数据库实验报告到学习通", now);
        send(ambiguous, "王老师", "周六18点前交物理实验报告到学习通", now + 1000);
        check("铺垫两份不同实验报告", ambiguous.all().size() == 2);
        long first = ambiguous.all().get(0).deadline, second = ambiguous.all().get(1).deadline;
        send(ambiguous, "王老师", "实验报告延期到下周一22点", now + 2000);
        check("无法区分两份报告时两张都不改", ambiguous.all().get(0).deadline == first
                && ambiguous.all().get(1).deadline == second);
        check("模糊更正明确显示待核对", ambiguous.all().get(0).needsConfirm
                && ambiguous.all().get(1).needsConfirm && ambiguous.all().get(0).reason.contains("对应事项不明确"));
        send(ambiguous, "王老师", "数据库实验报告延期到下周二22点", now + 3000);
        check("说清事项后只改对应报告", ambiguous.all().get(0).updated
                && ambiguous.all().get(1).deadline == second);
        check("明确更正解除该事项的待核对", !ambiguous.all().get(0).needsConfirm
                && ambiguous.all().get(0).reason.isEmpty() && ambiguous.all().get(1).needsConfirm);

        Engine generic = engine();
        send(generic, "王老师", "周五18点前交实验报告，交到学习通。", now);
        Engine.Card homework = only(generic);
        send(generic, "王老师", "更正一下，作业截止改到下周一23:59。", now + 3600_000L);
        check("同一人唯一作业可以用泛称改期", homework.updated
                && homework.deadline == Engine.parseDeadline("下周一23:59", now).value);

        Engine genericAmbiguous = engine();
        send(genericAmbiguous, "王老师", "周五18点前交实验报告到学习通", now);
        send(genericAmbiguous, "王老师", "周六18点前交读书报告到邮箱", now + 1000);
        send(genericAmbiguous, "王老师", "更正一下，作业截止改到下周一23:59。", now + 2000);
        check("泛称不能在多份作业中猜一个", genericAmbiguous.all().size() == 2
                && !genericAmbiguous.all().get(0).updated && !genericAmbiguous.all().get(1).updated);

        Engine vague = engine();
        send(vague, "王老师", "@所有人 请大家尽快提交实验报告", now);
        Engine.Card low = only(vague);
        Engine.AiVerdict uncertain = positive(low, now + 86400_000L);
        uncertain.confidence = .1;
        vague.applyAi(uncertain, now);
        check("低把握 AI 不能填入确定时间", low.deadline == 0 && low.needsConfirm);
        check("低把握 AI 不标记已复核", !low.aiChecked);

        Engine rejected = engine();
        send(rejected, "王老师", "@所有人 请大家尽快提交实验报告", now);
        Engine.Card c = only(rejected);
        c.deadline = now + 86400_000L; c.needsConfirm = true;
        Engine.AiVerdict invalid = positive(c, now - 400L * 86400_000L);
        rejected.applyAi(invalid, now);
        check("非法 AI 时间不能把旧候选时间标为确定", c.needsConfirm);
        Engine.AiVerdict nan = positive(c, now + 2 * 86400_000L);
        nan.confidence = Double.NaN;
        long oldTime = c.deadline;
        rejected.applyAi(nan, now);
        check("NaN 把握不写回卡片", c.deadline == oldTime);

        Engine stale = engine();
        List<Engine.Escalation> sent = new ArrayList<>();
        stale.escalator = sent::add;
        send(stale, "王老师", "@所有人 请大家尽快提交实验报告", now);
        Engine.Card current = only(stale);
        check("复核请求包含本机快照", sent.size() == 1 && sent.get(0).epoch != null
                && sent.get(0).cardState != null);
        Engine.AiVerdict oldRejection = reply(sent.get(0), false);
        send(stale, "王老师", "实验报告延期到下周一22点", now + 1000);
        long revised = current.deadline;
        check("改期后拒绝旧 AI 删除结果", !stale.applyAi(oldRejection, now + 2000)
                && stale.all().contains(current) && current.deadline == revised && !current.aiChecked);

        Engine persisted = engine();
        sent.clear(); persisted.escalator = sent::add;
        send(persisted, "王老师", "@所有人 请大家尽快提交实验报告", now);
        Engine.AiVerdict fresh = reply(sent.get(0), true);
        Engine restored = roundTrip(persisted);
        check("重启后仍能接受未过期的排队复核", restored.applyAi(fresh, now)
                && only(restored).deadline == fresh.deadline);
        check("同一份复核不能重复写回", !restored.applyAi(fresh, now));

        Engine cleared = engine();
        sent.clear(); cleared.escalator = sent::add;
        Engine.Msg missed = new Engine.Msg("missed", "daily-group", "大学物理课程群", "王老师",
                "周五18点前交实验报告", "com.tencent.mm", now);
        cleared.escalateMessage(missed, "discussion", .5, "low_conf");
        Engine.AiVerdict late = reply(sent.get(0), true);
        cleared.clearLocalData();
        check("清空后图片批次也失效", !cleared.acceptsAiEpoch(late.epoch));
        check("清空后旧 AI 不能重新生成卡片", !cleared.applyAi(late, now) && cleared.all().isEmpty());
        Engine afterClearRestart = roundTrip(cleared);
        check("重启保留新的图片数据批次", afterClearRestart.aiEpoch().equals(cleared.aiEpoch()));
        check("清空并重启后旧结果仍不能复活", !afterClearRestart.applyAi(late, now)
                && afterClearRestart.all().isEmpty());

        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed != 0) System.exit(1);
    }
}
