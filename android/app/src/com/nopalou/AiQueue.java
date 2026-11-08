package com.nopalou;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
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

    private static AtomicFile file(Context c) {
        return new AtomicFile(new File(c.getApplicationContext().getFilesDir(), FILE));
    }

    private static List<JSONObject> load(Context c) {
        if (items != null) return items;
        items = new ArrayList<>();
        try (FileInputStream in = file(c).openRead()) {
            JSONArray arr = new JSONArray(readAll(in));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) items.add(o);
            }
        } catch (FileNotFoundException first) {
            // 还没排过队
        } catch (Exception broken) {
            items.clear();
        }
        return items;
    }

    private static void store(Context c) {
        AtomicFile f = file(c);
        FileOutputStream out = null;
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : items) arr.put(o);
            out = f.startWrite();
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (Exception ex) {
            if (out != null) f.failWrite(out);
        }
    }

    static synchronized int size(Context c) {
        return load(c).size();
    }

    static synchronized void add(Context c, JSONObject item) {
        List<JSONObject> list = load(c);
        String key = item.optString("key");
        for (JSONObject o : list) if (key.equals(o.optString("key"))) return;   // 同一条不重复排
        list.add(item);
        while (list.size() > MAX) {
            JSONObject old = list.remove(0);
            deleteImage(old);
        }
        store(c);
    }

    /** 取前 n 条某类条目（不移除，处理完再 remove，中途被杀下次接着做）。 */
    static synchronized List<JSONObject> peek(Context c, String type, int n) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : load(c)) {
            if (type.equals(o.optString("type"))) out.add(o);
            if (out.size() >= n) break;
        }
        return out;
    }

    static synchronized void remove(Context c, Collection<JSONObject> done) {
        List<JSONObject> list = load(c);
        for (JSONObject d : done) {
            String key = d.optString("key");
            for (int i = list.size() - 1; i >= 0; i--) {
                if (key.equals(list.get(i).optString("key"))) {
                    deleteImage(list.remove(i));
                }
            }
        }
        store(c);
    }

    /** 失败一次记一次；超过上限的直接丢掉。 */
    static synchronized void failed(Context c, Collection<JSONObject> batch) {
        List<JSONObject> drop = new ArrayList<>();
        for (JSONObject o : batch) {
            int tries = o.optInt("tries", 0) + 1;
            try { o.put("tries", tries); } catch (JSONException ignored) { }
            if (tries >= MAX_TRIES) drop.add(o);
        }
        if (!drop.isEmpty()) remove(c, drop); else store(c);
    }

    static synchronized void clear(Context c) {
        for (JSONObject o : load(c)) deleteImage(o);
        items.clear();
        store(c);
    }

    private static void deleteImage(JSONObject o) {
        String path = o == null ? null : o.optString("path", null);
        if (path != null && !path.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        }
    }

    // ---------- 序列化 ----------

    static JSONObject msgJson(Engine.Msg m) throws JSONException {
        return new JSONObject()
                .put("id", m.id).put("groupId", m.groupId).put("groupName", m.groupName)
                .put("sender", m.sender).put("text", m.text).put("pkg", m.pkg).put("ts", m.ts);
    }

    static Engine.Msg msgFrom(JSONObject o) {
        return new Engine.Msg(o.optString("id"), o.optString("groupId"), o.optString("groupName"),
                o.optString("sender"), o.optString("text"), o.optString("pkg"), o.optLong("ts"));
    }

    static JSONObject fromEscalation(Engine.Escalation e) throws JSONException {
        JSONArray ctx = new JSONArray();
        for (String s : e.context) ctx.put(s);
        return new JSONObject()
                .put("type", "esc")
                .put("key", e.cardId != null ? "card:" + e.cardId : "msg:" + e.msg.id)
                .put("cardId", e.cardId == null ? JSONObject.NULL : e.cardId)
                .put("msg", msgJson(e.msg))
                .put("label", e.label).put("prob", e.prob).put("reason", e.reason)
                .put("context", ctx);
    }

    static Engine.Escalation toEscalation(JSONObject o) {
        Engine.Escalation e = new Engine.Escalation();
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

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
