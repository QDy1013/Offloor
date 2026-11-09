package com.nopalou;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 成绩 / 考试 / 课表的变化检测。纯 Java，不依赖 android 和 org.json，电脑上可直接测。
 *
 * 设计上刻意避开同类应用里见过的两种误报：
 * 1. 每次都提示“有更新”：比较基线必须是“上一次保存的内容”，而且 key 要稳定——名字统一去首尾空白、
 *    压缩空白、全角转半角、转小写，学期等容器名不进 key；第一次导入（没有基线）不发提醒。
 * 2. 成绩没变也提醒：签名只放真正影响用户的字段（成绩、绩点、学分），不放“成绩构成”这类
 *    会随采集路径变化的说明文字，数字统一格式化。
 */
final class ChangeDiff {
    private ChangeDiff() { }

    static final class Item {
        final String key, name, signature, display;
        Item(String key, String name, String signature, String display) {
            this.key = key; this.name = name; this.signature = signature; this.display = display;
        }
    }

    static final class Change {
        static final int ADDED = 1, CHANGED = 2, REMOVED = 3;
        final int kind;
        final Item before, after;
        Change(int kind, Item before, Item after) { this.kind = kind; this.before = before; this.after = after; }
        String name() { return after != null ? after.name : before.name; }
        String display() { return after != null ? after.display : before.display; }
    }

    /** 名字归一：去首尾空白、压缩空白、全角字母数字括号转半角、转小写。 */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.trim().toCharArray()) {
            if (ch == '　') ch = ' ';
            else if (ch >= '！' && ch <= '～') ch = (char) (ch - 0xFEE0);
            b.append(ch);
        }
        return b.toString().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String num(Double d) {
        if (d == null || d.isNaN()) return "";
        return String.format(Locale.ROOT, "%.3f", d);
    }

    /** 成绩：同学期同课程一条；分数为空（未发布）也进列表，发布后签名变化即提醒。 */
    static Item grade(String semesterId, String name, String code, String score, Double gp, Double credits) {
        String key = norm(semesterId) + "|" + norm(code == null || code.trim().isEmpty() ? name : code);
        String sig = norm(score) + "|" + num(gp) + "|" + num(credits);
        String shown = (name == null ? "" : name.trim()) + (score == null || score.trim().isEmpty() ? "" : " " + score.trim());
        return new Item(key, name == null ? "" : name.trim(), sig, shown);
    }

    /** 考试：按课程；日期、时刻、地点任一变化都算改了。 */
    static Item exam(String course, String date, String start, String end, String location) {
        String sig = norm(date) + "|" + norm(start) + "|" + norm(end) + "|" + norm(location);
        String when = (date == null ? "" : date) + (start == null || start.isEmpty() ? "" : " " + start);
        String shown = (course == null ? "" : course.trim()) + (when.trim().isEmpty() ? "" : " " + when.trim());
        return new Item(norm(course), course == null ? "" : course.trim(), sig, shown);
    }

    /** 课表里的一次课（同一门课的多个时段由 courses 合并）。 */
    static final class Slot {
        final String name, room, teachers;
        final int weekday, start, end;
        final List<Integer> weeks;
        Slot(String name, int weekday, int start, int end, List<Integer> weeks, String room, String teachers) {
            this.name = name; this.weekday = weekday; this.start = start; this.end = end;
            this.weeks = weeks == null ? new ArrayList<Integer>() : weeks; this.room = room; this.teachers = teachers;
        }
        String signature() {
            List<Integer> w = new ArrayList<>(weeks);
            Collections.sort(w);
            return weekday + ":" + start + "-" + end + ":" + w + ":" + norm(room) + ":" + norm(teachers);
        }
    }

    /** 同名课程的所有时段合成一项，签名是排好序的时段串，时段顺序不影响结果。 */
    static List<Item> courses(List<Slot> slots) {
        Map<String, List<String>> sig = new TreeMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Slot s : slots) {
            String key = norm(s.name);
            if (key.isEmpty()) continue;
            List<String> list = sig.get(key);
            if (list == null) sig.put(key, list = new ArrayList<>());
            list.add(s.signature());
            if (!names.containsKey(key)) names.put(key, s.name.trim());
        }
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : sig.entrySet()) {
            Collections.sort(e.getValue());
            String name = names.get(e.getKey());
            out.add(new Item(e.getKey(), name, String.join(";", e.getValue()), name));
        }
        return out;
    }

    /**
     * 比较两份清单。before 为 null 表示第一次（没有基线）：返回空，不提醒。
     * 同一 key 出现多次时取最后一条（重修等同名记录由调用方先挑好）。
     */
    static List<Change> diff(List<Item> before, List<Item> after) {
        List<Change> out = new ArrayList<>();
        if (before == null || after == null) return out;
        Map<String, Item> a = index(before), b = index(after);
        for (Map.Entry<String, Item> e : b.entrySet()) {
            Item old = a.get(e.getKey());
            if (old == null) out.add(new Change(Change.ADDED, null, e.getValue()));
            else if (!old.signature.equals(e.getValue().signature)) out.add(new Change(Change.CHANGED, old, e.getValue()));
        }
        for (Map.Entry<String, Item> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) out.add(new Change(Change.REMOVED, e.getValue(), null));
        }
        return out;
    }

    private static Map<String, Item> index(List<Item> items) {
        Map<String, Item> m = new LinkedHashMap<>();
        for (Item i : items) if (i != null && !i.key.replace("|", "").isEmpty()) m.put(i.key, i);
        return m;
    }

    /** 只留新增和改动（成绩被撤下不打扰；课表、考试的删除另算）。 */
    static List<Change> addedOrChanged(List<Change> all) {
        List<Change> out = new ArrayList<>();
        for (Change c : all) if (c.kind != Change.REMOVED) out.add(c);
        return out;
    }

    /** “A、B 等 N 门”式的一句话；最多列 3 个。 */
    static String summary(List<Change> changes, boolean useDisplay) {
        List<String> names = new ArrayList<>();
        for (Change c : changes) {
            String n = useDisplay ? c.display() : c.name();
            if (n != null && !n.trim().isEmpty() && !names.contains(n.trim())) names.add(n.trim());
        }
        if (names.isEmpty()) return "";
        if (names.size() <= 3) return String.join("、", names);
        return String.join("、", names.subList(0, 2)) + " 等 " + names.size() + " 门";
    }

    /** 电费、余额这类字段文字里的第一个数（“剩余 12.5 度” → 12.5），没有返回 null。 */
    static Double firstNumber(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text.replace(",", ""));
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * 余额提醒去抖：只在“从不低变成低”时提醒一次；回到阈值以上后重置，再跌破会再提醒。
     * @return {应当提醒, 新的“已处于低位”状态}
     */
    static boolean[] lowAlert(Double value, double threshold, boolean wasLow) {
        if (value == null) return new boolean[]{false, wasLow};      // 没读到不改变状态
        boolean low = value < threshold;
        return new boolean[]{low && !wasLow, low};
    }
}
