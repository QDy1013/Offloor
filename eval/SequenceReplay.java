package com.nopalou;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Replays notifications through the Android engine, with or without cross-message state. */
public final class SequenceReplay {
    private static final String PACKAGE = "com.tencent.mm";
    private final Path model;
    private final boolean stateful;
    private final List<String> courseNames = new ArrayList<>();
    private final Map<String, List<TodayPlan.Course>> courses = new LinkedHashMap<>();
    private final List<Engine.Card> independentCards = new ArrayList<>();
    private Engine engine;

    private SequenceReplay(Path model, boolean stateful) {
        this.model = model;
        this.stateful = stateful;
    }

    private Engine fresh() throws Exception {
        try (InputStream source = Files.newInputStream(model)) {
            Engine result = new Engine(source);
            result.setTimetableCourses(courseNames);
            return result;
        }
    }

    private void process(String[] fields) throws Exception {
        String id = fields[1], group = ReliabilityReplay.decode(fields[3]);
        long at = Long.parseLong(fields[2]);
        Engine target = stateful ? engine : fresh();
        String name = "课程事项群（构造）";
        target.setGroupMode(group, name, PACKAGE, Engine.MODE_FOCUS, at);
        target.process(new Engine.Msg(id, group, name, ReliabilityReplay.decode(fields[4]),
                ReliabilityReplay.decode(fields[5]), PACKAGE, at));
        if (!stateful) independentCards.addAll(target.all());
    }

    private void act(String operation, long at, String task, String change) {
        boolean applied = false;
        if (stateful) {
            for (Engine.Card card : engine.all()) {
                if (!task.equals(card.sourceId)) continue;
                applied = "resolve".equals(operation) ? engine.resolvePendingChange(card, change, at)
                        : engine.ignorePendingChange(card, change, at);
                break;
            }
        }
        System.out.println("ACTION\t" + applied);
    }

    private static boolean isTask(Engine.Card card) {
        return "todo".equals(card.kind) || "exam".equals(card.kind);
    }

    private static void printCard(Engine.Card card) {
        String pending = card.changes.pending().stream().map(m -> m.id).sorted().collect(Collectors.joining(","));
        System.out.println("CARD\t" + card.sourceId + "\t" + card.deadline + "\t"
                + (card.cancelled ? "cancelled" : "active") + "\t" + card.needsConfirm + "\t" + pending
                + "\t" + ReliabilityReplay.encode(card.displayTitle()));
    }

    private void inspect(String id, long at) {
        System.out.println("CHECK\t" + id);
        List<Engine.Card> cards = stateful ? engine.all() : independentCards;
        cards.stream().filter(SequenceReplay::isTask).forEach(SequenceReplay::printCard);
        if (stateful) engine.removedCards().stream().filter(SequenceReplay::isTask)
                .filter(c -> c.cancelled).forEach(SequenceReplay::printCard);
        List<StudyPlanner.DayCourses> days = new ArrayList<>();
        courses.forEach((date, classes) -> days.add(new StudyPlanner.DayCourses(date, classes)));
        for (StudyPlanner.LessonClash clash : StudyPlanner.findLessonClashes(cards, days, at)) {
            System.out.println("CLASH\t" + clash.card.sourceId + "\t" + ReliabilityReplay.encode(clash.lesson.name));
        }
        StudyPlanner.GapSuggestion gap = StudyPlanner.findStudyGap(days, cards, at);
        if (gap != null && gap.targetCard != null) {
            long start = StudyRoomPlan.at(gap.date, gap.startMinute);
            long end = StudyRoomPlan.at(gap.date, gap.endMinute);
            long deadline = gap.targetCard.deadline;
            long minutes = deadline <= 0 || gap.targetCard.needsConfirm ? -1
                    : Math.max(0, (Math.min(end, deadline) - start) / 60_000L);
            System.out.println("PLAN\t" + gap.targetCard.sourceId + "\t" + gap.date + "\t"
                    + StudyRoomPlan.clock(gap.startMinute) + "\t" + StudyRoomPlan.clock(gap.endMinute) + "\t" + minutes);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("stateful", "isolated").contains(args[1])) {
            throw new IllegalArgumentException("model stateful|isolated");
        }
        SequenceReplay replay = new SequenceReplay(Path.of(args[0]), "stateful".equals(args[1]));
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        String line;
        while ((line = input.readLine()) != null) {
            String[] fields = line.split("\t", -1);
            switch (fields[0]) {
                case "CASE":
                    replay.courseNames.clear();
                    replay.courses.clear();
                    replay.independentCards.clear();
                    System.out.println("CASE\t" + fields[1]);
                    break;
                case "COURSE":
                    String name = ReliabilityReplay.decode(fields[2]);
                    replay.courseNames.add(name);
                    replay.courses.computeIfAbsent(fields[1], key -> new ArrayList<>()).add(
                            new TodayPlan.Course(name, "友谊校区教室（构造）", fields[3], fields[4], 1));
                    break;
                case "START":
                    if (replay.stateful) replay.engine = replay.fresh();
                    break;
                case "MSG":
                    replay.process(fields);
                    break;
                case "USER":
                    replay.act(fields[1], Long.parseLong(fields[2]), fields[3], fields[4]);
                    break;
                case "CHECK":
                    replay.inspect(fields[1], Long.parseLong(fields[2]));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown replay command " + fields[0]);
            }
        }
    }
}
