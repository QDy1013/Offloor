package com.nopalou;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 界面设计规范（v0.20「墨与光」）。
 *
 * 方向：安静的纸面，一块有分量的墨色。石灰白纸色打底，白卡承载内容，墨黑做主文字；
 * 首页顶部只有一张深墨渐变的「今日概览」卡负责第一眼的分量，其余地方都克制留白。
 * 只有一个墨水蓝强调色。红 / 橙 / 琥珀只表达时间压力，紫 / 青只表达卡片类型，
 * 每种颜色只承担一种含义，用户看到颜色就知道是什么情况。
 *
 * 形状：卡片 20dp 圆角、胶囊按钮全圆角、底部导航是一枚悬浮胶囊；
 * 字号只用 11 / 12 / 13 / 15 / 16 / 20 / 26 这几档，层级靠字重和颜色而不是更多字号。
 */
final class Ui {

    private Ui() {
    }

    // 颜色随系统深浅色切换：每个 Activity 在 onCreate 里先调 Ui.apply(this)。
    // 默认值是浅色，没调 apply 的地方（服务、通知）也不会出错。

    /** 当前是否深色。 */
    static boolean dark;

    // ---------- 中性色 ----------
    /** 页面底色：暖白纸色，比冷灰更柔和，白卡浮在上面层次清楚。 */
    static int BG;
    /** 卡片 / 面板：纯白。 */
    static int CARD;
    /** 输入框、次级按钮的填充色：比底色再深一档。 */
    static int FILL;
    /** 分割线 / 描边。 */
    static int LINE;
    /** 主文字：墨黑。 */
    static int TEXT;
    /** 次级文字：白底对比约 5:1。 */
    static int SUB;
    /** 弱化文字：脚注、占位符。 */
    static int MUTE;
    /** 未到紧急程度的完成圈。 */
    static int RING;

    // ---------- 强调色 ----------
    /** 唯一的品牌强调色：靛蓝。主按钮、选中态、链接。 */
    static int ACCENT;
    static int ACCENT_BG;
    /** 强调色实心块上的文字。 */
    static int ON_ACCENT;
    /** 逾期 / 3 小时内。 */
    static int RED;
    static int RED_BG;
    /** 24 小时内。 */
    static int ORANGE;
    static int ORANGE_BG;
    /** 待确认：琥珀色，提示“需要你看一眼”。 */
    static int AMBER_TX;
    static int AMBER_BG;
    /** 完成。 */
    static int GREEN;
    static int GREEN_BG;
    /** 卡片类型：考试 / 接龙。 */
    static int VIOLET;
    static int VIOLET_BG;
    static int TEAL;
    static int TEAL_BG;
    /** 底部撤销条的深色底和其上的操作字。 */
    static int INK;
    static int INK_ACTION;
    /** 卡片投影色（API 28+ 生效）。 */
    static int SHADOW;

    // ---------- 首页概览卡（深墨渐变） ----------
    static int HERO_TOP;
    static int HERO_BOTTOM;
    /** 概览卡上的主文字 / 次级文字。 */
    static int ON_HERO;
    static int HERO_SUB;
    /** 概览卡右上角那一团很淡的光晕。 */
    static int HERO_GLOW;

    // ---------- 兼容旧名（ProfileActivity 等仍在用） ----------
    static int BLUE;
    static int BLUE_BG;
    static int SURFACE;
    static int SURFACE2;
    static int STROKE;
    static int AMBER;

    static {
        palette(false);
    }

