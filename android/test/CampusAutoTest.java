package com.nopalou;
import org.json.JSONArray;
import org.json.JSONObject;

public class CampusAutoTest {
    static int count;
    static void check(String name,boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("PASS "+name); }
    static JSONObject module(String state) throws Exception { return new JSONObject().put("state",state).put("source","https://yktapp.nwpu.edu.cn/personal").put("fields",new JSONArray()).put("tables",new JSONArray()).put("links",new JSONArray()); }
    static JSONObject page(String row) throws Exception { return module("ok").put("tables",new JSONArray().put(new JSONObject().put("title","").put("columns",new JSONArray().put("date")).put("rows",new JSONArray().put(new JSONArray().put(row))))); }
    static CampusData housing(String[] labels,String[] values) throws Exception {
        JSONArray f=new JSONArray(); for(int i=0;i<labels.length;i++) f.put(new JSONObject().put("label",labels[i]).put("value",values[i]));
        return CampusData.merge(null,new JSONObject().put("version",1).put("school","西北工业大学").put("identity","1").put("modules",new JSONObject().put("housing",module("ok").put("fields",f))).toString(),100,null);
    }
    public static void main(String[] args) throws Exception {
        check("successful campus login always chains full read",CampusAutoPolicy.fullReadAfterLogin("nwpu",false));
        check("reauthentication returns to existing queue instead of duplicating",!CampusAutoPolicy.fullReadAfterLogin("nwpu",true));
        check("other trial schools retain their own flow",!CampusAutoPolicy.fullReadAfterLogin("tyut",false));
        check("first app entry refreshes automatically",CampusAutoPolicy.due(100,0,false,0,23,23));
        check("active batch prevents page resume from duplicating it",!CampusAutoPolicy.due(200,100,true,0,22,23));
        check("expired run can recover after process death",CampusAutoPolicy.due(100+CampusAutoPolicy.LEASE,100,true,0,23,23));
        check("successful fresh snapshot waits until interval",!CampusAutoPolicy.due(100,0,false,200,23,23));
        check("failed batch retries at next due entry",CampusAutoPolicy.due(200,0,false,200,23,23));
        check("new app version refreshes automatically",CampusAutoPolicy.due(100,0,false,200,22,23));
        check("clock rollback cannot postpone forever",CampusAutoPolicy.due(1,0,false,CampusAutoPolicy.SUCCESS_INTERVAL+100,23,23));
        CampusReadSession s=new CampusReadSession();
        check("first snapshot waits for stability",!s.stable(page("one"),0));
        check("pending requests cannot count as stable",!s.stable(page("one"),1));
        check("same settled page becomes stable",s.stable(page("one"),0));
        check("changed page waits again",!s.stable(page("two"),0));
        s.add(page("one")); s.add(page("two")); s.add(page("one"));
        check("pagination accumulates and deduplicates rows",s.result().getJSONArray("tables").getJSONObject(0).getJSONArray("rows").length()==2);
        s.add(module("empty")); check("empty later page does not discard earlier records",CampusData.count(s.result())==2);
        check("each observed action executes once",s.visit("next:1") && !s.visit("next:1"));
        for(int i=0;i<50;i++) s.visit("key"+i);
        check("navigation stops at bounded limit",s.actions()==40 && !s.visit("more"));
        CampusReadSession empty=new CampusReadSession(); empty.add(module("empty"));
        check("explicit empty result can be saved",empty.hasResult() && "empty".equals(empty.result().getString("state")));
        CampusData old=CampusData.merge(null,new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put("card",page("old"))).toString(),100,null);
        CampusData cleared=CampusData.merge(old,new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put("card",module("empty"))).toString(),200,old.owner);
        check("confirmed empty state clears outdated records",CampusData.count(cleared.module("card"))==0 && cleared.module("card").getLong("updated")==200);
        JSONArray options=new JSONArray().put(new JSONObject().put("name","12号楼").put("value","building12"));
        CampusData h=housing(new String[]{"住宿校区","宿舍楼","房间号"},new String[]{"长安校区","12栋","301"});
        check("explicit personal building matches school option",CampusBindings.match(h,options,1)!=null);
        check("room is never inferred from building alone",CampusBindings.match(h,new JSONArray().put(new JSONObject().put("name","302室").put("value","302")),2)==null);
        options.put(new JSONObject().put("name","12楼").put("value","other12"));
        check("ambiguous housing options are rejected",CampusBindings.match(h,options,1)==null);
        check("unavailable housing never guesses sole option",CampusBindings.match(null,options,1)==null);
        System.out.println(count+" checks passed");
    }
}
