package com.nopalou;

import java.io.*;
import java.nio.file.*;

/** 通知处理异常及备份恢复后，去重记录必须与实际卡片一致。 */
public final class Step48Test {
    static String root; static int checks;
    static void check(String label, boolean ok) { if (!ok) throw new AssertionError(label); checks++; System.out.println("PASS " + label); }
    static class FaultEngine extends Engine {
        boolean fail;
        FaultEngine(InputStream in) throws Exception { super(in); }
        @Override public void process(Msg m) {
            if (fail) { fail = false; throw new IllegalStateException("injected failure"); }
            super.process(m);
        }
    }
    static FaultEngine fresh() throws Exception { return new FaultEngine(Files.newInputStream(Paths.get(root,"app/assets/model.tsv"))); }
    static Engine.Msg message(String id, String text) { return new Engine.Msg(id,"g","大学物理课程群","王老师",text,"com.tencent.mm",1800000000000L); }
    static byte[] save(Engine e) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); e.saveState(out); return out.toByteArray(); }
    public static void main(String[] args) throws Exception {
        root=args[0]; FaultEngine e=fresh(); e.fail=true;
        Engine.Msg one=message("one","明天18点前交实验报告到学习通");
        try { e.processNotification(one,false); } catch (IllegalStateException injected) { }
        check("处理异常不提交消息编号",!e.notificationSeen("one") && e.all().isEmpty());
        check("重试可以完整出卡",e.processNotification(one,false) && e.all().size()==1);
        check("相同通知重复回放不出卡",!e.processNotification(one,false) && e.all().size()==1);
        byte[] first=save(e);
        Engine.Msg two=message("two","后天18点前交读书报告到邮箱");
        e.processNotification(two,false); byte[] second=save(e);
        FaultEngine restored=fresh(); restored.loadState(new ByteArrayInputStream(first));
        check("恢复旧快照同时恢复旧去重版本",restored.notificationSeen("one") && !restored.notificationSeen("two") && restored.all().size()==1);
        check("恢复后新消息可重新处理",restored.processNotification(two,false) && restored.all().size()==2);
        FaultEngine latest=fresh();latest.loadState(new ByteArrayInputStream(second));
        check("完整快照重启后不会再次出卡",latest.notificationSeen("two") && !latest.processNotification(two,false));
        long revision=latest.stateRevision();latest.processNotification(message("noise","收到"),false);
        check("纯闲聊去重不强制整份写盘",latest.stateRevision()==revision);
        String wechat=NotifFilter.messageId("n","com.tencent.mm","同名群","王老师","交报告",123);
        String qq=NotifFilter.messageId("n","com.tencent.mobileqq","同名群","王老师","交报告",123);
        check("同名群同时间同正文跨应用不冲突",!wechat.equals(qq));
        check("相同通知编号稳定",wechat.equals(NotifFilter.messageId("n","com.tencent.mm","同名群","王老师","交报告",123)));
        System.out.println("PASS " + checks);
    }
}