    /** 按系统深浅色设置调色板。返回是否深色。 */
    static boolean apply(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        palette(mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        return dark;
    }

    private static int c(String hex) {
        return Color.parseColor(hex);
    }

    private static void palette(boolean night) {
        dark = night;
        if (!night) {
            // 浅色：石灰白纸面 + 纯白卡，墨色文字；强调色是一支偏紫的「墨水蓝」，比标准蓝更沉稳。
            BG = c("#F3F2EE");
            CARD = c("#FFFFFF");
            FILL = c("#EBEAE5");
            LINE = c("#E4E2DC");
            TEXT = c("#16161A");
            SUB = c("#62626B");
            MUTE = c("#93939B");
            RING = c("#BDBBB4");
            ACCENT = c("#4A55D8");
            ACCENT_BG = c("#EDEEFC");
            ON_ACCENT = Color.WHITE;
            RED = c("#DC3E42");
            RED_BG = c("#FCECEC");
            ORANGE = c("#D46A12");
            ORANGE_BG = c("#FBEFE2");
            AMBER_TX = c("#94650A");
            AMBER_BG = c("#F9F1DC");
            GREEN = c("#2A9467");
            GREEN_BG = c("#E3F2EA");
            VIOLET = c("#7351D4");
            VIOLET_BG = c("#F1ECFC");
            TEAL = c("#0E8580");
            TEAL_BG = c("#E1F2F0");
            INK = c("#1B1B22");
            INK_ACTION = c("#A9B2FF");
            SHADOW = c("#2B2A4A");
            HERO_TOP = c("#1D1E2B");
            HERO_BOTTOM = c("#2B2D48");
            ON_HERO = c("#F5F5FA");
            HERO_SUB = c("#A3A6C2");
            HERO_GLOW = c("#6F7BFF");
        } else {
            // 深色：近黑底 + 抬升一档的卡片，彩色整体提亮、降饱和，浅底标签改成同色深底。
            BG = c("#0F0F12");
            CARD = c("#19191D");
            FILL = c("#232328");
            LINE = c("#2B2B31");
            TEXT = c("#EDEDF0");
            SUB = c("#A1A1AA");
            MUTE = c("#6E6E78");
            RING = c("#55555E");
            ACCENT = c("#939CFF");
            ACCENT_BG = c("#22244A");
            ON_ACCENT = c("#0C0D1F");
            RED = c("#FF6B6E");
            RED_BG = c("#3A1C1E");
            ORANGE = c("#F29A4C");
            ORANGE_BG = c("#37281A");
            AMBER_TX = c("#E6B751");
            AMBER_BG = c("#322A16");
            GREEN = c("#4CC38A");
            GREEN_BG = c("#152E28");
            VIOLET = c("#B39BFF");
            VIOLET_BG = c("#2A2345");
            TEAL = c("#3CCBC2");
            TEAL_BG = c("#142E2D");
            INK = c("#2A2A31");
            INK_ACTION = c("#AEB6FF");
            SHADOW = Color.BLACK;
            HERO_TOP = c("#1B1C2A");
            HERO_BOTTOM = c("#23254A");
            ON_HERO = c("#F2F2F8");
            HERO_SUB = c("#9EA1C0");
            HERO_GLOW = c("#7D88FF");
        }
        BLUE = ACCENT;
        BLUE_BG = ACCENT_BG;
        SURFACE = CARD;
        SURFACE2 = FILL;
        STROKE = LINE;
        AMBER = ORANGE;
    }

    /** 状态栏 / 导航栏跟随页面底色，图标深浅随主题。 */
    static void systemBars(android.app.Activity a, View root) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        int flags = 0;
        if (!dark) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
        root.setSystemUiVisibility(flags);
    }

