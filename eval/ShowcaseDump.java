package com.nopalou;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 将 Android 情境演示使用的同一引擎逐步导出，供无手机审查及 CI 归档。 */
public final class ShowcaseDump {
    static String enc(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    static void dump(ShowcaseScenario s){
        System.out.println("STEP\t"+s.step()+"\t"+enc(s.title())+"\t"+enc(s.note())+"\t"+enc(s.summary()));
        for(ShowcaseScenario.Message m:s.messages())System.out.println("MESSAGE\t"+m.at+"\t"+enc(m.actor)+"\t"+enc(m.text)+"\t"+m.userAction);
        for(Engine.Card c:s.engine.all())card("CARD",c);
        for(Engine.Card c:s.engine.removedCards())card("REMOVED",c);
    }
    static void card(String tag,Engine.Card c){
        System.out.println(tag+"\t"+enc(c.displayTitle())+"\t"+c.deadline+"\t"+c.needsConfirm+"\t"+c.done+"\t"+enc(TaskTrace.text(c)));
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3 || !Arrays.asList("database","physics","ignore").contains(args[1]))throw new IllegalArgumentException("model branch startedAt");
        ShowcaseScenario scenario;
        try(InputStream model=Files.newInputStream(Paths.get(args[0]))){scenario=new ShowcaseScenario(model,Long.parseLong(args[2]));}
        dump(scenario);
        while(!scenario.finished()){
            if(scenario.awaitingReview()){
                boolean applied=false;
                if("ignore".equals(args[1]))applied=scenario.ignore();
                else for(Engine.Card c:scenario.reviewChoices())if(c.title.contains("database".equals(args[1])?"数据库":"物理")){applied=scenario.choose(c.id);break;}
                if(!applied)throw new IllegalStateException("核对分支未执行");
            }else if(!scenario.advance())throw new IllegalStateException("回放未推进");
            dump(scenario);
        }
    }
}
