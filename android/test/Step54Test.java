package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 用户动作和 AI 动作必须如实留痕；完成后撤销也不能复用旧 AI 快照。 */
public final class Step54Test {
    static String root;static int checks;static long now=1800000000000L;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static Engine fresh()throws Exception{try(InputStream in=Files.newInputStream(Paths.get(root,"app/assets/model.tsv"))){return new Engine(in);}}
    static Engine source()throws Exception{Engine e=fresh();e.process(new Engine.Msg("a","g","大学物理课程群","王老师","明天18点前交实验报告到学习通","com.tencent.mm",now));return e;}
    static Engine restore(Engine e)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();e.saveState(out);Engine r=fresh();r.loadState(new ByteArrayInputStream(out.toByteArray()));return r;}
    static TaskChanges.Event last(Engine.Card c){List<TaskChanges.Event> h=c.changes.history();return h.get(h.size()-1);}
    public static void main(String[] args)throws Exception{
        root=args[0];Engine e=source();Engine.Card c=e.all().get(0);String original=c.sourceText;String snapshot=Engine.aiCardState(c);
        long revision=e.stateRevision();check("完成会通知保存并记录本人",e.setCardDone(c,true,now+1000)&&e.stateRevision()>revision&&"本人".equals(last(c).actor));
        check("重复完成不会增加历史",!e.setCardDone(c,true,now+1500)&&c.changes.history().size()==1);
        check("撤销完成保留真实操作",e.setCardDone(c,false,now+2000)&&last(c).description.contains("撤销完成"));
        Engine.AiVerdict v=new Engine.AiVerdict();v.cardId=c.id;v.cardState=snapshot;v.todo=false;v.confidence=.99;
        check("完成后撤销也使旧 AI 快照失效",!e.applyAi(v,now+3000)&&e.all().contains(c));
        check("人工修正保留原始来源",e.editCard(c,"报告终稿\n核对\t✓",now+86400000L,now+4000)&&original.equals(c.sourceText));
        check("修正前后的标题与时间可追溯",last(c).text.contains("标题：")&&last(c).text.contains("时间：")&&last(c).text.contains("→"));
        int n=c.changes.history().size();check("无变化保存不制造新操作",e.editCard(c,c.displayTitle(),c.deadline,now+5000)&&c.changes.history().size()==n);
        check("手动移除有明确操作记录",e.dismissCard(c,now+6000)&&"本人".equals(last(c).actor)&&last(c).description.contains("移除"));
        check("恢复有独立记录",e.restoreCard(c,now+7000)&&last(c).description.contains("恢复"));
        e=restore(e);c=e.all().get(0);
        check("重启后原文和人工记录完整",TaskTrace.text(c).contains(original)&&TaskTrace.text(c).contains("本人")&&TaskTrace.text(c).contains("终稿\n核对\t✓"));
        List<TaskTrace.Entry> trace=TaskTrace.entries(c);check("来源始终独立于历史保留",trace.get(0).original&&trace.get(0).text.equals(original));
        for(int i=0;i<45;i++)e.setCardDone(c,!c.done,now+8000+i);
        check("历史有界但原文仍能查看",c.changes.history().size()==20&&TaskTrace.entries(c).size()==21&&TaskTrace.entries(c).get(0).text.equals(original));
        revision=e.stateRevision();check("非法和外来动作不修改当前引擎",!e.setCardDone(new Engine.Card(),true,now)&&!e.editCard(c,null,0L,now)&&!e.setCardDone(c,false,0)&&e.stateRevision()==revision);
        Engine ai=source();Engine.Card aiCard=ai.all().get(0);v=new Engine.AiVerdict();v.cardId=aiCard.id;v.todo=false;v.confidence=.99;
        check("AI 移除不冒充本人操作",ai.applyAi(v,now+1000)&&"AI 复核".equals(last(ai.removedCards().get(0)).actor));
        Engine ambiguous=fresh();
        ambiguous.process(new Engine.Msg("db","g","课程群","王老师","明天18点前交数据库实验报告到学习通","eval",now));
        ambiguous.process(new Engine.Msg("ph","g","课程群","王老师","后天18点前交物理实验报告到学习通","eval",now+1000));
        ambiguous.process(new Engine.Msg("rev","g","课程群","王老师","实验报告延期到大后天21点","eval",now+2000));
        Engine.Card chosen=ambiguous.all().get(0),other=ambiguous.all().get(1);
        ambiguous.editCard(chosen,chosen.displayTitle(),chosen.deadline,now+2500);
        v=new Engine.AiVerdict();v.cardId=chosen.id;v.cardState=Engine.aiCardState(chosen);v.todo=true;v.confidence=.99;v.title=chosen.title;v.deadline=chosen.deadline;v.timeSure=true;
        ambiguous.applyAi(v,now+2600);
        check("AI 不替本人解除未关联变更",chosen.changes.hasPending()&&chosen.needsConfirm);
        check("关联变更后其他候选也有解除原因",ambiguous.resolvePendingChange(chosen,"rev",now+3000)&&last(other).description.contains(chosen.displayTitle())&&"本人".equals(last(other).actor));
        check("核对操作保留实际操作时刻",last(chosen).at==now+3000);
        System.out.println("PASS "+checks);
    }
}
