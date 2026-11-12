package com.nopalou;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 研修室时段与冲突的纯逻辑回归；不联网、不读校方数据。 */
public final class Step59Test {
    private static int checks;
    private static final String DATE = "2026-11-12";

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    private static StudyRoomPlan.Room room() {
        return new StudyRoomPlan.Room(1, 3, 30, 180, 30).period(480, 1320);
    }

    private static List<StudyRoomPlan.Busy> busy(StudyRoomPlan.Busy... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static String slots(List<StudyRoomPlan.Slot> list) {
        StringBuilder text = new StringBuilder();
        for (StudyRoomPlan.Slot slot : list) {
            if (text.length() > 0) text.append(",");
            text.append(slot.label());
        }
        return text.toString();
    }

    private static StudyRoomPlan.Busy at(String start, String end) {
        return new StudyRoomPlan.Busy(StudyRoomPlan.at(DATE, start), StudyRoomPlan.at(DATE, end), "");
    }

    public static void main(String[] args) {
        // ---------- 时钟与日期 ----------
        check("clock and minutes round trip", StudyRoomPlan.minutes("09:30") == 570 && "09:30".equals(StudyRoomPlan.clock(570)));
        check("midnight stays a legal clock value", StudyRoomPlan.minutes("00:00") == 0 && "00:00".equals(StudyRoomPlan.clock(0)));
        check("24:00 is accepted as a period end", StudyRoomPlan.minutes("24:00") == 1440 && "24:00".equals(StudyRoomPlan.clock(1440)));
        check("invalid clock values are rejected", StudyRoomPlan.minutes("9:00") == -1 && StudyRoomPlan.minutes("24:30") == -1
                && StudyRoomPlan.minutes("ab:cd") == -1 && StudyRoomPlan.minutes(null) == -1);
        check("impossible calendar days are rejected", !StudyRoomPlan.validDate("2026-02-30") && !StudyRoomPlan.validDate("2026-13-01")
                && !StudyRoomPlan.validDate("2026-1-1") && StudyRoomPlan.validDate("2026-02-28"));
        check("leap day is valid", StudyRoomPlan.validDate("2028-02-29"));
        check("weekday uses China time", "周四".equals(StudyRoomPlan.weekday(DATE)) && "周一".equals(StudyRoomPlan.weekday("2026-11-16")));
        check("day arithmetic crosses months", "2026-12-01".equals(StudyRoomPlan.addDays("2026-11-30", 1))
                && "2026-10-31".equals(StudyRoomPlan.addDays("2026-11-01", -1)));
        check("relative days read naturally", "今天".equals(StudyRoomPlan.relative(DATE, StudyRoomPlan.at(DATE, 480))));
        check("relative days roll over at China midnight",
                "明天".equals(StudyRoomPlan.relative("2026-11-13", StudyRoomPlan.at(DATE, 1439))));
        check("relative label falls back to a date beyond a week",
                "11/20".equals(StudyRoomPlan.relative("2026-11-20", StudyRoomPlan.at(DATE, 480))));
        check("duration counts minutes and never goes negative",
                StudyRoomPlan.duration(StudyRoomPlan.at(DATE, "09:00"), StudyRoomPlan.at(DATE, "10:30")) == 90
                        && StudyRoomPlan.duration(StudyRoomPlan.at(DATE, "10:00"), StudyRoomPlan.at(DATE, "09:00")) == 0);
        check("invalid date yields no instant", StudyRoomPlan.at("2026-02-30", 480) == -1);
        check("school timestamps parse in China time", StudyRoomPlan.stamp("2026-11-12 09:30:00") == StudyRoomPlan.at(DATE, "09:30")
                && StudyRoomPlan.stamp("2026/11/12 09:30") == StudyRoomPlan.at(DATE, "09:30")
                && StudyRoomPlan.stamp("2026-11-12 09:30") == StudyRoomPlan.at(DATE, "09:30"));
        check("unreadable timestamps are rejected", StudyRoomPlan.stamp("unknown") == -1 && StudyRoomPlan.stamp("") == -1
                && StudyRoomPlan.stamp(null) == -1 && StudyRoomPlan.stamp("2026-11-12") == -1);

        // ---------- 可选时段 ----------
        List<StudyRoomPlan.Slot> slots = StudyRoomPlan.slots(room(), DATE, busy(), StudyRoomPlan.at(DATE, 0), 0);
        check("opening hours are split on the school grid", slots.size() == 27 && "08:00–09:00".equals(slots.get(0).label())
                && "21:00–22:00".equals(slots.get(slots.size() - 1).label()));
        check("default length is one hour but never exceeds the rule", slots.get(0).minutes() == 60);

        StudyRoomPlan.Room shortRoom = new StudyRoomPlan.Room(1, 4, 120, 240, 60).period(480, 720);
        List<StudyRoomPlan.Slot> longSlots = StudyRoomPlan.slots(shortRoom, DATE, busy(), StudyRoomPlan.at(DATE, 0), 0);
        check("rooms with a long minimum still produce legal slots", !longSlots.isEmpty()
                && longSlots.get(0).minutes() >= 120 && longSlots.get(0).minutes() % 60 == 0);

        slots = StudyRoomPlan.slots(room(), DATE, busy(), StudyRoomPlan.at(DATE, "10:00"), 0);
        check("slots that already started are dropped", "10:00–11:00".equals(slots.get(0).label()));
        slots = StudyRoomPlan.slots(room(), DATE, busy(), StudyRoomPlan.at(DATE, "10:10"), 0);
        check("a partially started interval snaps to the next grid point", "10:30–11:30".equals(slots.get(0).label()));

        slots = StudyRoomPlan.slots(room(), DATE, busy(at("09:00", "10:00")), StudyRoomPlan.at(DATE, 0), 0);
        check("occupied time is skipped without inventing a slot", "08:00–09:00".equals(slots.get(0).label())
                && "10:00–11:00".equals(slots.get(1).label()));

        slots = StudyRoomPlan.slots(room(), DATE, busy(new StudyRoomPlan.Busy(
                StudyRoomPlan.at(DATE, "10:00"), StudyRoomPlan.at(DATE, "11:00"), "占用", 15)), StudyRoomPlan.at(DATE, 0), 0);
        check("school freezing time shrinks the free window", "08:30–09:30".equals(slots.get(1).label())
                && "11:30–12:30".equals(slots.get(2).label()));

        slots = StudyRoomPlan.slots(room(), DATE, busy(), StudyRoomPlan.at(DATE, 0), 5);
        check("slot list is capped for the card layout", slots.size() == 5);
        check("incomplete rules never produce bookable slots",
                StudyRoomPlan.slots(new StudyRoomPlan.Room(1, 3, 30, 180, 0).period(480, 1320), DATE, busy(),
                        StudyRoomPlan.at(DATE, 0), 0).isEmpty());
        check("a room without opening hours has no slots",
                StudyRoomPlan.slots(new StudyRoomPlan.Room(1, 3, 30, 180, 30), DATE, busy(), StudyRoomPlan.at(DATE, 0), 0).isEmpty());
        check("periods ending at 24:00 still produce slots",
                StudyRoomPlan.slots(new StudyRoomPlan.Room(1, 2, 60, 60, 60).period(1380, 1440), DATE, busy(),
                        StudyRoomPlan.at(DATE, 0), 0).size() == 1);

        // ---------- 结束时间 ----------
        List<Integer> ends = StudyRoomPlan.ends(room(), DATE, busy(), 540);
        check("end times step on the grid up to the maximum", ends.size() == 6 && ends.get(0) == 570 && ends.get(ends.size() - 1) == 720);
        ends = StudyRoomPlan.ends(room(), DATE, busy(at("11:00", "12:00")), 540);
        check("end times stop before an occupied block", ends.get(ends.size() - 1) == 660);
        check("end times are empty outside opening hours", StudyRoomPlan.ends(room(), DATE, busy(), 420).isEmpty());
        check("inside() requires the whole span in one period",
                StudyRoomPlan.inside(room(), 540, 660) && !StudyRoomPlan.inside(room(), 540, 1330)
                        && !StudyRoomPlan.inside(room(), 470, 500));

        // ---------- 冲突 ----------
        List<StudyRoomPlan.Busy> classes = busy(
                new StudyRoomPlan.Busy(StudyRoomPlan.at(DATE, "08:00"), StudyRoomPlan.at(DATE, "09:40"), "高等数学"),
                new StudyRoomPlan.Busy(StudyRoomPlan.at(DATE, "14:00"), StudyRoomPlan.at(DATE, "15:40"), "大学物理"));
        check("class clash is detected", StudyRoomPlan.overlaps(classes,
                StudyRoomPlan.at(DATE, "09:00"), StudyRoomPlan.at(DATE, "10:00")).size() == 1);
        check("adjacent spans are not a clash", StudyRoomPlan.overlaps(classes,
                StudyRoomPlan.at(DATE, "09:40"), StudyRoomPlan.at(DATE, "10:40")).isEmpty());
        check("conflict text names the course and time", "「高等数学」08:00–09:40".equals(
                StudyRoomPlan.describe(StudyRoomPlan.overlaps(classes, StudyRoomPlan.at(DATE, "09:00"),
                        StudyRoomPlan.at(DATE, "10:00")), DATE)));
        check("empty conflict text for a free span", "".equals(
                StudyRoomPlan.describe(StudyRoomPlan.overlaps(classes, StudyRoomPlan.at(DATE, "10:00"),
                        StudyRoomPlan.at(DATE, "11:00")), DATE)));
        List<StudyRoomPlan.Busy> many = busy(
                new StudyRoomPlan.Busy(StudyRoomPlan.at(DATE, "14:00"), StudyRoomPlan.at(DATE, "15:00"), "A"),
                new StudyRoomPlan.Busy(StudyRoomPlan.at(DATE, "14:30"), StudyRoomPlan.at(DATE, "15:30"), "B"),
                new StudyRoomPlan.Busy(StudyRoomPlan.at(DATE, "15:00"), StudyRoomPlan.at(DATE, "16:00"), "C"));
        String text = StudyRoomPlan.describe(StudyRoomPlan.overlaps(many,
                StudyRoomPlan.at(DATE, "14:30"), StudyRoomPlan.at(DATE, "15:30")), DATE);
        check("more than two clashes are summarised", text.contains("A") && text.contains("B") && text.contains("等 3 项"));
        check("overlaps are sorted by start time", StudyRoomPlan.overlaps(many,
                StudyRoomPlan.at(DATE, "14:30"), StudyRoomPlan.at(DATE, "15:30")).get(0).label.equals("A"));
        check("broken intervals are ignored", StudyRoomPlan.overlaps(busy(
                new StudyRoomPlan.Busy(StudyRoomPlan.at(DATE, "10:00"), StudyRoomPlan.at(DATE, "10:00"), "空")),
                StudyRoomPlan.at(DATE, "09:00"), StudyRoomPlan.at(DATE, "11:00")).isEmpty());
        check("null busy list is tolerated", StudyRoomPlan.overlaps(null,
                StudyRoomPlan.at(DATE, "09:00"), StudyRoomPlan.at(DATE, "10:00")).isEmpty());

        System.out.println(checks + " study room plan checks passed");
    }
}
