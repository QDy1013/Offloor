package com.nopalou;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 桌面小组件上显示什么：今天还没上完的课 + 最近要做的待办。
 * 纯 Java（只依赖 Engine.Card），电脑上可测；安卓端 TodayWidget 只负责把结果画出来。
 */
final class TodayPlan {
    private TodayPlan() { }

    /** 一节课：时刻用“HH:mm”，取不到时刻时为空串，只按节次排序。 */
    static final class Course {
        final String name, room, start, end;
        final int unit;
        Course(String name, String room, String start, String end, int unit) {
            this.name = name == null ? "" : name.trim();
            this.room = room == null ? "" : room.trim();
            this.start = start == null ? "" : start;
            this.end = end == null ? "" : end;
            this.unit = unit;
        }
    }

    static final class Line {
        final String left, text;
        final boolean highlight;
        Line(String left, String text, boolean highlight) { this.left = left; this.text = text; this.highlight = highlight; }
    }

    static final class Result {
        String header = "";
        String courseHead = "课程", todoHead = "待办";   // 放不下时把总数写进小标题，不占一行
        String courseNote = "";            // 课程区为空时的一句话
        final List<Line> courses = new ArrayList<>();
        String todoNote = "";
        final List<Line> todos = new ArrayList<>();
    }

    /**
     * @param hasTimetable 是否导入过课表
     * @param week         今天是第几周（≤0 表示不在学期内）
     * @param today        今天的课（已按周次和星期筛好）
     * @param tomorrow     明天的课（今天没课或上完时，提示明天第一节）
     */
    static Result plan(boolean hasTimetable, int week, List<Course> today, List<Course> tomorrow,
                       List<Engine.Card> cards, long now, int maxCourses, int maxTodos) {
        Result r = new Result();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        r.header = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 周"
                + "一二三四五六日".charAt((c.get(Calendar.DAY_OF_WEEK) + 5) % 7)
                + (hasTimetable && week > 0 ? " · 第" + week + "周" : "");
        String clock = String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));

        // ---- 课程 ----
        if (!hasTimetable) {
            r.courseNote = "还没导入课表";
        } else {
            List<Course> sorted = sort(today);
            int total = sorted.size();
            List<Course> remaining = new ArrayList<>();
            for (Course k : sorted) if (k.end.isEmpty() || k.end.compareTo(clock) > 0) remaining.add(k);
            int shown = Math.min(remaining.size(), Math.max(1, maxCourses));
            for (int i = 0; i < shown; i++) {
                Course k = remaining.get(i);
                boolean now_ = !k.start.isEmpty() && k.start.compareTo(clock) <= 0;
                r.courses.add(new Line(now_ ? "上课中" : k.start.isEmpty() ? "第" + k.unit + "节" : k.start,
                        k.name + (k.room.isEmpty() ? "" : " · " + k.room), now_));
            }
            if (remaining.size() > shown) r.courseHead = "课程 · 还剩 " + remaining.size() + " 节";
            if (r.courses.isEmpty()) {
                List<Course> next = sort(tomorrow);
                String first = next.isEmpty() ? "" : "，明天 " + (next.get(0).start.isEmpty() ? "第" + next.get(0).unit + "节" : next.get(0).start)
                        + " " + next.get(0).name;
                r.courseNote = (total == 0 ? "今天没有课" : "今天的课上完了") + first;
            }
        }

        // ---- 待办：未完成、未过期太久；有时间的按时间先后，没时间的排后面 ----
        List<Engine.Card> list = new ArrayList<>();
        for (Engine.Card k : cards) {
            if (k == null || k.done || "media".equals(k.kind)) continue;
            if (k.deadline > 0 && k.deadline < now - 12 * 3600_000L) continue;
            list.add(k);
        }
        list.sort((a, b) -> {
            if ((a.deadline == 0) != (b.deadline == 0)) return a.deadline == 0 ? 1 : -1;
            if (a.deadline != b.deadline) return Long.compare(a.deadline, b.deadline);
            return Long.compare(b.sourceTs, a.sourceTs);
        });
        int shownTodos = Math.min(list.size(), Math.max(1, maxTodos));
        for (Engine.Card k : list) {
            if (r.todos.size() >= shownTodos) break;
            String when = k.deadline == 0 ? "待确认" : k.deadline < now ? "已过期" : shortWhen(k.deadline, now);
            boolean soon = k.deadline > 0 && k.deadline - now < 24 * 3600_000L;
            String title = k.displayTitle() == null ? "" : k.displayTitle();
            boolean prefix = "exam".equals(k.kind) && !title.contains("考试") && !title.contains("测验");
            r.todos.add(new Line(when, (prefix ? "考试 · " : "") + title, soon));
        }
        if (list.size() > r.todos.size()) r.todoHead = "待办 · 共 " + list.size() + " 项";
        if (r.todos.isEmpty()) r.todoNote = "没有待办";
        return r;
    }

    /**
     * 按总行数分配：课程先拿它需要的（至少 1 行，最多 rows-1），剩下的全给待办（至少 1 行）。
     * 课程区空着（今天没课 / 上完了）时只占 1 行提示，其余行都给待办。
     */
    static Result fit(boolean hasTimetable, int week, List<Course> today, List<Course> tomorrow,
                      List<Engine.Card> cards, long now, int rows, int maxCourses, int maxTodos) {
        int total = Math.max(2, rows);
        Result first = plan(hasTimetable, week, today, tomorrow, cards, now,
                Math.max(1, Math.min(maxCourses, total - 1)), 1);
        int used = Math.max(1, first.courses.size());
        return plan(hasTimetable, week, today, tomorrow, cards, now,
                Math.max(1, Math.min(maxCourses, total - 1)), Math.max(1, Math.min(maxTodos, total - used)));
    }

    /** 小组件左栏的短时间：今天/明天/本周用“周X”，再远用“11/19”。 */
    static String shortWhen(long deadline, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(deadline);
        String hm = String.format(Locale.ROOT, "%02d:%02d", b.get(Calendar.HOUR_OF_DAY), b.get(Calendar.MINUTE));
        Calendar a0 = (Calendar) a.clone(), b0 = (Calendar) b.clone();
        for (Calendar c : new Calendar[]{a0, b0}) {
            c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        }
        long days = Math.round((b0.getTimeInMillis() - a0.getTimeInMillis()) / 86400000.0);
        if (days == 0) return "今天 " + hm;
        if (days == 1) return "明天 " + hm;
        if (days >= 2 && days <= 6) return "周" + "一二三四五六日".charAt((b.get(Calendar.DAY_OF_WEEK) + 5) % 7) + " " + hm;
        return (b.get(Calendar.MONTH) + 1) + "/" + b.get(Calendar.DAY_OF_MONTH) + " " + hm;
    }

    static List<Course> sort(List<Course> in) {
        List<Course> out = new ArrayList<>(in == null ? new ArrayList<Course>() : in);
        out.sort((a, b) -> a.unit != b.unit ? Integer.compare(a.unit, b.unit) : a.start.compareTo(b.start));
        return out;
    }
}
