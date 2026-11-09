package com.nopalou;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** One confirmed semester; kept separate from message cards and their lifecycle. */
final class Timetable {
    static final int MAX_BYTES = 2_000_000;
    final JSONObject json;
    final List<Lesson> lessons = new ArrayList<>();
    final String termName;
    final TimetableSchool school;
    String firstMonday;
    int maxWeek = 1;

    static final class Lesson {
        final String name, room, teachers;
        final int weekday, start, end;
        final List<Integer> weeks = new ArrayList<>();
        Lesson(JSONObject j) throws JSONException {
            name = required(j, "name", 200);
            room = j.optString("room", "");
            teachers = j.optString("teachers", "");
            weekday = bounded(j.getInt("weekday"), 1, 7);
            start = bounded(j.getInt("start"), 1, 30);
            end = bounded(j.getInt("end"), start, 30);
            JSONArray w = j.getJSONArray("weeks");
            if (w.length() == 0 || w.length() > 60) throw new JSONException("上课周次无效");
            for (int i = 0; i < w.length(); i++) {
                int n = bounded(w.getInt(i), 1, 60);
                if (!weeks.contains(n)) weeks.add(n);
            }
            java.util.Collections.sort(weeks);
        }
        String weekText() {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < weeks.size(); i++) {
                int a = weeks.get(i), b = a;
                while (i + 1 < weeks.size() && weeks.get(i + 1) == b + 1) b = weeks.get(++i);
                if (s.length() > 0) s.append("、");
                s.append(a);
                if (b != a) s.append("–").append(b);
            }
            return s + "周";
        }
    }

    Timetable(String value) throws JSONException {
        if (value == null || value.length() > MAX_BYTES) throw new JSONException("课表数据过大或缺失");
        json = new JSONObject(value);
        school = TimetableSchool.byName(json.getString("school"));
        if (json.getInt("version") != 1 || school == null)
            throw new JSONException("课表格式不受支持");
        termName = required(json, "termName", 200);
        JSONArray array = json.getJSONArray("lessons");
        if (array.length() == 0 || array.length() > 2000) throw new JSONException("没有可保存的课程或数据过大");
        for (int i = 0; i < array.length(); i++) {
            Lesson lesson = new Lesson(array.getJSONObject(i));
            lessons.add(lesson);
            for (int week : lesson.weeks) maxWeek = Math.max(maxWeek, week);
        }
        firstMonday = monday(json.optString("firstMonday", json.optString("startDate", "")));
        JSONArray units = json.getJSONArray("units");
        if (units.length() > 30) throw new JSONException("作息表过大");
        for (int i = 0; i < units.length(); i++) {
            JSONObject u = units.getJSONObject(i);
            bounded(u.getInt("number"), 1, 30);
            String a = u.getString("start"), b = u.getString("end");
            if (!a.matches("(?:[01]\\d|2[0-3]):[0-5]\\d") || !b.matches("(?:[01]\\d|2[0-3]):[0-5]\\d") || a.compareTo(b) >= 0)
                throw new JSONException("作息时间无效");
        }
    }

    static String required(JSONObject j, String key, int max) throws JSONException {
        String s = j.getString(key).trim();
        if (s.isEmpty() || s.length() > max) throw new JSONException("课表字段缺失或过长");
        return s;
    }
    static int bounded(int n, int a, int b) throws JSONException {
        if (n < a || n > b) throw new JSONException("课表数值超出范围");
        return n;
    }
    static SimpleDateFormat format() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        return f;
    }
    static Calendar calendar(String s) {
        if (s == null || !s.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            Date d = format().parse(s);
            if (!s.equals(format().format(d))) return null;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTime(d);
            return c;
        } catch (Exception e) { return null; }
    }
    static String monday(String date) {
        Calendar c = calendar(date);
        if (c == null) return "";
        c.add(Calendar.DAY_OF_MONTH, -((c.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        return format().format(c.getTime());
    }
    int currentWeek() {
        Calendar start = calendar(firstMonday);
        if (start == null) return 1;
        // Convert today's local calendar date to UTC before subtracting, avoiding DST drift.
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        Calendar now = calendar(today);
        return (int) Math.floorDiv(now.getTimeInMillis() - start.getTimeInMillis(), 7L * 86400000L) + 1;
    }
    String dayLabel(int week, int weekday) {
        String s = "周" + "一二三四五六日".charAt(weekday - 1);
        Calendar c = calendar(firstMonday);
        if (c == null) return s;
        c.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + weekday - 1);
        return s + " · " + format().format(c.getTime()).substring(5);
    }
    String time(Lesson l) {
        String result = "第" + l.start + "–" + l.end + "节";
        JSONArray units = json.optJSONArray("units");
        String a = "", b = "";
        for (int i = 0; units != null && i < units.length(); i++) {
            JSONObject u = units.optJSONObject(i);
            if (u == null) continue;
            if (u.optInt("number") == l.start) a = u.optString("start");
            if (u.optInt("number") == l.end) b = u.optString("end");
        }
        return a.isEmpty() || b.isEmpty() ? result : result + " · " + a + "–" + b;
    }
    /** {开始, 结束} 的“HH:mm”；友谊校区按月份换作息；取不到为空串。 */
    String[] clock(Lesson l, int week) {
        if (SectionTimes.friendship(l.room)) {
            Calendar c = calendar(firstMonday);
            int month = Calendar.getInstance().get(Calendar.MONTH) + 1;
            if (c != null && week >= 1) {
                c.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + l.weekday - 1);
                month = c.get(Calendar.MONTH) + 1;
            }
            String range = SectionTimes.friendshipRange(month, l.start, l.end);
            if (range != null) return range.split("–");
        }
        String a = "", b = "";
        JSONArray units = json.optJSONArray("units");
        for (int i = 0; units != null && i < units.length(); i++) {
            JSONObject u = units.optJSONObject(i);
            if (u == null) continue;
            if (u.optInt("number") == l.start) a = u.optString("start");
            if (u.optInt("number") == l.end) b = u.optString("end");
        }
        return new String[]{a, b};
    }
    /** 同上，但友谊校区的课按那一周所在月份换成友谊校区夏/冬季作息。 */
    String time(Lesson l, int week) {
        if (!SectionTimes.friendship(l.room)) return time(l);
        Calendar c = calendar(firstMonday);
        int month;
        if (c != null && week >= 1) {
            c.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + l.weekday - 1);
            month = c.get(Calendar.MONTH) + 1;
        } else {
            month = Calendar.getInstance().get(Calendar.MONTH) + 1;
        }
        String range = SectionTimes.friendshipRange(month, l.start, l.end);
        String result = "第" + l.start + "–" + l.end + "节";
        return range == null ? time(l) : result + " · " + range + "（友谊校区" + (SectionTimes.summer(month) ? "夏季" : "冬季") + "作息）";
    }
    String encode() throws JSONException {
        if (calendar(firstMonday) == null || !firstMonday.equals(monday(firstMonday)))
            throw new JSONException("请确认第1周周一的日期");
        json.put("firstMonday", firstMonday);
        return json.toString();
    }
}
