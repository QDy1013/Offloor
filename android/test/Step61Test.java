package com.nopalou;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 跨源学习闭环（StudyPlanner + Engine 课表上下文注入）的纯逻辑回归；不联网、不依赖 Android。 */
public final class Step61Test {
    private static int checks;

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    private static Engine.Card card(String id, String kind, String title, String group, String text,
                                    long deadline, boolean needsConfirm, boolean done) {
        Engine.Card c = new Engine.Card();
        c.id = id;
        c.kind = kind;
        c.title = title;
        c.groupName = group;
        c.sourceText = text;
        c.deadline = deadline;
        c.needsConfirm = needsConfirm;
        c.done = done;
        return c;
    }

    private static TodayPlan.Course course(String name, String room, String start, String end, int unit) {
        return new TodayPlan.Course(name, room, start, end, unit);
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. 课程名归一化与匹配 ----------
        List<String> courses = StudyPlanner.normalizeCourseNames(
                Arrays.asList("数据库系统（Ⅰ）", "计算机网络", "计算机网络实验", " ", "A"));
        check("base name extracted for long CJK course", courses.contains("数据库系统"));
        check("short single-char name filtered out", !courses.contains("A"));
        Engine.Card netLab = card("c1", "todo", "交实验报告", "计算机网络实验 2026秋", "周五前交计算机网络实验报告",
                StudyRoomPlan.at("2026-11-18", "20:00"), false, false);
        check("longest course match wins", "计算机网络实验".equals(StudyPlanner.matchCourse(netLab, courses)));
        Engine.Card dbCard = card("c2", "todo", "提交作业", "数据库系统课程群", "明天18点前交作业",
                StudyRoomPlan.at("2026-11-18", "18:00"), false, false);
        check("normalized base name matches group", "数据库系统".equals(StudyPlanner.matchCourse(dbCard, courses)));

        // ---------- 2. 跨源撞课检测 ----------
        long now = StudyRoomPlan.at("2026-11-16", "11:30");
        List<StudyPlanner.DayCourses> days = Arrays.asList(
                new StudyPlanner.DayCourses("2026-11-16", Arrays.asList(
                        course("高等数学", "友谊校区诚字楼102", "08:00", "09:50", 1),
                        course("操作系统", "友谊校区诚字楼201", "14:00", "15:50", 7),
                        course("数据库系统", "友谊校区研究生西馆301", "16:00", "17:50", 9)
                )),
                new StudyPlanner.DayCourses("2026-11-17", Arrays.asList(
                        course("计算机网络", "教学东楼B座203", "10:10", "12:00", 3)
                ))
        );

        Engine.Card examClash = card("e1", "exam", "大学物理随堂测试", "大物群", "今天14:00测试",
                StudyRoomPlan.at("2026-11-16", "14:00"), false, false);
        Engine.Card todoDuringClass = card("t1", "todo", "提交大物预习表", "大物群", "今天14:30前交",
                StudyRoomPlan.at("2026-11-16", "14:30"), false, false);
        Engine.Card todoAtClassStart = card("t2", "todo", "交操作系统作业", "OS群", "上课前14:00交",
                StudyRoomPlan.at("2026-11-16", "14:00"), false, false);
        Engine.Card sameCourseExam = card("e2", "exam", "数据库系统阶段测验", "DB群", "今天16:00测验",
                StudyRoomPlan.at("2026-11-16", "16:00"), false, false);
        Engine.Card doneClash = card("t3", "todo", "已完成测验", "大物群", "今天14:30",
                StudyRoomPlan.at("2026-11-16", "14:30"), false, true);
        Engine.Card confirmClash = card("t4", "todo", "待确认事项", "大物群", "今天14:30",
                StudyRoomPlan.at("2026-11-16", "14:30"), true, false);

        List<StudyPlanner.LessonClash> clashes = StudyPlanner.findLessonClashes(
                Arrays.asList(examClash, todoDuringClass, todoAtClassStart, sameCourseExam, doneClash, confirmClash),
                days, now);
        check("only real cross-course active clashes are flagged", clashes.size() == 2);
        check("exam starting at lesson start is flagged", clashes.get(0).card == examClash && clashes.get(0).exam);
        check("todo strictly inside lesson is flagged", clashes.get(1).card == todoDuringClass && !clashes.get(1).exam);
        check("todo at exact lesson start does not false-alarm",
                clashes.stream().noneMatch(c -> c.card == todoAtClassStart));
        check("same-course exam during its own class is skipped",
                clashes.stream().noneMatch(c -> c.card == sameCourseExam));

        // ---------- 3. 学习空档与空间行动推荐 ----------
        Engine.Card urgentNet = card("u1", "todo", "提交计算机网络实验报告", "计算机网络群", "明天22点前提交",
                StudyRoomPlan.at("2026-11-17", "22:00"), false, false);
        StudyPlanner.GapSuggestion gap = StudyPlanner.findStudyGap(days, Collections.singletonList(urgentNet), now);
        check("finds today 11:30-13:30 gap before 14:00 class",
                gap != null && gap.isToday && gap.startMinute == 11 * 60 + 30 && gap.endMinute == 13 * 60 + 30);
        StudyPlanner.GapSuggestion subSecondGap = StudyPlanner.findStudyGap(
                days, Collections.singletonList(urgentNet), now + 5_000L);
        check("rounds past sub-minute boundary up to 11:45 so slot stays in the future",
                subSecondGap != null && subSecondGap.isToday
                        && subSecondGap.startMinute == 11 * 60 + 45 && subSecondGap.endMinute == 13 * 60 + 45);
        check("infers friendship campus from today's rooms", "友谊校区".equals(gap.campus));
        check("links urgent card and course theme",
                gap.targetCard == urgentNet && "计算机网络复习".equals(gap.studyTheme()));

        // 如果 12:00-14:00 被一场考试占住，应自动往后找晚上 17:50-19:50 空档
        Engine.Card noonExam = card("e3", "exam", "英语听力测试", "英语群", "今天12:00考试",
                StudyRoomPlan.at("2026-11-16", "12:00"), false, false);
        StudyPlanner.GapSuggestion afterExam = StudyPlanner.findStudyGap(days, Arrays.asList(urgentNet, noonExam), now);
        check("skips exam window to evening gap",
                afterExam != null && afterExam.isToday && afterExam.startMinute == 17 * 60 + 50
                        && afterExam.endMinute == 19 * 60 + 50);

        // 晚上 21:20 只剩 40 分钟，应顺延推荐明天早八空档并切到明天课表所在校区
        long lateEvening = StudyRoomPlan.at("2026-11-16", "21:20");
        StudyPlanner.GapSuggestion tomorrowGap = StudyPlanner.findStudyGap(
                days, Collections.singletonList(urgentNet), lateEvening);
        check("falls through to tomorrow 08:00-10:00 when today is nearly over",
                tomorrowGap != null && !tomorrowGap.isToday
                        && tomorrowGap.startMinute == 8 * 60 && tomorrowGap.endMinute == 10 * 60);
        check("infers changan campus for tomorrow", "长安校区".equals(tomorrowGap.campus));
        check("no timetable produces no gap",
                StudyPlanner.findStudyGap(Collections.emptyList(), Collections.singletonList(urgentNet), now) == null);

        // ---------- 4. Engine 课表课程注入（不污染个人档案） ----------
        File root = new File(args[0]);
        Engine engine = new Engine(new FileInputStream(new File(root, "app/assets/model.tsv")));
        String beforeKey = engine.profileKey();
        engine.setTimetableCourses(Collections.singletonList("编译原理（Ⅰ）"));
        check("profileKey remains untouched by runtime timetable courses", beforeKey.equals(engine.profileKey()));
        check("saved profile courses remain empty", engine.getProfile().courses.isEmpty());
        check("engine relevance picks up imported course",
                engine.relevance("周五前交编译原理作业", "编译原理课程群") == 0.8);
        check("effectiveProfile forwards imported courses to AI review",
                engine.effectiveProfile().courses.contains("编译原理"));

        System.out.println(checks + " checks passed");
    }
}
