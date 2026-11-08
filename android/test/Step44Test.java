package com.nopalou;
import java.util.*;
/** 桌面小组件内容规划（TodayPlan）。 */
public class Step44Test {
 static int count; static void check(String n,boolean b){if(!b)throw new AssertionError(n);count++;System.out.println("PASS "+n);}
 static TodayPlan.Course C(String n,String room,String s,String e,int u){return new TodayPlan.Course(n,room,s,e,u);}
 static Engine.Card K(String title,long deadline,boolean done,String kind){Engine.Card c=new Engine.Card();c.title=title;c.deadline=deadline;c.done=done;c.kind=kind;return c;}
 public static void main(String[] args) throws Exception {
  TimeZone original=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  try {
   Calendar cal=Calendar.getInstance();cal.set(2026,10,9,10,40,0);cal.set(Calendar.MILLISECOND,0);long now=cal.getTimeInMillis(); // 周一 10:40
   List<TodayPlan.Course> today=Arrays.asList(C("英语","教东201","14:00","15:40",7),C("高数","教西A101","08:30","10:10",1),C("数据结构","教西B203","10:30","12:10",3),C("体育","操场","16:00","17:40",9));
   List<TodayPlan.Course> tomorrow=Arrays.asList(C("物理","教西C1","10:30","12:10",3),C("线代","教西A2","08:30","10:10",1));

   TodayPlan.Result r=TodayPlan.plan(true,10,today,tomorrow,new ArrayList<>(),now,4,3);
   check("标题:"+r.header,r.header.equals("11月9日 周一 · 第10周"));
   check("上完的课不显示",r.courses.size()==3&&r.courses.stream().noneMatch(l->l.text.startsWith("高数")));
   check("正在上的课标上课中",r.courses.get(0).left.equals("上课中")&&r.courses.get(0).highlight&&r.courses.get(0).text.equals("数据结构 · 教西B203"));
   check("按节次排序",r.courses.get(1).text.startsWith("英语")&&r.courses.get(2).text.startsWith("体育"));
   check("没待办",r.todos.isEmpty()&&r.todoNote.equals("没有待办"));

   // 放不下：最后一行变成“还有 N 节课”，总行数不超过上限
   r=TodayPlan.plan(true,10,today,tomorrow,new ArrayList<>(),now,2,3);
   check("截断:"+r.courses.size(),r.courses.size()==2&&r.courses.get(1).text.equals("还有 2 节课"));
   r=TodayPlan.plan(true,10,today,tomorrow,new ArrayList<>(),now,1,3);
   check("只放一行时显示下一节",r.courses.size()==1&&r.courses.get(0).text.startsWith("数据结构"));

   // 晚上：今天上完了，提示明天第一节
   Calendar night=(Calendar)cal.clone();night.set(Calendar.HOUR_OF_DAY,21);
   r=TodayPlan.plan(true,10,today,tomorrow,new ArrayList<>(),night.getTimeInMillis(),4,3);
   check("上完提示明天:"+r.courseNote,r.courses.isEmpty()&&r.courseNote.equals("今天的课上完了，明天 08:30 线代"));
   r=TodayPlan.plan(true,10,new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),now,4,3);
   check("今天没课",r.courseNote.equals("今天没有课"));
   r=TodayPlan.plan(false,0,new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),now,4,3);
   check("没导入课表",r.courseNote.equals("还没导入课表")&&!r.header.contains("周 ·")&&r.header.equals("11月9日 周一"));
   r=TodayPlan.plan(true,0,new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),now,4,3);
   check("假期不显示周次",!r.header.contains("第"));
   r=TodayPlan.plan(true,10,Arrays.asList(C("实验","","","",5)),tomorrow,new ArrayList<>(),now,4,3);
   check("没时刻按节次显示",r.courses.size()==1&&r.courses.get(0).left.equals("第5节")&&r.courses.get(0).text.equals("实验"));

   // 待办：已完成、提示卡、过期太久的不显示；有时间的在前、按先后
   List<Engine.Card> cards=new ArrayList<>(Arrays.asList(
     K("交实验报告",now+2*86400_000L,false,"todo"),
     K("已经交了",now+86400_000L,true,"todo"),
     K("看一下老师发的图片",0,false,"media"),
     K("填问卷",0,false,"todo"),
     K("数据结构",now+5*3600_000L,false,"exam"),
     K("很久以前的",now-3*86400_000L,false,"todo"),
     K("刚过期",now-3600_000L,false,"todo")));
   r=TodayPlan.plan(true,10,today,tomorrow,cards,now,4,5);
   check("待办数量:"+r.todos.size(),r.todos.size()==4);
   check("过期的排最前",r.todos.get(0).left.equals("已过期")&&r.todos.get(0).text.equals("刚过期"));
   check("考试带前缀且24小时内高亮",r.todos.get(1).text.equals("考试 · 数据结构")&&r.todos.get(1).highlight);
   check("没时间的排最后",r.todos.get(3).left.equals("待确认")&&r.todos.get(3).text.equals("填问卷"));
   r=TodayPlan.plan(true,10,today,tomorrow,cards,now,4,2);
   check("待办截断",r.todos.size()==2&&r.todos.get(1).text.equals("还有 3 项"));
   System.out.println("PASS "+count);
  }finally{TimeZone.setDefault(original);}
 }
}