    /**
     * 通用底部面板（待办详情、更多、课程详情共用）：顶部圆角、从下往上滑入、最高占屏 88%，
     * 内容超出时可滚动，footer 固定在底部；按住顶部拖把往下拖可以关掉，拖不够远就弹回。
     */
    static android.app.Dialog sheet(Context c, View content, View footer) {
        final android.app.Dialog dialog = new android.app.Dialog(c);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final int max = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.88f);
        final android.widget.LinearLayout sheet = new android.widget.LinearLayout(c) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int size = View.MeasureSpec.getSize(heightSpec);
                int cap = View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED ? max : Math.min(size, max);
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
            }
        };
        sheet.setOrientation(android.widget.LinearLayout.VERTICAL);
        sheet.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 18));
        sheet.setBackground(topRound(c, CARD, 28));
        android.widget.FrameLayout grip = new android.widget.FrameLayout(c);
        View handle = new View(c);
        handle.setBackground(round(c, dark ? RING : withAlpha(TEXT, 38), 3));
        grip.addView(handle, new android.widget.FrameLayout.LayoutParams(dp(c, 40), dp(c, 5), android.view.Gravity.CENTER));
        sheet.addView(grip, new android.widget.LinearLayout.LayoutParams(-1, dp(c, 20)));
        grip.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            long startT;
            boolean dragging;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        startT = e.getEventTime();
                        dragging = true;
                        sheet.animate().cancel();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!dragging) return false;
                        sheet.setTranslationY(Math.max(0f, e.getRawY() - startY));
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (!dragging) return false;
                        dragging = false;
                        float dy = Math.max(0f, e.getRawY() - startY);
                        long dt = Math.max(1, e.getEventTime() - startT);
                        boolean fling = dy / dt > 0.9f && dy > dp(c, 24);
                        if (dy > sheet.getHeight() * 0.28f || fling) {
                            sheet.animate().translationY(sheet.getHeight()).setDuration(160)
                                    .withEndAction(() -> { if (dialog.isShowing()) dialog.dismiss(); }).start();
                        } else {
                            sheet.animate().translationY(0f).setDuration(200)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content);
        sheet.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, -2, 1f));
        if (footer != null) sheet.addView(footer);
        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setGravity(android.view.Gravity.BOTTOM);
            w.setLayout(-1, -2);
            w.setDimAmount(dark ? 0.62f : 0.32f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setNavigationBarColor(CARD);
        }
        return dialog;
    }

    /** 系统弹窗主题：跟随深浅色。 */
    static int dialogTheme() {
        return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    /**
     * 卡片底：浅色是白卡 + 一层很散的冷调投影（不描边，靠投影浮起来）；
     * 深色投影看不见，改用亮一档的卡 + 极细描边。
     */
    static void cardSurface(View v, float radiusDp) {
        Context c = v.getContext();
        GradientDrawable face = round(c, CARD, radiusDp);
        if (dark) face.setStroke(1, LINE);
        v.setBackground(ripple(c, face, press(), radiusDp));
        if (!dark) lift(v, 2f);
    }

    /** 浅色模式下的柔和浮起：只在 API 28+ 上色，低版本是系统默认灰影。 */
    static void lift(View v, float elevationDp) {
        if (dark) return;
        v.setElevation(dp(v.getContext(), elevationDp));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(withAlpha(SHADOW, 40));
            v.setOutlineSpotShadowColor(withAlpha(SHADOW, 70));
        }
    }

    /**
     * 首页「今日概览」卡的底：自上而下的深墨渐变，右上角叠一团很淡的径向光，
     * 让整块墨色有一点纵深，但不抢文字。
     */
    static Drawable heroSurface(Context c, float radiusDp) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{HERO_TOP, HERO_BOTTOM});
        base.setCornerRadius(dp(c, radiusDp));
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{withAlpha(HERO_GLOW, dark ? 70 : 80), withAlpha(HERO_GLOW, 0)});
        glow.setGradientCenter(0.92f, 0.0f);
        glow.setGradientRadius(dp(c, 220));
        glow.setCornerRadius(dp(c, radiusDp));
        android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{base, glow});
        if (dark) {
            GradientDrawable edge = new GradientDrawable();
            edge.setCornerRadius(dp(c, radiusDp));
            edge.setColor(Color.TRANSPARENT);
            edge.setStroke(1, withAlpha(Color.WHITE, 18));
            layer = new android.graphics.drawable.LayerDrawable(new Drawable[]{base, glow, edge});
        }
        return layer;
    }

    /** 分组小标题：12sp 中粗、弱化色、略加字距，像杂志的栏目名。 */
    static TextView sectionLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(medium());
        t.setTextColor(MUTE);
        t.setLetterSpacing(0.06f);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 设置类列表的分组底板：和页面同色系、比卡片低一档，组内各行用细线分开。 */
    static android.widget.LinearLayout group(Context c) {
        android.widget.LinearLayout g = new android.widget.LinearLayout(c);
        g.setOrientation(android.widget.LinearLayout.VERTICAL);
        g.setBackground(round(c, dark ? FILL : BG, 18));
        g.setClipToOutline(true);
        return g;
    }

    /** 列表行尾的「›」。 */
    static Drawable chevron(Context c, int color) {
        return new Chevron(dp(c, 16), color);
    }

    private static final class Chevron extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(int size, int color) {
            this.size = Math.max(1, size);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.11f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
            path.reset();
            path.moveTo(x + s * 0.38f, y + s * 0.22f);
            path.lineTo(x + s * 0.66f, y + s * 0.5f);
            path.lineTo(x + s * 0.38f, y + s * 0.78f);
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    /**
     * 按下时轻微缩小，松手回弹：给整张卡一点“实体感”。
     * 用 StateListAnimator，不拦截点击 / 长按事件。
     */
    static void pressScale(View v, float scale) {
        android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
        android.animation.AnimatorSet down = new android.animation.AnimatorSet();
        down.playTogether(
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_X, scale),
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_Y, scale));
        down.setDuration(90);
        android.animation.AnimatorSet up = new android.animation.AnimatorSet();
        up.playTogether(
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_X, 1f),
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f));
        up.setDuration(160);
        up.setInterpolator(new android.view.animation.OvershootInterpolator(1.6f));
        sla.addState(new int[]{android.R.attr.state_pressed}, down);
        sla.addState(new int[0], up);
        v.setStateListAnimator(sla);
    }

    /** 按紧急程度返回强调色：3 逾期红、2 三小时内红、1 一天内橙、0 中性。 */
    static int urgencyColor(int level) {
        switch (level) {
            case 3:
            case 2:
                return RED;
            case 1:
                return ORANGE;
            default:
                return SUB;
        }
    }

    static int urgencyBg(int level) {
        switch (level) {
            case 3:
            case 2:
                return RED_BG;
            case 1:
                return ORANGE_BG;
            default:
                return FILL;
        }
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int dp(Context c, float v) {
        return MainActivity.dp(c, v);
    }

    static GradientDrawable round(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable roundStroke(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    /** 只有上方两个角是圆的：底部面板用。 */
    static GradientDrawable topRound(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float r = dp(c, radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** 小标签：浅底深字的胶囊，11sp 中粗。 */
    static TextView tag(Context c, String text, int textColor, int bgColor) {
        TextView t = new TextView(c);
        t.setText(text == null ? "" : text);
        t.setTextSize(11);
        t.setTypeface(medium());
        t.getPaint().setTextSkewX(0); t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setTextColor(textColor);
        t.setIncludeFontPadding(false);
        t.setBackground(round(c, bgColor, 99));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    static TextView pill(Context c, String text, int textColor) {
        return tag(c, text, textColor, withAlpha(textColor, 28));
    }

    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        int size = dp(c, sizeDp);
        v.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return v;
    }

    static GradientDrawable disc(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    static int deep(int color) {
        float f = 0.72f;
        return Color.rgb((int) (Color.red(color) * f),
                (int) (Color.green(color) * f), (int) (Color.blue(color) * f));
    }

    static Drawable ripple(Context c, Drawable content, int rippleColor, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(c, radiusDp));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static Drawable rippleCircle(Context c, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    /** 按压反馈统一用墨色 8% 透明度，不在白底上泛出蓝光。 */
    static int press() {
        return withAlpha(TEXT, 20);
    }

    // ---------- 设置页通用控件：所有二级页面共用同一套尺寸、字号和颜色 ----------

    /** 二级页面顶栏：返回 + 20sp 标题。 */
    static android.widget.LinearLayout topBar(android.app.Activity a, String title) {
        android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
        bar.setPadding(dp(a, 4), dp(a, 8), dp(a, 12), dp(a, 4));
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.addView(CourseUi.icon(a, "prev", "返回", a::finish));
        bar.addView(CourseUi.text(a, title, 20, TEXT, true));
        return bar;
    }

    /** 设置页的一张内容卡：左右 16dp 外边距、20dp 圆角，返回卡片本身用来继续加内容。 */
    static android.widget.LinearLayout card(android.widget.LinearLayout parent) {
        Context c = parent.getContext();
        android.widget.LinearLayout card = CourseUi.column(c);
        card.setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 20));
        cardSurface(card, 20);
        android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(c, 16), dp(c, 12), dp(c, 16), 0);
        parent.addView(card, p);
        return card;
    }

    /** 卡片标题（16sp）加一段可选说明（13sp 次级色）。返回说明文字，没有说明时返回 null。 */
    static TextView heading(android.widget.LinearLayout parent, String title, String desc) {
        Context c = parent.getContext();
        parent.addView(CourseUi.text(c, title, 16, TEXT, true));
        if (desc == null || desc.isEmpty()) return null;
        TextView d = CourseUi.text(c, desc, 13, SUB, false);
        d.setLineSpacing(dp(c, 3), 1);
        d.setPadding(0, dp(c, 6), 0, 0);
        parent.addView(d);
        return d;
    }

    /** 标签 + 输入框：浅灰填充、14dp 圆角、15sp，返回输入框。 */
    static android.widget.EditText field(android.widget.LinearLayout parent, String label, String hint,
                                         String value, int inputType) {
        Context c = parent.getContext();
        TextView t = CourseUi.text(c, label, 12, SUB, false);
        t.setPadding(0, dp(c, 12), 0, dp(c, 6));
        parent.addView(t);
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTE);
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setBackground(round(c, FILL, 14));
        if (value != null) e.setText(value);
        parent.addView(e, new android.widget.LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    /** 开关跟随强调色，不再用系统默认的蓝绿色。 */
    static void tint(android.widget.Switch s) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, dark ? SUB : CARD}));
        s.setTrackTintList(new ColorStateList(states, new int[]{withAlpha(ACCENT, 110), dark ? FILL : RING}));
    }

    /** 一行开关：左边标题和说明，右边开关，整行都能点。返回开关。 */
    static android.widget.Switch toggle(android.widget.LinearLayout parent, String title, String desc, boolean on) {
        Context c = parent.getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 10), 0, dp(c, 10));
        android.widget.LinearLayout words = CourseUi.column(c);
        words.addView(CourseUi.text(c, title, 15, TEXT, true));
        if (desc != null && !desc.isEmpty()) {
            TextView d = CourseUi.text(c, desc, 12, SUB, false);
            d.setLineSpacing(dp(c, 2), 1);
            d.setPadding(0, dp(c, 3), 0, 0);
            words.addView(d);
        }
        row.addView(words, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Switch s = new android.widget.Switch(c);
        s.setChecked(on);
        s.setContentDescription(title);
        tint(s);
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = dp(c, 12);
        row.addView(s, sp);
        row.setOnClickListener(v -> s.toggle());
        parent.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    /** 说明要点：左边一枚带字的小圆，右边标题和说明。用于“这是做什么的”一类解释。 */
    static void point(android.widget.LinearLayout parent, String mark, int color, int bg, String title, String desc) {
        Context c = parent.getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setPadding(0, dp(c, 12), 0, 0);
        TextView m = CourseUi.text(c, mark, 13, color, true);
        m.setGravity(android.view.Gravity.CENTER);
        m.setIncludeFontPadding(false);
        m.setBackground(disc(c, bg));
        android.widget.LinearLayout.LayoutParams mp = new android.widget.LinearLayout.LayoutParams(dp(c, 28), dp(c, 28));
        mp.rightMargin = dp(c, 12);
        row.addView(m, mp);
        android.widget.LinearLayout words = CourseUi.column(c);
        words.addView(CourseUi.text(c, title, 15, TEXT, true));
        if (desc != null && !desc.isEmpty()) {
            TextView d = CourseUi.text(c, desc, 13, SUB, false);
            d.setLineSpacing(dp(c, 3), 1);
            d.setPadding(0, dp(c, 3), 0, 0);
            words.addView(d);
        }
        row.addView(words, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }

    /** 小号胶囊操作：强调色字、浅强调底，用在清单行尾。 */
    static TextView chipAction(Context c, String text, Runnable click) {
        TextView t = CourseUi.text(c, text, 13, ACCENT, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinHeight(dp(c, 36));
        t.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        t.setBackground(ripple(c, round(c, ACCENT_BG, 99), press(), 99));
        t.setOnClickListener(v -> click.run());
        t.setFocusable(true);
        return t;
    }

    /**
     * 引导清单的一步：左边序号或对勾，中间标题和说明，右边可选操作。
     * action 为空或已完成时不显示操作按钮。
     */
    static android.widget.LinearLayout step(android.widget.LinearLayout parent, int n, String title, String desc,
                                            boolean done, String action, Runnable run) {
        Context c = parent.getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 10), 0, dp(c, 10));
        View mark;
        if (done) {
            android.widget.ImageView check = new android.widget.ImageView(c);
            check.setImageDrawable(new CheckCircle(c, GREEN, GREEN, 24, true));
            check.setContentDescription("已完成");
            mark = check;
        } else {
            TextView num = CourseUi.text(c, String.valueOf(n), 12, SUB, true);
            num.setGravity(android.view.Gravity.CENTER);
            num.setIncludeFontPadding(false);
            num.setBackground(disc(c, FILL));
            mark = num;
        }
        android.widget.LinearLayout.LayoutParams mp = new android.widget.LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
        mp.rightMargin = dp(c, 12);
        row.addView(mark, mp);
        android.widget.LinearLayout words = CourseUi.column(c);
        words.addView(CourseUi.text(c, title, 15, done ? SUB : TEXT, true));
        if (desc != null && !desc.isEmpty()) {
            TextView d = CourseUi.text(c, desc, 12, done ? MUTE : SUB, false);
            d.setLineSpacing(dp(c, 2), 1);
            d.setPadding(0, dp(c, 2), 0, 0);
            words.addView(d);
        }
        row.addView(words, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        if (!done && action != null && run != null) {
            android.widget.LinearLayout.LayoutParams ap = new android.widget.LinearLayout.LayoutParams(-2, -2);
            ap.leftMargin = dp(c, 10);
            row.addView(chipAction(c, action, run), ap);
        }
        parent.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /**
     * 单选卡片：圆形选择标记、标题、可选徽标和说明。选中时描边与底色换成强调色；
     * 不可用时变淡并把说明换成原因，点不动。
     */
    static final class Choice {
        final android.widget.LinearLayout view;
        private final View mark;
        private final TextView title;
        private final TextView desc;
        private boolean selected;
        private boolean enabled = true;

        Choice(android.widget.LinearLayout parent, String titleText, String badge, String descText, Runnable click) {
            Context c = parent.getContext();
            view = CourseUi.column(c);
            view.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
            android.widget.LinearLayout head = new android.widget.LinearLayout(c);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            mark = new View(c);
            head.addView(mark, new android.widget.LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
            title = CourseUi.text(c, titleText, 15, TEXT, true);
            android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = dp(c, 10);
            head.addView(title, tp);
            if (badge != null && !badge.isEmpty()) head.addView(tag(c, badge, ACCENT, ACCENT_BG));
            view.addView(head);
            desc = CourseUi.text(c, descText, 12, SUB, false);
            desc.setLineSpacing(dp(c, 3), 1);
            desc.setPadding(dp(c, 28), dp(c, 6), 0, 0);
            view.addView(desc);
            view.setOnClickListener(v -> { if (enabled && click != null) click.run(); });
            view.setFocusable(true);
            android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(c, 10);
            parent.addView(view, p);
            paint();
        }

        boolean selected() { return selected; }
        boolean enabled() { return enabled; }

        void select(boolean on) {
            selected = on;
            paint();
        }

        void disable(String reason) {
            enabled = false;
            if (reason != null) desc.setText(reason);
            paint();
        }

        private void paint() {
            Context c = view.getContext();
            int fill = selected ? ACCENT_BG : (dark ? FILL : BG);
            int stroke = selected ? ACCENT : LINE;
            view.setBackground(ripple(c, roundStroke(c, fill, stroke, 16, selected ? 1.5f : 1f), press(), 16));
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(selected ? CARD : Color.TRANSPARENT);
            ring.setStroke(dp(c, selected ? 5 : 1.5f), selected ? ACCENT : RING);
            mark.setBackground(ring);
            view.setAlpha(enabled ? 1f : 0.55f);
            view.setContentDescription(title.getText() + (selected ? "，已选" : "") + (enabled ? "" : "，不可用"));
        }
    }

    static Drawable searchGlyph(Context c, int color, float sizeDp) {
        return new SearchGlyph(dp(c, sizeDp), color);
    }

    /** 纯 Canvas 绘制的放大镜。 */
    private static final class SearchGlyph extends Drawable {
        private final int size;
        private final Paint paint;

        SearchGlyph(int size, int color) {
            this.size = Math.max(1, size);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.1f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float side = Math.min(b.width(), b.height());
            float cx = b.left + side * 0.43f;
            float cy = b.top + side * 0.43f;
            float r = side * 0.28f;
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f,
                    b.left + side * 0.86f, b.top + side * 0.86f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    /**
     * 完成圈：未完成是空心圆环（颜色表达紧急程度），
     * 完成时实心填充并画出白色对勾。progress 0→1 用于点击时的填充动画。
     */
    static final class CheckCircle extends Drawable {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int diameter;
        private float progress;

        CheckCircle(Context c, int ringColor, int fillColor, float diameterDp, boolean checked) {
            diameter = dp(c, diameterDp);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(c, 1.8f));
            ring.setColor(ringColor);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(fillColor);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(dp(c, 2f));
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            tick.setColor(Color.WHITE);
            progress = checked ? 1f : 0f;
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = diameter / 2f;
            float inner = r - ring.getStrokeWidth() / 2f;
            if (progress < 1f) canvas.drawCircle(cx, cy, inner, ring);
            if (progress > 0f) {
                canvas.drawCircle(cx, cy, r * Math.min(1f, 0.4f + progress * 0.6f), fill);
                float t = Math.max(0f, (progress - 0.35f) / 0.65f);
                if (t > 0f) {
                    float x0 = cx - r * 0.42f, y0 = cy + r * 0.02f;
                    float x1 = cx - r * 0.1f, y1 = cy + r * 0.34f;
                    float x2 = cx + r * 0.45f, y2 = cy - r * 0.3f;
                    path.reset();
                    path.moveTo(x0, y0);
                    if (t < 0.4f) {
                        float k = t / 0.4f;
                        path.lineTo(x0 + (x1 - x0) * k, y0 + (y1 - y0) * k);
                    } else {
                        float k = (t - 0.4f) / 0.6f;
                        path.lineTo(x1, y1);
                        path.lineTo(x1 + (x2 - x1) * k, y1 + (y2 - y1) * k);
                    }
                    canvas.drawPath(path, tick);
                }
            }
        }

        @Override public void setAlpha(int alpha) { ring.setAlpha(alpha); fill.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { ring.setColorFilter(cf); fill.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return diameter; }
        @Override public int getIntrinsicHeight() { return diameter; }
    }
}
