package com.nopalou;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/** A filtered display copy; the complete disk snapshot is preserved by CampusStore. */
final class CampusScope {
    static CampusData compact(CampusData old) throws Exception {
        JSONObject json=new JSONObject(old.encode()), modules=json.getJSONObject("modules"), routes=json.optJSONObject("routes");
        JSONObject calendar=modules.optJSONObject("calendar");
        if (!modules.has("bus") && calendar!=null) {
            JSONObject bus=new JSONObject(calendar.toString()); JSONArray links=new JSONArray(), source=calendar.optJSONArray("links");
            for (int i=0;source!=null && i<source.length();i++) if (source.getJSONObject(i).optString("title").matches(".*(?:校车|班车).*$")) links.put(source.getJSONObject(i));
            if (links.length()>0) { bus.put("fields",new JSONArray()).put("tables",new JSONArray()).put("links",links); modules.put("bus",bus); }
        }
        java.util.List<String> remove=new java.util.ArrayList<>();
        for (Iterator<String> it=modules.keys();it.hasNext();) { String id=it.next(); if (!CampusServices.selected(id) && !id.equals("housing")) remove.add(id); }
        for (String id:remove) modules.remove(id);
        JSONObject electric=modules.optJSONObject("electric");
        if (electric!=null) { JSONArray fields=new JSONArray(),source=electric.optJSONArray("fields"); for (int i=0;source!=null && i<source.length();i++) { JSONObject f=source.optJSONObject(i); if (f!=null && f.optString("label").matches(".*(?:电费|电量|余额).*") && !f.optString("label").matches(".*(?:宿舍|房间|楼栋|校区).*$")) fields.put(f); } electric.put("fields",fields); }
        JSONObject housing=modules.optJSONObject("housing");
        if (housing!=null) {
            JSONArray fields=new JSONArray(), source=housing.optJSONArray("fields");
            for (int i=0;source!=null && i<source.length();i++) { JSONObject field=source.getJSONObject(i); if (field.optString("label").replaceAll("[\\s：:]","").matches("住宿校区|宿舍校区|校区|宿舍楼|宿舍楼栋|楼栋|楼号|公寓楼|住宿楼栋|房间号|房间|宿舍号|宿舍房间|寝室号|寝室")) fields.put(field); }
            if (fields.length()==0) modules.remove("housing"); else housing.put("fields",fields).put("tables",new JSONArray()).put("links",new JSONArray());
        }
        remove.clear(); for (Iterator<String> it=routes==null?java.util.Collections.emptyIterator():routes.keys();it.hasNext();) { String id=it.next(); if (!CampusServices.selected(id) && !id.equals("housing")) remove.add(id); }
        for (String id:remove) routes.remove(id);
        json.put("catalog",new JSONArray()); return new CampusData(json.toString());
    }
}
