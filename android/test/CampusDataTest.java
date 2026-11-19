package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Synthetic identity/failure/merge boundaries. No school account or private data required. */
public class CampusDataTest {
    static int checks;
    static void check(String name,boolean ok) { if (!ok) throw new AssertionError(name); checks++; System.out.println("PASS "+name); }
    static JSONObject module(String state,String value) throws Exception {
        return new JSONObject().put("state",state).put("source","https://jwxt.nwpu.edu.cn/student/for-std/student-portrait")
                .put("fields",value==null?new JSONArray():new JSONArray().put(new JSONObject().put("label","姓名").put("value",value))).put("tables",new JSONArray()).put("links",new JSONArray());
    }
    static String fresh(String identity,JSONObject modules) throws Exception {
        JSONObject f=new JSONObject().put("version",1).put("school","西北工业大学").put("modules",modules);
        if (identity!=null) f.put("identity",identity); return f.toString();
    }
    interface Throwing { void run() throws Exception; }
    static void reject(String name,Throwing action) throws Exception { try { action.run(); } catch (Exception e) { check(name,true); return; } throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        CampusData a=CampusData.merge(null,fresh("123",new JSONObject().put("profile",module("ok","合成甲"))),100,null);
        check("identity stored only as a hash",a.owner.length()==64 && !a.json.has("identity"));
        check("successful snapshot and time saved",CampusData.count(a.module("profile"))==1 && a.module("profile").getLong("updated")==100);
        CampusData stale=CampusData.merge(a,fresh(null,new JSONObject().put("profile",module("login",null))),200,a.owner);
        check("expired login retains last content and success timestamp",stale.module("profile").getJSONArray("fields").getJSONObject(0).getString("value").equals("合成甲") && stale.module("profile").getLong("updated")==100);
        check("latest failure and attempt time remain visible",stale.module("profile").getString("state").equals("login") && stale.module("profile").getLong("attempted")==200);
        CampusData next=CampusData.merge(stale,fresh("456",new JSONObject().put("program",module("ok","合成乙"))),300,null);
        check("switching identity discards old account modules",next.module("profile")==null && next.module("program")!=null && !next.owner.equals(a.owner));
        reject("late callback from old account rejected",() -> CampusData.merge(next,fresh(null,new JSONObject().put("profile",module("ok","合成甲"))),400,a.owner));
        reject("successful empty pages cannot replace real content",() -> CampusData.merge(a,fresh(null,new JSONObject().put("profile",module("ok",null))),400,a.owner));
        reject("unknown modules rejected",() -> CampusData.merge(a,fresh(null,new JSONObject().put("unknown",module("ok","x"))),400,a.owner));
        reject("external source rejected",() -> CampusData.merge(a,fresh(null,new JSONObject().put("profile",module("ok","x").put("source","https://evil.test/"))),400,a.owner));
        JSONObject routes=new JSONObject(fresh(null,new JSONObject())).put("routes",new JSONObject().put("program","https://evil.test/").put("completion","https://jwxt.nwpu.edu.cn/student/for-std/observed-progress"));
        CampusData discovered=CampusData.merge(a,routes.toString(),500,a.owner);
        check("only official discovered routes accepted",!discovered.routes.has("program") && discovered.routes.has("completion"));
        check("roundtrip validates content",new CampusData(discovered.encode()).owner.equals(a.owner));
        check("no cleartext untrusted origin accepted",!CampusServices.allowed("http://nwpu.edu.cn/") && !CampusServices.allowed("https://nwpu.edu.cn.evil.test/"));
        check("destructive routes rejected",!CampusServices.readUrl("https://jwxt.nwpu.edu.cn/student/delete/123") && !CampusServices.readUrl("https://jwxt.nwpu.edu.cn/x?ticket=private"));
        JSONObject netMod=new JSONObject().put("state","ok").put("source","https://ecampus.nwpu.edu.cn/main.html")
                .put("fields",new JSONArray().put(new JSONObject().put("label","账户余额").put("value","42.50 元")).put(new JSONObject().put("label","身份证号").put("value","PRIVATE_ID")))
                .put("tables",new JSONArray()).put("links",new JSONArray());
        CampusData diagData=CampusData.merge(null,fresh("20230137",new JSONObject().put("network",netMod)),1700000000000L,null);
        String diag=CampusDiag.report(diagData,"0.82","TestPhone / Android 14","同步完成","complete",5,5,"");
        check("campus diagnostic includes sanitized sync state and balance presence",
                diag.contains("账号绑定：已绑定") && diag.contains("network(网费)：state=ok") && diag.contains("已读余额=是") && diag.contains("ecampus.nwpu.edu.cn/main.html"));
        check("campus diagnostic never leaks owner hash, student id, balance number or sensitive labels",
                !diag.contains(diagData.owner) && !diag.contains("20230137") && !diag.contains("42.50") && !diag.contains("PRIVATE_ID") && !diag.contains("身份证号"));
        check("campus diagnostic strips query tokens from source URLs",
                "yktapp.nwpu.edu.cn/plat".equals(CampusDiag.safeSource("https://yktapp.nwpu.edu.cn/plat?synjones-auth=SECRET")));
        if (args.length>0) {
            String js=Files.readString(Paths.get(args[0])); CampusData fromJs=CampusData.merge(null,js,1,null);
            check("real browser adapter output accepted by Java",fromJs.module("profile").getString("state").equals("ok") && CampusData.count(fromJs.module("program"))==1);
        }
        System.out.println(checks+" checks passed");
    }
}
