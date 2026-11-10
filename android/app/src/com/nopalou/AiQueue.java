package com.nopalou;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 送去上一级复核的排队：落盘，进程被杀、断网、等充电都不丢。
 * 两种条目：type=esc（一条消息或一张卡），type=img（一张待识别的图片，文件在 cache/ai-img）。
 */
final class AiQueue {
    static final String FILE = "ai_queue.json";
    static final int MAX = 300;
    static final int MAX_TRIES = 4;

    private static List<JSONObject> items;

    private AiQueue() { }

    private static StateStore storage;

    private static List<JSONObject> load(Context c) {
        if (items != null) return items;
        storage = new StateStore(new File(c.getApplicationContext().getFilesDir(), FILE));
        StateStore.Loaded loaded = storage.load(bytes -> decode(bytes));
        items = new ArrayList<>();
        try {
            if (loaded.bytes != null) items.addAll(decode(loaded.bytes));
            for (JSONObject item : items) AiQueueCore.prepare(item, System.currentTimeMillis());
        } catch (Exception error) { throw new IllegalStateException("AI 队列读取失败，原文件已保留", error); }
        if (loaded.message != null) EngineHolder.diag(c, "队列", loaded.message);
        return items;
    }

    private static List<JSONObject> decode(byte[] bytes) throws JSONException {
        JSONArray data = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
        if (data.length() > AiQueueCore.MAX) throw new JSONException("队列数量超过上限");
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String type = item.optString("type");
            if ((!"esc".equals(type) && !"img".equals(type)) || item.optString("key").isEmpty())
                throw new JSONException("队列条目无效");
            result.add(item);
        }
        return result;
    }

    private static void store(Context c) {
        try {
            JSONArray data = new JSONArray(); for (JSONObject item : items) data.put(item);
            storage.write(data.toString().getBytes(StandardCharsets.UTF_8), bytes -> decode(bytes));
        } catch (Exception error) {
            EngineHolder.diag(c, "队列", "AI 队列保存失败，旧文件保留；可在 AI 设置中清空队列后重试");
            throw new IllegalStateException("AI 队列保存失败", error);
        }
    }

    static synchronized int size(Context c) {
        int count = 0;
        for (JSONObject item : load(c)) if (!"failed".equals(item.optString("state"))) count++;
        return count;
    }
    static synchronized int failedCount(Context c) {
        int count = 0; for (JSONObject item : load(c)) if ("failed".equals(item.optString("state"))) count++;
        return count;
    }
    static synchronized String failureSummary(Context c) {
        StringBuilder out=new StringBuilder(); int shown=0;
        for(JSONObject item:load(c)) {
            if(!"failed".equals(item.optString("state")))continue;
            if(shown++>=5)break;
            out.append("\n· ").append("img".equals(item.optString("type"))?"图片":"文字复核").append("：")
                    .append(item.optString("error","请求失败，请检查连接或设置"));
        }
        return out.toString();
    }
    static synchronized boolean storageBlocked(Context c) { load(c); return storage.blocked(); }
    static synchronized int retryFailed(Context c) {
        try { int count = AiQueueCore.retry(load(c), System.currentTimeMillis()); store(c); return count; }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }
    static synchronized void add(Context c, JSONObject item) {
        try {
            List<JSONObject> list = load(c);
            List<JSONObject> removed = AiQueueCore.add(list, item, System.currentTimeMillis());
            store(c);
            for (JSONObject old : removed) deleteImage(c, old);
        } catch (JSONException error) { throw new IllegalArgumentException(error); }
    }
    static synchronized List<JSONObject> peek(Context c, String type, int n) {
        List<JSONObject> list = load(c), result = new ArrayList<>();
        try { if (AiQueueCore.expire(list, System.currentTimeMillis())) store(c); }
        catch (JSONException error) { throw new IllegalStateException(error); }
        for (JSONObject item : list) {
            if (type.equals(item.optString("type")) && "queued".equals(item.optString("state"))) result.add(item);
            if (result.size() >= n) break;
        }
        return result;
    }
    static synchronized void remove(Context c, Collection<JSONObject> done) {
        List<JSONObject> removed = AiQueueCore.remove(load(c), done);
        store(c);
        for (JSONObject item : removed) deleteImage(c, item);
    }
    static synchronized void failed(Context c, Collection<JSONObject> batch) {
        try { AiQueueCore.failed(load(c), batch); store(c); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }
    static synchronized void park(Context c,Collection<JSONObject> batch,String reason) {
        java.util.Set<String> ids=new java.util.HashSet<>();for(JSONObject item:batch)ids.add(item.optString("requestId"));
        try {
            for(JSONObject item:load(c)) if(ids.contains(item.optString("requestId"))) item.put("state","failed").put("error",reason);
            store(c);
        } catch(JSONException error) { throw new IllegalStateException(error); }
    }
    static synchronized void imageUnavailable(Context c,Collection<JSONObject> batch) {
        try { AiQueueCore.imageUnavailable(load(c),batch);store(c); }
        catch(JSONException error) { throw new IllegalStateException(error); }
    }
    static synchronized void clear(Context c) {
        List<JSONObject> all = new ArrayList<>(load(c)); items.clear();
        try { storage.allowRebuild(); } catch (Exception error) { throw new IllegalStateException(error); }
        store(c);
        for (JSONObject item : all) deleteImage(c, item);
        cleanupImages(c);
    }
    /** 只处理本应用图片缓存；孤立图片一天后删除，失败图片最多保留七天。 */
    static synchronized void cleanupImages(Context c) {
        if(storageBlocked(c)) return;
        long now=System.currentTimeMillis(); boolean changed=false;
        for(JSONObject item:load(c)) {
            if(!"img".equals(item.optString("type")) || now-item.optLong("createdAt",now)<7L*86400000L) continue;
            String path=item.optString("path",""); if(path.isEmpty()) continue;
            try {
                item.put("state","failed").put("imageExpired",true).put("error","图片缓存已过期，请重新分享截图");
                item.remove("path"); changed=true;
            } catch(JSONException ignored) { }
        }
        if(changed) store(c);
        File directory=new File(c.getApplicationContext().getCacheDir(),"ai-img");
        File[] files=directory.listFiles(); if(files==null) return;
        for(File file:files) {
            if(!file.isFile() || now-file.lastModified()<86400000L) continue;
            boolean referenced=false;
            for(JSONObject item:items) if(file.getAbsolutePath().equals(item.optString("path"))) { referenced=true;break; }
            if(!referenced) try { if(directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) file.delete(); } catch(IOException ignored) { }
        }
    }
    private static void deleteImage(Context c, JSONObject item) {
        String path = item == null ? null : item.optString("path", null);
        if (path == null || path.isEmpty()) return;
        for (JSONObject live : items) if (path.equals(live.optString("path"))) return;
        try {
            File file = new File(path).getCanonicalFile();
            File directory = new File(c.getApplicationContext().getCacheDir(), "ai-img").getCanonicalFile();
            if (directory.equals(file.getParentFile())) file.delete();
        } catch (IOException ignored) { }
    }

    // ---------- 序列化 ----------

    static JSONObject msgJson(Engine.Msg m) throws JSONException {
        return new JSONObject()
                .put("id", m.id).put("groupId", m.groupId).put("groupName", m.groupName)
                .put("sender", m.sender).put("text", m.text).put("pkg", m.pkg).put("ts", m.ts).put("timeUncertain", m.timeUncertain);
    }

    static Engine.Msg msgFrom(JSONObject o) {
        Engine.Msg m = new Engine.Msg(o.optString("id"), o.optString("groupId"), o.optString("groupName"),
                o.optString("sender"), o.optString("text"), o.optString("pkg"), o.optLong("ts"));
        m.timeUncertain = o.optBoolean("timeUncertain", false);
        return m;
    }

    static JSONObject fromEscalation(Engine.Escalation e) throws JSONException {
        JSONArray ctx = new JSONArray();
        for (String s : e.context) ctx.put(s);
        return new JSONObject()
                .put("type", "esc")
                .put("key", e.cardId != null ? "card:" + e.cardId : "msg:" + e.msg.id)
                .put("cardId", e.cardId == null ? JSONObject.NULL : e.cardId)
                .put("epoch", e.epoch).put("cardState", e.cardState)
                .put("scopeVersion", e.scopeVersion).put("profileKey", e.profileKey)
                .put("msg", msgJson(e.msg))
                .put("label", e.label).put("prob", e.prob).put("reason", e.reason)
                .put("context", ctx);
    }

    static Engine.Escalation toEscalation(JSONObject o) {
        Engine.Escalation e = new Engine.Escalation();
        e.epoch = o.optString("epoch", null);
        e.cardState = o.optString("cardState", null);
        e.scopeVersion = o.optString("scopeVersion", null);
        e.profileKey = o.optString("profileKey", null);
        e.cardId = o.isNull("cardId") ? null : o.optString("cardId", null);
        JSONObject m = o.optJSONObject("msg");
        e.msg = m == null ? new Engine.Msg("", "", "", "", "", "", 0) : msgFrom(m);
        e.label = o.optString("label");
        e.prob = o.optDouble("prob", 0);
        e.reason = o.optString("reason");
        JSONArray ctx = o.optJSONArray("context");
        if (ctx != null) for (int i = 0; i < ctx.length(); i++) e.context.add(ctx.optString(i));
        return e;
    }

}
