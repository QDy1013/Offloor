package com.nopalou;

import java.util.List;

/** 自动读取稳定性：诊断日志 DiagLog 与自检 HealthCheck。 */
public final class Step22Test {

    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name + (ok ? "" : "（期望=" + expected + "，实际=" + actual + "）"), ok);
    }

    // ---------------- 日志快照，避免 dump/load 期间被列表视图影响 ----------------

    private static final class Snap {
        final long[] time;
        final String[] kind;
        final String[] detail;

        Snap(List<DiagLog.Entry> list) {
            time = new long[list.size()];
            kind = new String[list.size()];
            detail = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                DiagLog.Entry e = list.get(i);
                time[i] = e.time;
                kind[i] = e.kind;
                detail[i] = e.detail;
            }
        }

        void assertEquals(String name, Snap other) {
            eq(name + "：条数", time.length, other.time.length);
            int n = Math.min(time.length, other.time.length);
            for (int i = 0; i < n; i++) {
                eq(name + "：第" + i + "条 time", Long.valueOf(time[i]), Long.valueOf(other.time[i]));
                eq(name + "：第" + i + "条 kind", kind[i], other.kind[i]);
                eq(name + "：第" + i + "条 detail", detail[i], other.detail[i]);
            }
        }
    }

    // ---------------- 健康检查辅助 ----------------

    private static final long NOW = 1700000000000L;
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long HOUR72 = 72L * HOUR;

    private static HealthCheck.Result eval(boolean access, boolean connected,
                                          boolean battery, long lastReceivedAt) {
        return HealthCheck.evaluate(access, connected, battery, lastReceivedAt, NOW);
    }

    private static HealthCheck.Issue find(List<HealthCheck.Issue> issues, String fix) {
        if (issues == null) return null;
        for (HealthCheck.Issue i : issues) {
            if (i == null) continue;
            if (fix.equals(i.fix)) return i;
        }
        return null;
    }

    private static boolean hasFix(List<HealthCheck.Issue> issues, String fix) {
        return find(issues, fix) != null;
    }

    private static void expectFix(String name, HealthCheck.Issue issue, int level, String fix, String text) {
        if (issue == null) {
            check(name + "：没有找到问题 " + fix, false);
            return;
        }
        eq(name + "：fix", fix, issue.fix);
        eq(name + "：level", Integer.valueOf(level), Integer.valueOf(issue.level));
        if (text != null) {
            boolean ok = issue.text != null && issue.text.startsWith(text);
            check(name + "：text 以“" + text + "”开头" + (ok ? "" : "（实际=" + issue.text + "）"), ok);
        }
    }

    public static void main(String[] args) {
        testRecentOrder();
        testRecentLimit();
        testCapacity();
        testCountNotCapped();
        testReset();
        testDumpLoadRoundTrip();
        testDumpLoadFull();
        testLoadInvalid();
        testHealthy();
        testNoAccess();
        testDisconnected();
        testBatteryOnly();
        testMute();
        testOrderAndLevel();
        testRebindWithBatteryNoMute();

        if (failures > 0) {
            System.out.println(failures + " 项未通过");
            System.exit(1);
        }
        System.out.println("Step22 全部通过");
    }

    // ---------------- DiagLog ----------------

    private static void testRecentOrder() {
        DiagLog.reset();
        DiagLog.record(100L, "收到", "第一条");
        DiagLog.record(200L, "跳过", "第二条");
        DiagLog.record(300L, "断开", "第三条");

        List<DiagLog.Entry> list = DiagLog.recent(10);
        if (list == null) {
            check("recent(10) 返回了 null", false);
            return;
        }
        eq("recent(10)：条数", 3, list.size());
        if (list.size() != 3) return;

        eq("recent(10)：第1条 time", Long.valueOf(300L), Long.valueOf(list.get(0).time));
        eq("recent(10)：第1条 kind", "断开", list.get(0).kind);
        eq("recent(10)：第1条 detail", "第三条", list.get(0).detail);

        eq("recent(10)：第2条 time", Long.valueOf(200L), Long.valueOf(list.get(1).time));
        eq("recent(10)：第2条 kind", "跳过", list.get(1).kind);
        eq("recent(10)：第2条 detail", "第二条", list.get(1).detail);

        eq("recent(10)：第3条 time", Long.valueOf(100L), Long.valueOf(list.get(2).time));
        eq("recent(10)：第3条 kind", "收到", list.get(2).kind);
        eq("recent(10)：第3条 detail", "第一条", list.get(2).detail);
    }

    private static void testRecentLimit() {
        DiagLog.reset();
        for (int i = 1; i <= 5; i++) {
            DiagLog.record((long) i, "收到", "d" + i);
        }

        List<DiagLog.Entry> two = DiagLog.recent(2);
        if (two == null) {
            check("recent(2) 返回了 null", false);
        } else {
            eq("recent(2)：条数", 2, two.size());
            if (two.size() == 2) {
                eq("recent(2)：第1条 time", Long.valueOf(5L), Long.valueOf(two.get(0).time));
                eq("recent(2)：第1条 detail", "d5", two.get(0).detail);
                eq("recent(2)：第2条 time", Long.valueOf(4L), Long.valueOf(two.get(1).time));
                eq("recent(2)：第2条 detail", "d4", two.get(1).detail);
            }
        }

        List<DiagLog.Entry> zero = DiagLog.recent(0);
        check("recent(0) 不是 null", zero != null);
        if (zero != null) eq("recent(0)：空列表", 0, zero.size());

        List<DiagLog.Entry> negative = DiagLog.recent(-1);
        check("recent(-1) 不是 null", negative != null);
        if (negative != null) eq("recent(-1)：空列表", 0, negative.size());
    }

    private static void testCapacity() {
        DiagLog.reset();
        for (int i = 1; i <= 60; i++) {
            DiagLog.record((long) i, "收到", "d" + i);
        }

        List<DiagLog.Entry> list = DiagLog.recent(100);
        if (list == null) {
            check("容量测试：recent(100) 返回了 null", false);
            return;
        }
        eq("容量 50：recent(100) 条数", 50, list.size());
        if (list.size() != 50) return;
        eq("容量 50：最新一条 detail", "d60", list.get(0).detail);
        eq("容量 50：最新一条 time", Long.valueOf(60L), Long.valueOf(list.get(0).time));
        eq("容量 50：最老一条 detail", "d11", list.get(49).detail);
        eq("容量 50：最老一条 time", Long.valueOf(11L), Long.valueOf(list.get(49).time));
    }

    private static void testCountNotCapped() {
        DiagLog.reset();
        for (int i = 0; i < 60; i++) {
            DiagLog.record((long) i, "收到", "第" + i);
        }
        eq("count(收到) 不受容量限制", 60, DiagLog.count("收到"));

        DiagLog.record(100L, "跳过", "汇总通知");
        DiagLog.record(101L, "跳过", "没有正文");
        eq("count(跳过)", 2, DiagLog.count("跳过"));
        eq("count(出错) 没记过", 0, DiagLog.count("出错"));
        eq("count(从未出现的 kind)", 0, DiagLog.count("从未记录的类别"));
    }

    private static void testReset() {
        DiagLog.reset();
        DiagLog.record(1L, "收到", "a");
        DiagLog.record(2L, "跳过", "b");
        DiagLog.record(3L, "出错", "c");
        DiagLog.record(4L, "重连", "d");

        DiagLog.reset();

        List<DiagLog.Entry> list = DiagLog.recent(50);
        if (list == null) {
            check("reset 后 recent 返回了 null", false);
        } else {
            eq("reset 后 recent(50) 为空", 0, list.size());
        }
        eq("reset 后 count(收到)", 0, DiagLog.count("收到"));
        eq("reset 后 count(跳过)", 0, DiagLog.count("跳过"));
        eq("reset 后 count(出错)", 0, DiagLog.count("出错"));
        eq("reset 后 count(重连)", 0, DiagLog.count("重连"));
        eq("reset 后 count(连接)", 0, DiagLog.count("连接"));
    }

    private static void testDumpLoadRoundTrip() {
        DiagLog.reset();
        DiagLog.record(1000L, "收到", "a\nb");
        DiagLog.record(2000L, "跳过", "c\td");
        DiagLog.record(3000L, "出错", "中文冒号：和\"引号\"");
        DiagLog.record(4000L, "收到", "多行\n第二行\t带制表符");

        List<DiagLog.Entry> beforeList = DiagLog.recent(50);
        if (beforeList == null) {
            check("dump/load：dump 前 recent 返回了 null", false);
            return;
        }
        Snap before = new Snap(beforeList);
        int receivedBefore = DiagLog.count("收到");
        int skippedBefore = DiagLog.count("跳过");
        int errorBefore = DiagLog.count("出错");

        String dumped = DiagLog.dump();
        check("dump 返回非 null", dumped != null);

        DiagLog.reset();
        DiagLog.load(dumped);

        List<DiagLog.Entry> afterList = DiagLog.recent(50);
        if (afterList == null) {
            check("dump/load：load 后 recent 返回了 null", false);
            return;
        }
        before.assertEquals("dump/load 往返", new Snap(afterList));
        eq("dump/load：count(收到)", receivedBefore, DiagLog.count("收到"));
        eq("dump/load：count(跳过)", skippedBefore, DiagLog.count("跳过"));
        eq("dump/load：count(出错)", errorBefore, DiagLog.count("出错"));
    }

    private static void testDumpLoadFull() {
        DiagLog.reset();
        for (int i = 1; i <= 60; i++) {
            DiagLog.record((long) i, "收到", "d" + i);
        }

        List<DiagLog.Entry> beforeList = DiagLog.recent(100);
        if (beforeList == null) {
            check("满容量 dump/load：dump 前 recent 返回了 null", false);
            return;
        }
        Snap before = new Snap(beforeList);

        String dumped = DiagLog.dump();
        DiagLog.reset();
        DiagLog.load(dumped);

        List<DiagLog.Entry> afterList = DiagLog.recent(100);
        if (afterList == null) {
            check("满容量 dump/load：load 后 recent 返回了 null", false);
            return;
        }
        eq("满容量 dump/load：条数仍为 50", 50, afterList.size());
        before.assertEquals("满容量 dump/load 往返", new Snap(afterList));
        eq("满容量 dump/load：count(收到) 仍是 60", 60, DiagLog.count("收到"));
    }

    private static void testLoadInvalid() {
        DiagLog.reset();
        DiagLog.record(1L, "收到", "记录一下，load 应先清空");
        try {
            DiagLog.load(null);
            check("load(null) 不抛异常", true);
        } catch (Throwable t) {
            check("load(null) 抛了异常：" + t, false);
        }
        List<DiagLog.Entry> afterNull = DiagLog.recent(50);
        check("load(null) 后 recent 不是 null", afterNull != null);
        if (afterNull != null) eq("load(null) 后 recent(50) 为空", 0, afterNull.size());
        eq("load(null) 后 count(收到)", 0, DiagLog.count("收到"));

        DiagLog.record(2L, "跳过", "再记一条");
        try {
            DiagLog.load("");
            check("load(\"\") 不抛异常", true);
        } catch (Throwable t) {
            check("load(\"\") 抛了异常：" + t, false);
        }
        List<DiagLog.Entry> afterEmpty = DiagLog.recent(50);
        check("load(\"\") 后 recent 不是 null", afterEmpty != null);
        if (afterEmpty != null) eq("load(\"\") 后 recent(50) 为空", 0, afterEmpty.size());

        DiagLog.record(3L, "出错", "再记一条");
        try {
            DiagLog.load("乱码\n\t\u0000随便什么");
            check("load(乱码) 不抛异常", true);
        } catch (Throwable t) {
            check("load(乱码) 抛了异常：" + t, false);
        }
        List<DiagLog.Entry> afterGarbage = DiagLog.recent(50);
        check("load(乱码) 后 recent 不是 null", afterGarbage != null);
        if (afterGarbage != null) eq("load(乱码) 后 recent(50) 为空", 0, afterGarbage.size());
        eq("load(乱码) 后 count(出错)", 0, DiagLog.count("出错"));
    }

    // ---------------- HealthCheck ----------------

    private static void testHealthy() {
        HealthCheck.Result r = eval(true, true, true, NOW - HOUR);
        if (r == null) {
            check("全部正常：返回了 null", false);
            return;
        }
        eq("全部正常：level", 0, r.level);
        check("全部正常：issues 不是 null", r.issues != null);
        if (r.issues != null) eq("全部正常：issues 为空", 0, r.issues.size());
        eq("全部正常：summary", "通知服务已连接", r.summary());
    }

    private static void testNoAccess() {
        HealthCheck.Result r = eval(false, true, true, NOW - HOUR);
        if (r == null || r.issues == null || r.issues.size() < 1) {
            check("无权限：没有返回问题", false);
            return;
        }
        eq("无权限：level", 2, r.level);
        expectFix("无权限第一条", r.issues.get(0), 2, "NOTIF_ACCESS", "通知读取权限没开");
        check("无权限：没有 REBIND", !hasFix(r.issues, "REBIND"));
        eq("无权限：summary", "发现 1 个问题", r.summary());

        HealthCheck.Result r2 = eval(false, false, true, NOW - HOUR);
        if (r2 == null || r2.issues == null) {
            check("无权限且未连接：没有返回问题", false);
            return;
        }
        eq("无权限且未连接：level", 2, r2.level);
        boolean hasNotif = false;
        for (HealthCheck.Issue i : r2.issues) {
            if (i != null && "NOTIF_ACCESS".equals(i.fix)) hasNotif = true;
        }
        check("无权限且未连接：仍有 NOTIF_ACCESS", hasNotif);
        check("无权限且未连接：规则 2 不触发，没有 REBIND", !hasFix(r2.issues, "REBIND"));
    }

    private static void testDisconnected() {
        HealthCheck.Result r = eval(true, false, true, NOW - HOUR);
        if (r == null || r.issues == null) {
            check("未连接：没有返回问题", false);
            return;
        }
        eq("未连接：level", 2, r.level);
        HealthCheck.Issue issue = find(r.issues, "REBIND");
        expectFix("未连接", issue, 2, "REBIND", null);
        eq("未连接：summary", "发现 1 个问题", r.summary());
    }

    private static void testBatteryOnly() {
        HealthCheck.Result r = eval(true, true, false, NOW - HOUR);
        if (r == null || r.issues == null) {
            check("电池限制：没有返回问题", false);
            return;
        }
        eq("电池限制：level", 1, r.level);
        eq("电池限制：问题数", 1, r.issues.size());
        HealthCheck.Issue issue = r.issues.get(0);
        expectFix("电池限制", issue, 1, "BATTERY", null);
        eq("电池限制：summary", "发现 1 个问题", r.summary());
    }

    private static void testMute() {
        HealthCheck.Result r1 = eval(true, true, true, NOW - HOUR72 - 1L);
        if (r1 == null || r1.issues == null) {
            check("MUTE：没有返回问题", false);
        } else {
            eq("超过 72 小时：level", 1, r1.level);
            eq("超过 72 小时：问题数", 1, r1.issues.size());
            HealthCheck.Issue issue = find(r1.issues, "MUTE");
            expectFix("超过 72 小时", issue, 1, "MUTE", null);
            eq("超过 72 小时：summary", "发现 1 个问题", r1.summary());
        }

        HealthCheck.Result r2 = eval(true, true, true, NOW - HOUR72);
        if (r2 == null || r2.issues == null) {
            check("正好 72 小时：没有返回结果", false);
        } else {
            eq("正好 72 小时：level", 0, r2.level);
            check("正好 72 小时：没有 MUTE", !hasFix(r2.issues, "MUTE"));
            eq("正好 72 小时：issues 为空", 0, r2.issues.size());
        }

        HealthCheck.Result r3 = eval(true, true, true, 0L);
        if (r3 == null || r3.issues == null) {
            check("lastReceivedAt=0：没有返回结果", false);
        } else {
            eq("lastReceivedAt=0：level", 0, r3.level);
            check("lastReceivedAt=0：没有 MUTE", !hasFix(r3.issues, "MUTE"));
            eq("lastReceivedAt=0：issues 为空", 0, r3.issues.size());
        }

        HealthCheck.Result r4 = eval(true, false, true, NOW - 10L * HOUR72);
        if (r4 == null || r4.issues == null) {
            check("未连接且很久没收到：没有返回结果", false);
        } else {
            check("未连接且很久没收到：没有 MUTE", !hasFix(r4.issues, "MUTE"));
            check("未连接且很久没收到：有 REBIND", hasFix(r4.issues, "REBIND"));
        }
    }

    private static void testOrderAndLevel() {
        HealthCheck.Result r = eval(false, true, false, NOW - HOUR);
        if (r == null || r.issues == null) {
            check("顺序与等级：没有返回问题", false);
            return;
        }
        eq("顺序与等级：问题数", 2, r.issues.size());
        eq("顺序与等级：level", 2, r.level);
        eq("顺序与等级：summary", "发现 2 个问题", r.summary());
        if (r.issues.size() == 2) {
            expectFix("顺序与等级第一条", r.issues.get(0), 2, "NOTIF_ACCESS", "通知读取权限没开");
            expectFix("顺序与等级第二条", r.issues.get(1), 1, "BATTERY", null);
        }
    }

    private static void testRebindWithBatteryNoMute() {
        HealthCheck.Result r = eval(true, false, false, NOW - 10L * HOUR72);
        if (r == null || r.issues == null) {
            check("未连接+电池+久未收到：没有返回问题", false);
            return;
        }
        eq("未连接+电池+久未收到：问题数", 2, r.issues.size());
        eq("未连接+电池+久未收到：level", 2, r.level);
        eq("未连接+电池+久未收到：summary", "发现 2 个问题", r.summary());
        check("未连接+电池+久未收到：没有 MUTE", !hasFix(r.issues, "MUTE"));
        if (r.issues.size() == 2) {
            expectFix("未连接+电池+久未收到第一条", r.issues.get(0), 2, "REBIND", null);
            expectFix("未连接+电池+久未收到第二条", r.issues.get(1), 1, "BATTERY", null);
        }
    }
}
