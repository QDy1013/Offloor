package com.nopalou;
import java.text.SimpleDateFormat;
import java.util.*;
public final class Step51Test {
    static int count;static SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT);
    static long t(String text)throws Exception{return f.parse(text).getTime();}
    static void check(String text,boolean ok){if(!ok)throw new AssertionError(text);count++;System.out.println("PASS "+text);}
    public static void main(String[] args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));f.setTimeZone(TimeZone.getDefault());
        long now=t("2026-11-09 10:00");
        check("昨天下午保留时段",ChatTime.screenshotTime("昨天下午3:00",now)==t("2026-11-08 15:00"));
        check("昨天晚上保留时段",ChatTime.screenshotTime("昨天晚上8:00",now)==t("2026-11-08 20:00"));
        check("截图日期按过去解析",ChatTime.screenshotTime("11月8日 20:05",now)==t("2026-11-08 20:05"));
        check("跨年日期不跳到未来",ChatTime.screenshotTime("12月31日 20:05",t("2027-01-01 10:00"))==t("2026-12-31 20:05"));
        check("非法日期不滚动到下月",ChatTime.parse("2026-02-30 08:00",now)<0);
        check("非法闰日不滚动",ChatTime.parse("2026年2月29日 08:00",now)<0);
        check("只有钟点的相对期限需确认",ChatTime.needsTimeConfirmation("20:05","明天18点交报告"));
        check("完整日期可锚定明天",!ChatTime.needsTimeConfirmation("2026-11-08 20:05","明天18点交报告"));
        check("缺少时刻不能确定两小时后",ChatTime.needsTimeConfirmation("2026-11-08","还有2小时截止"));
        check("原文绝对时间不受钟点锚点限制",!ChatTime.needsTimeConfirmation("20:05","11月15日18点交报告"));
        check("中文小时按发送时刻计算",Engine.parseDeadline("两小时内交实验报告",now).value==now+7200000L);
        check("多个时限不猜具体对象",!Engine.parseDeadline("两小时内交报告，三小时内交作业",now).ok());
        check("非法月日不退回另一个月份",!Engine.parseDeadline("2月30日18点前交报告",now).ok());
        check("非法月份不退回本月日期",!Engine.parseDeadline("13月10日18点前交报告",now).ok());
        check("不存在的本月日期不顺延",!Engine.parseDeadline("本月31日18点前交报告",now).ok());
        System.out.println("PASS "+count);
    }
}
