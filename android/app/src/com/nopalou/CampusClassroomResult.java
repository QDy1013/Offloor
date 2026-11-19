package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actual returned classrooms only; an absent room is never marked occupied. */
final class CampusClassroomResult {
    private static final long CACHE_AGE_MS=5*60_000L;
    private static final Pattern CONDITION=Pattern.compile("^(长安校区|友谊校区) · (\\d{4}-\\d{2}-\\d{2}) ([0-2]\\d):([0-5]\\d)—([0-2]\\d):([0-5]\\d)$");
    private static final Pattern WING=Pattern.compile("[A-D]\\s*(?:[0-9]+)?[座区栋]");
    private static final Pattern WING_FIELD=Pattern.compile("^([A-D])(?:[0-9]+)?(?:座|区|栋)?$");
    private static final Pattern TEACHING_BUILDING=Pattern.compile("教学|教[东西]");
    private static final Pattern ROOM_CODE=Pattern.compile("^(?:教学[东西]楼|教[东西])?\\s*([A-D])(?:座|区)?\\s*[0-9]+(?:[-－—][0-9]+)?(?:$|[^A-Z0-9]).*");
    static final class Room {
        final String name,detail,location;
        Room(String name,String detail,String location) { this.name=name; this.detail=detail; this.location=location; }
    }
    static boolean current(JSONObject module,String token,String date) {
        return module!=null && token!=null && !token.isEmpty() && token.equals(module.optString("queryToken"))
                && CampusSummary.field(module,"查询条件").contains(date)
                && ("ok".equals(module.optString("state")) || "empty".equals(module.optString("state")));
    }
    static boolean recent(JSONObject module,String campus,String date,int start,int end,long now) {
        if (module==null || !"ok".equals(module.optString("state")) || start>=end) return false;
        long updated=module.optLong("updated");
        if (updated<=0 || updated>now || now-updated>CACHE_AGE_MS) return false;
        Matcher match=CONDITION.matcher(CampusSummary.field(module,"查询条件"));
        if (!match.matches() || !campus.equals(match.group(1)) || !date.equals(match.group(2))) return false;
        int cachedStart=Integer.parseInt(match.group(3))*60+Integer.parseInt(match.group(4));
        int cachedEnd=Integer.parseInt(match.group(5))*60+Integer.parseInt(match.group(6));
        if (cachedStart>=cachedEnd || cachedEnd>=1440 || cachedStart>=end || cachedEnd<=start) return false;
        return cachedStart<=start && cachedEnd>=end;
    }
    static List<String> availableLocations(String campus,JSONObject module,List<Room> rooms) {
        Map<String,String> choices=new LinkedHashMap<>();
        String[] known="长安校区".equals(campus)
                ? new String[]{"教学东楼A座","教学东楼B座","教学东楼C座","教学东楼D座","教学西楼A座","教学西楼B座","教学西楼C座","教学西楼D座"}
                : new String[]{"诚字楼","研究生西馆"};
        for (String place:known) choices.put(locationKey(place),place);
        boolean sameCampus=CampusSummary.field(module,"查询条件").startsWith(campus+" · ");
        for (Room room:rooms) if (!room.location.isEmpty() && (sameCampus || room.detail.contains("校区："+campus))) {
            choices.putIfAbsent(locationKey(room.location),room.location);
        }
        return new ArrayList<>(choices.values());
    }
    static List<String> locations(List<Room> rooms) {
        java.util.Set<String> seen=new java.util.LinkedHashSet<>();
        for (Room room:rooms) if (!room.location.isEmpty()) seen.add(room.location);
        List<String> result=new ArrayList<>(seen);
        result.sort(java.text.Collator.getInstance(java.util.Locale.CHINA));
        return result;
    }
    static List<Room> atLocation(List<Room> rooms,String location) {
        List<Room> result=new ArrayList<>();
        for (Room room:rooms) if (location.isEmpty() || location.equals(room.location)) result.add(room);
        return result;
    }
    static List<Room> atLocations(List<Room> rooms,java.util.Collection<String> locations) {
        if (locations==null || locations.isEmpty()) return new ArrayList<>();
        java.util.Set<String> selected=new java.util.HashSet<>();
        for (String location:locations) selected.add(locationKey(location));
        List<Room> result=new ArrayList<>();
        for (Room room:rooms) if (selected.contains(locationKey(room.location))) result.add(room);
        return result;
    }
    private static String locationKey(String value) { return normalized(value).replace(" · ",""); }
    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).trim().toUpperCase(java.util.Locale.ROOT);
    }
    private static String location(String building,String wing,String room) {
        if (building.isEmpty()) return "";
        String name=normalized(building);
        // Explicit building names already containing a wing need no inferred suffix.
        if (WING.matcher(name).find()) return building;
        String letter="";
        Matcher explicit=WING_FIELD.matcher(normalized(wing));
        if (explicit.matches()) letter=explicit.group(1);
        // School teaching-room codes such as B1-302 identify B座. Gym codes do not.
        if (letter.isEmpty() && TEACHING_BUILDING.matcher(name).find()) {
            Matcher code=ROOM_CODE.matcher(normalized(room));
            if (code.matches()) letter=code.group(1);
        }
        return building+(letter.isEmpty()?"":" · "+letter+"座");
    }
    static List<Room> rooms(JSONObject module) {
        Map<String,Room> result=new LinkedHashMap<>();
        JSONArray tables=module==null?null:module.optJSONArray("tables");
        for (int i=0;tables!=null && i<tables.length();i++) {
            JSONObject table=tables.optJSONObject(i); if (table==null) continue;
            JSONArray columns=table.optJSONArray("columns"),rows=table.optJSONArray("rows"); int name=-1;
            for (int col=0;columns!=null && col<columns.length();col++) if (columns.optString(col).matches(".*(?:教室|房间).*") && !columns.optString(col).matches(".*(?:类型|人数|容量).*") ) { name=col; break; }
            if (name<0) continue;
            int building=-1,wing=-1;
            for (int col=0;col<columns.length();col++) {
                String heading=columns.optString(col);
                if (building<0 && col!=name && heading.matches(".*(?:教学楼|楼宇|楼栋|建筑|地点|位置).*") && !heading.matches(".*(?:楼层|层数|编号|代码|面积|容量|人数).*")) building=col;
                if (heading.matches("楼座|座|分区|区域|区域名称")) wing=col;
            }
            List<Integer> detailColumns=new ArrayList<>();
            for (int col=0;col<columns.length();col++) if (col!=name
                    && (col==building || col==wing || columns.optString(col).matches(".*(?:校区|楼|建筑|座位|容量|人数).*"))) detailColumns.add(col);
            for (int row=0;rows!=null && row<rows.length();row++) {
                JSONArray values=rows.optJSONArray(row); if (values==null) continue;
                String label=values.optString(name).trim(); if (label.isEmpty()) continue;
                List<String> details=new ArrayList<>();
                for (int col:detailColumns) if (!values.optString(col).isEmpty()) details.add(columns.optString(col)+"："+values.optString(col));
                String place=location(building<0?"":values.optString(building).trim(),wing<0?"":values.optString(wing),label);
                String detail=String.join(" · ",details); result.putIfAbsent(label+"|"+detail,new Room(label,detail,place));
            }
        }
        return new ArrayList<>(result.values());
    }
}
