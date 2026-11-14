package com.nopalou;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 研修室时段与冲突计算的纯逻辑：不碰 Android，也不碰 org.json，方便离线跑用例。
 *
 * 只回答三个问题：这一天还剩哪些能点的时段、选了开始时间之后结束时间能到哪、
 * 这个时段会不会撞上自己已有的预约或当天的课。校方仍然负责最终规则校验，
 * 这里算得再准也不替代提交前的那次重新读取。
 */
final class StudyRoomPlan {

    /** 校方时间都按中国标准时间，不跟随手机时区，避免跨时区算出“昨天”。 */
    static final TimeZone CHINA = TimeZone.getTimeZone("Asia/Shanghai");

    /** 一张房间卡片上最多算出这么多个可点时段；按 30 分钟间隔算，够铺满整天。 */
    static final int MAX_SLOTS = 48;

    /** 默认预约时长（分钟）；只是初始值，用户可以在面板里改。 */
    private static final int DEFAULT_MINUTES = 60;

    private StudyRoomPlan() {
    }

    // ---------- 时间换算 ----------

    /** "HH:mm" 换成一天中的分钟；24:00 记作 1440。格式不对返回 -1。 */
    static int minutes(String value) {
        if (value == null) return -1;
        String text = value.trim();
        if ("24:00".equals(text)) return 1440;
        if (!text.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) return -1;
        return Integer.parseInt(text.substring(0, 2)) * 60 + Integer.parseInt(text.substring(3, 5));
    }

    /** 分钟换成 "HH:mm"；超过一天的按 24:00 封顶，负数返回空串。 */
    static String clock(int minutes) {
        if (minutes < 0) return "";
        if (minutes >= 1440) return "24:00";
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** 日期格式与真实日历都合法才认；2 月 30 日这种不算。 */
    static boolean validDate(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(CHINA);
        format.setLenient(false);
        try {
            return date.equals(format.format(format.parse(date)));
        } catch (Exception e) {
            return false;
        }
    }

    /** 某天某个分钟数对应的毫秒；日期或分钟无效返回 -1。 */
    static long at(String date, int minutes) {
        if (!validDate(date) || minutes < 0 || minutes > 1440) return -1;
        java.util.Calendar c = java.util.Calendar.getInstance(CHINA);
        c.clear();
        c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1,
                Integer.parseInt(date.substring(8, 10)), 0, 0, 0);
        c.add(java.util.Calendar.MINUTE, minutes);
        return c.getTimeInMillis();
    }

    /** 某天某个 "HH:mm" 对应的毫秒；时间无效返回 -1。 */
    static long at(String date, String hhmm) {
        return at(date, minutes(hhmm));
    }

    /** 毫秒所在的中国日期 "yyyy-MM-dd"。 */
    static String day(long millis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(CHINA);
        return format.format(new java.util.Date(millis));
    }

