package com.nopalou;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同集端云/端侧大模型对照评测桥接器（纯 JVM，直接复用 APK 的 Engine、AiClient、LocalLlmCore）。
 *
 * 用法：
 *   1. export <model.tsv> <strict_ood.jsonl> <out.json>
 *      运行纯本机 Engine，导出纯本机卡片、升级列表、以及按 APK 真实格式构造的 hybrid/direct 批次请求（含 GBNF 语法）。
 *   2. apply <model.tsv> <strict_ood.jsonl> <responses.json> <out.json>
 *      将推理端返回的每批原始文本喂回 APK 原版 LocalLlmCore.stripThink -> AiClient.stripFence
 *      -> AiClient.parseVerdicts -> Engine.applyAi，输出逐条复核明细与最终卡片。
 */
public final class JavaAiEval {

    private static final String DIRECT_SYSTEM = AiClient.JUDGE_SYSTEM.replace(
            "你是“楼外”的复核模块。楼外读取大学生手机上的群聊通知，把需要本人去做的事变成待办卡片。"
                    + "本机规则已经处理过这些消息，但对它们拿不准，需要你复核。",
            "你是“楼外”的群消息待办抽取模块。楼外读取大学生手机上的群聊通知，把需要本人去做的事变成待办卡片。"
                    + "请直接判断下面每一条群聊消息。"
    );

    private static final class Row {
        String id, ts, groupId, groupName, sender, text;
        long millis;
    }

