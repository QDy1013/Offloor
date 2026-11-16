package com.nopalou;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.json.JSONArray;

/** Run with org.json on the JVM; the Android build supplies it from the platform. */
public class TimetableTest {
    static int checks;
    static void ok(boolean b, String name) { if (!b) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    static JSONObject lesson(String name, int day, int start, int end, int... weeks) throws Exception {
        JSONArray w = new JSONArray(); for (int week : weeks) w.put(week);
        return new JSONObject().put("name", name).put("weekday", day).put("start", start).put("end", end)
                .put("weeks", w).put("room", "测试教室").put("teachers", "测试教师");
    }
    public static void main(String[] args) throws Exception {
        String fixture = Files.readString(Paths.get(args[0]));
        Timetable t = new Timetable(fixture);
        ok(t.firstMonday.equals("2026-08-31"), "first week Monday");
        ok(t.maxWeek == 5, "sparse week range");
        ok(t.lessons.get(0).weekText().equals("1、3、5周"), "do not invent even weeks");
        ok(!t.lessons.get(0).weeks.contains(2), "even weeks excluded");
        ok(t.time(t.lessons.get(0)).contains("08:00–09:40"), "school period times");
        ok(Timetable.monday("2026-09-06").equals("2026-08-31"), "Sunday belongs to previous Monday");
        ok(Timetable.monday("2026-09-07").equals("2026-09-07"), "Monday preserved");
        ok(Timetable.monday("2026-02-30").isEmpty(), "invalid dates rejected");
        ok(Timetable.monday("2026-08-31suffix").isEmpty(), "date trailing content rejected");
        ok(t.dayLabel(2, 7).equals("周日 · 09-13"), "week navigation dates");
        Timetable restored = new Timetable(t.encode());
        ok(restored.lessons.size() == 1 && restored.firstMonday.equals(t.firstMonday), "storage serialization roundtrip");
        t.firstMonday = "2026-09-01";
        boolean invalid = false;
        try { t.encode(); } catch (Exception e) { invalid = true; }
        ok(invalid, "save requires a Monday");
        JSONObject json = new JSONObject(fixture);
        json.getJSONArray("lessons").getJSONObject(0).put("weekday", 0);
        invalid = false;
        try { new Timetable(json.toString()); } catch (Exception e) { invalid = true; }
        ok(invalid, "invalid stored courses cannot be loaded");
        JSONObject schedule = new JSONObject(fixture);
        JSONArray meetings = new JSONArray().put(lesson("B", 1, 3, 4, 1)).put(lesson("A", 1, 1, 2, 1, 3))
                .put(lesson("D", 2, 1, 2, 1)).put(lesson("Weekend", 6, 1, 4, 2));
        schedule.put("lessons", meetings);
        Timetable layoutTable = new Timetable(schedule.toString());
        java.util.List<TimetableLayout.Block> blocks = TimetableLayout.blocks(layoutTable, 1);
        ok(blocks.size() == 3, "adjacent periods and different days stay separate");
        ok(blocks.get(0).start == 1 && blocks.get(1).start == 3, "meetings sorted by day and period");
        ok(TimetableLayout.blocks(layoutTable, 2).size() == 1 && TimetableLayout.blocks(layoutTable, 2).get(0).weekday == 6, "weekend and week filtering");
        ok(TimetableLayout.blocks(layoutTable, 3).size() == 1, "sparse week filtering");
        ok(TimetableLayout.blocks(layoutTable, 4).isEmpty(), "empty weeks stay empty");
        meetings.put(lesson("C", 1, 2, 3, 1));
        blocks = TimetableLayout.blocks(new Timetable(schedule.toString()), 1);
        ok(blocks.size() == 2, "transitive overlaps grouped instead of painted over");
        ok(blocks.get(0).start == 1 && blocks.get(0).end == 4, "overlap group covers complete interval");
        ok(blocks.get(0).lessons.size() == 3, "all overlapping courses retained for details");
        ok(new Timetable(fixture).school.id.equals("nwpu"), "legacy NWPU storage remains compatible");
        for (String school : new String[]{"太原理工大学", "陕西师范大学", "哈尔滨工业大学（威海）"}) {
            JSONObject other = new JSONObject(fixture).put("school", school);
            ok(new Timetable(other.toString()).school.name.equals(school), "known school can retain canonical timetable");
        }
        invalid = false;
        try { new Timetable(new JSONObject(fixture).put("school", "unknown").toString()); } catch (Exception e) { invalid = true; }
        ok(invalid, "unregistered school rejected");
        ok(TimetableSchool.hostAllowed("ids.snnu.edu.cn", new String[]{"snnu.edu.cn"}), "school subdomain boundary");
        ok(!TimetableSchool.hostAllowed("snnu.edu.cn.evil.test", new String[]{"snnu.edu.cn"}), "lookalike domain rejected");
        ok(!TimetableSchool.hostAllowed("evil.utsp.sxzyckj.com", new String[]{"utsp.sxzyckj.com"}), "vendor host is exact-only");

        ok(layoutTable.distinctCourseNames().size() == 4, "distinct course names collected");
        ok(layoutTable.coursesForDay(1, 1).size() == 2, "coursesForDay collects week 1 Monday lessons");
        long week1Tue = StudyRoomPlan.at("2026-09-01", "09:00");
        java.util.List<StudyPlanner.DayCourses> weekAhead = layoutTable.weekAheadCourses(week1Tue);
        ok(weekAhead.size() == 7 && weekAhead.get(0).courses.size() == 1 && weekAhead.get(0).courses.get(0).name.equals("D"),
                "weekAheadCourses aligns term dates");
        ok(layoutTable.weekAheadCourses(StudyRoomPlan.at("2026-12-01", "09:00")).isEmpty(),
                "out-of-term weekAheadCourses stays empty");
        System.out.println(checks + " checks passed");
    }
}
