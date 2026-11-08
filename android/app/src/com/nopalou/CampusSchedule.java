package com.nopalou;
import java.net.URI;
import java.util.List;
import java.util.Set;

/** Pick an available host; electricity waits only for its own housing dependencies. */
final class CampusSchedule {
    static String host(CampusServices.Service service,CampusData data) {
        try { String host=new URI(data==null?service.entry:data.route(service)).getHost(); return service.id.equals("_academic")?host+"#history":host; } catch (Exception e) { return ""; }
    }
    static int next(List<CampusServices.Service> waiting,Set<String> busy,Set<String> done,CampusData data) {
        for (int i=0;i<waiting.size();i++) {
            CampusServices.Service service=waiting.get(i);
            try {
                String host=host(service,data);
                if (busy.contains(host)) continue;
                if (service.id.equals("electric") && (!done.contains("housing") || !done.contains("affairs"))) continue;
                return i;
            } catch (Exception ignored) { }
        }
        return -1;
    }
}
