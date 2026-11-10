package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** 按群管理：群登记、重点关注放宽识别、不用管跳过并移走已有卡片、平台按 App 管理、持久化。 */
public final class Step39Test {
    static int count;
    static final long NOW = 1_800_000_000_000L;
    static final String WX = "com.tencent.mm";
    static String model;

    static void check(String name, boolean ok, String detail) {
        if (!ok) throw new AssertionError(name + "：" + detail);
        count++;
        System.out.println("PASS " + name);
    }

    static Engine fresh() throws Exception {
        Engine e;
        try (InputStream w = Files.newInputStream(Paths.get(model))) {
            e = new Engine(w);
        }
        e.setProfile(Engine.Profile.demo());
        return e;
    }

    static void say(Engine e, String gid, String group, String sender, String text, long ts) {
        e.process(new Engine.Msg("m" + (gid + text + ts).hashCode(), gid, group, sender, text, WX, ts));
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";
        String exam = "下周三下午2点期中考试，地点教二305";
        String report = "明天18点前交实验报告到学习通";
        String chat = "那我们周五晚上8点前把PPT做完吧";

        // ① 登记：闲聊也登记群，但不让存档变脏
        Engine e = fresh();
        say(e, "g1", "数据结构课程群", "同学", "收到", NOW);
        List<Engine.GroupInfo> list = e.groupList();
        check("收到消息的群会登记", list.size() == 1 && "数据结构课程群".equals(list.get(0).name) && list.get(0).count == 1,
                "群数 " + list.size());
        check("登记群不触发整份存档", !e.hasStateChanges(), "dirty");
        check("默认是普通", Engine.MODE_NORMAL.equals(e.groupMode("g1")), e.groupMode("g1"));

        // ② 普通：课程群里同学发的考试和作业不出卡
        say(e, "g1", "数据结构课程群", "张三", exam, NOW + 1);
        say(e, "g1", "数据结构课程群", "李四", report, NOW + 2);
        check("普通群：同学发的考试和作业不出卡", e.all().isEmpty(), "卡数 " + e.all().size());

        // ③ 重点关注：同样的消息出卡，闲聊仍不出
        Engine f = fresh();
        f.setGroupMode("g1", "数据结构课程群", WX, Engine.MODE_FOCUS, NOW);
        check("设置后存档变脏", f.hasStateChanges(), "clean");
        say(f, "g1", "数据结构课程群", "张三", exam, NOW + 1);
        say(f, "g1", "数据结构课程群", "李四", report, NOW + 2);
        say(f, "g1", "数据结构课程群", "王五", chat, NOW + 3);
        int exams = 0, todos = 0;
        for (Engine.Card c : f.all()) {
            if ("exam".equals(c.kind)) exams++;
            if ("todo".equals(c.kind)) todos++;
        }
        check("重点关注：同学发的考试出考试卡", exams == 1, "考试卡 " + exams);
        check("重点关注：同学发的作业出待办卡", todos == 1, "待办卡 " + todos);
        check("重点关注：闲聊商量仍不出卡", f.all().size() == 2, "卡数 " + f.all().size());

        // ④ 不用管：已有卡移到已移除，之后的消息不出卡
        Engine g = fresh();
        say(g, "g2", "社团群", "王老师", report, NOW);
        check("不用管之前正常出卡", g.all().size() == 1, "卡数 " + g.all().size());
        check("群卡片数", g.cardCount("g2") == 1, "" + g.cardCount("g2"));
        int removed = g.setGroupMode("g2", "社团群", WX, Engine.MODE_IGNORE, NOW + 10);
        check("不用管移走已有卡", removed == 1 && g.all().isEmpty() && g.removedCards().size() == 1,
                "移走 " + removed + "，剩 " + g.all().size());
        say(g, "g2", "社团群", "王老师", "后天20点前交读书报告", NOW + 20);
        check("不用管的群不再出卡", g.all().isEmpty(), "卡数 " + g.all().size());
        say(g, "g3", "数据库系统", "王老师", "后天20点前交读书报告", NOW + 30);
        check("其他群不受影响", g.all().size() == 1, "卡数 " + g.all().size());

        // ⑤ 排序：重点关注在前，不用管在后
        g.setGroupMode("g3", "数据库系统", WX, Engine.MODE_FOCUS, NOW + 40);
        say(g, "g4", "宿舍群", "室友", "收到", NOW + 50);
        List<Engine.GroupInfo> order = g.groupList();
        check("排序：重点关注 → 普通 → 不用管",
                order.size() == 3 && "g3".equals(order.get(0).id) && "g4".equals(order.get(1).id) && "g2".equals(order.get(2).id),
                order.get(0).id + "," + order.get(1).id + "," + order.get(2).id);

        // ⑥ 持久化：设置和群信息重启后还在
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        g.saveState(out);
        Engine h = fresh();
        h.loadState(new ByteArrayInputStream(out.toByteArray()));
        check("重启后保留不用管", Engine.MODE_IGNORE.equals(h.groupMode("g2")), h.groupMode("g2"));
        check("重启后保留重点关注", Engine.MODE_FOCUS.equals(h.groupMode("g3")), h.groupMode("g3"));
        check("重启后保留群名和计数", "社团群".equals(h.groupList().get(2).name) && h.groupList().get(2).count >= 2,
                h.groupList().get(2).name + " " + h.groupList().get(2).count);
        say(h, "g2", "社团群", "王老师", "周五前交实验报告", NOW + 60);
        check("重启后不用管仍生效", h.all().size() == 1, "卡数 " + h.all().size());

        // ⑦ 改回普通：恢复出卡
        h.setGroupMode("g2", "社团群", WX, Engine.MODE_NORMAL, NOW + 70);
        say(h, "g2", "社团群", "王老师", "下周一前交课程论文", NOW + 80);
        check("改回普通后恢复出卡", h.all().size() == 2, "卡数 " + h.all().size());

        // ⑧ 学习平台按整个 App 管理
        Engine p = fresh();
        String xxt = "com.chaoxing.mobile";
        p.processPlatform(new Engine.Msg("p1", xxt + "|作业通知", "作业通知", "学习通",
                "【概率论】作业《第三章习题》截止时间：2027-03-12 23:59", xxt, NOW));
        Engine.Card pc = p.all().get(0);
        String key = p.groupKeyOf(pc);
        check("平台卡归到整个 App", (xxt + "|学习通").equals(key), key);
        check("平台登记成一个群", p.groupList().size() == 1 && p.groupList().get(0).isPlatform(), "" + p.groupList().size());
        check("平台卡片数按 App 统计", p.cardCount(key) == 1, "" + p.cardCount(key));
        p.setGroupMode(key, "学习通", xxt, Engine.MODE_IGNORE, NOW + 1);
        check("平台不用管移走卡片", p.all().isEmpty(), "卡数 " + p.all().size());
        p.processPlatform(new Engine.Msg("p2", xxt + "|作业通知", "作业通知", "学习通",
                "【线性代数】作业《矩阵》截止时间：2027-03-15 23:59", xxt, NOW + 2));
        check("平台不用管后不再出卡", p.all().isEmpty(), "卡数 " + p.all().size());

        // ⑨ 升级前的老卡：所在的群自动出现在列表里
        Engine old = fresh();
        say(old, "g9", "老课程群", "王老师", report, NOW);
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        old.saveState(legacy);
        String noGroups = legacy.toString("UTF-8").replaceAll("(?m)^(?:GROUP|FORMAT|CHECKSUM)\t.*\n", "");
        Engine up = fresh();
        up.loadState(new ByteArrayInputStream(noGroups.getBytes("UTF-8")));
        List<Engine.GroupInfo> seeded = up.groupList();
        check("老卡所在的群补登记", seeded.size() == 1 && "g9".equals(seeded.get(0).id) && "老课程群".equals(seeded.get(0).name),
                "群数 " + seeded.size());
        check("补登记不触发存档", !up.hasStateChanges(), "dirty");

        // ⑩ 老卡所在的群没登记过时也能设置
        Engine q = fresh();
        q.setGroupMode("old", "老群", WX, Engine.MODE_IGNORE, NOW);
        check("未登记的群也能设置", Engine.MODE_IGNORE.equals(q.groupMode("old")) && q.groupList().size() == 1, q.groupMode("old"));

        System.out.println("PASS " + count);
    }
}
