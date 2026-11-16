package com.nopalou;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/** JVM 入口：按顺序喂入消息并输出最终卡片。自由文本用 Base64 避免换行和制表符破坏记录。 */
public class EngineEval {
    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        return sorted.get((int) Math.ceil(sorted.size() * q) - 1);
    }

    private static void printTiming(List<Long> all, List<Long> nonNoise) {
        Collections.sort(all);
        Collections.sort(nonNoise);
        long total = 0;
        for (long nanos : all) total += nanos;
        System.out.println(String.join("\t", System.getProperty("java.version"), String.valueOf(all.size()),
                String.valueOf(nonNoise.size()), String.valueOf(total),
                String.valueOf(percentile(all, 0.50)), String.valueOf(percentile(all, 0.95)),
                String.valueOf(percentile(all, 0.99)), String.valueOf(percentile(nonNoise, 0.95))));
    }

    public static void main(String[] args) throws Exception {
        boolean timing = false;
        boolean countEscalations = false;
        for (int i = 1; i < args.length; i++) {
            if ("--timing".equals(args[i])) timing = true;
            if ("--escalations".equals(args[i])) countEscalations = true;
        }
        List<Long> durations = new ArrayList<>();
        List<Long> nonNoise = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        Engine engine;
        try (InputStream model = Files.newInputStream(Paths.get(args[0]))) {
            engine = new Engine(model);
        }
        engine.setProfile(Engine.Profile.demo());
        final int[] escalations = {0};
        if (countEscalations) {
            engine.escalator = esc -> escalations[0]++;
        }
        StringJoiner jielongSources = new StringJoiner(",");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\t", -1);
                long timestamp = LocalDateTime.parse(fields[0]).atZone(zone).toInstant().toEpochMilli();
                String sourceId = decode(fields[1]);
                String text = decode(fields[5]);
                Engine.Msg message = new Engine.Msg(sourceId, decode(fields[2]), decode(fields[3]),
                        decode(fields[4]), text, "eval", timestamp);
                if (timing) {
                    int previousNoise = engine.nNoise;
                    long started = System.nanoTime();
                    engine.process(message);
                    long elapsed = System.nanoTime() - started;
                    durations.add(elapsed);
                    if (engine.nNoise == previousNoise) nonNoise.add(elapsed);
                } else {
                    engine.process(message);
                }
                if (Engine.isJielong(text) && engine.all().stream().anyMatch(
                        card -> "jielong".equals(card.kind) && sourceId.equals(card.sourceId))) {
                    jielongSources.add(fields[1]);
                }
            }
        }
        System.out.println(engine.nIn + "\t" + engine.nNoise + "\t" + engine.nLevel3 + "\t" + engine.nPromoted);
        System.out.println(jielongSources);
        if (timing) printTiming(durations, nonNoise);
        if (countEscalations) System.out.println("ESCALATIONS\t" + escalations[0]);
        for (Engine.Card card : engine.all()) {
            String deadline = card.deadline == 0 ? "" :
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(card.deadline), zone).toString();
            System.out.println(String.join("\t", card.kind, encode(card.sourceId),
                    encode(card.groupId), encode(card.sourceText), deadline));
        }
    }
}
