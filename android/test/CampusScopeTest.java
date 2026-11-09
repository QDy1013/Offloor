package com.nopalou;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;

public class CampusScopeTest {
    static int n;
    static void check(String name,boolean value) { if (!value) throw new AssertionError(name); n++; System.out.println("PASS "+name); }
    static JSONObject module(String label,String value) throws Exception { return new JSONObject().put("state","ok").put("source","https://xgpt.nwpu.edu.cn/").put("fields",new JSONArray().put(new JSONObject().put("label",label).put("value",value))).put("tables",new JSONArray()).put("links",new JSONArray()); }
    public static void main(String[] args) throws Exception {
        String[] expected={"network","electric","card","classroom","bus"};
        check("campus shows the retained account and bus features",CampusServices.CAMPUS.length==expected.length);
        for (int i=0;i<expected.length;i++) check("selected order "+expected[i],CampusServices.CAMPUS[i].id.equals(expected[i]));
        check("borrowing, profile and catalog entries are not campus features",!CampusServices.selected("library") && !CampusServices.selected("profile") && !CampusServices.selected("portal_other"));
        JSONObject modules=new JSONObject().put("profile",module("姓名","合成同学")).put("library",module("书名","合成书目")).put("network",module("账户余额","12.5 元"));
        modules.put("housing",module("房间号","301").put("fields",new JSONArray().put(new JSONObject().put("label","房间号").put("value","301")).put(new JSONObject().put("label","姓名").put("value","合成同学"))));
        JSONArray catalog=new JSONArray().put(new JSONObject().put("title","其他服务").put("url","https://sports.nwpu.edu.cn/personal"));
        CampusData old=CampusData.merge(null,new JSONObject().put("version",1).put("school","西北工业大学").put("identity","123").put("catalog",catalog).put("modules",modules).toString(),100,null);
        CampusData compact=CampusScope.compact(old);
        check("upgrade removes obsolete personal modules and service catalog",compact.module("profile")==null && compact.module("library")==null && compact.catalog.length()==0);
        check("upgrade retains owner and selected balances",compact.owner.equals(old.owner) && compact.module("network")!=null);
        check("electricity keeps only necessary housing location fields",compact.module("housing").getJSONArray("fields").length()==1);
        CampusSummary balance=CampusSummary.of("network",compact.module("network"),0);
        check("fee card shows its actual balance directly",balance.value.equals("12.5 元"));
        check("missing balance is never rendered as zero",!CampusSummary.of("network",null,0).value.contains("0"));
        CampusData cleanup=CampusScope.compact(CampusData.merge(null,new JSONObject().put("version",1).put("school","西北工业大学").put("identity","123").put("modules",new JSONObject().put("electric",module("当前剩余电量","42 度").put("fields",new JSONArray().put(new JSONObject().put("label","当前剩余电量").put("value","42 度")).put(new JSONObject().put("label","宿舍").put("value","合成宿舍")))).put("classroom",module("查询日期","2099-01-01"))).toString(),100,null));
        check("electricity display excludes housing details",cleanup.module("electric").getJSONArray("fields").length()==1);
        check("classroom remains an on-demand campus feature",cleanup.module("classroom")!=null);
        check("hiding old features does not mutate the full original snapshot",old.module("library")!=null && old.catalog.length()==1);
        CampusData removed=CampusScope.compact(CampusData.merge(null,new JSONObject().put("version",1).put("school","西北工业大学").put("identity","123").put("modules",new JSONObject().put("deadlines",module("截止时间","2099-01-01"))).put("routes",new JSONObject().put("deadlines",CampusServices.HOME)).toString(),100,null));
        check("campus deadlines and routes are hidden from the selected view",removed.module("deadlines")==null && !removed.routes.has("deadlines"));
        CampusData bus=new CampusData(Files.readString(Paths.get(args[0])));
        check("student timetable excludes the teacher-only service",bus.module("bus").getJSONArray("tables").getJSONObject(0).getJSONArray("rows").length()==50);
        long sunday=CampusSummary.date("2026-11-08 07:30");
        CampusSummary weekend=CampusSummary.of("bus",bus.module("bus"),sunday);
        check("weekend shows next departure in both directions",weekend.value.contains("友谊→长安 08:00") && weekend.note.contains("长安→友谊 09:10"));
        check("early weekend student view never suggests the teacher-only 07:00 service",CampusSummary.of("bus",bus.module("bus"),CampusSummary.date("2026-11-08 06:30")).value.contains("08:00"));
        CampusSummary weekday=CampusSummary.of("bus",bus.module("bus"),CampusSummary.date("2026-11-09 07:30"));
        check("weekday uses its own timetable",weekday.value.contains("08:00") && weekday.note.contains("08:30"));
        check("invalid deadline date is rejected",CampusSummary.date("2026-02-30 08:00")==-1);
        System.out.println(n+" checks passed");
    }
}
