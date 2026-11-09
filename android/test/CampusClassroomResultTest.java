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
        System.out.println(checks+" checks passed");
    }
}
