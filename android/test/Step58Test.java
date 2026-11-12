package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

/** Manual cards use the normal archive, new-card tracking and reminder pipeline. */
public final class Step58Test {
    private static int checks;
    private static final long NOW = 1800000000000L;
    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label); checks++;
    }
    private static void rejects(String label, Runnable operation) {
        try { operation.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { checks++; System.out.println("PASS " + label); }
    }
    public static void main(String[] args) throws Exception {
        Engine engine;
        try (java.io.InputStream model = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) { engine = new Engine(model); }
        final Engine e = engine;
        int[] changes = {0}, reviews = {0};
        engine.listener = () -> changes[0]++;
        engine.escalator = request -> reviews[0]++;
        long revision = engine.stateRevision();
        rejects("blank title is rejected", () -> e.addManualCard(" \n ", 0, "", "", NOW));
        rejects("negative deadline is rejected", () -> e.addManualCard("事项", -1, "", "", NOW));
        rejects("invalid creation time is rejected", () -> e.addManualCard("事项", 0, "", "", 0));
        rejects("oversize title is rejected without silent truncation", () -> e.addManualCard("a".repeat(201), 0, "", "", NOW));
        check("rejected writes leave state unchanged", engine.all().isEmpty() && engine.stateRevision() == revision && changes[0] == 0);
        Engine.Card card = engine.addManualCard("  自己的事项  ", NOW + 3600000, "  图书馆  ", "第一行\n第二行\t备注", NOW);
        check("editable fields are preserved", "自己的事项".equals(card.displayTitle()) && "图书馆".equals(card.place) && card.manualNotes.contains("\n第二行\t"));
        check("manual origin is explicit and does not pretend to be a chat", "manual".equals(card.sourcePackageName()) && "手动添加".equals(card.sourceAppName()) && engine.groupKeyOf(card) == null);
        check("specific time is confirmed and not AI checked", card.deadlineUserSet && !card.needsConfirm && !card.aiChecked);
        check("change listeners and archive dirty state are notified", changes[0] == 1 && engine.hasStateChanges());
        check("source trace records manual creation", TaskTrace.text(card).contains("手动创建任务") && TaskTrace.text(card).contains("本人"));
        check("normal reminder pipeline accepts manual card", ReminderPlan.create(engine.all(), Collections.emptyMap(), NOW).due.contains(card));
        NewCardSession session = new NewCardSession(Collections.emptySet()); session.observe(Collections.singletonList(card.id));
        check("manual cards appear in new-card visit", session.isNew(card.id));
        check("search includes current notes and source", engine.queryCards("第二行 手动添加", Engine.CardScope.ALL, 10, NOW).contains(card));
        String original = card.sourceText;
        check("manual card can be edited", engine.editManualCard(card, "修改后的事项", 0, "教学楼", "新的备注", NOW + 1));
        check("editing preserves origin and records current fields", original.equals(card.sourceText) && "教学楼".equals(card.place) && "新的备注".equals(card.manualNotes));
        check("unset time remains visible but is not reminded", card.needsConfirm && card.deadline == 0 && ReminderPlan.create(engine.all(), Collections.emptyMap(), NOW).due.isEmpty());
        check("AI backfill does not alter user authored cards", engine.backfillReviews() == 0 && reviews[0] == 0);
        check("current notes replace old notes in search", engine.queryCards("新的备注", Engine.CardScope.ALL, 10, NOW).contains(card) && !engine.queryCards("第二行", Engine.CardScope.ALL, 10, NOW).contains(card));
        Engine.Card twin = engine.addManualCard("修改后的事项", 0, null, null, NOW + 2);
        check("same-title manual tasks are independent", !card.id.equals(twin.id) && engine.all().size() == 2);
        Engine.Card literal = engine.addManualCard("提交给老师", 0, "", "不要重新推断我填写的标题", NOW + 3);
        ByteArrayOutputStream archive = new ByteArrayOutputStream(); engine.saveState(archive);
        Engine restored;
        try (java.io.InputStream model = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) { restored = new Engine(model); }
        restored.loadState(new ByteArrayInputStream(archive.toByteArray()));
        Engine.Card saved = restored.cardById(card.id);
        check("archive round trip preserves editable fields and title", saved != null && "修改后的事项".equals(saved.displayTitle()) && "新的备注".equals(saved.manualNotes) && saved.deadlineUserSet);
        check("archive keeps manual titles verbatim", "提交给老师".equals(restored.cardById(literal.id).displayTitle()));
        check("manual traces survive restart", TaskTrace.text(saved).contains("编辑任务地点和备注") && original.equals(saved.sourceText));
        check("manual task can be completed", restored.setCardDone(saved, true, NOW + 4));
        check("manual task can be removed and restored", restored.dismissCard(saved, NOW + 5) && restored.restoreCard(saved, NOW + 6));
        check("removed manual card cannot be edited", restored.dismissCard(saved, NOW + 7) && !restored.editManualCard(saved, "不能写入", 0, "", "", NOW + 8));
        check("new ids remain unique after restore", !restored.addManualCard("重启后新任务", 0, "", "", NOW + 9).id.equals(card.id));
        System.out.println(checks + " manual card checks passed");
    }
}
