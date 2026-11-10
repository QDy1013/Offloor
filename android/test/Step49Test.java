package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 歧义变更可由本人关联，并能追溯与恢复。 */
public final class Step49Test {
    static String root; static int count,seq; static long now=1800000000000L;
    static void check(String label,boolean ok) { if(!ok) throw new AssertionError(label);count++;System.out.println("PASS "+label); }
    static Engine fresh() throws Exception { try(InputStream in=Files.newInputStream(Paths.get(root,"app/assets/model.tsv"))) { return new Engine(in); } }
    static void say(Engine e,String text) { e.process(new Engine.Msg("change-"+(++seq),"g","大学物理课程群","王老师",text,"com.tencent.mm",now+seq*1000L)); }
    static Engine pair() throws Exception {
        Engine e=fresh();say(e,"周五18点前交数据库实验报告到学习通");say(e,"周六18点前交物理实验报告到学习通");return e;
    }
    static Engine roundTrip(Engine e) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();e.saveState(b);Engine f=fresh();f.loadState(new ByteArrayInputStream(b.toByteArray()));return f;
    }
    public static void main(String[] args) throws Exception {
        root=args[0];Engine e=pair();long second=e.all().get(1).deadline;
        say(e,"实验报告延期到下周一22点");
        check("歧义通知保留两份候选关系",e.all().get(0).changes.pending().size()==1&&e.all().get(1).changes.hasPending());
        e=roundTrip(e);Engine.Card chosen=e.all().get(0);String id=chosen.changes.pending().get(0).id;
        check("重启后可以选择明确事项",e.resolvePendingChange(chosen,id));
        check("只更新本人选择的事项",chosen.updated&&!chosen.needsConfirm&&e.all().get(1).deadline==second);
        check("其他候选解除这条通知的待核对",!e.all().get(1).needsConfirm&&!e.all().get(1).changes.hasPending());
        check("原文与处理历史保留",chosen.changes.history().size()>=2&&chosen.changes.history().get(0).text.contains("延期"));
        Engine ignore=pair();say(ignore,"实验报告延期到下周一22点");
        String ignored=ignore.all().get(0).changes.pending().get(0).id;
        check("可忽略整条不相关变更",ignore.ignorePendingChange(ignore.all().get(0),ignored)&&!ignore.all().get(1).needsConfirm);
        Engine cancel=fresh();say(cancel,"明天18点前交实验报告到学习通");Engine.Card old=cancel.all().get(0);
        say(cancel,"实验报告取消了，不用交了");
        check("取消事项保留可恢复记录",cancel.all().isEmpty()&&cancel.removedCards().contains(old)&&old.cancelled);
        cancel=roundTrip(cancel);check("取消记录重启后仍保留",cancel.removedCards().get(0).cancelled&&!cancel.removedCards().get(0).changes.history().isEmpty());
        say(cancel,"明天18点前交实验报告到学习通");
        check("重新布置不会被取消档案挡住",cancel.all().size()==1);
        check("有新版本时不恢复旧取消卡",!cancel.restoreCard(cancel.removedCards().get(0)));
        System.out.println("PASS "+count);
    }
}
