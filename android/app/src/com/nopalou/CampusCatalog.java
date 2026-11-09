package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Services observed in this account's portal, beyond the built-in adapters. */
final class CampusCatalog {
    static final int MAX=120;
    static String id(String title,String entry) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest((title+"\n"+entry).getBytes(StandardCharsets.UTF_8));
        StringBuilder value=new StringBuilder("portal_"); for (int i=0;i<10;i++) value.append(String.format(java.util.Locale.ROOT,"%02x",bytes[i]&255)); return value.toString();
    }
    static JSONArray normalize(JSONArray incoming) throws Exception {
        JSONArray result=new JSONArray(); java.util.Set<String> seen=new java.util.HashSet<>();
        for (int i=0;incoming!=null && i<incoming.length();i++) {
            JSONObject row=incoming.optJSONObject(i); if (row==null) continue;
            String name=CampusData.string(row,"title",80), entry=CampusData.string(row,"url",2000);
            if (name.isEmpty() || !CampusServices.readUrl(entry)) continue;
            String key=id(name,entry);
            if (seen.add(key) && result.length()<MAX) result.put(new JSONObject().put("id",key).put("title",name).put("url",entry));
        }
        return result;
    }
    static void validate(JSONArray catalog) throws Exception {
        if (catalog.length()>MAX) throw new IllegalArgumentException("校园服务目录过大");
        java.util.Set<String> seen=new java.util.HashSet<>();
        for (int i=0;i<catalog.length();i++) {
            JSONObject row=catalog.getJSONObject(i); String name=CampusData.string(row,"title",80),entry=CampusData.string(row,"url",2000);
            if (name.isEmpty() || !CampusServices.readUrl(entry) || !id(name,entry).equals(row.getString("id")) || !seen.add(row.getString("id"))) throw new IllegalArgumentException("校园服务目录无效");
        }
    }
    static CampusServices.Service find(JSONArray catalog,String id) {
        for (int i=0;catalog!=null && i<catalog.length();i++) {
            JSONObject row=catalog.optJSONObject(i);
            if (row!=null && row.optString("id").equals(id)) return new CampusServices.Service(id,row.optString("title"),"从本人门户发现 · 自动读取可见信息",row.optString("url"),false);
        }
        return null;
    }
    static List<CampusServices.Service> services(JSONArray catalog) {
        List<CampusServices.Service> result=new ArrayList<>();
        for (int i=0;catalog!=null && i<catalog.length();i++) { CampusServices.Service s=find(catalog,catalog.optJSONObject(i).optString("id")); if (s!=null) result.add(s); }
        return result;
    }
}
