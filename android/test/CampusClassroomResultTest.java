package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;

/** No account or live school service required: query boundaries and partial-result merges. */
public class CampusClassroomResultTest {
    private static int checks;
    private static void check(String name,boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS "+name);
    }
    private static JSONObject module(String token,String date,String room) throws Exception {
        return new JSONObject().put("state","ok").put("partial",true).put("queryToken",token)
                .put("fields",new JSONArray().put(new JSONObject().put("label","查询条件").put("value","长安校区 · "+date+" 14:00—16:00")))
                .put("links",new JSONArray()).put("tables",new JSONArray().put(new JSONObject().put("title","查询结果")
                    .put("columns",new JSONArray().put("教室").put("容量")).put("rows",new JSONArray().put(new JSONArray().put(room).put("40")))));
    }
    private static CampusData merge(CampusData previous,JSONObject module) throws Exception {
        return CampusData.merge(previous,new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put("classroom",module)).toString(),100,null);
    }
    private static java.util.List<CampusClassroomResult.Room> roomTable(String[] columns,String[][] values) throws Exception {
        JSONArray headings=new JSONArray(),rows=new JSONArray();
        for (String column:columns) headings.put(column);
        for (String[] value:values) { JSONArray row=new JSONArray(); for (String cell:value) row.put(cell); rows.put(row); }
        return CampusClassroomResult.rooms(new JSONObject().put("tables",new JSONArray().put(new JSONObject().put("columns",headings).put("rows",rows))));
    }
    public static void main(String[] args) throws Exception {
        String date="2026-11-09";
        CampusData old=merge(null,module("old-query",date,"旧时段教室"));
        CampusData fresh=merge(old,module("new-query",date,"新时段教室"));
        JSONObject current=fresh.module("classroom");
        check("a new query replaces the old token",CampusClassroomResult.current(current,"new-query",date));
        check("a new partial result contains no rooms from the previous query",CampusClassroomResult.rooms(current).size()==1 && CampusClassroomResult.rooms(current).get(0).name.equals("新时段教室"));
        check("merging never mutates the prior snapshot",CampusClassroomResult.rooms(old.module("classroom")).get(0).name.equals("旧时段教室"));
        CampusData more=merge(fresh,module("new-query",date,"当前时段第二间"));
        check("pages from the same query still accumulate",CampusClassroomResult.rooms(more.module("classroom")).size()==2 && CampusClassroomResult.current(more.module("classroom"),"new-query",date));
        CampusData legacy=merge(null,module("",date,"无标记旧缓存"));
        CampusData replaced=merge(legacy,module("new-query",date,"新时段教室"));
        check("legacy untagged cache is replaced",CampusClassroomResult.rooms(replaced.module("classroom")).size()==1 && CampusClassroomResult.current(replaced.module("classroom"),"new-query",date));
        check("stale tokens and old dates cannot become current results",!CampusClassroomResult.current(current,"old-query",date) && !CampusClassroomResult.current(current,"new-query","2026-11-10") && !CampusClassroomResult.current(current,"",date));
        check("failed reads never count as successful current results",!CampusClassroomResult.current(new JSONObject(current.toString()).put("state","network"),"new-query",date));
        JSONObject empty=module("empty-query",date,"unused").put("state","empty").put("tables",new JSONArray());
        CampusData noRooms=merge(fresh,empty);
        check("explicit empty responses clear previous rooms",CampusClassroomResult.current(noRooms.module("classroom"),"empty-query",date) && CampusClassroomResult.rooms(noRooms.module("classroom")).isEmpty());
        check("missing results invent no rooms",CampusClassroomResult.rooms(null).isEmpty());
        long late=CampusSummary.date("2026-11-09 23:59");
        int[] range=CampusClassroomQuery.defaultRange(late);
        check("evening defaults stay within the same Shanghai day",CampusClassroomQuery.today(late).equals(date) && range[0]<range[1] && range[1]==1439);
        boolean invalid=false;
        try { new CampusClassroomQuery("长安校区",date,960,840); } catch (IllegalArgumentException expected) { invalid=true; }
        check("reversed times are rejected",invalid);
        java.util.List<CampusClassroomResult.Room> located=roomTable(new String[]{"教室","校区","楼层","教学楼","座位数"},new String[][]{
                {"A101","长安校区","1","教学西楼","60"},
                {"B1-302","长安校区","3","教学西楼","60"},
                {"C2-201","长安校区","2","教学西楼","40"},
                {"D1-104","长安校区","1","教学西楼","40"},
                {"B2-303","长安校区","3"," 教学西楼 ","80"},
                {"A101","长安校区","1","教学东楼","40"},
                {"C0101翱翔体育馆主馆篮球场","长安校区","1","翱翔体育馆（主馆）","30"},
                {"C2-999","长安校区","9","","40"}});
        java.util.List<String> locations=CampusClassroomResult.locations(located);
        check("teaching buildings are extracted instead of the earlier floor column",located.get(0).location.equals("教学西楼 · A座"));
        check("teaching-room codes distinguish all four wings",locations.containsAll(java.util.Arrays.asList("教学西楼 · A座","教学西楼 · B座","教学西楼 · C座","教学西楼 · D座")));
        check("same building and wing yield one dropdown option",java.util.Collections.frequency(locations,"教学西楼 · B座")==1);
        check("wing filtering retains every matching room",CampusClassroomResult.atLocation(located,"教学西楼 · B座").size()==2);
        check("same room codes in a different building never match",CampusClassroomResult.atLocation(located,"教学西楼 · A座").size()==1 && CampusClassroomResult.atLocation(located,"教学东楼 · A座").size()==1);
        check("gym room codes are never interpreted as teaching wings",located.get(6).location.equals("翱翔体育馆（主馆）"));
        check("missing buildings create no guessed dropdown entry",located.get(7).location.isEmpty() && !locations.contains(""));
        check("all locations retains rooms with missing location fields",CampusClassroomResult.atLocation(located,"").size()==8);
        check("an absent location never falls back to unrelated rooms",CampusClassroomResult.atLocation(located,"不存在的教学楼 · A座").isEmpty());
        check("filtering does not mutate the full query result",located.size()==8 && located.get(0).name.equals("A101"));
        java.util.List<CampusClassroomResult.Room> explicit=roomTable(new String[]{"房间名称","教学楼","楼座"},new String[][]{{"研讨室","教学西楼","D座"},{"研讨室2","教学西楼A座",""},{"Ｂ２－２０４","教学西楼",""}});
        check("an explicit wing field is retained for rooms without coded names",explicit.get(0).location.equals("教学西楼 · D座"));
        check("explicit wings in building names are not appended twice",explicit.get(1).location.equals("教学西楼A座"));
        check("full-width school room codes identify the correct wing",explicit.get(2).location.equals("教学西楼 · B座"));
        java.util.List<CampusClassroomResult.Room> floors=roomTable(new String[]{"教室","楼层","座位数"},new String[][]{{"A101","3","60"}});
        check("floors and seat counts never become locations",CampusClassroomResult.locations(floors).isEmpty());
        check("an empty current response has no location options",CampusClassroomResult.locations(CampusClassroomResult.rooms(noRooms.module("classroom"))).isEmpty());
        System.out.println(checks+" checks passed");
    }
}
