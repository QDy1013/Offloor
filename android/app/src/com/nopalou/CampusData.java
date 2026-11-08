package com.nopalou;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;

/** Bounded snapshots. Failed reads keep the last successful content and its timestamp. */
final class CampusData {
    static final int MAX_BYTES = 2_000_000;
    final JSONObject json, modules, routes;
    final String owner;
    CampusData(String value) throws JSONException {
        if (value == null || value.length() > MAX_BYTES) throw new JSONException("校园数据过大");
        json = new JSONObject(value);
        if (json.getInt("version") != 1 || !"西北工业大学".equals(json.getString("school"))) throw new JSONException("校园数据格式不受支持");
        owner = string(json, "owner", 64);
        if (!owner.isEmpty() && !owner.matches("[a-f0-9]{64}")) throw new JSONException("账号标记无效");
        modules = json.optJSONObject("modules") == null ? new JSONObject() : json.getJSONObject("modules");
        routes = json.optJSONObject("routes") == null ? new JSONObject() : json.getJSONObject("routes");
        for (Iterator<String> it = modules.keys(); it.hasNext();) {
            String id = it.next(); if (CampusServices.byId(id) == null) throw new JSONException("未知校园模块");
            validateModule(modules.getJSONObject(id));
        }
        for (Iterator<String> it = routes.keys(); it.hasNext();) {
            String id = it.next(); if (CampusServices.byId(id) == null || !CampusServices.readUrl(routes.getString(id))) throw new JSONException("校园入口无效");
        }
    }
    static String string(JSONObject j, String key, int max) throws JSONException {
        Object v = j.opt(key); if (v == null || v == JSONObject.NULL) return "";
        if (!(v instanceof String) || ((String) v).length() > max) throw new JSONException("校园数据字段无效");
        return ((String) v).trim();
    }
    private static JSONArray array(JSONObject j, String key, int max) throws JSONException {
        JSONArray a = j.optJSONArray(key);
        if (a == null || a.length() > max) throw new JSONException("校园数据列表无效"); return a;
    }
    static void validateModule(JSONObject m) throws JSONException {
        String state = string(m, "state", 30);
        if (!java.util.Arrays.asList("ok", "login", "binding", "network", "unavailable", "unsupported").contains(state)) throw new JSONException("同步状态无效");
        String src = string(m, "source", 2000); if (!src.isEmpty() && !CampusServices.readUrl(src)) throw new JSONException("数据来源无效");
        JSONArray fields = array(m, "fields", 80), tables = array(m, "tables", 12), links = array(m, "links", 40);
        for (int i=0; i<fields.length(); i++) {
            JSONObject f = fields.getJSONObject(i);
            if (string(f, "label", 80).isEmpty() || string(f, "value", 800).isEmpty()) throw new JSONException("字段缺失");
        }
        for (int i=0; i<tables.length(); i++) {
            JSONObject t = tables.getJSONObject(i); string(t, "title", 100);
            JSONArray cols = array(t, "columns", 12), rows = array(t, "rows", 200);
            if (cols.length() == 0) throw new JSONException("表格缺少列");
            for (int c=0; c<cols.length(); c++) if (!(cols.get(c) instanceof String) || cols.getString(c).length() > 600) throw new JSONException("表头无效");
            for (int r=0; r<rows.length(); r++) {
                JSONArray row = rows.getJSONArray(r); if (row.length() != cols.length()) throw new JSONException("表格列数不一致");
                for (int c=0; c<row.length(); c++) if (!(row.get(c) instanceof String) || row.getString(c).length() > 600) throw new JSONException("单元格无效");
            }
        }
        for (int i=0; i<links.length(); i++) {
            JSONObject l = links.getJSONObject(i);
            if (string(l, "title", 180).isEmpty() || !CampusServices.readUrl(string(l, "url", 2000))) throw new JSONException("公告链接无效");
        }
        if ("ok".equals(state) && count(m) == 0) throw new JSONException("空页面不能标为同步成功");
        if (m.optLong("updated", 0) < 0 || m.optLong("attempted", 0) < 0) throw new JSONException("时间无效");
    }
    static int count(JSONObject m) {
        if (m == null) return 0;
        JSONArray f = m.optJSONArray("fields"), t = m.optJSONArray("tables"), l = m.optJSONArray("links");
        int n = (f == null ? 0 : f.length()) + (l == null ? 0 : l.length());
        for (int i=0; t != null && i<t.length(); i++) { JSONArray rows = t.optJSONObject(i) == null ? null : t.optJSONObject(i).optJSONArray("rows"); if (rows != null) n += rows.length(); }
        return n;
    }
    static String accountKey(String identity) throws Exception {
        if (!identity.matches("\\d{1,20}")) throw new JSONException("学生身份无效");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(("nwpu:" + identity).getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    static CampusData merge(CampusData previous, String fresh, long now, String expectedOwner) throws Exception {
        JSONObject f = new JSONObject(fresh);
        if (f.getInt("version") != 1 || !"西北工业大学".equals(f.getString("school"))) throw new JSONException("校园数据格式无效");
        String owner = previous == null ? "" : previous.owner;
        if (expectedOwner != null && !expectedOwner.equals(owner)) throw new JSONException("账号已切换，本次结果失效");
        String identity = string(f, "identity", 20);
        if (!identity.isEmpty()) owner = accountKey(identity);
        boolean changed = previous != null && !owner.equals(previous.owner);
        JSONObject next = previous == null || changed ? new JSONObject("{\"version\":1,\"school\":\"西北工业大学\",\"modules\":{},\"routes\":{}}") : new JSONObject(previous.encode());
        next.put("owner", owner);
        JSONObject modules = next.getJSONObject("modules"), routes = next.getJSONObject("routes"), incoming = f.optJSONObject("modules");
        for (Iterator<String> it = incoming == null ? java.util.Collections.emptyIterator() : incoming.keys(); it.hasNext();) {
            String id = it.next(); if (CampusServices.byId(id) == null) throw new JSONException("未知校园模块");
            JSONObject m = new JSONObject(incoming.getJSONObject(id).toString()); validateModule(m);
            if ("ok".equals(m.getString("state"))) { m.put("updated", now); modules.put(id, m); }
            else {
                JSONObject old = modules.optJSONObject(id);
                if (old != null && count(old) > 0) { old.put("state", m.getString("state")); old.put("attempted", now); }
                else { m.put("attempted", now); modules.put(id, m); }
            }
        }
        JSONObject discovered = f.optJSONObject("routes");
        for (Iterator<String> it = discovered == null ? java.util.Collections.emptyIterator() : discovered.keys(); it.hasNext();) {
            String id = it.next(); if (CampusServices.byId(id) != null && CampusServices.readUrl(discovered.optString(id))) routes.put(id, discovered.getString(id));
        }
        return new CampusData(next.toString());
    }
    JSONObject module(String id) { return modules.optJSONObject(id); }
    String route(CampusServices.Service s) { return routes.optString(s.id, s.entry); }
    String encode() { return json.toString(); }
}
