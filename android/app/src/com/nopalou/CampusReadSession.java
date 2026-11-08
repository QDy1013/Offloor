package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Accumulates stable read pages before moving on; no user confirmation is required. */
final class CampusReadSession {
    private JSONObject result;
    private String fingerprint="";
    private int stable;
    private final Set<String> actions=new HashSet<>();
    boolean stable(JSONObject snapshot,int pending) {
        String current=snapshot.toString();
        if (pending>0 || !current.equals(fingerprint)) { fingerprint=current; stable=0; return false; }
        return ++stable>=1;
    }
    boolean visit(String key) { return key.length()<2500 && actions.size()<40 && actions.add(key); }
    int actions() { return actions.size(); }
    JSONArray visited() { return new JSONArray(actions); }
    void add(JSONObject snapshot) throws Exception {
        CampusData.validateModule(snapshot);
        if (!"ok".equals(snapshot.optString("state")) && !"empty".equals(snapshot.optString("state"))) return;
        if (result==null) result=new JSONObject(snapshot.toString());
        else {
            JSONArray fields=result.getJSONArray("fields"), incomingFields=snapshot.getJSONArray("fields");
            for (int i=0;i<incomingFields.length();i++) {
                JSONObject f=incomingFields.getJSONObject(i); int at=-1;
                for (int j=0;j<fields.length();j++) if (fields.getJSONObject(j).getString("label").equals(f.getString("label"))) { at=j; break; }
                if (at>=0) fields.put(at,f); else if (fields.length()<80) fields.put(f);
            }
            merge(result.getJSONArray("links"),snapshot.getJSONArray("links"),40);
            JSONArray tables=result.getJSONArray("tables"), incoming=snapshot.getJSONArray("tables");
            for (int i=0;i<incoming.length();i++) {
                JSONObject t=incoming.getJSONObject(i), destination=null;
                for (int j=0;j<tables.length();j++) if (tables.getJSONObject(j).getJSONArray("columns").toString().equals(t.getJSONArray("columns").toString()) && tables.getJSONObject(j).optString("title").equals(t.optString("title"))) { destination=tables.getJSONObject(j); break; }
                if (destination==null) { if (tables.length()<12) tables.put(new JSONObject(t.toString())); }
                else {
                    JSONArray rows=destination.getJSONArray("rows"), fresh=t.getJSONArray("rows"); int before=rows.length();
                    merge(rows,fresh,2000);
                    destination.put("truncated",destination.optBoolean("truncated") || t.optBoolean("truncated") || (rows.length()==2000 && before+fresh.length()>2000));
                }
            }
            if (CampusData.count(result)>0) result.put("state","ok");
        }
        fingerprint=""; stable=0;
    }
    private static void merge(JSONArray into,JSONArray from,int max) throws Exception {
        Set<String> seen=new HashSet<>(); for (int i=0;i<into.length();i++) seen.add(into.get(i).toString());
        for (int i=0;i<from.length() && into.length()<max;i++) if (seen.add(from.get(i).toString())) into.put(from.get(i));
    }
    boolean hasResult() { return result!=null; }
    JSONObject result() throws Exception { return result==null?null:new JSONObject(result.toString()); }
}
