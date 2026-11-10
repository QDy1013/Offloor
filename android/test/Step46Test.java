package com.nopalou;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 损坏或不完整的存档必须拒绝，并保留加载前的状态。 */
public final class Step46Test {
    static String root;
    static int passed, failed;
    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passed++; else failed++;
    }
    static Engine fresh() throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get(root, "app/assets/model.tsv"))) {
            return new Engine(in);
        }
    }
    static byte[] state(Engine e) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); e.saveState(out); return out.toByteArray();
    }
    static void reject(String label, String text) throws Exception {
        Engine e = fresh();
        e.process(new Engine.Msg("keep", "g", "大学物理课程群", "王老师", "明天18点前交实验报告", "com.tencent.mm", 1800000000000L));
        byte[] before = state(e);
        boolean rejected = false;
        try { e.loadState(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception expected) { rejected = true; }
        check(label + " 被拒绝", rejected);
        check(label + " 不改变内存状态", Arrays.equals(before, state(e)));
    }
    public static void main(String[] args) throws Exception {
        root = args[0];
        reject("空文件", "");
        reject("错误文件头", "wrong\n");
        reject("缺失记录结束", "NPL1\nSEQ\t1\nN\t1\nCARD\nid\tc1\nkind\ttodo\ntitle\t报告\n");
        reject("数量不符", "NPL1\nSEQ\t1\nN\t2\nCARD\nid\tc1\nkind\ttodo\ntitle\t报告\nEND\n");
        reject("重复编号", "NPL1\nSEQ\t1\nN\t2\nCARD\nid\tc1\nkind\ttodo\nEND\nCARD\nid\tc1\nkind\ttodo\nEND\n");
        reject("非法截止值", "NPL1\nSEQ\t1\nN\t1\nCARD\nid\tc1\nkind\ttodo\ndeadline\tbad\nEND\n");
        Engine empty = fresh(); empty.loadState(new ByteArrayInputStream("NPL1\nSEQ\t0\nN\t0\n".getBytes(StandardCharsets.UTF_8)));
        check("旧版合法空存档可加载", empty.all().isEmpty());
        byte[] valid = state(empty);
        String modern = new String(valid, StandardCharsets.UTF_8);
        reject("新格式摘要被改动", modern.replace("SEQ\t0", "SEQ\t9"));
        reject("新格式摘要被截断", modern.substring(0, modern.indexOf("CHECKSUM")));
        Path directory = Files.createTempDirectory(Paths.get(root).getParent(), ".state-test-");
        try {
            File file = directory.resolve("cards.dat").toFile();
            StateStore store = new StateStore(file);
            check("首次启动无存档不阻止保存", !store.load(StateFormat::validate).blocked);
            store.write(valid, StateFormat::validate);
            Engine withCard = fresh();
            withCard.process(new Engine.Msg("report", "g", "大学物理课程群", "王老师", "明天18点前交实验报告", "com.tencent.mm", 1800000000000L));
            byte[] next = state(withCard);
            store.write(next, StateFormat::validate);
            check("有效旧版本备份保留", Arrays.equals(valid, StateStore.read(store.backup)));
            Files.write(file.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
            StateStore recovered = new StateStore(file);
            StateStore.Loaded loaded = recovered.load(StateFormat::validate);
            check("损坏主文件从有效备份恢复", loaded.recovered && Arrays.equals(valid, loaded.bytes));
            check("损坏文件有独立副本", directory.toFile().listFiles((d, n) -> n.contains(".corrupt-")).length == 1);
            recovered.write(next, StateFormat::validate);
            check("保存恢复结果不覆盖有效备份", Arrays.equals(valid, StateStore.read(recovered.backup)));
            File lost = directory.resolve("lost.dat").toFile(); Files.write(lost.toPath(), new byte[0]);
            StateStore blocked = new StateStore(lost);
            check("无有效备份时停止覆盖", blocked.load(StateFormat::validate).blocked);
            boolean refused = false; try { blocked.write(valid, StateFormat::validate); } catch (Exception expected) { refused = true; }
            check("未确认的坏档仍完整保留", refused && lost.length() == 0);
            blocked.allowRebuild(); blocked.write(valid, StateFormat::validate);
            check("确认后重建合法存档", Arrays.equals(valid, StateStore.read(lost)));
        } finally {
            // 只清理由本用例在项目内建立的临时目录。
            for (File file : directory.toFile().listFiles()) Files.delete(file.toPath());
            Files.delete(directory);
        }
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) System.exit(1);
    }
}
