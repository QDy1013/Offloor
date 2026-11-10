package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 截图发送日期不足和乱序修订不应变成已确认时间。 */
public final class Step52Test {
    static int count;
    static void check(String name,boolean ok) {if(!ok)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    static Engine fresh(String root) throws Exception {try(InputStream in=Files.newInputStream(Paths.get(root,"app/assets/model.tsv"))){return new Engine(in);}}
    static Engine.Msg msg(String id,String body,long at) {return new Engine.Msg(id,"g","大学物理课程群","王老师",body,"com.tencent.mm",at);}
    public static void main(String[] args) throws Exception {
        long now=1800000000000L;Engine engine=fresh(args[0]);List<Engine.Escalation> sent=new ArrayList<>();engine.escalator=sent::add;
        Engine.Msg screenshot=msg("s","明天18点前交实验报告到学习通",now);screenshot.timeUncertain=true;engine.process(screenshot);
        Engine.Card c=engine.all().get(0);check("未知截图日期保留待核对",c.needsConfirm&&c.sourceTimeUncertain);
        Engine.AiVerdict v=new Engine.AiVerdict();v.cardId=c.id;v.todo=true;v.confidence=.99;v.deadline=c.deadline;v.timeSure=true;v.title=c.title;
        engine.applyAi(v,now);check("模型自报确定也不能解除未知来源日期",c.needsConfirm);
        ByteArrayOutputStream out=new ByteArrayOutputStream();engine.saveState(out);Engine restored=fresh(args[0]);restored.loadState(new ByteArrayInputStream(out.toByteArray()));
        check("重启仍保留来源时间不足",restored.all().get(0).sourceTimeUncertain&&restored.all().get(0).needsConfirm);
        Engine revision=fresh(args[0]);revision.process(msg("a","明天18点前交实验报告到学习通",now));
        revision.process(msg("b","实验报告延期到下周一22点",now+60000));long finalTime=revision.all().get(0).deadline;
        revision.process(msg("old","实验报告延期到周六20点",now+30000));check("乱序旧修订不能覆盖更新期限",revision.all().get(0).deadline==finalTime);
        Engine freshAi=fresh(args[0]);Engine.Msg unknown=msg("u","明天18点前交实验报告到学习通",now);unknown.timeUncertain=true;
        v=new Engine.AiVerdict();v.msg=unknown;v.todo=true;v.title="提交实验报告";v.deadline=now+86400000;v.timeSure=true;v.confidence=.99;
        freshAi.applyAi(v,now);check("AI 新建卡也遵守来源时间限制",freshAi.all().get(0).needsConfirm);
        System.out.println("PASS "+count);
    }
}
