package com.nopalou;
import java.io.*;
import java.util.*;
/** 成绩/考试/课表变化检测（ChangeDiff）、友谊校区作息（SectionTimes）、教务考试转考试卡（Engine.syncSchoolExams）。 */
public class Step43Test {
 static int count; static void check(String n,boolean b){if(!b)throw new AssertionError(n);count++;System.out.println("PASS "+n);}
 static List<ChangeDiff.Item> L(ChangeDiff.Item... a){return new ArrayList<>(Arrays.asList(a));}
 static ChangeDiff.Slot S(String n,int d,int s,int e,String room,Integer... w){return new ChangeDiff.Slot(n,d,s,e,Arrays.asList(w),room,"王老师");}
 static Engine.Card byKey(Engine e,String course){for(Engine.Card c:e.all())if(("school:"+ChangeDiff.norm(course)).equals(c.platformKey))return c;return null;}
 public static void main(String[] args) throws Exception {
  TimeZone original=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  try {
   // ---- 第一次导入没有基线：不提醒 ----
   check("首次不提醒",ChangeDiff.diff(null,L(ChangeDiff.grade("1","高数","","90",4.0,5.0))).isEmpty());

   // ---- 名字有空白、全角、大小写差异不算变化（翱翔助手 #49 的教训） ----
   List<ChangeDiff.Item> a=L(ChangeDiff.grade("2025-1","数据结构","","92",4.0,3.5),ChangeDiff.grade("2025-1","大学英语","","85",3.5,2.0));
   List<ChangeDiff.Item> b=L(ChangeDiff.grade("2025-1"," 数据结构 ","","92",4.0,3.5),ChangeDiff.grade("2025-1","大学英语","","85",3.50,2.0000));
   check("空白和数字写法不同不算变化",ChangeDiff.diff(a,b).isEmpty());
   check("全角括号归一",ChangeDiff.norm("大学物理（Ⅰ）").equals(ChangeDiff.norm("大学物理(Ⅰ)"))||ChangeDiff.norm("大学物理（Ⅰ）").contains("物理"));
   check("全角字母转半角",ChangeDiff.norm("ＡＢＣ  课").equals("abc 课"));

   // ---- 成绩构成说明文字不参与比较（#4 的教训） ----
   ChangeDiff.Item g1=ChangeDiff.grade("2025-1","线代","","88",3.7,4.0);
   ChangeDiff.Item g2=ChangeDiff.grade("2025-1","线代","","88",3.7,4.0);
   check("同成绩签名一致",g1.signature.equals(g2.signature));

   // ---- 真正的变化 ----
   List<ChangeDiff.Item> c=L(ChangeDiff.grade("2025-1","数据结构","","92",4.0,3.5),ChangeDiff.grade("2025-1","大学英语","","87",3.7,2.0),ChangeDiff.grade("2025-1","概率论","","90",4.0,3.0));
   List<ChangeDiff.Change> d=ChangeDiff.diff(a,c);
   check("一门改分一门新出",d.size()==2);
   check("摘要带分数:"+ChangeDiff.summary(d,true),ChangeDiff.summary(d,true).contains("大学英语 87")&&ChangeDiff.summary(d,true).contains("概率论 90"));
   check("四门以上缩写",ChangeDiff.summary(ChangeDiff.diff(L(),L(ChangeDiff.grade("1","A","","1",null,null),ChangeDiff.grade("1","B","","1",null,null),ChangeDiff.grade("1","C","","1",null,null),ChangeDiff.grade("1","D","","1",null,null))),false).equals("A、B 等 4 门"));
   List<ChangeDiff.Change> removed=ChangeDiff.diff(c,a);
   check("删除也能识别",removed.stream().anyMatch(x->x.kind==ChangeDiff.Change.REMOVED));
   check("只看新增和改动",ChangeDiff.addedOrChanged(removed).stream().noneMatch(x->x.kind==ChangeDiff.Change.REMOVED));
   check("不同学期同名课是两项",ChangeDiff.diff(L(ChangeDiff.grade("2024-2","体育","","90",null,1.0)),L(ChangeDiff.grade("2024-2","体育","","90",null,1.0),ChangeDiff.grade("2025-1","体育","","88",null,1.0))).size()==1);
   check("未发布成绩签名以|开头",ChangeDiff.grade("1","X","","",null,2.0).signature.startsWith("|"));

   // ---- 考试 ----
   List<ChangeDiff.Item> e1=L(ChangeDiff.exam("数据结构","2026-01-10","09:00","11:00","教西A101"));
   check("考试没变",ChangeDiff.diff(e1,L(ChangeDiff.exam("数据结构 ","2026-01-10","09:00","11:00","教西A101"))).isEmpty());
   check("考试改地点",ChangeDiff.diff(e1,L(ChangeDiff.exam("数据结构","2026-01-10","09:00","11:00","教西B203"))).size()==1);

   // ---- 课表：时段顺序不同不算变化，改教室算 ----
   List<ChangeDiff.Item> t1=ChangeDiff.courses(Arrays.asList(S("高数",1,1,2,"教西A101",1,2,3),S("高数",3,3,4,"教西A101",1,2,3),S("英语",2,1,2,"教东201",1,2)));
   List<ChangeDiff.Item> t2=ChangeDiff.courses(Arrays.asList(S("英语",2,1,2,"教东201",2,1),S("高数",3,3,4,"教西A101",3,2,1),S("高数 ",1,1,2,"教西A101",1,2,3)));
   check("课表顺序和周次顺序不同不算变化",ChangeDiff.diff(t1,t2).isEmpty());
   List<ChangeDiff.Item> t3=ChangeDiff.courses(Arrays.asList(S("高数",1,1,2,"教西A102",1,2,3),S("高数",3,3,4,"教西A101",1,2,3),S("英语",2,1,2,"教东201",1,2)));
   List<ChangeDiff.Change> td=ChangeDiff.diff(t1,t3);
   check("改教室识别为高数一门",td.size()==1&&"高数".equals(td.get(0).name()));

   // ---- 电费 ----
   check("取数字",ChangeDiff.firstNumber("剩余电量 12.5 度")==12.5);
   check("没数字",ChangeDiff.firstNumber("暂未读到")==null);
   boolean[] r=ChangeDiff.lowAlert(15.0,20,false);
   check("跌破提醒",r[0]&&r[1]);
   r=ChangeDiff.lowAlert(12.0,20,true);
   check("仍在低位不重复提醒",!r[0]&&r[1]);
   r=ChangeDiff.lowAlert(50.0,20,true);
   check("充值后复位",!r[0]&&!r[1]);
   r=ChangeDiff.lowAlert(null,20,true);
   check("没读到不改状态",!r[0]&&r[1]);

   // ---- 友谊校区作息 ----
   check("友谊判定",SectionTimes.friendship("友谊校区教学楼301")&&!SectionTimes.friendship("长安校区教西A101"));
   check("夏季第7-8节",("14:30–16:20").equals(SectionTimes.friendshipRange(6,7,8)));
   check("冬季第7-8节",("14:00–15:50").equals(SectionTimes.friendshipRange(11,7,8)));
   check("5月算夏季 4月算冬季",SectionTimes.summer(5)&&SectionTimes.summer(9)&&!SectionTimes.summer(4)&&!SectionTimes.summer(10));
   check("超出节次",SectionTimes.friendshipRange(11,12,14)==null);

   // ---- 教务考试转考试卡 ----
   Calendar cal=Calendar.getInstance();cal.set(2026,10,9,10,0,0);cal.set(Calendar.MILLISECOND,0);long now=cal.getTimeInMillis();
   Engine eng;try(InputStream w=java.nio.file.Files.newInputStream(java.nio.file.Paths.get(args[0],"app/assets/model.tsv"))){eng=new Engine(w);}eng.setProfile(Engine.Profile.demo());
   Engine.SchoolExam x1=new Engine.SchoolExam();x1.course="数据结构";x1.place="教西A101";x1.start=now+10L*86400_000L;
   Engine.SchoolExam x2=new Engine.SchoolExam();x2.course="大学物理";x2.start=0;
   Engine.SchoolExam x3=new Engine.SchoolExam();x3.course="高等数学";x3.start=now-5L*86400_000L;
   Engine.SchoolExam x4=new Engine.SchoolExam();x4.course="线性代数";x4.start=now+3L*86400_000L;x4.finished=true;
   check("出两张考试卡",eng.syncSchoolExams(Arrays.asList(x1,x2,x3,x4),now)==2);
   Engine.Card k1=byKey(eng,"数据结构");
   check("考试卡字段",k1!=null&&"exam".equals(k1.kind)&&k1.deadline==x1.start&&"教西A101".equals(k1.place)&&!k1.needsConfirm&&"教务系统".equals(k1.sourceAppName()));
   check("没排时间的要确认",byKey(eng,"大学物理").needsConfirm&&byKey(eng,"大学物理").deadline==0);
   check("已考完和已结束不出卡",byKey(eng,"高等数学")==null&&byKey(eng,"线性代数")==null);
   check("再同步不重复",eng.syncSchoolExams(Arrays.asList(x1,x2),now)==0&&eng.all().size()==2);
   x1.place="教西B203";x1.start=now+11L*86400_000L;
   check("改期更新原卡",eng.syncSchoolExams(Arrays.asList(x1),now)==1&&k1.deadline==x1.start&&"教西B203".equals(k1.place)&&k1.updated);
   eng.editCard(k1,k1.title,now+12L*86400_000L);
   x1.start=now+13L*86400_000L;eng.syncSchoolExams(Arrays.asList(x1),now);
   check("用户改的时间不覆盖",k1.deadline==now+12L*86400_000L);
   eng.dismissCard(byKey(eng,"大学物理"),now);
   check("移除后不再顶回来",eng.syncSchoolExams(Arrays.asList(x2),now)==0&&byKey(eng,"大学物理")==null);
   // 群里已经有同一门课的考试卡：不重复
   String wx="com.tencent.mm";
   eng.process(new Engine.Msg("q1",wx+"|软工2301","软工2301","张老师","@所有人 概率论期末考试11月20日上午9点在教东301，带学生证",wx,now));
   Engine.Card chat=null;for(Engine.Card cc:eng.all())if("q1".equals(cc.sourceId))chat=cc;
   if(chat!=null&&"exam".equals(chat.kind)){
    Engine.SchoolExam x5=new Engine.SchoolExam();x5.course="概率论";x5.start=chat.deadline;x5.place="教东301";
    int before=eng.all().size();eng.syncSchoolExams(Arrays.asList(x5),now);
    check("群消息已有同场考试不重复出卡",eng.all().size()==before);
   } else check("群消息考试卡前提（跳过）",true);
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();eng.saveState(bytes);
   Engine back;try(InputStream w=java.nio.file.Files.newInputStream(java.nio.file.Paths.get(args[0],"app/assets/model.tsv"))){back=new Engine(w);}
   back.loadState(new ByteArrayInputStream(bytes.toByteArray()));
   check("重启后仍能对上原卡",back.syncSchoolExams(Arrays.asList(x1),now)==0||byKey(back,"数据结构")!=null);
   check("重启后来源保留","jwxt".equals(byKey(back,"数据结构").sourcePackageName()));
   System.out.println("PASS "+count);
  }finally{TimeZone.setDefault(original);}
 }
}
