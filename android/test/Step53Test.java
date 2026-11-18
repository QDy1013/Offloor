package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 引导演示运行真实引擎；核对分支、恢复和重复播放必须得到真实一致的结果。 */
public final class Step53Test {
    static String root;static int checks;static long now=1800000000000L;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static InputStream model()throws Exception{return Files.newInputStream(Paths.get(root,"app/assets/model.tsv"));}
    static ShowcaseScenario fresh()throws Exception{try(InputStream in=model()){return new ShowcaseScenario(in,now);}}
    static ShowcaseScenario restore(String checkpoint)throws Exception{try(InputStream in=model()){return ShowcaseScenario.restore(in,checkpoint);}}
    static byte[] save(Engine e)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();e.saveState(out);return out.toByteArray();}
    static Engine.Card card(ShowcaseScenario s,String title){for(Engine.Card c:s.engine.all())if(c.title.contains(title))return c;throw new AssertionError(title);}
    static boolean rejected(String checkpoint){try{restore(checkpoint);return false;}catch(Exception expected){return true;}}
    public static void main(String[] args)throws Exception{
        root=args[0];TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        Engine real;try(InputStream in=model()){real=new Engine(in);}
        real.process(new Engine.Msg("private","private-group","大学物理课程群","王老师","明天18点前交实验报告到学习通","com.tencent.mm",now));byte[] before=save(real);
        ShowcaseScenario s=fresh();check("演示最初没有预制卡片",s.step()==0&&s.engine.all().isEmpty()&&s.engine.escalator==null);
        check("第一步真实处理两条通知",s.advance()&&s.engine.nIn==2&&s.engine.all().size()==2&&s.open()==2);
        long physics=card(s,"物理").deadline;
        check("明确改期仅改对应数据库事项",s.advance()&&card(s,"数据库").updated&&card(s,"物理").deadline==physics);
        long database=card(s,"数据库").deadline;
        check("含糊变更保留两项原时间",s.advance()&&s.awaitingReview()&&s.pending()==2&&card(s,"数据库").deadline==database&&card(s,"物理").deadline==physics);
        check("没有本人选择不能跳过核对",!s.advance()&&s.step()==3&&s.messages().size()==4);
        String pending=s.checkpoint();s=restore(pending);
        check("页面重建可恢复待核对分支",s.awaitingReview()&&s.reviewChoices().size()==2&&s.pending()==2);
        check("无效选择不改变演示",!s.choose("foreign")&&pending.equals(s.checkpoint()));
        for(String decision:new String[]{"数据库","物理","ignore"}){
            ShowcaseScenario branch=restore(pending);
            boolean selected="ignore".equals(decision)?branch.ignore():branch.choose(card(branch,decision).id);
            check("本人可选择或忽略："+decision,selected&&branch.step()==4&&branch.pending()==0);
            if("ignore".equals(decision))check("忽略不修改任何截止",card(branch,"数据库").deadline==database&&card(branch,"物理").deadline==physics);
            else check("只更新本人选定事项："+decision,card(branch,decision).deadline==Engine.parseDeadline("大后天21点",now+3*60000L).value);
            branch=restore(branch.checkpoint());check("本人选择在重建后保留："+decision,branch.step()==4&&!branch.awaitingReview());
            check("明确取消保留可追溯归档："+decision,branch.advance()&&branch.open()==1&&branch.engine.removedCards().size()==1&&branch.engine.removedCards().get(0).cancelled);
            check("最后一步由本人完成剩余事项："+decision,branch.advance()&&branch.finished()&&branch.open()==0&&branch.completed()==1);
            int history=card(branch,"数据库").changes.history().size();
            check("播放结束不会重复处理："+decision,!branch.advance()&&card(branch,"数据库").changes.history().size()==history&&branch.engine.nIn==5);
            ShowcaseScenario restored=restore(branch.checkpoint());
            check("最终状态可以重放恢复："+decision,restored.finished()&&restored.completed()==1&&restored.engine.removedCards().size()==1);
            check("导出明确标注构造数据："+decision,restored.recap().contains("构造场景")&&restored.recap().contains("不代表真实用户效果"));
        }
        check("演示全过程不写入日常引擎",Arrays.equals(before,save(real)));
        check("重开从空演示起步",fresh().step()==0&&fresh().engine.all().isEmpty());
        check("无效进度和版本被拒绝",rejected("SHOWCASE1\n1\n7\nignore")&&rejected("SHOWCASE1\n1\n4\n")&&rejected("SHOWCASE2\n1\n0\n"));
        check("溢出时间不能让恢复卡在循环中",rejected("SHOWCASE1\n9223372036854775807\n4\nignore"));
        System.out.println("PASS "+checks);
    }
}
