package com.nopalou;

import java.io.*;
import java.util.*;

/** 隔离的构造会话；逐步调用真实引擎，演示结果不使用预制卡片或真实账户。 */
public final class ShowcaseScenario {
    public static final int LAST_STEP=6;
    private static final String GROUP="showcase|course", NAME="课程事项群（样例）";
    private static final String DATABASE="showcase-database", PHYSICS="showcase-physics", REVIEW="showcase-review";
    private static final String[] TITLES={"演示就绪","从通知形成事项","明确改期","含糊变更待核对","本人选择处理方式","来源取消事项","本人完成事项"};
    private static final String[] NOTES={
        "一段构造会话，展示两份实验报告如何随通知变化。点击下一步开始。",
        "两条通知形成独立事项；点击卡片可查看原文。",
        "老师明确提到数据库实验报告，只调整这一项；原文和变更保留。",
        "两项都叫实验报告，暂时无法确定对象。先保留原时间，请选择对应事项或忽略整条变更。",
        "处理方式由本人选择。其他候选解除这条通知的待核对状态。",
        "物理实验报告收到明确取消通知，保留取消记录。",
        "数据库实验报告由本人标记完成。完整过程可回看，也可重新开始。"
    };
    public static final class Message {
        public final long at;
        public final String actor,text;
        public final boolean userAction;
        Message(long at,String actor,String text,boolean userAction){this.at=at;this.actor=actor;this.text=text;this.userAction=userAction;}
    }
    public final Engine engine;
    public final long startedAt;
    private int step;
    private String decision="";
    private final List<Message> messages=new ArrayList<>();

    public ShowcaseScenario(InputStream model,long now) throws Exception {
        if(now<=0 || now>Long.MAX_VALUE-14L*86400000L)throw new IllegalArgumentException("演示起点无效");
        startedAt=now;engine=new Engine(model);
        engine.setGroupMode(GROUP,NAME,"showcase",Engine.MODE_FOCUS,now);
    }
    public int step(){return step;}
    public String title(){return TITLES[step];}
    public String note(){return NOTES[step];}
    public boolean awaitingReview(){return step==3;}
    public boolean finished(){return step==LAST_STEP;}
    public String nextLabel(){
        return new String[]{"接收两条样例通知","接收明确改期","接收含糊变更","请先核对对应事项","接收取消通知","标记剩余事项完成","演示已完成"}[step];
    }
    public List<Message> messages(){return new ArrayList<>(messages);}
    public List<Engine.Card> reviewChoices(){
        List<Engine.Card> choices=new ArrayList<>();
        if(awaitingReview())for(Engine.Card card:engine.all())if(card.changes.find(REVIEW)!=null)choices.add(card);
        return choices;
    }
    private long actionTime(){return startedAt+messages.size()*60000L;}
    private void send(String id,String text){
        long at=actionTime();messages.add(new Message(at,"王老师",text,false));
        engine.process(new Engine.Msg(id,GROUP,NAME,"王老师",text,"showcase",at));
    }
    private Engine.Card cardFor(String source){
        for(Engine.Card card:engine.all())if(source.equals(card.sourceId))return card;
        return null;
    }
    public boolean advance(){
        switch(step){
            case 0:
                send(DATABASE,"明天18点前交数据库实验报告到学习通");
                send(PHYSICS,"后天18点前交物理实验报告到学习通");break;
            case 1:send("showcase-change","数据库实验报告改为大后天22点提交到学习通");break;
            case 2:send(REVIEW,"实验报告延期到大后天21点");break;
            case 3:return false;
            case 4:send("showcase-cancel","物理实验报告取消了，不用交了");break;
            case 5:
                Engine.Card card=cardFor(DATABASE);
                long at=actionTime();
                if(card==null || !engine.setCardDone(card,true,at))return false;
                messages.add(new Message(at,"本人","标记数据库实验报告已完成",true));break;
            default:return false;
        }
        step++;return true;
    }
    public boolean choose(String cardId){
        if(!awaitingReview())return false;
        for(Engine.Card card:reviewChoices())if(card.id.equals(cardId)){
            long at=actionTime();
            if(!engine.resolvePendingChange(card,REVIEW,at))return false;
            decision=card.sourceId;
            messages.add(new Message(at,"本人","将含糊变更对应到："+card.displayTitle(),true));step=4;return true;
        }
        return false;
    }
    public boolean ignore(){
        List<Engine.Card> choices=reviewChoices();
        if(choices.isEmpty())return false;
        long at=actionTime();
        if(!engine.ignorePendingChange(choices.get(0),REVIEW,at))return false;
        decision="ignore";messages.add(new Message(at,"本人","忽略这条含糊变更，保留原安排",true));step=4;return true;
    }
    public int pending(){int n=0;for(Engine.Card c:engine.all())if(c.needsConfirm&&!c.done)n++;return n;}
    public int completed(){int n=0;for(Engine.Card c:engine.all())if(c.done)n++;return n;}
    public int open(){int n=0;for(Engine.Card c:engine.all())if(!c.done)n++;return n;}
    public String summary(){return "待办 "+open()+" · 待核对 "+pending()+" · 已完成 "+completed()+" · 已取消 "+engine.removedCards().size();}
    public String checkpoint(){return "SHOWCASE1\n"+startedAt+"\n"+step+"\n"+decision;}
    public static ShowcaseScenario restore(InputStream model,String checkpoint) throws Exception {
        if(checkpoint==null || checkpoint.length()>200)throw new IOException("演示进度无效");
        String[] parts=checkpoint.split("\n",-1);
        if(parts.length!=4 || !"SHOWCASE1".equals(parts[0]))throw new IOException("演示进度版本无效");
        long now;int target;
        try{now=Long.parseLong(parts[1]);target=Integer.parseInt(parts[2]);}catch(NumberFormatException ex){throw new IOException("演示进度数值无效");}
        String choice=parts[3];
        if(target<0 || target>LAST_STEP || now<=0 || (target<4?!choice.isEmpty():!Arrays.asList(DATABASE,PHYSICS,"ignore").contains(choice)))throw new IOException("演示步骤无效");
        ShowcaseScenario scenario=new ShowcaseScenario(model,now);
        while(scenario.step<target){
            if(scenario.awaitingReview()){
                if("ignore".equals(choice)){if(!scenario.ignore())throw new IOException("演示忽略状态无效");}
                else{Engine.Card selected=scenario.cardFor(choice);if(selected==null||!scenario.choose(selected.id))throw new IOException("演示核对状态无效");}
            }else if(!scenario.advance())throw new IOException("演示无法恢复");
        }
        return scenario;
    }
    public String recap(){
        StringBuilder out=new StringBuilder("outfloor 构造场景回放\n仅使用本机规则与分类，不调用云端；不代表真实用户效果。\n\n");
        out.append("步骤 ").append(step).append(" / ").append(LAST_STEP).append(" · ").append(title()).append("\n").append(summary());
        for(Message message:messages)out.append("\n\n").append(TaskTrace.time(message.at)).append(" · ").append(message.actor).append(message.userAction?"（本人操作）":"（样例通知）").append("\n").append(message.text);
        return out.toString();
    }
}
