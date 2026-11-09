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
        final String name,detail;
        Room(String name,String detail) { this.name=name; this.detail=detail; }
    }
    static boolean current(JSONObject module,String token,String date) {
        return module!=null && token!=null && !token.isEmpty() && token.equals(module.optString("queryToken"))
                && CampusSummary.field(module,"查询条件").contains(date)
                && ("ok".equals(module.optString("state")) || "empty".equals(module.optString("state")));
    }
    static List<Room> rooms(JSONObject module) {
        Map<String,Room> result=new LinkedHashMap<>();
        JSONArray tables=module==null?null:module.optJSONArray("tables");
        for (int i=0;tables!=null && i<tables.length();i++) {
            JSONObject table=tables.optJSONObject(i); if (table==null) continue;
            JSONArray columns=table.optJSONArray("columns"),rows=table.optJSONArray("rows"); int name=-1;
            for (int col=0;columns!=null && col<columns.length();col++) if (columns.optString(col).matches(".*(?:教室|房间).*") && !columns.optString(col).matches(".*(?:类型|人数|容量).*") ) { name=col; break; }
            if (name<0) continue;
            for (int row=0;rows!=null && row<rows.length();row++) {
                JSONArray values=rows.optJSONArray(row); if (values==null) continue;
                String label=values.optString(name).trim(); if (label.isEmpty()) continue;
                List<String> details=new ArrayList<>();
                for (int col=0;col<columns.length();col++) if (col!=name && columns.optString(col).matches(".*(?:校区|楼|建筑|座位|容量|人数).*") && !values.optString(col).isEmpty()) details.add(columns.optString(col)+"："+values.optString(col));
                String detail=String.join(" · ",details); result.putIfAbsent(label+"|"+detail,new Room(label,detail));
            }
        }
        return new ArrayList<>(result.values());
    }
}
