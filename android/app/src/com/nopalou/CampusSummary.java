package com.nopalou;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-facing values, never a made-up zero balance or an invented departure/deadline. */
final class CampusSummary {
    final String value,note;
    CampusSummary(String value,String note) { this.value=value; this.note=note; }
    static String field(JSONObject module,String pattern) {
        JSONArray fields=module==null?null:module.optJSONArray("fields");
        for (int i=0;fields!=null && i<fields.length();i++) { JSONObject f=fields.optJSONObject(i); if (f!=null && f.optString("label").matches(".*(?:"+pattern+").*")) return f.optString("value"); }
        return "";
    }
    static CampusSummary of(String id,JSONObject module,long now) { return of(id,module,now,CampusBalance.DEFAULT_PRICE); }
    /** 电费按电价折成金额显示；其它模块不受影响。 */
    static CampusSummary of(String id,JSONObject module,long now,double price) {
        if (id.equals("network") || id.equals("card")) {
            String balance=field(module,"余额|可用金额");
            return new CampusSummary(balance.isEmpty()?"余额暂未读到":balance,id.equals("network")?field(module,"套餐|到期|剩余流量"):recent(module));
        }
        if (id.equals("electric")) {
            Double kwh=ChangeDiff.firstNumber(field(module,"剩余电量|当前电量|电量余额"));
            String note=CampusBalance.electricNote(kwh,price);
            return new CampusSummary(CampusBalance.electricValue(kwh,price),note.isEmpty()?field(module,"宿舍|房间|寝室"):note);
        }
        if (id.equals("classroom")) {
            java.util.LinkedHashSet<String> rooms=new java.util.LinkedHashSet<>(); JSONArray tables=module==null?null:module.optJSONArray("tables");
            for (int i=0;tables!=null && i<tables.length();i++) { JSONObject table=tables.optJSONObject(i); if (table==null) continue; JSONArray columns=table.optJSONArray("columns"),rows=table.optJSONArray("rows"); int at=column(columns,"教室|房间|地点");
                for (int r=0;at>=0 && rows!=null && r<rows.length();r++) { String room=rows.optJSONArray(r)==null?"":rows.optJSONArray(r).optString(at); if (!room.isEmpty()) rooms.add(room); }
            }
            String condition=field(module,"查询条件|查询日期|日期|节次");
            StringBuilder sample=new StringBuilder(); int shown=0; for (String room:rooms) { if (shown++==3) break; if (sample.length()>0) sample.append("、"); sample.append(room); }
            return new CampusSummary(rooms.isEmpty()?(module!=null && module.optString("state").equals("empty")?"当前条件暂无空闲教室":"教室结果暂未读到"):"查询到 "+rooms.size()+" 间教室",condition.isEmpty()?sample.toString():condition);
        }
        if (id.equals("bus")) return bus(module,now);
        if (id.equals("deadlines")) return deadline(module,now);
        return new CampusSummary("","");
    }
    private static String recent(JSONObject module) {
        JSONArray tables=module==null?null:module.optJSONArray("tables"); long latest=-1; String result="";
        for (int i=0;tables!=null && i<tables.length();i++) { JSONObject table=tables.optJSONObject(i); if (table==null) continue; JSONArray columns=table.optJSONArray("columns"),rows=table.optJSONArray("rows"); int money=column(columns,"金额|扣款"), date=column(columns,"日期|时间");
            for (int r=0;money>=0 && date>=0 && rows!=null && r<rows.length();r++) { JSONArray row=rows.optJSONArray(r); if (row==null) continue; long at=date(row.optString(date)); if (at>latest && at>=0) { latest=at; result="最近交易 "+row.optString(money); } }
        }
        return result;
    }
    private static int column(JSONArray cols,String regex) { for (int i=0;cols!=null && i<cols.length();i++) if (cols.optString(i).matches(".*(?:"+regex+").*")) return i; return -1; }
    private static CampusSummary deadline(JSONObject module,long now) {
        JSONArray tables=module==null?null:module.optJSONArray("tables"); String name="",label=""; long first=Long.MAX_VALUE;
        for (int i=0;tables!=null && i<tables.length();i++) { JSONObject table=tables.optJSONObject(i); if (table==null) continue; int at=column(table.optJSONArray("columns"),"截止|结束|到期"); JSONArray rows=table.optJSONArray("rows");
            for (int j=0;at>=0 && rows!=null && j<rows.length();j++) { JSONArray row=rows.optJSONArray(j); if (row==null) continue; long end=date(row.optString(at)); if (end>=now && end<first) { first=end; name=row.optString(0); label=row.optString(at); } }
        }
        if (first==Long.MAX_VALUE) return new CampusSummary(module!=null && module.optString("state").equals("empty") && !module.optBoolean("partial")?"暂无待办截止":"截止信息暂未完整读到","");
        return new CampusSummary(name,label+" 截止");
    }
    private static CampusSummary bus(JSONObject module,long now) {
        java.util.Calendar clock=java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")); clock.setTimeInMillis(now);
        int current=clock.get(java.util.Calendar.HOUR_OF_DAY)*60+clock.get(java.util.Calendar.MINUTE);
        java.util.Map<String,Integer> next=new java.util.LinkedHashMap<>();
        JSONArray tables=module==null?null:module.optJSONArray("tables"); Pattern time=Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)");
        boolean weekend=clock.get(java.util.Calendar.DAY_OF_WEEK)==java.util.Calendar.SATURDAY || clock.get(java.util.Calendar.DAY_OF_WEEK)==java.util.Calendar.SUNDAY;
        for (int i=0;tables!=null && i<tables.length();i++) { JSONObject table=tables.optJSONObject(i); if (table==null) continue; JSONArray rows=table.optJSONArray("rows"),columns=table.optJSONArray("columns"); int departure=column(columns,"发车|出发时间|发车时刻"); if (departure<0) departure=column(columns,"^时间$|^时刻$");
            for (int r=0;departure>=0 && rows!=null && r<rows.length();r++) { JSONArray row=rows.optJSONArray(r); if (row==null) continue; String all=row.toString(); if ((weekend && all.contains("工作日")) || (!weekend && all.contains("周末"))) continue;
                Matcher m=time.matcher(row.optString(departure)); while (m.find()) { int minutes=Integer.parseInt(m.group(1))*60+Integer.parseInt(m.group(2)); int place=column(columns,"线路|出发地|乘车|校区|站点"); String route=place<0?"校方时刻表":row.optString(place); if (minutes>=current && minutes<next.getOrDefault(route,24*60)) next.put(route,minutes); }
            }
        }
        java.util.List<String> labels=new java.util.ArrayList<>();
        for (java.util.Map.Entry<String,Integer> item:next.entrySet()) labels.add(item.getKey()+String.format(Locale.CHINA," %02d:%02d",item.getValue()/60,item.getValue()%60));
        return labels.isEmpty()?new CampusSummary("查看校方时刻表","当日后续班次待确认"):new CampusSummary(labels.get(0),(labels.size()>1?labels.get(1)+" · ":"")+"按"+(weekend?"周末":"工作日")+"表；节假日调整见校方通知");
    }
    static long date(String text) {
        Matcher m=Pattern.compile("(20\\d{2})[-年/.](\\d{1,2})[-月/.](\\d{1,2})日?(?:\\s*[T ]\\s*(\\d{1,2})[:：](\\d{2}))?").matcher(text);
        if (!m.find()) return -1;
        String value=String.format(Locale.ROOT,"%s-%02d-%02d %02d:%02d",m.group(1),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),m.group(4)==null?23:Integer.parseInt(m.group(4)),m.group(5)==null?59:Integer.parseInt(m.group(5)));
        try { SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT); format.setLenient(false); format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); Date result=format.parse(value); return result==null?-1:result.getTime(); } catch (Exception e) { return -1; }
    }
}
