package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual returned classrooms only; an absent room is never marked occupied. */
final class CampusClassroomResult {
    static final class Room {
        final String name,detail,location;
        Room(String name,String detail,String location) { this.name=name; this.detail=detail; this.location=location; }
    }
    static boolean current(JSONObject module,String token,String date) {
        return module!=null && token!=null && !token.isEmpty() && token.equals(module.optString("queryToken"))
                && CampusSummary.field(module,"查询条件").contains(date)
                && ("ok".equals(module.optString("state")) || "empty".equals(module.optString("state")));
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
        java.util.Set<String> selected=new java.util.HashSet<>(locations);
        List<Room> result=new ArrayList<>();
        for (Room room:rooms) if (selected.contains(room.location)) result.add(room);
        return result;
    }
    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).trim().toUpperCase(java.util.Locale.ROOT);
    }
    private static String location(String building,String wing,String room) {
        if (building.isEmpty()) return "";
        String name=normalized(building);
        // Explicit building names already containing a wing need no inferred suffix.
        if (java.util.regex.Pattern.compile("[A-D]\\s*(?:[0-9]+)?[座区栋]").matcher(name).find()) return building;
        String letter="";
        java.util.regex.Matcher explicit=java.util.regex.Pattern.compile("^([A-D])(?:[0-9]+)?(?:座|区|栋)?$").matcher(normalized(wing));
        if (explicit.matches()) letter=explicit.group(1);
        // School teaching-room codes such as B1-302 identify B座. Gym codes do not.
        if (letter.isEmpty() && name.matches(".*(?:教学|教[东西]).*")) {
            java.util.regex.Matcher code=java.util.regex.Pattern.compile("^(?:教学[东西]楼|教[东西])?\\s*([A-D])(?:座|区)?\\s*[0-9]+(?:[-－—][0-9]+)?(?:$|[^A-Z0-9]).*").matcher(normalized(room));
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
            for (int row=0;rows!=null && row<rows.length();row++) {
                JSONArray values=rows.optJSONArray(row); if (values==null) continue;
                String label=values.optString(name).trim(); if (label.isEmpty()) continue;
                List<String> details=new ArrayList<>();
                for (int col=0;col<columns.length();col++) if (col!=name && (col==building || col==wing || columns.optString(col).matches(".*(?:校区|楼|建筑|座位|容量|人数).*")) && !values.optString(col).isEmpty()) details.add(columns.optString(col)+"："+values.optString(col));
                String place=location(building<0?"":values.optString(building).trim(),wing<0?"":values.optString(wing),label);
                String detail=String.join(" · ",details); result.putIfAbsent(label+"|"+detail,new Room(label,detail,place));
            }
        }
        return new ArrayList<>(result.values());
    }
}
