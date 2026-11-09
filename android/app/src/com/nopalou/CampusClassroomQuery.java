package com.nopalou;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** A same-day query in school local time; never rolls an evening query into tomorrow. */
final class CampusClassroomQuery {
    final String campus,date;
    final int start,end;
    CampusClassroomQuery(String campus,String date,int start,int end) {
        if (!("长安校区".equals(campus) || "友谊校区".equals(campus)) || !date.matches("\\d{4}-\\d{2}-\\d{2}") || start<0 || end>1439 || start>=end) throw new IllegalArgumentException("请选择今天有效的开始与结束时间");
        this.campus=campus; this.date=date; this.start=start; this.end=end;
    }
    static String today(long now) {
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA); format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return format.format(new java.util.Date(now));
    }
    static int[] defaultRange(long now) {
        Calendar clock=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")); clock.setTimeInMillis(now);
        int start=Math.min(1438,clock.get(Calendar.HOUR_OF_DAY)*60+clock.get(Calendar.MINUTE));
        return new int[]{start,Math.min(1439,start+60)};
    }
    static String time(int minute) { return String.format(Locale.CHINA,"%02d:%02d",minute/60,minute%60); }
    String label() { return campus+" · "+date+" "+time(start)+"—"+time(end); }
    String encode() throws Exception { return new JSONObject().put("campus",campus).put("date",date).put("start",time(start)).put("end",time(end)).toString(); }
}
