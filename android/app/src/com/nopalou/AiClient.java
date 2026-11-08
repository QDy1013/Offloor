package com.nopalou;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 上一级识别：OpenAI 兼容的 /chat/completions。
 * judge：对拿不准的消息/卡片做分类复判 + 字段补全（方案书里第二级、第三级的职责）；
 * ocr：把聊天截图逐条转写成消息（截图 OCR 兜底）。
 * 只在后台线程调用。
 */
final class AiClient {

    /** 密钥错误、余额不足、模型名错：重试也没用。 */
    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    /** 这一批内容被拒（例如内容审核）：丢掉这批，别的照常。 */
    static final class BadRequestException extends IOException {
        BadRequestException(String m) { super(m); }
    }

    static final String TIME_FMT = "yyyy-MM-dd HH:mm";

    private final AiConfig cfg;

    AiClient(AiConfig cfg) { this.cfg = cfg; }

    // ---------- 复判 / 补全 ----------

    static final String JUDGE_SYSTEM =
            "你是“楼外”的复核模块。楼外读取大学生手机上的群聊通知，把需要本人去做的事变成待办卡片。"
            + "本机规则已经处理过这些消息，但对它们拿不准，需要你复核。\n"
            + "对每条消息判断：它是否要求“我”（这位学生）在某个时间前做某件事，或参加某场考试/测验。\n"
            + "判为 todo=false 的情况：提问、闲聊、讨论、单纯告知（没有要我做的动作）、已经过去的事、"
            + "明确只针对别人（点名其他人或其他班/组，且与我的班级、小组、课程无关）、广告。\n"
            + "字段要求：\n"
            + "- kind：考试/测验/答辩用 exam，其余用 todo\n"
            + "- title：不超过 16 个字，动词开头，写清做什么，例如“提交数据库实验报告”；考试写“XX考试”\n"
            + "- deadline：绝对时间，格式 yyyy-MM-dd HH:mm，按消息发送时间换算“明天/周五/下周三”等；"
            + "只给日期没给时刻的提交类截止写 23:59；考试写开始时间；完全没有时间写空串\n"
            + "- time_sure：原文时间明确为 true；只能推测（如“下课前”“这几天”）为 false\n"
            + "- place：地点（教室、线上平台），没有写空串\n"
            + "- channel：提交到哪里（邮箱、学习通、班长、群文件等），没有写空串\n"
            + "- bring：考试要带的东西，没有写空串\n"
            + "- confidence：0~1，你对 todo 判断的把握\n"
            + "- note：不超过 20 字的理由\n"
            + "只输出 JSON：{\"items\":[{\"id\":\"…\",\"todo\":true,\"kind\":\"todo\",\"title\":\"…\",\"deadline\":\"…\","
            + "\"time_sure\":true,\"place\":\"\",\"channel\":\"\",\"bring\":\"\",\"confidence\":0.9,\"note\":\"…\"}]}，"
            + "每条输入消息都要有一项，id 原样返回。";

    List<Engine.AiVerdict> judge(List<Engine.Escalation> items, Engine.Profile profile, long now) throws IOException {
        List<Engine.AiVerdict> out = new ArrayList<>();
        if (items.isEmpty()) return out;
        try {
            JSONObject req = new JSONObject();
            req.put("now", fmt(now));
            JSONObject me = new JSONObject();
            if (profile != null) {
                if (!Engine.blank(profile.className)) me.put("class", profile.className.trim());
                if (!Engine.blank(profile.groupNo)) me.put("group", profile.groupNo.trim());
                JSONArray courses = new JSONArray();
                for (String c : Engine.clean(profile.courses)) courses.put(c);
                if (courses.length() > 0) me.put("courses", courses);
            }
            req.put("me", me);
            JSONArray arr = new JSONArray();
            Map<String, Engine.Escalation> byId = new HashMap<>();
            for (int i = 0; i < items.size(); i++) {
                Engine.Escalation e = items.get(i);
                String id = String.valueOf(i + 1);
                byId.put(id, e);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("send_time", fmt(e.msg.ts) + " 周" + "一二三四五六日".charAt(weekdayIndex(e.msg.ts)));
                o.put("group", nz(e.msg.groupName));
                o.put("sender", nz(e.msg.sender));
                o.put("text", clip(e.msg.text, 1200));
                JSONArray ctx = new JSONArray();
                for (String s : e.context) ctx.put(clip(s, 120));
                if (ctx.length() > 0) o.put("earlier_messages", ctx);
                o.put("local_guess", e.label);
                arr.put(o);
            }
            req.put("messages", arr);
            JSONArray msgs = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", JUDGE_SYSTEM))
                    .put(new JSONObject().put("role", "user").put("content", req.toString()));
            JSONObject res = new JSONObject(stripFence(chat(cfg.model, msgs, 1500, true)));
            JSONArray got = res.optJSONArray("items");
            if (got == null) throw new IOException("AI 返回格式不对");
            for (int i = 0; i < got.length(); i++) {
                JSONObject o = got.optJSONObject(i);
                if (o == null) continue;
                Engine.Escalation e = byId.get(o.optString("id"));
                if (e == null) continue;
                Engine.AiVerdict v = new Engine.AiVerdict();
                v.cardId = e.cardId;
                v.msg = e.cardId == null ? e.msg : null;
                v.todo = o.optBoolean("todo", false);
                v.kind = "exam".equals(o.optString("kind")) ? "exam" : "todo";
                v.title = o.optString("title", "");
                v.deadline = parseTime(o.optString("deadline", ""));
                v.timeSure = o.optBoolean("time_sure", false);
                v.place = o.optString("place", "");
                v.channel = o.optString("channel", "");
                v.bring = o.optString("bring", "");
                v.confidence = Math.max(0, Math.min(1, o.optDouble("confidence", 0.5)));
                v.note = o.optString("note", "");
                out.add(v);
            }
            return out;
        } catch (JSONException ex) {
            throw new IOException("AI 返回内容无法解析", ex);
        }
    }

