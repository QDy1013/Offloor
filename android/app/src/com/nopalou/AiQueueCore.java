package com.nopalou;

import org.json.*;
import java.util.*;

/** 请求版本与重试状态；不依赖 Android，旧请求不能移除或增加新请求的失败次数。 */
final class AiQueueCore {
    static final long TTL_MS = 48L * 60 * 60 * 1000;
    static final int MAX = 300, MAX_TRIES = 4;
    private AiQueueCore() { }

    static void prepare(JSONObject item, long now) throws JSONException {
        if (item.optString("requestId").isEmpty()) item.put("requestId", UUID.randomUUID().toString());
        if (!item.has("createdAt")) item.put("createdAt", now);
        if (!item.has("state")) item.put("state", "queued");
    }
    static boolean equivalent(JSONObject a, JSONObject b) {
        for (String key : new String[]{"epoch", "scopeVersion", "profileKey", "cardState", "path", "reason"})
            if (!Objects.equals(a.opt(key), b.opt(key))) return false;
        return Objects.equals(String.valueOf(a.opt("msg")), String.valueOf(b.opt("msg")));
    }
    static List<JSONObject> add(List<JSONObject> list, JSONObject item, long now) throws JSONException {
        if (item.optString("key").isEmpty()) throw new JSONException("请求编号缺失");
        List<JSONObject> removed = new ArrayList<>();
        prepare(item, now);
        for (int i = 0; i < list.size(); i++) {
            JSONObject old = list.get(i);
            if (!item.optString("key").equals(old.optString("key"))) continue;
            if (equivalent(old, item) && "queued".equals(old.optString("state"))) return removed;
            list.set(i, item); removed.add(old); return removed;
        }
        list.add(item);
        while (list.size() > MAX) {
            int index = 0;
            for (int i = 0; i < list.size(); i++) if ("failed".equals(list.get(i).optString("state"))) { index = i; break; }
            removed.add(list.remove(index));
        }
        return removed;
    }
    static List<JSONObject> remove(List<JSONObject> list, Collection<JSONObject> done) {
        Set<String> ids = new HashSet<>();
        for (JSONObject item : done) if (!item.optString("requestId").isEmpty()) ids.add(item.optString("requestId"));
        List<JSONObject> removed = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) if (ids.contains(list.get(i).optString("requestId"))) removed.add(list.remove(i));
        return removed;
    }
    static void failed(List<JSONObject> list, Collection<JSONObject> batch) throws JSONException {
        Set<String> ids = new HashSet<>();
        for (JSONObject item : batch) ids.add(item.optString("requestId"));
        for (JSONObject current : list) if (ids.contains(current.optString("requestId"))) {
            int tries = current.optInt("tries", 0) + 1; current.put("tries", tries);
            if (tries >= MAX_TRIES) current.put("state", "failed").put("error", "多次请求失败，已停止自动重试");
        }
    }
    static boolean expire(List<JSONObject> list, long now) throws JSONException {
        boolean changed = false;
        for (JSONObject item : list) {
            if (!"queued".equals(item.optString("state"))) continue;
            if (now - item.optLong("createdAt", now) > TTL_MS) {
                item.put("state", "failed").put("error", "请求已超过两天，请核对后重试"); changed = true;
            }
        }
        return changed;
    }
    static int retry(List<JSONObject> list, long now) throws JSONException {
        int count = 0;
        for (JSONObject item : list) if ("failed".equals(item.optString("state")) && !item.optBoolean("imageExpired",false)) {
            item.put("requestId", UUID.randomUUID().toString()).put("tries", 0).put("createdAt", now)
                    .put("state", "queued").remove("error"); count++;
        }
        return count;
    }
}
