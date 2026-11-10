package com.nopalou;

import org.json.*;
import java.util.*;

/** 从实际请求处理顺序验证替换、完成、重试与过期。 */
public final class AiQueueTest {
    static int count;
    static void check(String label, boolean ok) { if (!ok) throw new AssertionError(label); count++; System.out.println("PASS " + label); }
    static JSONObject request(String version) throws Exception {
        return new JSONObject().put("key", "card:c1").put("type", "esc").put("epoch", "epoch")
                .put("cardState", version).put("msg", new JSONObject().put("id", "m1"));
    }
    public static void main(String[] args) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        JSONObject first = request("1"); AiQueueCore.add(list, first, 1000);
        check("新请求有独立编号与时间", !first.optString("requestId").isEmpty() && first.optLong("createdAt") == 1000);
        JSONObject equivalent = request("1"); AiQueueCore.add(list, equivalent, 1100);
        check("相同版本去重保留原请求", list.size() == 1 && list.get(0) == first);
        JSONObject latest = request("2"); AiQueueCore.add(list, latest, 1200);
        check("新版本替换旧请求", list.size() == 1 && list.get(0) == latest
                && !latest.optString("requestId").equals(first.optString("requestId")));
        AiQueueCore.remove(list, Arrays.asList(first));
        check("旧请求完成不移除新请求", list.size() == 1 && list.get(0) == latest);
        AiQueueCore.failed(list, Arrays.asList(first));
        check("旧请求失败不影响新请求次数", latest.optInt("tries", 0) == 0);
        for (int i=0; i<AiQueueCore.MAX_TRIES; i++) AiQueueCore.failed(list, Arrays.asList(latest));
        check("重试耗尽保留失败记录", list.size() == 1 && "failed".equals(latest.optString("state")));
        String id = latest.optString("requestId"); AiQueueCore.retry(list, 2000);
        check("手动重试新建请求身份", !id.equals(latest.optString("requestId")) && latest.optInt("tries") == 0);
        AiQueueCore.expire(list, 2000 + AiQueueCore.TTL_MS + 1);
        check("过期请求转为可见失败", "failed".equals(latest.optString("state")) && !latest.optString("error").isEmpty());
        List<JSONObject> capacity = new ArrayList<>();
        for (int i=0;i<AiQueueCore.MAX+1;i++) AiQueueCore.add(capacity, request("v").put("key", "msg:"+i), i+1);
        check("队列上限受控", capacity.size() == AiQueueCore.MAX && !"msg:0".equals(capacity.get(0).optString("key")));
        List<JSONObject> images=new ArrayList<>();
        JSONObject oldImage=new JSONObject().put("type","img").put("key","img:one").put("path","old.jpg");
        AiQueueCore.add(images,oldImage,3000);
        JSONObject newImage=new JSONObject().put("type","img").put("key","img:one").put("path","new.jpg");
        AiQueueCore.add(images,newImage,3100);
        AiQueueCore.imageUnavailable(images,Arrays.asList(oldImage));
        check("旧图片缓存失效不误伤替换请求",!newImage.optBoolean("imageExpired")&&"queued".equals(newImage.optString("state")));
        AiQueueCore.imageUnavailable(images,Arrays.asList(newImage));
        check("缓存丢失保留可见失败及补充路径",images.size()==1&&"failed".equals(newImage.optString("state"))&&newImage.optString("error").contains("重新分享"));
        check("缺失图片不会反复重试空路径",AiQueueCore.retry(images,3200)==0&&"failed".equals(newImage.optString("state")));
        System.out.println("PASS " + count);
    }
}
