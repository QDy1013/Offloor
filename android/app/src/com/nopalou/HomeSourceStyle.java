package com.nopalou;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 卡片来源图标、应用名与品牌文字徽标解析器（从 MainActivity 拆出）：
 * 仅缓存 ConstantState 并在每处使用 newDrawable().mutate()，避免共享可变 Drawable；
 * 读取失败记入 miss 集合，避免列表重绘时重复查询 PackageManager。
 */
final class HomeSourceStyle {
    private static final Map<String, Drawable.ConstantState> ICON_CACHE = new HashMap<>();
    private static final Set<String> ICON_MISS = new HashSet<>();

    private HomeSourceStyle() { }

    static View sourceMark(Context ctx, Engine.Card c, float sizeDp) {
        return sourceMark(ctx, sourceName(c), c.sourcePackageName(), sizeDp);
    }

    static String appNameFor(String pkg) {
        Engine.Card probe = new Engine.Card();
        probe.sourcePackage = pkg;
        return sourceName(probe);
    }

    static View sourceMark(Context ctx, String name, String pkg, float sizeDp) {
        pkg = pkg == null ? "" : pkg.trim();
        int size = Ui.dp(ctx, sizeDp);
        Drawable icon = sourceIcon(ctx, pkg);
        if (icon != null) {
            ImageView image = new ImageView(ctx);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(Ui.round(ctx, Ui.CARD, 4));
            image.setClipToOutline(true);
            image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            return image;
        }
        TextView badge = new TextView(ctx);
        badge.setText(sourceBadge(name, pkg));
        badge.setTextSize(sizeDp * 0.55f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setBackground(Ui.round(ctx, sourceBadgeColor(name, pkg), 4));
        badge.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return badge;
    }

    /** “来源 · 群 · 发送者”：空的和与前面重复的跳过（教务系统卡三处都是“教务系统”）。 */
    static String joinDistinct(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (!out.contains(p.trim())) out.add(p.trim());
        }
        return TextUtils.join(" · ", out);
    }

    static String sourceName(Engine.Card c) {
        String name = c.sourceAppName();
        return TextUtils.isEmpty(name) || name.trim().isEmpty() ? "来源待确认" : name.trim();
    }

    static Drawable sourceIcon(Context ctx, String pkg) {
        if (TextUtils.isEmpty(pkg)) return null;
        Drawable.ConstantState state = ICON_CACHE.get(pkg);
        if (state == null && !ICON_MISS.contains(pkg)) {
            try {
                Drawable appIcon = ctx.getPackageManager().getApplicationIcon(pkg);
                state = appIcon == null ? null : appIcon.getConstantState();
            } catch (Exception ignored) {
                state = null;
            }
            if (state == null) {
                ICON_MISS.add(pkg);
                return null;
            }
            ICON_CACHE.put(pkg, state);
        }
        if (state == null) return null;
        try {
            Drawable drawable = state.newDrawable(ctx.getResources());
            return drawable == null ? null : drawable.mutate();
        } catch (Exception ignored) {
            ICON_CACHE.remove(pkg);
            ICON_MISS.add(pkg);
            return null;
        }
    }

    static String sourceBadge(String name, String pkg) {
        String key = (name + " " + pkg).toLowerCase(Locale.ROOT);
        if (key.contains("微信") || key.contains("wechat") || key.contains("com.tencent.mm")) return "微";
        if (key.contains("飞书") || key.contains("lark") || key.contains("feishu")) return "飞";
        if (key.contains("钉钉") || key.contains("dingtalk") || key.contains("rimet")) return "钉";
        if (key.contains("tim") || key.contains("com.tencent.tim")) return "T";
        if (key.contains("qq") || key.contains("com.tencent.mobileqq")) return "Q";
        if (key.contains("学习通") || key.contains("chaoxing")) return "学";
        if (key.contains("知到") || key.contains("wisdomtree")) return "知";
        if (key.contains("雨课堂") || key.contains("xuetangx")) return "雨";
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "源" : trimmed.substring(0, 1);
    }

    static int sourceBadgeColor(String name, String pkg) {
        String key = (name + " " + pkg).toLowerCase(Locale.ROOT);
        if (key.contains("微信") || key.contains("wechat") || key.contains("com.tencent.mm")) return Ui.GREEN;
        if (key.contains("飞书") || key.contains("lark") || key.contains("feishu")) return Color.parseColor("#5B5BD6");
        if (key.contains("钉钉") || key.contains("dingtalk") || key.contains("rimet")) return Color.parseColor("#2E7CF6");
        if (key.contains("tim") || key.contains("com.tencent.tim")) return Color.parseColor("#0FA9C9");
        if (key.contains("qq") || key.contains("com.tencent.mobileqq")) return Color.parseColor("#2B7DE9");
        if (key.contains("学习通") || key.contains("chaoxing")) return Color.parseColor("#E8452C");
        if (key.contains("知到") || key.contains("wisdomtree")) return Color.parseColor("#1A9E6A");
        if (key.contains("雨课堂") || key.contains("xuetangx")) return Color.parseColor("#2F6BFF");
        return Ui.BLUE;
    }
}