    // ---------- 截图转写 ----------

    static final String OCR_PROMPT =
            "这是一张手机截图，通常是群聊、班级通知或学习平台页面。请把里面的消息逐条转写出来。\n"
            + "要求：只转写，不总结不改写；保留原文里的日期、时间、地点、链接文字；"
            + "忽略状态栏、输入框、按钮、头像；一条消息换行的部分合并成一条。\n"
            + "只输出 JSON：{\"group\":\"截图顶部的群名或页面标题，看不出写空串\","
            + "\"messages\":[{\"sender\":\"发送者，看不出写空串\",\"time\":\"截图里这条消息附近显示的时间原文，没有写空串\","
            + "\"text\":\"消息原文\"}]}";

    static final class Shot {
        String group = "";
        final List<String[]> messages = new ArrayList<>();   // {sender, time, text}
    }

    Shot ocr(byte[] jpeg) throws IOException {
        try {
            JSONArray content = new JSONArray()
                    .put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", "data:image/jpeg;base64,"
                                    + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))))
                    .put(new JSONObject().put("type", "text").put("text", OCR_PROMPT));
            JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
            JSONObject res = new JSONObject(stripFence(chat(cfg.vision, msgs, 2500, false)));
            Shot s = new Shot();
            s.group = res.optString("group", "").trim();
            JSONArray arr = res.optJSONArray("messages");
            if (arr != null) {
                for (int i = 0; i < arr.length() && i < 60; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String text = o.optString("text", "").trim();
                    if (text.isEmpty()) continue;
                    s.messages.add(new String[]{o.optString("sender", "").trim(), o.optString("time", "").trim(), text});
                }
            }
            return s;
        } catch (JSONException ex) {
            throw new IOException("截图识别结果无法解析", ex);
        }
    }

    /** 设置页“测试连接”：发一句最短的请求。 */
    String ping() throws IOException {
        try {
            JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user")
                    .put("content", "只回复 JSON：{\"ok\":true}"));
            return chat(cfg.model, msgs, 20, true);
        } catch (JSONException ex) {
            throw new IOException(ex);
        }
    }

    // ---------- HTTP ----------

    private String chat(String model, JSONArray messages, int maxTokens, boolean jsonMode) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.1)
                .put("max_tokens", maxTokens);
        if (jsonMode) body.put("response_format", new JSONObject().put("type", "json_object"));
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.base + "/chat/completions").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(jsonMode ? 60000 : 90000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + cfg.key);
        c.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 401 || code == 403) throw new AuthException("接口拒绝访问（" + code + "），请检查密钥");
            if (code == 404) throw new AuthException("接口地址或模型名不对（404）：" + clip(errorMessage(text), 80));
            // 400 多半是这一批内容本身的问题（内容审核拦截、图片太大），丢掉这一批即可，不必停掉整条队列
            if (code == 400) throw new BadRequestException("接口拒绝了这批内容：" + clip(errorMessage(text), 80));
            if (code == 402) throw new AuthException("接口额度不足（402）");
            if (code != 200) throw new IOException("接口暂时不可用（" + code + "）");
            JSONObject res = new JSONObject(text);
            JSONArray choices = res.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IOException("AI 没有返回内容");
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            String content = msg == null ? "" : msg.optString("content", "");
            if (content.trim().isEmpty()) throw new IOException("AI 返回为空");
            return content;
        } finally {
            c.disconnect();
        }
    }

    private static String errorMessage(String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONObject err = o.optJSONObject("error");
            if (err != null) return err.optString("message", body);
            return o.optString("message", body);
        } catch (JSONException e) {
            return body;
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = s.read(b)) != -1) {
                if (out.size() + n > 2_000_000) throw new IOException("AI 返回过大");
                out.write(b, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------- 工具 ----------

    /** 有的模型会把 JSON 包在 ```json 代码块里，或前后带一句话。 */
    static String stripFence(String s) {
        String v = s == null ? "" : s.trim();
        int a = v.indexOf('{'), b = v.lastIndexOf('}');
        return a >= 0 && b > a ? v.substring(a, b + 1) : v;
    }

    static String fmt(long ts) {
        return new SimpleDateFormat(TIME_FMT, Locale.CHINA).format(new Date(ts));
    }

    static long parseTime(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        String v = s.trim().replace('T', ' ').replace('/', '-');
        if (v.length() > 16) v = v.substring(0, 16);
        try {
            SimpleDateFormat f = new SimpleDateFormat(TIME_FMT, Locale.CHINA);
            f.setLenient(false);
            return f.parse(v).getTime();
        } catch (ParseException e) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
                f.setLenient(false);
                return f.parse(v.length() > 10 ? v.substring(0, 10) : v).getTime() + (23 * 60 + 59) * 60_000L;
            } catch (ParseException e2) {
                return 0;
            }
        }
    }

    static int weekdayIndex(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static String nz(String s) { return s == null ? "" : s; }

    static String clip(String s, int n) {
        String v = nz(s);
        return v.length() > n ? v.substring(0, n) : v;
    }
}
