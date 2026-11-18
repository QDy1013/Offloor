package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.TimeZone;

/** Cancellation with a venue alias requires review and temporarily pauses the original reminder. */
public final class Step64Test {
    private static final String SUBJECT = "全国高校德语能力竞赛初赛笔试";
    private static final String NOTICE = "西城大学分赛区" + SUBJECT + "因故取消，原先安排在11月20日上午。";
    private static final long SENT = StudyRoomPlan.at("2026-11-18", "09:00");
    private static final long MINUTE = 60_000;
    private static Path model;
    private static int checks;

    private static Engine fresh() throws Exception {
        try (InputStream bytes = Files.newInputStream(model)) {
            return new Engine(bytes);
        }
    }

    private static void send(Engine engine, String id, String group, String sender, String text, long at) {
        engine.setGroupMode(group, "备考群", "test", Engine.MODE_FOCUS, at);
        engine.process(new Engine.Msg(id, group, "备考群", sender, text, "test", at));
    }

    private static Engine withExam() throws Exception {
        Engine engine = fresh();
        send(engine, "exam", "a", "王老师", "西城校" + SUBJECT + "定于11月20日上午9:00举行。", SENT);
        check("fixture creates one definite upcoming exam", engine.all().size() == 1
                && "exam".equals(engine.all().get(0).kind) && !engine.all().get(0).needsConfirm);
        return engine;
    }

    private static void change(Engine engine, String text) {
        send(engine, "cancel", "a", "王老师", text, SENT + 2 * MINUTE);
    }

    private static void check(String message, boolean passed) {
        if (!passed) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        model = Path.of(args[0], "app", "assets", "model.tsv");
        Engine engine = withExam();
        Engine.Card exam = engine.all().get(0);
        long original = exam.deadline;
        long reminderTime = original - MINUTE;
        check("original confirmed exam has a due reminder",
                ReminderPlan.create(engine.all(), Collections.emptyMap(), reminderTime).due.contains(exam));
        send(engine, "other-exam", "b", "王老师", "西城校" + SUBJECT + "定于11月20日上午9:00举行。", SENT);
        Engine.Card otherGroup = engine.all().get(1);
        change(engine, NOTICE);
        check("division alias is held for review with the old time and notice intact",
                exam.needsConfirm && !exam.cancelled && exam.deadline == original
                        && exam.changes.pending().size() == 1 && NOTICE.equals(exam.changes.find("cancel").text));
        ReminderPlan pending = ReminderPlan.create(engine.all(), Collections.emptyMap(), reminderTime);
        check("pending exam stops producing a definite reminder while the other group still can",
                !pending.due.contains(exam) && pending.due.contains(otherGroup) && !otherGroup.needsConfirm);

        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        engine.saveState(saved);
        Engine restored = fresh();
        restored.loadState(new ByteArrayInputStream(saved.toByteArray()));
        Engine.Card restoredExam = restored.all().stream().filter(c -> "exam".equals(c.sourceId)).findFirst().orElseThrow();
        check("reload preserves the pending cancellation and its reminder suppression",
                restoredExam.needsConfirm && restoredExam.changes.find("cancel") != null
                        && ReminderPlan.create(restored.all(), Collections.emptyMap(), reminderTime).due.size() == 1);
        check("confirmation archives the selected exam and records the user's decision",
                restored.resolvePendingChange(restoredExam, "cancel", SENT + 3 * MINUTE)
                        && restoredExam.cancelled && restored.removedCards().contains(restoredExam)
                        && restored.all().size() == 1
                        && restoredExam.changes.history().stream().anyMatch(v -> "本人".equals(v.actor)));
        check("ignoring the notice restores the original definite reminder",
                engine.ignorePendingChange(exam, "cancel", SENT + 3 * MINUTE)
                        && !exam.needsConfirm && exam.deadline == original
                        && ReminderPlan.create(engine.all(), Collections.emptyMap(), reminderTime).due.contains(exam));

        for (String rejected : new String[]{
                NOTICE.replace("11月20日", "11月21日"),
                NOTICE.replace("上午。", "上午10点。"),
                NOTICE.replace("上午。", "下午。"),
                NOTICE.replace("因故取消，", "取消报名资格，"),
                NOTICE.replace("因故取消，", "会取消吗？"),
                NOTICE.replace("原先安排在11月20日上午。", "具体信息另行通知。"),
                NOTICE.replace("全国高校德语能力竞赛初赛笔试", "全国高校法语能力竞赛初赛笔试")
        }) {
            Engine guarded = withExam();
            change(guarded, rejected);
            check("no candidate for unsupported or mismatched notice: " + rejected,
                    guarded.all().size() == 1 && !guarded.all().get(0).needsConfirm && guarded.removedCards().isEmpty());
        }
        Engine untrusted = withExam();
        send(untrusted, "cancel", "a", "某同学", NOTICE, SENT + 2 * MINUTE);
        check("a different untrusted sender does not suspend the existing reminder", !untrusted.all().get(0).needsConfirm);

        Engine parenthetical = withExam();
        change(parenthetical, "全国高校德语能力竞赛（西城大学分赛区）初赛笔试取消，原定于11月20日上午。");
        check("a division in parentheses can be reviewed", parenthetical.all().get(0).needsConfirm);
        Engine explicitOtherDivision = fresh();
        send(explicitOtherDivision, "exam", "a", "王老师", "东城大学分赛区" + SUBJECT + "将于11月20日上午9点举行。", SENT);
        change(explicitOtherDivision, NOTICE);
        check("an explicitly different division is not a candidate",
                explicitOtherDivision.all().size() == 1 && !explicitOtherDivision.all().get(0).needsConfirm);

        Engine stale = withExam();
        send(stale, "move", "a", "王老师", "西城校" + SUBJECT + "改为11月20日上午10点。", SENT + 3 * MINUTE);
        change(stale, NOTICE);
        check("an older cancellation cannot suspend a newer revision",
                stale.all().get(0).updated && !stale.all().get(0).needsConfirm && stale.removedCards().isEmpty());

        Engine resumed = withExam();
        change(resumed, NOTICE);
        send(resumed, "move", "a", "王老师", "西城校" + SUBJECT + "改为11月20日上午10点。", SENT + MINUTE);
        check("an older exact reschedule keeps the newer pending notice and reminder suppression",
                resumed.all().get(0).updated && resumed.all().get(0).needsConfirm
                        && resumed.all().get(0).changes.find("cancel") != null);
        System.out.println("PASS " + checks);
    }
}
