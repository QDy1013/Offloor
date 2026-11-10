package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class CampusBusScheduleTest {
    static int count;
    static void check(String name,boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("PASS "+name); }
    static JSONArray row(String day,String route,String time,String place) { return new JSONArray().put(day).put(route).put(time).put(place); }
    public static void main(String[] args) throws Exception {
        JSONArray rows=new JSONArray().put(row("工作日","友谊→长安","09:00","站点A"))
                .put(row("工作日","友谊→长安","08:00","站点A"))
                .put(row("工作日","友谊→长安","08:00","站点A"))
                .put(row("周末","长安→友谊","09:10","站点B"))
                .put(row("周末","长安→友谊","07:00","教师专用车"))
                .put(row("工作日","友谊→长安","28:00","站点A"));
        JSONObject module=new JSONObject().put("tables",new JSONArray().put(new JSONObject()
                .put("columns",new JSONArray().put("运行日").put("线路").put("发车时间").put("乘车地点")).put("rows",rows)));
        List<CampusBusSchedule.Route> work=CampusBusSchedule.routes(module,false);
        check("workday excludes weekend rows",work.size()==1 && work.get(0).name.equals("友谊→长安"));
        check("departures are sorted and deduplicated",work.get(0).departures.size()==2 && work.get(0).departures.get(0).time.equals("08:00"));
        check("next departure includes the current minute",work.get(0).next(480).time.equals("08:00"));
        check("past departures are skipped",work.get(0).next(481).time.equals("09:00"));
        check("no invented service after the last departure",work.get(0).next(541)==null);
        check("boarding place is retained",work.get(0).departures.get(0).place.equals("站点A"));
        check("route filter keeps the selected direction",CampusBusSchedule.filter(work,"友谊→长安","").size()==1);
        check("place filter keeps matching departures",CampusBusSchedule.filter(work,"","站点A").get(0).departures.size()==2);
        check("unknown place produces no routes",CampusBusSchedule.filter(work,"","不存在").isEmpty());
        check("route names and places are exposed",CampusBusSchedule.routeNames(work).size()==1 && CampusBusSchedule.places(work).contains("站点A"));
        List<CampusBusSchedule.Route> weekend=CampusBusSchedule.routes(module,true);
        check("weekend excludes teacher-only buses",weekend.size()==1 && weekend.get(0).departures.size()==1 && weekend.get(0).departures.get(0).time.equals("09:10"));
        check("missing timetable stays empty",CampusBusSchedule.routes(null,false).isEmpty());
        check("live reservation entry is the official first-party page",CampusServices.allowed(CampusServices.BUS_RESERVATION) && CampusServices.readUrl(CampusServices.BUS_RESERVATION));
        check("live read entry is the official bus H5 page",CampusServices.BUS_RESERVATION.equals("https://hq-bus.nwpu.edu.cn/h5/"));
        check("Shanghai weekday selection matches schedule",!CampusBusSchedule.weekend(CampusSummary.date("2026-11-09 08:00")) && CampusBusSchedule.weekend(CampusSummary.date("2026-11-08 08:00")));
        if (args.length>0) {
            JSONObject real=new JSONObject(Files.readString(Paths.get(args[0]))).getJSONObject("modules").getJSONObject("bus");
            check("official weekday rows group into the two directions",CampusBusSchedule.routes(real,false).size()==2);
            check("official weekend rows group into the two directions",CampusBusSchedule.routes(real,true).size()==2);
        }
        System.out.println(count+" checks passed");
    }
}
