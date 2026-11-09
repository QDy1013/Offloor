package com.nopalou;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Calendar;

final class AiBudget {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ai-usage", Context.MODE_PRIVATE); }
    private static AiBudgetCore load(Context c) {
        SharedPreferences p = prefs(c); AiBudgetCore b = new AiBudgetCore();
        b.day = p.getString("day", ""); b.calls = Math.max(0, p.getInt("calls", 0));
        b.reportedTokens = Math.max(0, p.getLong("tokens", 0)); b.roll(System.currentTimeMillis()); return b;
    }
    private static boolean save(Context c, AiBudgetCore b) {
        return prefs(c).edit().putString("day", b.day).putInt("calls", b.calls).putLong("tokens", b.reportedTokens).commit();
    }
    static synchronized void reserve(Context c, int limit) throws IOException {
        AiBudgetCore b = load(c);
        int currentLimit=AiBudgetCore.limit(AiConfig.prefs(c).getInt("daily_limit",limit));
        if (!b.reserve(System.currentTimeMillis(), currentLimit)) throw new AiClient.BudgetException("已达到今日 AI 请求上限，明日继续；可在设置中调整");
        if (!save(c, b)) throw new IOException("调用计数保存失败，本次未发送");
    }
    static synchronized void tokens(Context c, long tokens) { AiBudgetCore b = load(c); b.tokens(tokens); save(c, b); }
    static synchronized String summary(Context c, int limit) {
        AiBudgetCore b = load(c);
        return "今日请求 " + b.calls + "/" + AiBudgetCore.limit(limit) + " · 服务端已报告 " + b.reportedTokens + " tokens";
    }
    static long untilTomorrow() {
        long now = System.currentTimeMillis(); Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 1); c.set(Calendar.SECOND, 0);
        return Math.max(60_000L, c.getTimeInMillis() - now);
    }
}
