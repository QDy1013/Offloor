package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Match only explicit personal housing fields to live school options. Never guess a room. */
final class CampusBindings {
    static JSONObject match(CampusData data,JSONArray options,int level) {
        if (data==null || level<0 || level>2) return null;
        Set<String> hints=new HashSet<>();
        for (String module:new String[]{"housing","affairs"}) {
            JSONObject m=data.module(module);
            if (m==null || !"ok".equals(m.optString("state"))) continue;
            JSONArray fields=m.optJSONArray("fields");
            for (int i=0;fields!=null && i<fields.length();i++) {
                JSONObject f=fields.optJSONObject(i); if (f==null) continue;
                String label=f.optString("label").replaceAll("[\\s：:]","");
                boolean fits=level==0?label.matches("住宿校区|宿舍校区") || (module.equals("housing") && label.equals("校区")):
                    level==1?label.matches("宿舍楼|宿舍楼栋|楼栋|公寓楼|楼号|住宿楼栋"):label.matches("房间号|宿舍号|寝室号|房间|宿舍房间|寝室");
                if (fits && !f.optString("value").isEmpty()) hints.add(normalize(f.optString("value"),level));
            }
        }
        if (hints.size()!=1) return null;
        String hint=hints.iterator().next(); JSONObject found=null;
        for (int i=0;i<options.length();i++) {
            JSONObject option=options.optJSONObject(i); if (option==null) continue;
            if (hint.equals(normalize(option.optString("name"),level))) { if (found!=null) return null; found=option; }
        }
        return found;
    }
    private static String normalize(String value,int level) {
        String v=value.replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT);
        if (level==0) return v.replaceFirst("校区$","");
        return v.replaceFirst(level==1?"(?:号楼|号栋|栋|楼)$":"(?:号房|房间|室|号)$","");
    }
}
