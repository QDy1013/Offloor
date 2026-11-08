package com.nopalou;

/**
 * 西工大友谊校区作息：与长安校区不同，且分夏季（5–9 月）、冬季（10 月–次年 4 月）两套。
 * 教务打印接口只给一套节次时间（长安），友谊校区的课按教室判断后改用这里的时间。
 * 纯 Java，电脑上可测。
 */
final class SectionTimes {
    private SectionTimes() { }

    static final String[][] FRIENDSHIP_SUMMER = {
            {"08:00", "08:50"}, {"09:00", "09:50"}, {"10:10", "11:00"}, {"11:10", "12:00"},
            {"12:20", "13:05"}, {"13:05", "13:50"}, {"14:30", "15:20"}, {"15:30", "16:20"},
            {"16:40", "17:30"}, {"17:40", "18:30"}, {"19:30", "20:20"}, {"20:30", "21:20"},
            {"21:30", "22:20"},
    };

    static final String[][] FRIENDSHIP_WINTER = {
            {"08:00", "08:50"}, {"09:00", "09:50"}, {"10:10", "11:00"}, {"11:10", "12:00"},
            {"12:20", "13:05"}, {"13:05", "13:50"}, {"14:00", "14:50"}, {"15:00", "15:50"},
            {"16:10", "17:00"}, {"17:10", "18:00"}, {"19:00", "19:50"}, {"20:00", "20:50"},
            {"21:00", "21:50"},
    };

    /** 教室或地点写着“友谊”的算友谊校区。 */
    static boolean friendship(String room) {
        return room != null && room.contains("友谊");
    }

    /** 5–9 月用夏季作息。month 为 1–12。 */
    static boolean summer(int month) {
        return month >= 5 && month <= 9;
    }

    /** 友谊校区第 start–end 节的“HH:mm–HH:mm”；节次超出表格返回 null。 */
    static String friendshipRange(int month, int start, int end) {
        String[][] t = summer(month) ? FRIENDSHIP_SUMMER : FRIENDSHIP_WINTER;
        if (start < 1 || end < start || end > t.length) return null;
        return t[start - 1][0] + "–" + t[end - 1][1];
    }
}
