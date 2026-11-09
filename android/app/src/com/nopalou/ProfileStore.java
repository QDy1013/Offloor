package com.nopalou;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 我的档案的落盘：SharedPreferences 里一个字符串，内容就是 Profile.encode()。
 * 只存本机，不联网。读坏了 / 没存过都返回空档案，不抛异常。
 */
public class ProfileStore {

    static final String PREF = "nopalou_profile";
    static final String KEY = "profile";

    static SharedPreferences pref(Context ctx) {
        Context app = ctx == null ? null : ctx.getApplicationContext();
        if (app == null) app = ctx;
        return app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 没存过时返回空档案（isEmpty() 为 true）。 */
    public static Engine.Profile load(Context ctx) {
        try {
            String s = pref(ctx).getString(KEY, "");
            return Engine.Profile.decode(s);
        } catch (Exception e) {
            return new Engine.Profile();
        }
    }

    /** null 当作空档案存，与 Engine.setProfile 口径一致。 */
    public static void save(Context ctx, Engine.Profile p) {
        try {
            UpgradeDataBackup.beforeWrite(ctx);
            String s = p == null ? new Engine.Profile().encode() : p.encode();
            pref(ctx).edit().putString(KEY, s).apply();
        } catch (Exception ignored) { }
    }

    /** 是否已经填过档案（键存在即算，内容是空档案也算用户确认过）。 */
    public static boolean exists(Context ctx) {
        try {
            return pref(ctx).contains(KEY);
        } catch (Exception e) {
            return false;
        }
    }
}
