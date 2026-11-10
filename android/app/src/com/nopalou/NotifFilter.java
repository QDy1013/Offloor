package com.nopalou;

/**
 * 通知来源过滤 + 群编号。
 * 纯 Java，不依赖 android，方便在电脑上直接跑测试。
 *
 * 真机问题：监听器原来把所有 App 的通知都收进来，而且拿包名当群编号，
 * 同一个 App 里的所有群就被当成了同一个群。
 */
public final class NotifFilter {

    /** 只收群聊类 App：微信 / QQ / TIM / 钉钉 / 飞书 / 回放器。 */
    private static final String[] ALLOWED = {
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
            "com.nopalou.replay",
    };

    private NotifFilter() {
    }

    /** 群聊类 App 和学习平台（学习通 / 知到 / 雨课堂，见 PlatformNotice）。 */
    public static boolean accept(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (Engine.PlatformNotice.isPlatform(pkg)) return true;
        for (String p : ALLOWED) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    public static String groupId(String pkg, String title) {
        String p = pkg == null ? "" : pkg;
        String g = title == null ? "" : title.trim();
        if (g.isEmpty()) return p;
        return p + "|" + g;
    }

    static int senderSeparator(String text) {
        int limit = Math.min(text.length() - 2, 12);
        for (int i = 0; i <= limit; i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') break;
            if (ch != '：' && ch != ':') continue;
            // 发送者分隔符只会出现在首行，且两侧不能是钟点数字。
            if (i == 0) return -1;
            if (i + 2 < text.length() && Character.isDigit(text.charAt(i - 1))
                    && Character.isDigit(text.charAt(i + 1)) && Character.isDigit(text.charAt(i + 2))) return -1;
            return i;
        }
        return -1;
    }

    static String messageId(String kind, String pkg, String group, String sender, String body, long sentAt) {
        StringBuilder key = new StringBuilder();
        for (String value : new String[]{kind, pkg, group, sender, body, String.valueOf(sentAt)}) {
            key.append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        try { return kind + StateFormat.digest(key.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
