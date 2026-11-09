package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/** Read only observed bus rows; group and sort without inventing departures. */
final class CampusBusSchedule {
    static final class Departure {
        final int minute;
        final String time,place;
        Departure(int minute,String place) {
            this.minute=minute; this.place=place;
            time=String.format(java.util.Locale.CHINA,"%02d:%02d",minute/60,minute%60);
        }
    }
    static final class Route {
        final String name;
        final List<Departure> departures=new ArrayList<>();
        Route(String name) { this.name=name; }
        Departure next(int minute) { for (Departure d:departures) if (d.minute>=minute) return d; return null; }
    }
    static boolean weekend(long now) {
        Calendar c=clock(now); int day=c.get(Calendar.DAY_OF_WEEK);
        return day==Calendar.SATURDAY || day==Calendar.SUNDAY;
    }
    static int minute(long now) { Calendar c=clock(now); return c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE); }
    private static Calendar clock(long now) { Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")); c.setTimeInMillis(now); return c; }
    private static int column(JSONArray columns,String pattern) {
        for (int i=0;columns!=null && i<columns.length();i++) if (columns.optString(i).matches(pattern)) return i;
        return -1;
    }
    static List<Route> routes(JSONObject module,boolean weekend) {
        Map<String,TreeMap<Integer,Departure>> grouped=new LinkedHashMap<>();
        JSONArray tables=module==null?null:module.optJSONArray("tables");
        for (int i=0;tables!=null && i<tables.length();i++) {
            JSONObject table=tables.optJSONObject(i); if (table==null) continue;
            JSONArray columns=table.optJSONArray("columns"), rows=table.optJSONArray("rows");
            int day=column(columns,"运行日|运行日期|适用日期"), route=column(columns,"线路|方向"), time=column(columns,"发车时间|发车时刻|出发时间|时间"), place=column(columns,"乘车地点|上车地点|站点");
            if (day<0 || route<0 || time<0) continue;
            for (int j=0;rows!=null && j<rows.length();j++) {
                JSONArray row=rows.optJSONArray(j); if (row==null || row.toString().contains("教师专用")) continue;
                String runDay=row.optString(day), name=row.optString(route).trim(), at=row.optString(time).trim();
                if (name.isEmpty() || !(weekend?runDay.contains("周末"):runDay.contains("工作日")) || !at.matches("(?:[01]?\\d|2[0-3]):[0-5]\\d")) continue;
                String[] parts=at.split(":"); int minute=Integer.parseInt(parts[0])*60+Integer.parseInt(parts[1]);
                grouped.computeIfAbsent(name,key -> new TreeMap<>()).putIfAbsent(minute,new Departure(minute,place<0?"":row.optString(place)));
            }
        }
        List<Route> result=new ArrayList<>();
        for (Map.Entry<String,TreeMap<Integer,Departure>> entry:grouped.entrySet()) {
            Route route=new Route(entry.getKey()); route.departures.addAll(entry.getValue().values()); result.add(route);
        }
        return result;
    }
}
