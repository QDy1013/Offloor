package com.nopalou;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天界面里的时间分隔条（QQ、微信）换算成毫秒时间。纯逻辑，可在 JVM 上测试。
 *
 * 支持的写法（前后空白忽略）：
 *   19:07 / 下午 3:07 / 晚上8:30（今天）
 *   昨天 19:07 / 前天 19:07
 *   星期二 10:20 / 周二 10:20（最近 7 天内的那一天）
 *   11月3日 10:20 / 11-03 10:20 / 11/03 10:20
 *   2025年11月3日 10:20 / 2025-11-03 10:20 / 2025/11/3 10:20
 *   日期后面的时刻可以省略（按当天 0 点）。
 * 不是时间分隔条的文字返回 -1。
 */
public final class ChatTime {

    private ChatTime() {}

    private static final String PERIOD = "(凌晨|早上|上午|中午|下午|晚上|傍晚)?";
    private static final Pattern CLOCK = Pattern.compile("^" + PERIOD + "\\s*(\\d{1,2})[:：](\\d{2})$");
    private static final Pattern REL = Pattern.compile("^(昨天|前天|今天)\\s*(.*)$");
    private static final Pattern WEEK = Pattern.compile("^(?:星期|周|礼拜)([一二三四五六日天])\\s*(.*)$");
    private static final Pattern YMD = Pattern.compile("^(\\d{4})[年/\\-.](\\d{1,2})[月/\\-.](\\d{1,2})日?\\s*(.*)$");
    private static final Pattern MD = Pattern.compile("^(\\d{1,2})[月/\\-](\\d{1,2})日?\\s*(.*)$");

    /** 把分隔条文字换算成时间；now 用来确定"今天、昨天、星期几"和省略的年份。 */
    public static long parse(String text, long now) {
        try { return parseChecked(text, now); }
        catch (IllegalArgumentException invalid) { return -1; }
    }

    private static long parseChecked(String text, long now) {
        if (text == null) return -1;
        String s = text.trim().replace(' ', ' ');
        if (s.isEmpty() || s.length() > 24) return -1;
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(now);
        clearDay(base);
        base.setLenient(false);

        Matcher m = CLOCK.matcher(s);
        if (m.matches()) {
            return withClock(base, m.group(1), m.group(2), m.group(3));
        }
        m = REL.matcher(s);
        if (m.matches()) {
            int back = "前天".equals(m.group(1)) ? 2 : "昨天".equals(m.group(1)) ? 1 : 0;
            base.add(Calendar.DAY_OF_MONTH, -back);
            return restClock(base, m.group(2));
        }
        m = WEEK.matcher(s);
        if (m.matches()) {
            int target = "一二三四五六日天".indexOf(m.group(1));
            int want = target >= 6 ? Calendar.SUNDAY : Calendar.MONDAY + target;
            // 聊天软件里"星期X"只用于最近一周内，往前找到那一天（不含今天）。
            for (int i = 1; i <= 7; i++) {
                base.add(Calendar.DAY_OF_MONTH, -1);
                if (base.get(Calendar.DAY_OF_WEEK) == want) break;
            }
            return restClock(base, m.group(2));
        }
        m = YMD.matcher(s);
        if (m.matches()) {
            int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
            if (!validDate(mo, d)) return -1;
            base.set(y, mo - 1, d);
            return restClock(base, m.group(4));
        }
        m = MD.matcher(s);
        if (m.matches()) {
            int mo = Integer.parseInt(m.group(1)), d = Integer.parseInt(m.group(2));
            if (!validDate(mo, d)) return -1;
            // "-" 和 "/" 的写法必须带时刻，否则 "3/4" 这类分数、编号会被误认。
            if (!s.contains("月") && m.group(3).trim().isEmpty()) return -1;
            int y = base.get(Calendar.YEAR);
            base.set(y, mo - 1, d);
            // 没写年份却比今天还晚，说明是去年的。
            if (base.getTimeInMillis() > now + 86400000L) base.add(Calendar.YEAR, -1);
            return restClock(base, m.group(3));
        }
        return -1;
    }

    private static boolean validDate(int mo, int d) {
        return mo >= 1 && mo <= 12 && d >= 1 && d <= 31;
    }

    /** 日期后面剩下的部分：空 = 当天 0 点；否则必须是时刻。 */
    private static long restClock(Calendar day, String rest) {
        String r = rest == null ? "" : rest.trim();
        if (r.isEmpty()) return day.getTimeInMillis();
        Matcher m = CLOCK.matcher(r);
        if (!m.matches()) return -1;
        return withClock(day, m.group(1), m.group(2), m.group(3));
    }

    private static long withClock(Calendar day, String period, String hh, String mm) {
        int h = Integer.parseInt(hh), mi = Integer.parseInt(mm);
        if (h > 23 || mi > 59) return -1;
        if (period != null) {
            boolean pm = period.equals("下午") || period.equals("晚上") || period.equals("傍晚");
            if (pm && h < 12) h += 12;
            if (period.equals("中午") && h < 11) h += 12;
            if ((period.equals("凌晨") || period.equals("早上") || period.equals("上午")) && h == 12) h = 0;
        }
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, mi);
        return c.getTimeInMillis();
    }

    private static void clearDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    static long screenshotTime(String raw, long fallback) {
        long parsed = parse(raw, fallback);
        if (parsed < 0) return fallback;
        if (raw != null && CLOCK.matcher(raw.trim()).matches() && parsed > fallback + 60000L) {
            Calendar c=Calendar.getInstance();c.setTimeInMillis(parsed);c.add(Calendar.DAY_OF_MONTH,-1);parsed=c.getTimeInMillis();
        }
        return parsed <= fallback + 60000L && parsed >= fallback - 60L*86400000L ? parsed : fallback;
    }
    static boolean needsTimeConfirmation(String raw, String body) {
        if (body == null || !body.matches("(?s).*(?:今天|明天|后天|今晚|明晚|本周|这周|下周|周[一二三四五六日天末]|星期|小时|分钟|上课前|下课前|放学前).*")) return false;
        if (raw == null || raw.trim().isEmpty()) return true;
        String text=raw.trim();
        boolean dated=YMD.matcher(text).matches() || REL.matcher(text).matches();
        if (!dated) return true;
        if (body.contains("小时") || body.contains("分钟")) return !text.matches("(?s).*\\d{1,2}[:：]\\d{2}.*");
        return false;
    }
}
