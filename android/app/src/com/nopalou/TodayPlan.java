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
            // 放不下时最后一行改成“还有 N 节课”，总行数不超过 maxCourses
            int shown = remaining.size() <= maxCourses ? remaining.size() : Math.max(1, maxCourses - 1);
            for (int i = 0; i < shown; i++) {
                Course k = remaining.get(i);
                boolean now_ = !k.start.isEmpty() && k.start.compareTo(clock) <= 0;
                r.courses.add(new Line(now_ ? "上课中" : k.start.isEmpty() ? "第" + k.unit + "节" : k.start,
                        k.name + (k.room.isEmpty() ? "" : " · " + k.room), now_));
            }
            if (remaining.size() > shown && shown < maxCourses) r.courses.add(new Line("", "还有 " + (remaining.size() - shown) + " 节课", false));
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
        int shownTodos = list.size() <= maxTodos ? list.size() : Math.max(1, maxTodos - 1);
        for (Engine.Card k : list) {
            if (r.todos.size() >= shownTodos) break;
            String when = k.deadline == 0 ? "待确认" : k.deadline < now ? "已过期" : Engine.humanDeadline(k.deadline);
            boolean soon = k.deadline > 0 && k.deadline - now < 24 * 3600_000L;
            r.todos.add(new Line(when, ("exam".equals(k.kind) ? "考试 · " : "") + k.displayTitle(), soon));
        }
        if (list.size() > r.todos.size() && r.todos.size() < maxTodos) r.todos.add(new Line("", "还有 " + (list.size() - r.todos.size()) + " 项", false));
        if (r.todos.isEmpty()) r.todoNote = "没有待办";
        return r;
    }

    static List<Course> sort(List<Course> in) {
        List<Course> out = new ArrayList<>(in == null ? new ArrayList<Course>() : in);
        out.sort((a, b) -> a.unit != b.unit ? Integer.compare(a.unit, b.unit) : a.start.compareTo(b.start));
        return out;
    }
}
