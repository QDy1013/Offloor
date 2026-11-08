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
        check("large histories continue past the former forty-action limit",s.actions()==51 && s.visit("more"));
        for(int i=0;i<CampusReadSession.MAX_ACTIONS+10;i++) s.visit("extra"+i);
        check("loop guard marks an unfinished scan",s.actions()==CampusReadSession.MAX_ACTIONS && s.limited() && !s.visit("beyond"));
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
        CampusReadSession frontier=new CampusReadSession();
        frontier.remember(new JSONArray().put(new JSONObject().put("key","page:a").put("type","navigate").put("url","https://yktapp.nwpu.edu.cn/personal/a")).put(new JSONObject().put("key","page:b").put("type","navigate").put("url","https://yktapp.nwpu.edu.cn/personal/b")));
        frontier.visit("page:a");
        check("sibling read pages remain queued after navigating away",frontier.nextPage().getString("key").equals("page:b"));
        frontier.openDetail("https://yktapp.nwpu.edu.cn/personal","记录一");
        check("full-page details retain the return page and record context",frontier.context().equals("记录一") && frontier.back().getString("url").endsWith("/personal") && frontier.context().isEmpty());
        CampusReadSession records=new CampusReadSession();
        for (int i=1;i<=2;i++) records.add(module("ok").put("fields",new JSONArray().put(new JSONObject().put("label","状态").put("value","记录"+i).put("group","同名课程").put("record","https://yktapp.nwpu.edu.cn/detail?id="+i))));
        check("same-named fields from different records cannot overwrite each other",records.result().getJSONArray("fields").length()==2);
        CampusData preview=CampusData.merge(old,new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put("card",page("new").put("partial",true))).toString(),200,old.owner);
        check("streaming results retain older records until the full read completes",CampusData.count(preview.module("card"))==2 && preview.module("card").getBoolean("partial"));
        CampusData finalData=CampusData.merge(preview,new JSONObject().put("version",1).put("school","西北工业大学").put("modules",new JSONObject().put("card",page("new").put("partial",false))).toString(),300,preview.owner);
        check("completed read replaces the streaming snapshot",CampusData.count(finalData.module("card"))==1 && !finalData.module("card").getBoolean("partial"));
        JSONArray apps=new JSONArray().put(new JSONObject().put("title","体测查询").put("url","https://sports.nwpu.edu.cn/personal")).put(new JSONObject().put("title","外部页面").put("url","https://evil.test/"));
        String dynamicId=CampusCatalog.id("体测查询","https://sports.nwpu.edu.cn/personal");
        CampusData catalog=CampusData.merge(null,new JSONObject().put("version",1).put("school","西北工业大学").put("identity","1").put("catalog",apps).put("modules",new JSONObject().put(dynamicId,page("fitness"))).toString(),100,null);
        check("observed extra services are persisted and displayed as distinct modules",catalog.catalog.length()==1 && catalog.service(dynamicId)!=null && catalog.module(dynamicId)!=null);
        CampusData changed=CampusData.merge(catalog,new JSONObject().put("version",1).put("school","西北工业大学").put("identity","2").put("modules",new JSONObject()).toString(),200,catalog.owner);
        check("account change also clears discovered service metadata",changed.catalog.length()==0 && changed.module(dynamicId)==null);
        java.util.List<CampusServices.Service> queue=new java.util.ArrayList<>(java.util.Arrays.asList(CampusServices.byId("card"),CampusServices.byId("library"),CampusServices.byId("network")));
        java.util.Set<String> busy=new java.util.HashSet<>(), done=new java.util.HashSet<>();
        for (int i=0;i<3;i++) { int at=CampusSchedule.next(queue,busy,done,null); CampusServices.Service service=queue.remove(at); busy.add(CampusSchedule.host(service,null)); }
        check("three unrelated sites can read concurrently",busy.size()==3 && queue.isEmpty());
        queue.add(CampusServices.byId("card"));
        check("same-site navigation is not duplicated across browser lanes",CampusSchedule.next(queue,busy,done,null)==-1);
        queue.clear(); busy.clear(); queue.add(CampusServices.byId("electric")); queue.add(CampusServices.byId("network"));
        check("electricity dependencies do not block unrelated reads",CampusSchedule.next(queue,busy,done,null)==1);
        done.add("housing"); done.add("affairs");
        check("electricity starts as soon as housing dependencies finish",CampusSchedule.next(queue,busy,done,null)==0);
        CampusServices.Service history=new CampusServices.Service("_academic","学期成绩","","https://jwxt.nwpu.edu.cn/student/for-std/course-table",true);
        busy.add(CampusSchedule.host(history,null)); queue.clear(); queue.add(CampusServices.byId("program"));
        check("slow historical grade API does not hold up academic page reads",CampusSchedule.next(queue,busy,done,null)==0);
        System.out.println(count+" checks passed");
    }
}
