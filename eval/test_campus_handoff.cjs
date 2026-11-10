// Exercise the actual Java portal coordinator, including delayed WebView callbacks.
// All pages, cookies and services below are synthetic; no Android device is required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const source = fs.readFileSync(path.join(__dirname, '../android/app/src/com/nopalou/CampusBatch.java'), 'utf8');
const portal = source.slice(source.indexOf('        void portal(JSONObject r) {'), source.indexOf('        void read(JSONObject r,String url) {'));
assert.ok(portal.includes('void portal('), 'test executes the production coordinator');
assert.match(source, /pageUrl:location\.href/);
assert.ok(source.indexOf('if (!url.equals(result.optString("pageUrl")))') < source.indexOf('if (login==null || !authenticate(login,url))'), 'reject previous-document auth before acting on it');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'offloor-handoff-'));
const java = `import java.util.*;
public class CampusHandoffTest {
 static int checks;
 static void check(String label,boolean value) { if(!value)throw new AssertionError(label); checks++;System.out.println("PASS "+label); }
 static class JSONObject {
  Map<String,Object> values=new HashMap<>();
  JSONObject optJSONObject(String k){return (JSONObject)values.get(k);}
  String optString(String k){Object v=values.get(k);return v==null?"":v.toString();}
  static String quote(String s){return "\\\""+s+"\\\"";}
 }
 static class CampusServices {
  static boolean readUrl(String s){return s.startsWith("https://");}
  static boolean isPortal(String s){return s.contains("ecampus.nwpu.edu.cn");}
 }
 static class Service {String id="classroom",title="空闲教室";}
 interface Callback {void accept(String value);}
 static class Web {
  String url="https://ecampus.nwpu.edu.cn/main.html";
  String getUrl(){return url;}
  List<Callback> callbacks=new ArrayList<>();List<String> calls=new ArrayList<>();
  void evaluateJavascript(String code,Callback cb){calls.add(code);callbacks.add(cb);}
  void reply(String v){callbacks.remove(0).accept(v);}
 }
 static class Lane {
  Web web=new Web();Service service=new Service();int generation=1,schedules,timeouts;boolean alive=true,expired;
  Set<String> handoffs=new HashSet<>();
  boolean alive(){return alive;}
  void schedule(long delay){schedules++;}
  void timeout(){timeouts++;if(!expired)schedule(100);}
  void store(Lane lane,JSONObject m,boolean ok){throw new AssertionError("unexpected cached result");}
  void navigate(String url){web.url=url;}
${portal}
 }
 static void miss(Lane l){l.portal(new JSONObject());l.web.reply("false");if(!l.web.callbacks.isEmpty())l.web.reply("false");}
 public static void main(String[] args){
  Lane late=new Lane();miss(late);miss(late);miss(late);
  check("missing app and catalog remain retryable beyond two probes",late.web.calls.size()==6 && late.handoffs.isEmpty());
  late.portal(new JSONObject());late.web.reply("false");late.web.reply("true");
  check("catalog is consumed only after a successful activation",late.handoffs.contains("catalog:classroom")&&!late.handoffs.contains("portal:classroom"));
  late.portal(new JSONObject());late.web.reply("false");
  check("slow app remains retryable after catalog opened",!late.handoffs.contains("portal:classroom")&&late.web.callbacks.isEmpty());
  late.portal(new JSONObject());late.web.reply("true");int calls=late.web.calls.size();late.portal(new JSONObject());
  check("loaded app activates once without duplicate ticket requests",late.handoffs.contains("portal:classroom")&&late.web.calls.size()==calls);
  Lane stale=new Lane();stale.portal(new JSONObject());stale.generation++;stale.web.reply("false");
  check("callback from previous job never opens another service",stale.web.calls.size()==1&&stale.timeouts==0);
  Lane moved=new Lane();moved.portal(new JSONObject());moved.web.url="https://jwxt.nwpu.edu.cn/home";moved.web.reply("false");
  check("navigation during callback never clicks catalog in destination",moved.web.calls.size()==1&&moved.schedules==1);
  Lane bounded=new Lane();bounded.expired=true;miss(bounded);
  check("missing portal controls still honor overall deadline",bounded.timeouts==1&&bounded.schedules==0);
  Lane named=new Lane();named.service.id="portal_example";named.portal(new JSONObject());named.web.reply("false");named.web.reply("false");
  check("named portal services use the same retry-safe coordinator",named.web.calls.get(0).contains("clickService")&&named.handoffs.isEmpty());
  System.out.println(checks+" checks passed");
 }
}
`;
try {
    const file = path.join(work, 'CampusHandoffTest.java');
    fs.writeFileSync(file, java);
    for (const [command, args] of [['javac', ['-encoding', 'UTF-8', '-d', work, file]], ['java', ['-cp', work, 'CampusHandoffTest']]]) {
        const result = cp.spawnSync(command, args, {stdio: 'inherit'});
        if (result.error) throw result.error;
        assert.equal(result.status, 0, command + ' succeeds');
    }
} finally { fs.rmSync(work, {recursive: true, force: true}); }
