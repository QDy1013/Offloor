package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 课表课程属于 AI 请求上下文；课程变化后旧结果不得新增或移除卡片。 */
public final class Step63Test {
    private static String root;
    private static int checks, failures;
    private static final long NOW = 1800000000000L;

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        checks++;
        if (!ok) failures++;
    }

    private static Engine fresh() throws Exception {
        Engine engine;
        try (FileInputStream model = new FileInputStream(root + "/app/assets/model.tsv")) {
            engine = new Engine(model);
        }
        Engine.Profile profile = new Engine.Profile();
        profile.name = "测试同学";
        profile.courses = new ArrayList<>(Collections.singletonList("手动课程"));
        engine.setProfile(profile);
        return engine;
    }

    private static Engine.Msg message(String id) {
        return new Engine.Msg(id, "physics", "大学物理课程群", "王老师",
                "明天18点前提交大学物理实验报告到学习通", "com.tencent.mm", NOW);
    }

    private static Engine.Escalation queueMessage(Engine engine, String id) {
        List<Engine.Escalation> requests = new ArrayList<>();
        engine.escalator = requests::add;
        engine.escalateMessage(message(id), "notice", .5, "low_conf");
        return requests.get(0);
    }

    private static Engine.Escalation queueCard(Engine engine, Engine.Card card) {
        List<Engine.Escalation> requests = new ArrayList<>();
        engine.escalator = requests::add;
        engine.escalateCard(card, message(card.sourceId), "extract");
        return requests.get(0);
    }

    private static Engine.AiVerdict verdict(Engine.Escalation request, boolean todo) {
        Engine.AiVerdict verdict = new Engine.AiVerdict();
        verdict.epoch = request.epoch;
        verdict.profileKey = request.profileKey;
        verdict.scopeVersion = request.scopeVersion;
        verdict.scopeGroup = request.msg.groupId;
        verdict.cardId = request.cardId;
        verdict.cardState = request.cardState;
        verdict.msg = request.cardId == null ? request.msg : null;
        verdict.todo = todo;
        verdict.title = "提交大学物理实验报告";
        verdict.deadline = NOW + 86400000L;
        verdict.timeSure = true;
        verdict.confidence = .95;
        return verdict;
    }

    private static void replaceCourses(Engine engine, boolean clear) {
        engine.setTimetableCourses(clear ? null : Collections.singletonList("编译原理"));
    }

    private static void checkChangedContext(boolean clear) throws Exception {
        String action = clear ? "clearing timetable" : "replacing timetable";
        Engine add = fresh();
        String savedProfile = add.getProfile().encode();
        add.setTimetableCourses(Collections.singletonList("大学物理"));
        Engine.Escalation oldAdd = queueMessage(add, "pending-add");
        check(action + ": original message request is valid", add.currentRequest(oldAdd));
        replaceCourses(add, clear);
        check(action + ": old message request is stale", !add.currentRequest(oldAdd));
        check(action + ": old verdict cannot add a card",
                !add.applyAi(verdict(oldAdd, true), NOW + 1) && add.all().isEmpty());
        Engine.Escalation newAdd = queueMessage(add, "fresh-add");
        check(action + ": new request can add a card",
                add.currentRequest(newAdd) && add.applyAi(verdict(newAdd, true), NOW + 2)
                        && add.all().size() == 1);
        check(action + ": saved profile is unchanged", savedProfile.equals(add.getProfile().encode()));

        Engine drop = fresh();
        drop.setTimetableCourses(Collections.singletonList("大学物理"));
        drop.process(message("existing-card"));
        if (drop.all().size() != 1) throw new AssertionError("Expected one local task card");
        Engine.Card card = drop.all().get(0);
        Engine.Escalation oldDrop = queueCard(drop, card);
        check(action + ": original card request is valid", drop.currentRequest(oldDrop));
        replaceCourses(drop, clear);
        check(action + ": old card request is stale", !drop.currentRequest(oldDrop));
        check(action + ": old verdict cannot remove a card",
                !drop.applyAi(verdict(oldDrop, false), NOW + 1) && drop.cardById(card.id) == card);
        Engine.Escalation newDrop = queueCard(drop, card);
        check(action + ": new request can remove a card",
                drop.currentRequest(newDrop) && drop.applyAi(verdict(newDrop, false), NOW + 2)
                        && drop.cardById(card.id) == null);
    }

    public static void main(String[] args) throws Exception {
        root = args[0];
        checkChangedContext(false);
        checkChangedContext(true);

        Engine repeated = fresh();
        String savedProfile = repeated.getProfile().encode();
        List<String> courses = new ArrayList<>(Arrays.asList("大学物理（Ⅰ）", "编译原理"));
        repeated.setTimetableCourses(courses);
        Engine.Escalation request = queueMessage(repeated, "repeat");
        courses.clear();
        check("caller changes cannot mutate installed course context", repeated.currentRequest(request)
                && repeated.effectiveProfile().courses.contains("大学物理"));
        repeated.setTimetableCourses(Arrays.asList(" 编译原理 ", "大学物理（Ⅰ）", "大学物理（Ⅰ）"));
        check("equivalent repeated sync preserves queued request", repeated.currentRequest(request));
        check("equivalent repeated sync preserves valid verdict", repeated.applyAi(verdict(request, true), NOW + 1));
        check("imported courses do not alter persisted manual profile", savedProfile.equals(repeated.getProfile().encode())
                && repeated.getProfile().courses.equals(Collections.singletonList("手动课程")));

        Engine original = fresh();
        original.setTimetableCourses(Arrays.asList("大学物理（Ⅰ）", "编译原理"));
        Engine.Escalation persisted = queueMessage(original, "persisted");
        ByteArrayOutputStream state = new ByteArrayOutputStream();
        original.saveState(state);
        Engine restored = fresh();
        restored.setTimetableCourses(Arrays.asList("编译原理", "大学物理（Ⅰ）"));
        restored.loadState(new ByteArrayInputStream(state.toByteArray()));
        check("restart with same profile and timetable preserves queued request", restored.currentRequest(persisted));
        check("restart with same context accepts queued result", restored.applyAi(verdict(persisted, true), NOW + 1));
        for (boolean clear : new boolean[]{false, true}) {
            Engine changed = fresh();
            replaceCourses(changed, clear);
            changed.loadState(new ByteArrayInputStream(state.toByteArray()));
            check("restart with " + (clear ? "cleared" : "different") + " timetable rejects old request",
                    !changed.currentRequest(persisted));
            check("restart with " + (clear ? "cleared" : "different") + " timetable rejects old result",
                    !changed.applyAi(verdict(persisted, true), NOW + 1) && changed.all().isEmpty());
        }
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
