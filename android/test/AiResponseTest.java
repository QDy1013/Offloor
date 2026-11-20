package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** 直接测试外部模型响应的解析边界；需要 Android SDK 与 org.json 的 JVM 实现。 */
public final class AiResponseTest {
    static int checks;
    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    static List<Engine.AiVerdict> parse(Map<String, Engine.Escalation> requests, String item) throws Exception {
        return AiClient.parseVerdicts(new JSONObject("{\"items\":[" + item + "]}"), requests);
    }
    public static void main(String[] args) throws Exception {
        Engine engine;
        try (InputStream in = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            engine = new Engine(in);
        }
        List<Engine.Escalation> sent = new ArrayList<>();
        engine.escalator = sent::add;
        engine.process(new Engine.Msg("source", "group", "大学物理课程群", "王老师",
                "@所有人 请大家尽快提交实验报告", "com.tencent.mm", 1800000000000L));
        check("铺垫排队卡片", engine.all().size() == 1 && sent.size() == 1);
        Map<String, Engine.Escalation> requests = new HashMap<>(); requests.put("1", sent.get(0));
        check("缺 todo 不当成删除", parse(requests, "{\"id\":\"1\",\"confidence\":0.99}").isEmpty());
        check("字符串判定不当成布尔", parse(requests, "{\"id\":\"1\",\"todo\":\"false\",\"confidence\":0.99}").isEmpty());
        check("缺把握不采用", parse(requests, "{\"id\":\"1\",\"todo\":false}").isEmpty());
        check("越界把握不采用", parse(requests, "{\"id\":\"1\",\"todo\":false,\"confidence\":9}").isEmpty());
        check("无限把握不采用", parse(requests, "{\"id\":\"1\",\"todo\":false,\"confidence\":\"Infinity\"}").isEmpty());
        check("未知编号不采用", parse(requests, "{\"id\":\"2\",\"todo\":false,\"confidence\":0.99}").isEmpty());
        List<Engine.AiVerdict> valid = parse(requests, "{\"id\":\"1\",\"todo\":false,\"confidence\":0.99}");
        check("有效结果保留本机快照", valid.size() == 1
                && sent.get(0).epoch.equals(valid.get(0).epoch)
                && sent.get(0).cardState.equals(valid.get(0).cardState));
        JSONObject stored = AiQueue.fromEscalation(sent.get(0));
        Engine.Escalation restored = AiQueue.toEscalation(new JSONObject(stored.toString()));
        check("排队序列化保留校验信息", restored.epoch.equals(sent.get(0).epoch)
                && restored.cardState.equals(sent.get(0).cardState));
        sent.get(0).epoch = null;
        check("旧版本无快照请求保留本机结果", parse(requests,
                "{\"id\":\"1\",\"todo\":false,\"confidence\":0.99}").isEmpty());
        sent.get(0).epoch = engine.aiEpoch();
        String hallucination="{\"id\":\"1\",\"todo\":true,\"confidence\":0.99,\"deadline\":\"2027-01-16 18:00\",\"time_sure\":true,\"place\":\"教学楼101\",\"channel\":\"某邮箱\"}";
        Engine.AiVerdict ungrounded=parse(requests,hallucination).get(0);
        check("原文没有的地点和渠道不写入",ungrounded.place.isEmpty()&&ungrounded.channel.isEmpty());
        check("模型自报确定不代替原文时间依据",!ungrounded.timeSure);
        check("重复编号的矛盾响应不应用",parse(requests,hallucination+","+hallucination).isEmpty());
        check("非法时刻不降级为同日深夜",AiClient.parseTime("2026-11-09 25:30")==0);
        check("日期尾随内容不作为合法时间",AiClient.parseTime("2026-11-09 arbitrary")==0);
        check("无时刻的完整日期按日末",AiClient.fmt(AiClient.parseTime("2026-11-09")).equals("2026-11-09 23:59"));
        check("同账号的快照可见",AccountFiles.visible(new JSONObject().put(AccountFiles.OWNER,"a"),"a"));
        check("账号切换后旧快照立即不可见",!AccountFiles.visible(new JSONObject().put(AccountFiles.OWNER,"a"),"b"));
        engine.setTimetableCourses(Collections.singletonList("大学物理（Ⅰ）"));
        JSONObject req = AiClient.buildJudgeRequest(sent, engine.effectiveProfile(), true, 1800000000000L, null);
        check("课表课程注入 AI 请求画像", req.getJSONObject("me").getJSONArray("courses").toString().contains("大学物理"));
        check("AI 请求附带匹配到的课程名", "大学物理".equals(req.getJSONArray("messages").getJSONObject(0).optString("matched_course")));
        // 端侧小模型非对称权限护栏：允许补全已有卡片，禁止对未出卡消息凭空新建待办
        Engine.Escalation existingEsc = sent.get(0);
        existingEsc.profileKey = engine.profileKey();
        Engine.Escalation uncarded = new Engine.Escalation();
        uncarded.epoch = engine.aiEpoch();
        uncarded.profileKey = engine.profileKey();
        uncarded.scopeVersion = existingEsc.scopeVersion;
        uncarded.msg = new Engine.Msg("joke1", "group", "大学物理课程群", "同学甲",
                "哈哈哈交了个寂寞", "com.tencent.mm", 1800000005000L);
        Map<String, Engine.Escalation> localMap = new HashMap<>();
        localMap.put("1", existingEsc);
        localMap.put("2", uncarded);
        List<Engine.AiVerdict> localVerdicts = AiClient.parseVerdicts(new JSONObject(
                "{\"items\":["
                        + "{\"id\":\"1\",\"todo\":true,\"kind\":\"todo\",\"title\":\"提交物理实验报告\",\"deadline\":\"\",\"time_sure\":false,\"place\":\"\",\"channel\":\"\",\"bring\":\"\",\"confidence\":0.92,\"note\":\"整理标题\"},"
                        + "{\"id\":\"2\",\"todo\":true,\"kind\":\"todo\",\"title\":\"交寂寞\",\"deadline\":\"\",\"time_sure\":false,\"place\":\"\",\"channel\":\"\",\"bring\":\"\",\"confidence\":0.95,\"note\":\"误判\"}"
                        + "]}"), localMap, true);
        check("端侧复核标记 fromLocalModel", localVerdicts.size() == 2 && localVerdicts.get(0).fromLocalModel && localVerdicts.get(1).fromLocalModel);
        boolean existingUpdated = engine.applyAi(localVerdicts.get(0), 1800000010000L);
        boolean uncardedBlocked = !engine.applyAi(localVerdicts.get(1), 1800000010000L);
        check("端侧小模型允许补全已有待确认卡标题", existingUpdated && "提交物理实验报告".equals(engine.all().get(0).title));
        check("端侧小模型禁止对未出卡消息凭空新建待办", uncardedBlocked && engine.all().size() == 1);
        System.out.println("PASS " + checks);
    }
}
