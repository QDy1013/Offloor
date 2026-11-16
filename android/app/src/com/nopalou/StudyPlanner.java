package com.nopalou;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 跨源学习闭环的纯逻辑：
 * 1) 课程名归一化与待办关联；
 * 2) 待办/考试与课表上课时间的跨源撞课检测；
 * 3) 基于课表空档与临近待办的学习时段与空间行动推荐。
 * 只依赖 java.util 与纯 Java 模块，不碰 Android 或 org.json，JVM 可直接单测。
 */
final class StudyPlanner {
    private static final long DAY_MS = 24L * 3600_000L;
    private static final long CLASH_WINDOW_MS = 7L * DAY_MS;
    private static final int STUDY_DAY_START = 8 * 60;
    private static final int STUDY_DAY_END = 22 * 60;
    private static final int MIN_GAP_MINUTES = 60;
    private static final int MAX_SUGGEST_MINUTES = 120;

    private StudyPlanner() { }

    static final class DayCourses {
        final String date;
        final List<TodayPlan.Course> courses;

        DayCourses(String date, List<TodayPlan.Course> courses) {
            this.date = date == null ? "" : date;
            this.courses = courses == null ? Collections.emptyList() : courses;
        }
    }

    static final class LessonClash {
        final Engine.Card card;
        final TodayPlan.Course lesson;
        final String date;
        final long start, end;
        final boolean exam;

        LessonClash(Engine.Card card, TodayPlan.Course lesson, String date, long start, long end, boolean exam) {
            this.card = card;
            this.lesson = lesson;
            this.date = date;
            this.start = start;
            this.end = end;
            this.exam = exam;
        }

        String text() {
            String range = lesson.start + "–" + lesson.end;
            if (exam) {
                return "「" + card.displayTitle() + "」与《" + lesson.name + "》上课时间（" + range + "）冲突";
            }
            return "「" + card.displayTitle() + "」截止在《" + lesson.name + "》上课期间（" + range + "）";
        }
    }

    static final class GapSuggestion {
        final String date;
        final boolean isToday;
        final int startMinute, endMinute;
        final String campus;
        final Engine.Card targetCard;
        final String matchedCourse;

        GapSuggestion(String date, boolean isToday, int startMinute, int endMinute,
                      String campus, Engine.Card targetCard, String matchedCourse) {
            this.date = date;
            this.isToday = isToday;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.campus = campus == null ? "" : campus;
            this.targetCard = targetCard;
            this.matchedCourse = matchedCourse == null ? "" : matchedCourse;
        }

        String clockRange() {
            return StudyRoomPlan.clock(startMinute) + "–" + StudyRoomPlan.clock(endMinute);
        }

        String headline() {
            return "学习空档 · " + (isToday ? "今天 " : "明天 ") + clockRange() + " 无课";
        }

        String detail(long now) {
            StringBuilder sb = new StringBuilder();
            if (targetCard != null) {
                String timing = targetCard.deadline > 0 && !targetCard.needsConfirm
                        ? TodayView.countdown(targetCard.deadline, now) : "时间待确认";
                sb.append("建议优先处理「").append(targetCard.displayTitle()).append("」（").append(timing).append("）");
            } else {
                sb.append("可利用这段时间自习、复习或推进课程作业");
            }
            if (!campus.isEmpty()) sb.append(" · ").append(campus);
            return sb.toString();
        }

        String studyTheme() {
            if (!matchedCourse.isEmpty()) return matchedCourse + "复习";
            if (targetCard != null) {
                String t = targetCard.displayTitle();
                if (t != null && !t.trim().isEmpty()) {
                    t = t.trim();
                    return t.length() > 16 ? t.substring(0, 16) : t;
                }
            }
            return "课程复习";
        }
    }

    /** 归一化课程名（复用 Engine 口径，避免 Engine 反向依赖本类）。 */
    static List<String> normalizeCourseNames(Collection<String> raw) {
        return Engine.normalizeCourseNames(raw);
    }

