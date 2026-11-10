package com.nopalou;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 本地请求上限（不是费用估算）；失败请求也占用额度。 */
final class AiBudgetCore {
    String day = "";
    int calls;
    long reportedTokens;
    static int limit(int requested) { return Math.max(1, Math.min(500, requested)); }
    void roll(long now) {
        String current = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(now));
        if (!current.equals(day)) { day = current; calls = 0; reportedTokens = 0; }
    }
    boolean reserve(long now, int limit) {
        roll(now);
        if (calls >= limit(limit)) return false;
        calls++; return true;
    }
    void tokens(long count) { if (count > 0) reportedTokens += Math.min(count, 10_000_000L); }
}
