package com.nopalou;
import java.io.*;
import java.util.*;
/** 二/三级复核接口（escalator + applyAi）、图片/语音提示卡、NotifParser.media。 */
public class Step42Test {
 static int count; static void check(String n,boolean b){if(!b)throw new AssertionError(n);count++;System.out.println("PASS "+n);}
 static Engine engine(String root) throws Exception {
  try(InputStream w=java.nio.file.Files.newInputStream(java.nio.file.Paths.get(root,"app/assets/model.tsv"))){Engine e=new Engine(w);e.setProfile(Engine.Profile.demo());return e;}
 }
 static Engine.Card find(Engine e,String sourceId){for(Engine.Card c:e.all())if(sourceId.equals(c.sourceId))return c;return null;}
 public static void main(String[] args) throws Exception {
  TimeZone original=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  try {
   Calendar cal=Calendar.getInstance();cal.set(2026,10,9,10,0,0);cal.set(Calendar.MILLISECOND,0);long t=cal.getTimeInMillis();
   String wx="com.tencent.mm", g=wx+"|软工2301班";

   // ---- 没装 escalator：行为不变，不抛异常 ----
   Engine plain=engine(args[0]);
   plain.process(new Engine.Msg("p1",g,"软工2301班","张老师","@所有人 请大家尽快提交实验报告",wx,t));
   check("无复核时照常处理",plain.nIn==1);

   // ---- 缺时间的待办卡会送去复核 ----
   Engine e=engine(args[0]);
   List<Engine.Escalation> sent=new ArrayList<>();
   e.escalator=sent::add;
   e.process(new Engine.Msg("m1",g,"软工2301班","张老师","@所有人 请大家尽快提交实验报告",wx,t));
   Engine.Card c=find(e,"m1");
   check("出了待办卡",c!=null&&"todo".equals(c.kind));
   check("缺时间的卡需要确认",c.needsConfirm&&c.deadline==0);
   Engine.Escalation es=null;for(Engine.Escalation x:sent)if(c.id.equals(x.cardId))es=x;
   check("卡片送去复核",es!=null&&"extract".equals(es.reason)&&es.msg.text.contains("实验报告"));

   // ---- 复核补全：标题、时间、提交方式 ----
   Engine.AiVerdict v=new Engine.AiVerdict();
   v.cardId=c.id;v.todo=true;v.title="提交数据库实验报告";v.deadline=t+2*86400_000L;v.timeSure=true;v.channel="学习通";v.confidence=0.9;
   check("复核写回有变化",e.applyAi(v,t));
   check("时间补上",c.deadline==t+2*86400_000L&&!c.needsConfirm);
   check("标题补全:"+c.title,"提交数据库实验报告".equals(c.title));
   check("提交方式补上","学习通".equals(c.channel));
   check("标记已复核",c.aiChecked&&c.reason.startsWith("AI"));

   // ---- 用户改过的时间不被覆盖 ----
   e.process(new Engine.Msg("m2",g,"软工2301班","张老师","@所有人 请大家尽快交一下班费",wx,t+60_000L));
   Engine.Card c2=find(e,"m2");
   check("第二张卡",c2!=null);
   e.editCard(c2,"交班费",t+86400_000L);
   Engine.AiVerdict v2=new Engine.AiVerdict();v2.cardId=c2.id;v2.todo=true;v2.title="交班费50元";v2.deadline=t+5*86400_000L;v2.timeSure=true;v2.confidence=0.9;
   e.applyAi(v2,t);
   check("用户设的时间保留",c2.deadline==t+86400_000L);
   check("用户改的标题保留","交班费".equals(c2.displayTitle()));

   // ---- 复核认为不是待办：移到已移除，可恢复 ----
   e.process(new Engine.Msg("m3",g,"软工2301班","张老师","@所有人 请大家尽快看一下群公告",wx,t+120_000L));
   Engine.Card c3=find(e,"m3");
   check("第三张卡",c3!=null);
   Engine.AiVerdict v3=new Engine.AiVerdict();v3.cardId=c3.id;v3.todo=false;v3.confidence=0.92;v3.note="只是提醒看公告";
   check("不是待办被移走",e.applyAi(v3,t)&&!e.all().contains(c3)&&e.removedCards().contains(c3));
   check("可以恢复",e.restoreCard(c3));
   // 把握不够不移走
   Engine.AiVerdict v3b=new Engine.AiVerdict();v3b.cardId=c3.id;v3b.todo=false;v3b.confidence=0.5;
   e.applyAi(v3b,t);
   check("把握不够不移走",e.all().contains(c3));

   // ---- 漏判消息：复核新建卡，重复结论不重复建 ----
   Engine.Msg missed=new Engine.Msg("m4",g,"软工2301班","学委","下周三上课前把读书笔记放讲台上",wx,t+180_000L);
   Engine.AiVerdict v4=new Engine.AiVerdict();v4.msg=missed;v4.todo=true;v4.title="交读书笔记";v4.deadline=t+9*86400_000L;v4.timeSure=false;v4.place="讲台";v4.confidence=0.8;
   int before=e.all().size();
   check("漏判消息出卡",e.applyAi(v4,t)&&e.all().size()==before+1);
   Engine.Card c4=find(e,"m4");
   check("时间待核对",c4.needsConfirm&&c4.aiChecked&&"讲台".equals(c4.place));
   check("重复结论不重复建",!e.applyAi(v4,t)&&e.all().size()==before+1);
   Engine.AiVerdict v5=new Engine.AiVerdict();v5.msg=new Engine.Msg("m5",g,"软工2301班","学委","有人去吃饭吗",wx,t);v5.todo=true;v5.confidence=0.4;v5.title="吃饭";
   check("把握低不新建",!e.applyAi(v5,t));
   Engine.AiVerdict v6=new Engine.AiVerdict();v6.msg=new Engine.Msg("m6",g,"软工2301班","学委","作业周五交",wx,t);v6.todo=true;v6.confidence=0.9;v6.title="交作业";v6.deadline=t-400L*86400_000L;
   e.applyAi(v6,t);
   check("离谱的时间不采用",find(e,"m6")!=null&&find(e,"m6").deadline==0);

   // ---- 低置信度的闲聊/讨论送去复判 ----
   int esc0=sent.size();
   e.process(new Engine.Msg("m7",g,"软工2301班","同学甲","明天下午记得带实验报告来",wx,t+240_000L));
   boolean reviewed=false;for(int i=esc0;i<sent.size();i++)if(sent.get(i).msg.id.equals("m7"))reviewed=true;
   check("带动作和时间的消息要么出卡要么送复核",reviewed||find(e,"m7")!=null);
   int esc1=sent.size();
   e.process(new Engine.Msg("m8",g,"软工2301班","同学乙","明天交作业吗？",wx,t+300_000L));
   boolean asked=false;for(int i=esc1;i<sent.size();i++)if(sent.get(i).msg.id.equals("m8")&&sent.get(i).cardId==null)asked=true;
   check("提问不送复判",!asked);

   // ---- 图片/语音提示卡 ----
   check("普通群友的图片不出卡",!e.noteMedia(new Engine.Msg("i1",g,"软工2301班","同学甲","[图片]",wx,t),"[图片]"));
   check("老师的图片出提示卡",e.noteMedia(new Engine.Msg("i2",g,"软工2301班","张老师","[图片]",wx,t),"[图片]"));
   Engine.Card mc=find(e,"i2");
   check("提示卡标题:"+mc.title,"media".equals(mc.kind)&&mc.title.contains("张老师")&&mc.title.contains("图片"));
   check("合并同人语音",e.noteMedia(new Engine.Msg("i3",g,"软工2301班","张老师","[语音]",wx,t+60_000L),"[语音]"));
   int media=0;for(Engine.Card x:e.all())if("media".equals(x.kind))media++;
   check("同人十分钟内只一张",media==1&&e.all().stream().anyMatch(x->"media".equals(x.kind)&&x.title.contains("图片和语音")));
   check("@全体的图片也出卡",e.noteMedia(new Engine.Msg("i4",wx+"|另一个群","另一个群","群友","[有全体消息][图片]",wx,t),"[图片]"));
   e.setGroupMode(wx+"|吵闹群","吵闹群",wx,Engine.MODE_IGNORE,t);
   check("不用管的群不出卡",!e.noteMedia(new Engine.Msg("i5",wx+"|吵闹群","吵闹群","张老师","[图片]",wx,t),"[图片]"));
   check("表情不算",!e.noteMedia(new Engine.Msg("i6",g,"软工2301班","张老师","[表情]",wx,t),"[表情]"));

   // ---- 存档往返 ----
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();e.saveState(bytes);
   Engine back=engine(args[0]);back.loadState(new ByteArrayInputStream(bytes.toByteArray()));
   check("复核标记保留",find(back,"m1")!=null&&find(back,"m1").aiChecked);
   check("提示卡保留",find(back,"i2")!=null&&"media".equals(find(back,"i2").kind));

   // ---- NotifParser.media ----
   List<NotifParser.Parsed> p=NotifParser.media("软工2301班","张老师: [图片]",null,null,null,false);
   check("单条图片",p.size()==1&&"张老师".equals(p.get(0).sender)&&"[图片]".equals(p.get(0).body));
   p=NotifParser.media("软工2301班",null,new String[]{"甲","乙","丙"},new String[]{"[语音]","今晚交作业","[表情]"},new long[]{1,2,3},false);
   check("多条只挑媒体",p.size()==1&&"甲".equals(p.get(0).sender)&&p.get(0).time==1);
   check("parse 仍跳过占位符",NotifParser.parse("软工2301班","张老师: [图片]",null,null,null,false).isEmpty());
   check("汇总通知不算",NotifParser.media("群","[图片]",null,null,null,true).isEmpty());
   p=NotifParser.media("群","[3条] 张老师: [有全体消息][文件]",null,null,null,false);
   check("折叠计数 + 全体",p.size()==0||p.get(0).body.endsWith("[文件]"));

   System.out.println("PASS "+count);
  }finally{TimeZone.setDefault(original);}
 }
}
