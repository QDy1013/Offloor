package com.nopalou;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** New-card visits: ID membership, stable refreshes, search and pagination. */
public final class Step57Test {
    private static int checks;
    private static final long NOW = 1800000000000L;

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    private static Engine.Card card(String id, String title, long deadline) {
        Engine.Card c = new Engine.Card();
        c.id = id;
        c.title = title;
        c.deadline = deadline;
        return c;
    }

    public static void main(String[] args) throws Exception {
        NewCardSession first = new NewCardSession(null);
        check("first visit persists a baseline", first.observe(Arrays.asList("old", "same", null, "")));
        check("upgrade does not call historical cards new", !first.isNew("old") && !first.isNew("same"));
        check("empty and null IDs are ignored", !first.isNew(null) && !first.isNew(""));
        check("unchanged render does not rewrite preferences", !first.observe(Arrays.asList("old", "same")));
        check("foreground arrival is added", first.observe(Arrays.asList("old", "same", "live")) && first.isNew("live"));
        first.observe(Arrays.asList("old", "same", "live"));
        check("refresh does not clear the new batch", first.isNew("live"));
        Set<String> saved = first.seenIds();
        saved.clear();
        check("saved IDs are defensive copies", first.seenIds().contains("live"));

        Set<String> previous = first.seenIds();
        NewCardSession reopened = new NewCardSession(previous);
        previous.add("background");
        reopened.observe(Arrays.asList("old", "same", "live", "background"));
        check("next visit only includes background arrivals", reopened.isNew("background") && !reopened.isNew("live"));
        check("AI edits preserve ID membership", !reopened.isNew("same"));
        reopened.observe(Arrays.asList("same", "background"));
        reopened.observe(Arrays.asList("old", "same", "background"));
        check("delete and restore does not turn old cards new", !reopened.isNew("old"));
        NewCardSession emptyInstall = new NewCardSession(null);
        emptyInstall.observe(Arrays.asList());
        emptyInstall.observe(Arrays.asList("first-arrival"));
        check("first install can receive new cards after empty baseline", emptyInstall.isNew("first-arrival"));

        Engine engine;
        try (InputStream in = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            engine = new Engine(in);
        }
        NewCardSession session = new NewCardSession(new HashSet<>(Arrays.asList("old")));
        engine.all().add(card("old", "数据库旧作业", NOW + 1000));
        for (int i = 0; i < 65; i++) engine.all().add(card("new-" + i, "数据库新作业 " + i, NOW + 2000 + i));
        Engine.Card uncertain = card("uncertain", "含糊事项", 0);
        uncertain.needsConfirm = true;
        engine.all().add(uncertain);
        engine.all().add(card("expired", "已过期事项", NOW - 1000));
        Engine.Card done = card("done", "已完成事项", NOW + 5000);
        done.done = true;
        engine.all().add(done);
        session.observe(Arrays.asList("old", "new-0", "uncertain", "expired", "done"));
        check("badge counts only existing new cards", session.count(engine.all()) == 68);
        List<Engine.Card> page = session.queryCards(engine, "数据库", Engine.CardScope.ALL, 31, NOW);
        check("new filtering happens before pagination", page.size() == 31 && !page.stream().anyMatch(c -> "old".equals(c.id)));
        check("expanded pages retain full new batch", session.queryCards(engine, "数据库", Engine.CardScope.ALL, 100, NOW).size() == 65);
        List<Engine.Card> ranked = engine.queryCards("数据库", Engine.CardScope.ALL, Integer.MAX_VALUE, NOW);
        ranked.removeIf(c -> !session.isNew(c.id));
        check("existing ranking is retained", page.equals(ranked.subList(0, 31)));
        check("search is applied within new cards", session.queryCards(engine, "含糊", Engine.CardScope.ALL, 30, NOW).size() == 1);
        check("no dates, expired and completed new cards are included", session.queryCards(engine, "", Engine.CardScope.ALL, 100, NOW).size() == 68);
        check("state filter remains supported", session.queryCards(engine, "", Engine.CardScope.COMPLETED, 30, NOW).equals(Arrays.asList(done)));
        check("zero limit stays empty", session.queryCards(engine, "", Engine.CardScope.ALL, 0, NOW).isEmpty());
        engine.all().remove(uncertain);
        check("deleted cards leave badge immediately", session.count(engine.all()) == 67);
        check("all tab data is untouched", engine.queryCards("", Engine.CardScope.ALL, 100, NOW).size() == 68);
        System.out.println(checks + " new-card checks passed");
    }
}