    static String matchCourse(Engine.Card card, Collection<String> courseNames) {
        if (card == null || courseNames == null || courseNames.isEmpty()) return "";
        List<String> normalized = normalizeCourseNames(courseNames);
        normalized.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String hay = compact(card.displayTitle() + " " + nz(card.groupName) + " " + nz(card.sourceText));
        if (hay.isEmpty()) return "";
        for (String name : normalized) {
            String needle = compact(name);
            if (needle.length() >= 2 && hay.contains(needle)) return name;
        }
        return "";
    }

    static boolean sameCourse(Engine.Card card, String lessonName) {
        if (card == null || lessonName == null || lessonName.trim().isEmpty()) return false;
        return !matchCourse(card, Collections.singletonList(lessonName)).isEmpty();
    }

    /** 找出 7 天内待办/考试与课表上课时间的跨源冲突（跳过同课程随堂事项与整点“上课前交”边界）。 */
    static List<LessonClash> findLessonClashes(List<Engine.Card> cards, List<DayCourses> days, long now) {
        List<LessonClash> out = new ArrayList<>();
        if (cards == null || days == null || days.isEmpty()) return out;
        Map<String, DayCourses> byDate = new HashMap<>();
        for (DayCourses d : days) if (d != null && !d.date.isEmpty()) byDate.put(d.date, d);

        List<Engine.Card> active = new ArrayList<>();
        for (Engine.Card card : cards) {
            if (card == null || card.done || card.needsConfirm) continue;
            if (card.deadline <= now || card.deadline > now + CLASH_WINDOW_MS) continue;
            active.add(card);
        }
        active.sort((a, b) -> Long.compare(a.deadline, b.deadline));

        Set<String> seen = new LinkedHashSet<>();
        for (Engine.Card card : active) {
            String date = StudyRoomPlan.day(card.deadline);
            DayCourses day = byDate.get(date);
            if (day == null) continue;
            boolean exam = "exam".equals(card.kind);
            for (TodayPlan.Course lesson : day.courses) {
                if (lesson == null || lesson.start.isEmpty() || lesson.end.isEmpty()) continue;
                long start = StudyRoomPlan.at(date, lesson.start);
                long end = StudyRoomPlan.at(date, lesson.end);
                if (start < 0 || end <= start) continue;
                boolean hit = exam ? (card.deadline >= start && card.deadline < end)
                        : (card.deadline > start && card.deadline < end);
                if (!hit || sameCourse(card, lesson.name)) continue;
                String key = card.id + "|" + lesson.name + "|" + start;
                if (seen.add(key)) out.add(new LessonClash(card, lesson, date, start, end, exam));
            }
        }
        return out;
    }