    private static List<Row> loadRows(Path path) throws IOException, JSONException {
        ZoneId zone = ZoneId.systemDefault();
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            JSONObject o = new JSONObject(line);
            Row r = new Row();
            r.id = o.getString("id");
            r.ts = o.getString("ts");
            r.groupId = o.optString("group_id", "");
            r.groupName = o.optString("group_name", "");
            r.sender = o.optString("sender", "");
            r.text = o.optString("text", "");
            r.millis = LocalDateTime.parse(r.ts).atZone(zone).toInstant().toEpochMilli();
            rows.add(r);
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.millis).thenComparing(r -> r.id));
        return rows;
    }

    private static String iso(long millis) {
        if (millis <= 0) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }

    private static JSONArray serializeCards(Engine engine) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Engine.Card c : engine.all()) {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("kind", c.kind);
            o.put("source_id", c.sourceId == null ? "" : c.sourceId);
            o.put("title", c.title == null ? "" : c.title);
            o.put("deadline", iso(c.deadline));
            o.put("needs_confirm", c.needsConfirm);
            o.put("done", c.done);
            o.put("cancelled", c.cancelled);
            o.put("ai_checked", c.aiChecked);
            o.put("place", c.place == null ? "" : c.place);
            o.put("channel", c.channel == null ? "" : c.channel);
            o.put("bring", c.bring == null ? "" : c.bring);
            o.put("reason", c.reason == null ? "" : c.reason);
            arr.put(o);
        }
        return arr;
    }

    private static final class RunState {
        Engine engine;
        List<Engine.Escalation> escalations = new ArrayList<>();
        Set<String> jielongHits = new HashSet<>();
        List<Engine.Msg> messages = new ArrayList<>();
    }

    private static RunState runLocal(Path modelPath, List<Row> rows) throws Exception {
        RunState st = new RunState();
        try (InputStream in = Files.newInputStream(modelPath)) {
            st.engine = new Engine(in);
        }
        st.engine.setProfile(Engine.Profile.demo());
        st.engine.escalator = st.escalations::add;
        for (Row r : rows) {
            Engine.Msg m = new Engine.Msg(r.id, r.groupId, r.groupName, r.sender, r.text, "eval", r.millis);
            st.messages.add(m);
            st.engine.process(m);
            if (Engine.isJielong(r.text) && st.engine.all().stream().anyMatch(
                    c -> "jielong".equals(c.kind) && r.id.equals(c.sourceId))) {
                st.jielongHits.add(r.id);
            }
        }
        return st;
    }

    private static JSONArray buildBatches(List<Engine.Escalation> items, Engine.Profile profile,
                                          String systemPrompt, boolean local) throws JSONException {
        JSONArray batches = new JSONArray();
        int step = LocalLlmCore.BATCH;
        for (int start = 0; start < items.size(); start += step) {
            int end = Math.min(items.size(), start + step);
            List<Engine.Escalation> sub = items.subList(start, end);
            long batchNow = sub.get(sub.size() - 1).msg.ts;
            JSONObject req = AiClient.buildJudgeRequest(sub, profile, local, batchNow, null);
            JSONObject b = new JSONObject();
            b.put("batch_index", batches.length());
            b.put("size", sub.size());
            b.put("system", systemPrompt);
            b.put("user", req.toString());
            b.put("grammar", LocalLlmCore.judgeGrammar(sub.size()));
            JSONArray ids = new JSONArray();
            JSONArray idxs = new JSONArray();
            for (int k = 0; k < sub.size(); k++) {
                ids.put(sub.get(k).msg.id);
                idxs.put(start + k);
            }
            b.put("source_ids", ids);
            b.put("escalation_indices", idxs);
            batches.put(b);
        }
        return batches;
    }

    private static void doExport(Path modelPath, Path oodPath, Path outPath) throws Exception {
        List<Row> rows = loadRows(oodPath);
        RunState st = runLocal(modelPath, rows);
        JSONObject out = new JSONObject();
        out.put("n_in", st.engine.nIn);
        out.put("n_noise", st.engine.nNoise);
        out.put("n_level3", st.engine.nLevel3);
        out.put("n_escalations", st.escalations.size());
        out.put("batch_size", LocalLlmCore.BATCH);
        out.put("local_cards", serializeCards(st.engine));
        out.put("jielong_hits", new JSONArray(st.jielongHits));

        JSONArray escArr = new JSONArray();
        for (int i = 0; i < st.escalations.size(); i++) {
            Engine.Escalation e = st.escalations.get(i);
            JSONObject eo = new JSONObject();
            eo.put("index", i);
            eo.put("source_id", e.msg.id);
            eo.put("card_id", e.cardId == null ? "" : e.cardId);
            eo.put("reason", e.reason == null ? "" : e.reason);
            eo.put("label", e.label == null ? "" : e.label);
            eo.put("prob", e.prob);
            eo.put("sender", e.msg.sender);
            eo.put("group_name", e.msg.groupName);
            eo.put("text", e.msg.text);
            eo.put("ts", iso(e.msg.ts));
            escArr.put(eo);
        }
        out.put("escalations", escArr);
        out.put("hybrid_batches", buildBatches(st.escalations, st.engine.effectiveProfile(), AiClient.JUDGE_SYSTEM, true));
        // v0.86 端侧非对称权限护栏：本机小模型只接收已有待确认卡（cardId != null）进行保守补全，跳过未出卡复判
        List<Engine.Escalation> localGuarded = new ArrayList<>();
        for (Engine.Escalation e : st.escalations) {
            if (e.cardId != null) localGuarded.add(e);
        }
        out.put("n_local_guarded_escalations", localGuarded.size());
        out.put("hybrid_local_guarded_batches", buildBatches(localGuarded, st.engine.effectiveProfile(), AiClient.JUDGE_SYSTEM, true));

        // 构造纯大模型直抽批次（100 条全送，不经过本机筛选，不泄露 local_guess）
        List<Engine.Escalation> allDirect = new ArrayList<>();
        Map<String, List<String>> ctxByGroup = new HashMap<>();
        for (Engine.Msg m : st.messages) {
            Engine.Escalation e = new Engine.Escalation();
            e.msg = m;
            e.label = "";
            e.context = new ArrayList<>(ctxByGroup.getOrDefault(m.groupId, new ArrayList<>()));
            allDirect.add(e);
            ctxByGroup.computeIfAbsent(m.groupId, k -> new ArrayList<>()).add(m.sender + ": " + m.text);
        }
        out.put("direct_batches", buildBatches(allDirect, st.engine.effectiveProfile(), DIRECT_SYSTEM, true));

        Files.writeString(outPath, out.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static void doApply(Path modelPath, Path oodPath, Path respPath, Path outPath, boolean localGuarded) throws Exception {
        List<Row> rows = loadRows(oodPath);
        RunState st = runLocal(modelPath, rows);
        List<Engine.Escalation> targets = new ArrayList<>();
        for (Engine.Escalation e : st.escalations) {
            if (!localGuarded || e.cardId != null) targets.add(e);
        }
        JSONObject respObj = new JSONObject(Files.readString(respPath, StandardCharsets.UTF_8));
        JSONArray batchOutputs = respObj.getJSONArray("batch_outputs");

        int step = LocalLlmCore.BATCH;
        int jsonValidBatches = 0;
        int rawItemsReturned = 0;
        int parsedVerdicts = 0;
        int appliedVerdicts = 0;
        JSONArray parseErrors = new JSONArray();
        JSONArray trace = new JSONArray();

        Map<String, Engine.Card> beforeBySource = new HashMap<>();
        for (Engine.Card c : st.engine.all()) {
            if (c.sourceId != null) beforeBySource.put(c.sourceId, c);
        }

        int batchIdx = 0;
        for (int start = 0; start < targets.size(); start += step, batchIdx++) {
            int end = Math.min(targets.size(), start + step);
            List<Engine.Escalation> sub = targets.subList(start, end);
            long batchNow = sub.get(sub.size() - 1).msg.ts;
            Map<String, Engine.Escalation> byId = new HashMap<>();
            AiClient.buildJudgeRequest(sub, st.engine.effectiveProfile(), true, batchNow, byId);
            String raw = batchIdx < batchOutputs.length() ? batchOutputs.optString(batchIdx, "") : "";
            String cleaned = AiClient.stripFence(LocalLlmCore.stripThink(raw));

            List<Engine.AiVerdict> verdicts = new ArrayList<>();
            String err = null;
            try {
                JSONObject parsed = new JSONObject(cleaned);
                jsonValidBatches++;
                JSONArray arr = parsed.optJSONArray("items");
                if (arr != null) rawItemsReturned += arr.length();
                verdicts = AiClient.parseVerdicts(parsed, byId, localGuarded);
                parsedVerdicts += verdicts.size();
            } catch (Exception ex) {
                err = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                parseErrors.put(new JSONObject().put("batch_index", batchIdx).put("error", err));
            }

            Map<String, Engine.AiVerdict> verdictBySource = new HashMap<>();
            for (Engine.AiVerdict v : verdicts) {
                String sid = v.msg != null ? v.msg.id : null;
                if (sid == null && v.cardId != null) {
                    for (Engine.Escalation e : sub) {
                        if (v.cardId.equals(e.cardId)) { sid = e.msg.id; break; }
                    }
                }
                if (sid != null) verdictBySource.put(sid, v);
                boolean applied = st.engine.applyAi(v, batchNow);
                if (applied) appliedVerdicts++;
                if (sid != null) {
                    Engine.Card after = null;
                    for (Engine.Card c : st.engine.all()) {
                        if (sid.equals(c.sourceId)) { after = c; break; }
                    }
                    JSONObject tr = new JSONObject();
                    tr.put("source_id", sid);
                    tr.put("had_card_before", beforeBySource.containsKey(sid));
                    tr.put("verdict_todo", v.todo);
                    tr.put("verdict_kind", v.kind);
                    tr.put("verdict_conf", v.confidence);
                    tr.put("verdict_deadline", iso(v.deadline));
                    tr.put("verdict_time_sure", v.timeSure);
                    tr.put("verdict_note", v.note == null ? "" : v.note);
                    tr.put("applied", applied);
                    tr.put("has_card_after", after != null);
                    tr.put("after_deadline", after == null ? "" : iso(after.deadline));
                    tr.put("after_needs_confirm", after != null && after.needsConfirm);
                    trace.put(tr);
                }
            }
            for (Engine.Escalation e : sub) {
                if (!verdictBySource.containsKey(e.msg.id)) {
                    JSONObject tr = new JSONObject();
                    tr.put("source_id", e.msg.id);
                    tr.put("had_card_before", beforeBySource.containsKey(e.msg.id));
                    tr.put("verdict_missing", true);
                    tr.put("batch_error", err == null ? "invalid_or_missing_item" : err);
                    trace.put(tr);
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("total_batches", batchIdx);
        out.put("total_escalations", targets.size());
        out.put("local_guarded", localGuarded);
        out.put("json_valid_batches", jsonValidBatches);
        out.put("raw_items_returned", rawItemsReturned);
        out.put("parsed_verdicts", parsedVerdicts);
        out.put("applied_verdicts", appliedVerdicts);
        out.put("parse_errors", parseErrors);
        out.put("trace", trace);
        out.put("final_cards", serializeCards(st.engine));
        out.put("jielong_hits", new JSONArray(st.jielongHits));
        Files.writeString(outPath, out.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if ("export".equals(mode)) {
            doExport(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
        } else if ("apply".equals(mode)) {
            boolean guarded = args.length > 5 && "local_guarded".equals(args[5]);
            doApply(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]), Paths.get(args[4]), guarded);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }
}
