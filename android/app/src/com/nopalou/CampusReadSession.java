package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Accumulates stable read pages before moving on; no user confirmation is required. */
final class CampusReadSession {
    static final int MAX_ACTIONS=1000;
    private JSONObject result;
    private String fingerprint="";
    private String context="";
    private int stable;
    private boolean limited;
    private boolean incomplete;
    private final Set<String> actions=new HashSet<>();
    private final java.util.LinkedHashMap<String,JSONObject> pending=new java.util.LinkedHashMap<>();
    private final java.util.ArrayDeque<JSONObject> returns=new java.util.ArrayDeque<>();
    boolean stable(JSONObject snapshot,int pending) {
        String current=snapshot.toString();
        if (pending>0 || !current.equals(fingerprint)) { fingerprint=current; stable=0; return false; }
        return ++stable>=1;
    }
    boolean visit(String key) { return !key.isEmpty() && key.length()<2500 && actions.size()<MAX_ACTIONS && actions.add(key); }
    int actions() { return actions.size(); }
    JSONArray visited() { return new JSONArray(actions); }
    String context() { return context; }
    void remember(JSONArray candidates) throws Exception {
        for (int i=0;candidates!=null && i<candidates.length() && pending.size()<1000;i++) {
            JSONObject a=candidates.getJSONObject(i); String key=a.optString("key"), url=a.optString("url");
            if (!key.isEmpty() && key.length()<2500 && CampusServices.readUrl(url) && ("navigate".equals(a.optString("type")) || "detail".equals(a.optString("type"))) && !actions.contains(key)) pending.put(key,new JSONObject(a.toString()));
        }
    }
    JSONObject nextPage() {
        java.util.Iterator<java.util.Map.Entry<String,JSONObject>> it=pending.entrySet().iterator();
        while (it.hasNext()) { java.util.Map.Entry<String,JSONObject> e=it.next(); it.remove(); if (!actions.contains(e.getKey())) return e.getValue(); }
        return null;
    }
    boolean hasPending() { for (String key:pending.keySet()) if (!actions.contains(key)) return true; return false; }
    void openDetail(String url,String detail) throws Exception {
        if (CampusServices.readUrl(url) && returns.size()<12) { returns.push(new JSONObject().put("url",url).put("context",context)); context=detail; }
        else limited=true;
    }
    void openPage() { context=""; }
    JSONObject back() { if (returns.isEmpty()) return null; JSONObject back=returns.pop(); context=back.optString("context"); return back; }
    boolean limited() { return limited || actions.size()>=MAX_ACTIONS; }
    void markIncomplete() { incomplete=true; }
    boolean incomplete() { return incomplete || limited(); }
    void add(JSONObject snapshot) throws Exception {
        CampusData.validateModule(snapshot);
        if (!"ok".equals(snapshot.optString("state")) && !"empty".equals(snapshot.optString("state"))) return;
        String before=result==null?null:result.toString();
        if (result==null) result=new JSONObject(snapshot.toString());
        else {
            JSONArray fields=result.getJSONArray("fields"), incomingFields=snapshot.getJSONArray("fields");
            for (int i=0;i<incomingFields.length();i++) {
                JSONObject f=incomingFields.getJSONObject(i); int at=-1;
                for (int j=0;j<fields.length();j++) if (fields.getJSONObject(j).getString("label").equals(f.getString("label")) && fields.getJSONObject(j).optString("group").equals(f.optString("group")) && fields.getJSONObject(j).optString("record").equals(f.optString("record"))) { at=j; break; }
                if (at>=0) fields.put(at,f); else if (fields.length()<2000) fields.put(f); else limited=true;
            }
            limited|=merge(result.getJSONArray("links"),snapshot.getJSONArray("links"),400);
            JSONArray tables=result.getJSONArray("tables"), incoming=snapshot.getJSONArray("tables");
            for (int i=0;i<incoming.length();i++) {
                JSONObject t=incoming.getJSONObject(i), destination=null;
                for (int j=0;j<tables.length();j++) if (tables.getJSONObject(j).getJSONArray("columns").toString().equals(t.getJSONArray("columns").toString()) && tables.getJSONObject(j).optString("title").equals(t.optString("title"))) { destination=tables.getJSONObject(j); break; }
                if (destination==null) { if (tables.length()<80) tables.put(new JSONObject(t.toString())); else limited=true; }
                else {
                    JSONArray rows=destination.getJSONArray("rows"), fresh=t.getJSONArray("rows");
                    boolean overflow=merge(rows,fresh,10000); limited|=overflow;
                    destination.put("truncated",destination.optBoolean("truncated") || t.optBoolean("truncated") || overflow);
                }
            }
            if (CampusData.count(result)>0) result.put("state","ok");
        }
        JSONArray tables=result.getJSONArray("tables"); for (int i=0;i<tables.length();i++) limited|=tables.getJSONObject(i).optBoolean("truncated");
        if (result.toString().length()>4_000_000) { result=before==null?null:new JSONObject(before); limited=true; }
        fingerprint=""; stable=0;
    }
    private static boolean merge(JSONArray into,JSONArray from,int max) throws Exception {
        Set<String> seen=new HashSet<>(); for (int i=0;i<into.length();i++) seen.add(into.get(i).toString());
        boolean overflow=false;
        for (int i=0;i<from.length();i++) if (seen.add(from.get(i).toString())) { if (into.length()<max) into.put(from.get(i)); else overflow=true; }
        return overflow;
    }
    boolean hasResult() { return result!=null; }
    JSONObject result() throws Exception { return result==null?null:new JSONObject(result.toString()); }
}
