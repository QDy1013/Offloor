package com.nopalou;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 学习空档只能关联开始后仍可处理的事项。 */
public final class Step62Test {
    private static int checks;

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    private static Engine.Card card(String id, String kind, String title, String day, String time,
                                    boolean needsConfirm) {
        Engine.Card c = new Engine.Card();
        c.id = id; c.kind = kind; c.title = title;
        c.deadline = time == null ? 0 : StudyRoomPlan.at(day, time);
        c.needsConfirm = needsConfirm;
        return c;
    }

    public static void main(String[] args) {
        String today = "2026-11-16", tomorrow = "2026-11-17";
        List<StudyPlanner.DayCourses> days = Arrays.asList(
                new StudyPlanner.DayCourses(today, Arrays.asList(
                        new TodayPlan.Course("操作系统", "友谊校区诚字楼201", "14:00", "15:50", 7),
                        new TodayPlan.Course("数据库系统", "友谊校区研究生西馆301", "16:00", "17:50", 9))),
                new StudyPlanner.DayCourses(tomorrow, Collections.emptyList()));
        long now = StudyRoomPlan.at(today, "11:30");
        Engine.Card noonExam = card("exam", "exam", "英语听力测试", today, "12:00", false);
        Engine.Card report = card("report", "todo", "提交实验报告", tomorrow, "22:00", false);

        StudyPlanner.GapSuggestion afterExam = StudyPlanner.findStudyGap(
                days, Collections.singletonList(noonExam), now);
        check("exam still pushes study gap to after classes",
                afterExam != null && afterExam.startMinute == 17 * 60 + 50);
        check("exam ending before the gap is not its study target", afterExam.targetCard == null);

        StudyPlanner.GapSuggestion nextTask = StudyPlanner.findStudyGap(days, Arrays.asList(noonExam, report), now);
        check("future task replaces the exam that finishes before the gap", nextTask.targetCard == report);

        Engine.Card atGapStart = card("boundary", "todo", "上课后立即提交", today, "17:50", false);
        StudyPlanner.GapSuggestion boundary = StudyPlanner.findStudyGap(
                days, Arrays.asList(noonExam, atGapStart, report), now);
        check("deadline exactly at gap start is excluded", boundary.targetCard == report);

        Engine.Card duringGap = card("during", "todo", "提交预习表", today, "18:30", false);
        StudyPlanner.GapSuggestion usable = StudyPlanner.findStudyGap(
                days, Arrays.asList(noonExam, duringGap, report), now);
        check("deadline within the gap remains eligible", usable.targetCard == duringGap);
        check("shows only the time available before a deadline inside the gap",
                usable.detail(now).contains("截止前可用 40 分钟"));
        check("does not warn when the deadline is after the gap",
                !nextTask.detail(now).contains("截止前可用"));

        Engine.Card dueTonight = card("tonight", "todo", "今晚提交", today, "22:00", false);
        long lateEvening = StudyRoomPlan.at(today, "21:20");
        StudyPlanner.GapSuggestion tomorrowGap = StudyPlanner.findStudyGap(
                days, Collections.singletonList(dueTonight), lateEvening);
        check("short evening falls through to tomorrow", tomorrowGap != null && !tomorrowGap.isToday);
        check("tonight deadline is not recommended for tomorrow", tomorrowGap.targetCard == null);

        StudyPlanner.GapSuggestion tomorrowTask = StudyPlanner.findStudyGap(
                days, Arrays.asList(dueTonight, report), lateEvening);
        check("tomorrow gap selects a still valid later task", tomorrowTask.targetCard == report);

        Engine.Card unsure = card("unsure", "todo", "确认作业时间", today, null, true);
        StudyPlanner.GapSuggestion fallback = StudyPlanner.findStudyGap(
                days, Arrays.asList(dueTonight, unsure), lateEvening);
        check("time awaiting confirmation remains a fallback", fallback.targetCard == unsure);
        StudyPlanner.GapSuggestion timedFirst = StudyPlanner.findStudyGap(
                days, Arrays.asList(unsure, report), lateEvening);
        check("valid timed target still takes priority over fallback", timedFirst.targetCard == report);

        System.out.println(checks + " checks passed");
    }
}