    static LessonClash nearestLessonClash(List<Engine.Card> cards, List<DayCourses> days, long now) {
        List<LessonClash> list = findLessonClashes(cards, days, now);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 寻找今天或明天最早的可用学习空档（至少 60 分钟，建议块封顶 120 分钟），并关联最紧急待办。 */
    static GapSuggestion findStudyGap(List<DayCourses> days, List<Engine.Card> cards, long now) {
        if (days == null || days.isEmpty()) return null;
        String today = StudyRoomPlan.day(now);
        String tomorrow = StudyRoomPlan.addDays(today, 1);

        Calendar cal = Calendar.getInstance(StudyRoomPlan.CHINA);
        cal.setTimeInMillis(now);
        int nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        List<String> allCourses = new ArrayList<>();
        Map<String, DayCourses> byDate = new HashMap<>();
        for (DayCourses d : days) {
            if (d == null || d.date.isEmpty()) continue;
            byDate.put(d.date, d);
            for (TodayPlan.Course c : d.courses) if (c != null && !c.name.isEmpty()) allCourses.add(c.name);
        }

        Engine.Card target = pickTargetCard(cards, now);
        String matchedCourse = matchCourse(target, allCourses);

        String[] candidates = {today, tomorrow};
        for (int idx = 0; idx < candidates.length; idx++) {
            String date = candidates[idx];
            DayCourses day = byDate.get(date);
            if (day == null) continue;
            boolean isToday = idx == 0;
            int winStart = isToday ? Math.max(STUDY_DAY_START, ceil15(nowMinute)) : STUDY_DAY_START;
            if (STUDY_DAY_END - winStart < MIN_GAP_MINUTES) continue;

            int[] gap = firstFreeGap(day, cards, winStart, STUDY_DAY_END);
            if (gap == null) continue;
            String campus = inferCampus(day.courses, days, gap[0], gap[1]);
            return new GapSuggestion(date, isToday, gap[0], gap[1], campus, target, matchedCourse);
        }
        return null;
    }

    private static int[] firstFreeGap(DayCourses day, List<Engine.Card> cards, int winStart, int winEnd) {
        List<int[]> busy = new ArrayList<>();
        for (TodayPlan.Course c : day.courses) {
            int s = StudyRoomPlan.minutes(c.start), e = StudyRoomPlan.minutes(c.end);
            if (s >= 0 && e > s) clampAdd(busy, s, e, winStart, winEnd);
        }
        if (cards != null) {
            for (Engine.Card c : cards) {
                if (c == null || c.done || c.needsConfirm || c.deadline <= 0 || !"exam".equals(c.kind)) continue;
                if (!day.date.equals(StudyRoomPlan.day(c.deadline))) continue;
                Calendar cal = Calendar.getInstance(StudyRoomPlan.CHINA);
                cal.setTimeInMillis(c.deadline);
                int s = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                clampAdd(busy, s, Math.min(STUDY_DAY_END, s + 120), winStart, winEnd);
            }
        }
        busy.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        int cursor = winStart;
        for (int[] b : busy) {
            if (b[0] - cursor >= MIN_GAP_MINUTES) {
                return new int[]{cursor, Math.min(b[0], cursor + MAX_SUGGEST_MINUTES)};
            }
            cursor = Math.max(cursor, b[1]);
        }
        if (winEnd - cursor >= MIN_GAP_MINUTES) {
            return new int[]{cursor, Math.min(winEnd, cursor + MAX_SUGGEST_MINUTES)};
        }
        return null;
    }

    private static void clampAdd(List<int[]> out, int s, int e, int min, int max) {
        int a = Math.max(min, s), b = Math.min(max, e);
        if (b > a) out.add(new int[]{a, b});
    }

    private static Engine.Card pickTargetCard(List<Engine.Card> cards, long now) {
        if (cards == null) return null;
        Engine.Card bestTimed = null;
        Engine.Card bestConfirm = null;
        for (Engine.Card c : cards) {
            if (c == null || c.done || "media".equals(c.kind)) continue;
            if (c.deadline > now && !c.needsConfirm) {
                if (c.deadline <= now + CLASH_WINDOW_MS
                        && (bestTimed == null || c.deadline < bestTimed.deadline)) {
                    bestTimed = c;
                }
            } else if (bestConfirm == null && (c.needsConfirm || c.deadline == 0)) {
                bestConfirm = c;
            }
        }
        return bestTimed != null ? bestTimed : bestConfirm;
    }

    private static String inferCampus(List<TodayPlan.Course> dayCourses, List<DayCourses> allDays,
                                      int gapStart, int gapEnd) {
        String best = "";
        int bestDist = Integer.MAX_VALUE;
        for (TodayPlan.Course c : dayCourses) {
            String campus = campusOfRoom(c.room);
            if (campus.isEmpty()) continue;
            int s = StudyRoomPlan.minutes(c.start), e = StudyRoomPlan.minutes(c.end);
            int dist = (s < 0 || e < 0) ? 10000 : (e <= gapStart ? gapStart - e : s >= gapEnd ? s - gapEnd : 0);
            if (dist < bestDist) { bestDist = dist; best = campus; }
        }
        if (!best.isEmpty()) return best;
        for (DayCourses d : allDays) {
            for (TodayPlan.Course c : d.courses) {
                String campus = campusOfRoom(c.room);
                if (!campus.isEmpty()) return campus;
            }
        }
        return "";
    }

    static String campusOfRoom(String room) {
        if (room == null || room.trim().isEmpty()) return "";
        if (SectionTimes.friendship(room)) return "友谊校区";
        if (room.contains("长安") || room.contains("教东") || room.contains("教西")
                || room.contains("教学东") || room.contains("教学西")
                || room.contains("东阶") || room.contains("西阶")
                || room.contains("启翔") || room.contains("实验楼")) {
            return "长安校区";
        }
        return "";
    }

    private static int ceil15(int minutes) {
        return ((minutes + 14) / 15) * 15;
    }

    private static String compact(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