    /** 校方返回的时间串（"yyyy-MM-dd HH:mm:ss" 或毫秒时间戳等）换成毫秒；认不出返回 -1。 */
    static long stamp(String value) {
        if (value == null) return -1;
        String text = value.trim().replace('/', '-').replace('T', ' ');
        if (text.matches("\\d{10,15}")) {
            try {
                long num = Long.parseLong(text);
                return text.length() == 10 ? num * 1000L : num;
            } catch (Exception ignored) {
                return -1;
            }
        }
        if (text.length() < 16 || !text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?.*")) return -1;
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss.SSS"};
        for (String pattern : patterns) {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern, Locale.ROOT);
            format.setTimeZone(CHINA);
            format.setLenient(false);
            try {
                // 只取前面对得上的那段，尾部的时区或毫秒不再纠结。
                return format.parse(text.length() > pattern.length() ? text.substring(0, pattern.length()) : text).getTime();
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    /** 日期加减天数；输入非法原样返回。 */
    static String addDays(String date, int days) {
        long base = at(date, 0);
        if (base < 0) return date == null ? "" : date;
        return day(base + days * 86400000L);
    }

    /** 星期几的中文写法，"周一" … "周日"；日期非法返回空串。 */
    static String weekday(String date) {
        long base = at(date, 0);
        if (base < 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance(CHINA);
        c.setTimeInMillis(base);
        return "周" + "日一二三四五六".charAt(c.get(java.util.Calendar.DAY_OF_WEEK) - 1);
    }

    /** 相对今天的说法：今天 / 明天 / 后天 / 周X；超过一周就写日期。 */
    static String relative(String date, long now) {
        long base = at(date, 0);
        if (base < 0) return "";
        long diff = Math.round((base - at(day(now), 0)) / 86400000.0);
        if (diff == 0) return "今天";
        if (diff == 1) return "明天";
        if (diff == 2) return "后天";
        if (diff > 2 && diff < 7) return weekday(date);
        return date.substring(5).replace('-', '/');
    }

    /** 两个时刻之间的分钟数，负数按 0 处理。 */
    static int duration(long start, long end) {
        return end <= start ? 0 : (int) Math.round((end - start) / 60000.0);
    }

    // ---------- 输入结构 ----------

    /** 房间规则。只在字段读全时才构造，缺字段的房间应当标成不可预约。 */
    static final class Room {
        final int minUser, maxUser, minMinutes, maxMinutes, interval;
        final List<int[]> periods = new ArrayList<>();

        Room(int minUser, int maxUser, int minMinutes, int maxMinutes, int interval) {
            this.minUser = minUser;
            this.maxUser = maxUser;
            this.minMinutes = minMinutes;
            this.maxMinutes = maxMinutes;
            this.interval = interval;
        }

        /** 加一个开放时段（分钟）；起止不合法就忽略。 */
        Room period(int start, int end) {
            if (start >= 0 && end > start && end <= 1440) periods.add(new int[]{start, end});
            return this;
        }

        boolean complete() {
            return interval > 0 && minMinutes > 0 && maxMinutes >= minMinutes && minUser > 0 && maxUser >= minUser;
        }
    }

    /** 已被人占用的区间（本人预约、课程、校方占用都算）。 */
    static final class Busy {
        final long start, end;
        final String label;
        /** 校方要求的间隔冻结时间（分钟），只对预约占用有意义。 */
        final int buffer;

        Busy(long start, long end, String label, int buffer) {
            this.start = start;
            this.end = end;
            this.label = label == null ? "" : label;
            this.buffer = Math.max(0, buffer);
        }

        Busy(long start, long end, String label) {
            this(start, end, label, 0);
        }
    }

    /** 一个可点的时段。 */
    static final class Slot {
        final int start, end;

        Slot(int start, int end) {
            this.start = start;
            this.end = end;
        }

        int minutes() {
            return end - start;
        }

        String label() {
            return clock(start) + "–" + clock(end);
        }
    }

    // ---------- 时段计算 ----------

    /**
     * 这一天还剩下的可点时段：按校方间隔对齐开放时间起点，跳过已开始的时间，
     * 避开占用（含校方要求的冻结间隔），每个时段默认给一小段（默认 60 分钟，夹在
     * 校方最短/最长时长之间）。用户点中之后还能在面板里把结束时间往后拖。
     */
    static List<Slot> slots(Room room, String date, List<Busy> busy, long now, int limit) {
        List<Slot> out = new ArrayList<>();
        if (room == null || !room.complete() || room.periods.isEmpty()) return out;
        long dayStart = at(date, 0);
        if (dayStart < 0) return out;
        int cap = limit <= 0 ? MAX_SLOTS : Math.min(limit, MAX_SLOTS);
        int preferred = preferredLength(room);
        int earliest = now <= dayStart ? 0 : (int) Math.ceil((now - dayStart) / 60000.0);
        for (int[] period : room.periods) {
            for (int[] free : free(room, period, busy, dayStart)) {
                // 网格以开放时段的起点为基准，和校方的间隔校验保持同一套算法。
                int from = align(free[0], period[0], room.interval, true);
                if (earliest > from) from = align(earliest, period[0], room.interval, true);
                if (from + preferred > free[1]) {
                    // 剩下的空隙不够一个默认时长：够校方最短时长才给一个缩短的时段。
                    int end = align(free[1], period[0], room.interval, false);
                    if (end - from >= room.minMinutes && bookable(end) && out.size() < cap) out.add(new Slot(from, end));
                    continue;
                }
                for (int start = from; start + preferred <= free[1] && out.size() < cap; start += room.interval) {
                    int end = start + preferred;
                    // 结束正好落在 24:00 的时段校方提交接口不收，宁可不给。
                    if (!bookable(end)) break;
                    out.add(new Slot(start, end));
                }
            }
        }
        return out;
    }

    /** 选定开始时间后，所有合法结束时间（分钟）；不在开放时段内返回空列表。 */
    static List<Integer> ends(Room room, String date, List<Busy> busy, int start) {
        List<Integer> out = new ArrayList<>();
        if (room == null || !room.complete()) return out;
        long dayStart = at(date, 0);
        if (dayStart < 0) return out;
        for (int[] period : room.periods) {
            if (start < period[0] || start >= period[1]) continue;
            for (int[] free : free(room, period, busy, dayStart)) {
                if (start < free[0] || start >= free[1]) continue;
                int last = Math.min(start + room.maxMinutes, free[1]);
                for (int end = start + room.minMinutes; end <= last; end += room.interval) {
                    if (bookable(end)) out.add(end);
                }
            }
        }
        return out;
    }

    /** 这个时段是否整段落在某个开放区间内。 */
    static boolean inside(Room room, int start, int end) {
        if (room == null || start < 0 || end <= start) return false;
        for (int[] period : room.periods) if (start >= period[0] && end <= period[1]) return true;
        return false;
    }

    /** 与已有事项重叠的条目，按开始时间排序；只要有一笔就说明撞上了。 */
    static List<Busy> overlaps(List<Busy> items, long start, long end) {
        List<Busy> out = new ArrayList<>();
        if (items == null || end <= start) return out;
        for (Busy item : items) {
            if (item == null || item.end <= item.start) continue;
            if (start < item.end && end > item.start) out.add(item);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** 重叠条目的简短说明，最多列两条，其余用“等 N 项”带过；没有重叠返回空串。 */
    static String describe(List<Busy> items, String date) {
        StringBuilder text = new StringBuilder();
        if (items == null || items.isEmpty()) return "";
        int shown = Math.min(items.size(), 2);
        for (int i = 0; i < shown; i++) {
            Busy item = items.get(i);
            if (text.length() > 0) text.append("、");
            if (!item.label.isEmpty()) text.append("「").append(item.label).append("」");
            text.append(clock(onDay(item.start, date))).append("–").append(clock(onDay(item.end, date)));
        }
        if (items.size() > shown) text.append(" 等 ").append(items.size()).append(" 项");
        return text.toString();
    }

    /** 把毫秒换成当天的分钟；跨天的一律夹到 0–1440，避免出现负时间。 */
    private static int onDay(long millis, String date) {
        long dayStart = at(date, 0);
        if (dayStart < 0) return 0;
        int value = (int) Math.round((millis - dayStart) / 60000.0);
        return Math.max(0, Math.min(1440, value));
    }

    /** 默认时长：优先 60 分钟，夹在最短与最长之间，并对齐间隔。 */
    private static int preferredLength(Room room) {
        int length = Math.max(room.minMinutes, Math.min(room.maxMinutes, DEFAULT_MINUTES));
        int aligned = (length / room.interval) * room.interval;
        if (aligned < room.minMinutes) aligned = room.minMinutes;
        return Math.min(aligned, room.maxMinutes);
    }

    /** 校方提交接口只认 00:00–23:59，结束正好是 24:00 的组合不列出来。 */
    private static boolean bookable(int end) {
        return end > 0 && end < 1440;
    }

    /** 以 base 为基准把 minute 向上取整到 interval 网格。 */
    private static int align(int minute, int base, int interval, boolean up) {
        if (interval <= 0) return minute;
        int offset = minute - base;
        int steps = Math.floorDiv(offset, interval) * interval;
        int value = base + steps;
        if (up && value < minute) value += interval;
        return value;
    }

    /** 从开放时段里扣掉占用（含冻结间隔），返回剩余的空闲片段。 */
    private static List<int[]> free(Room room, int[] period, List<Busy> busy, long dayStart) {
        List<int[]> segments = new ArrayList<>();
        segments.add(new int[]{period[0], period[1]});
        if (busy == null) return segments;
        for (Busy item : busy) {
            if (item == null || item.end <= item.start) continue;
            long from = item.start - item.buffer * 60000L;
            long to = item.end + item.buffer * 60000L;
            int start = (int) Math.floorDiv(from - dayStart, 60000L);
            int end = (int) Math.ceil((to - dayStart) / 60000.0);
            if (end <= period[0] || start >= period[1]) continue;
            List<int[]> next = new ArrayList<>();
            for (int[] segment : segments) {
                if (end <= segment[0] || start >= segment[1]) {
                    next.add(segment);
                    continue;
                }
                if (start > segment[0]) next.add(new int[]{segment[0], Math.min(start, segment[1])});
                if (end < segment[1]) next.add(new int[]{Math.max(end, segment[0]), segment[1]});
            }
            segments = next;
        }
        return segments;
    }
}
