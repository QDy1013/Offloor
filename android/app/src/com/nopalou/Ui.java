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
 * 界面设计规范（v0.13）。
 *
 * 方向：「纸面 + 墨色」。暖白纸色打底，白卡承载内容，墨黑做主文字；
 * 只有一个靛蓝强调色。红 / 橙 / 琥珀只表达时间压力，紫 / 青只表达卡片类型，
 * 每种颜色只承担一种含义，用户看到颜色就知道是什么情况。
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
            BG = c("#F5F4F0");
            CARD = c("#FFFFFF");
            FILL = c("#ECEBE6");
            LINE = c("#E6E4DE");
            TEXT = c("#1C1C1E");
            SUB = c("#64646B");
            MUTE = c("#8E8E93");
            RING = c("#B9B7B0");
            ACCENT = c("#3D5AFE");
            ACCENT_BG = c("#ECEFFF");
            ON_ACCENT = Color.WHITE;
            RED = c("#E5484D");
            RED_BG = c("#FDECEC");
            ORANGE = c("#D9730D");
            ORANGE_BG = c("#FCF0E3");
            AMBER_TX = c("#9A6700");
            AMBER_BG = c("#FBF3DB");
            GREEN = c("#2E9E6A");
            GREEN_BG = c("#E4F4EC");
            VIOLET = c("#7C4DDB");
            VIOLET_BG = c("#F2ECFD");
            TEAL = c("#0F8B86");
            TEAL_BG = c("#E3F4F2");
            INK = c("#24242A");
            INK_ACTION = c("#9DB0FF");
            SHADOW = c("#5A4A2A");
        } else {
            // 深色：暖墨底 + 略亮的卡片，彩色整体提亮、降饱和，浅底标签改成同色深底。
            BG = c("#111113");
            CARD = c("#1C1C1F");
            FILL = c("#26262A");
            LINE = c("#2E2E33");
            TEXT = c("#EDEDEF");
            SUB = c("#A3A3AB");
            MUTE = c("#6F6F78");
            RING = c("#55555C");
            ACCENT = c("#8C9BFF");
            ACCENT_BG = c("#23274A");
            ON_ACCENT = c("#0D0F1E");
            RED = c("#FF6B70");
            RED_BG = c("#3A1C1F");
            ORANGE = c("#F2994A");
            ORANGE_BG = c("#38281A");
            AMBER_TX = c("#E8B84E");
            AMBER_BG = c("#332B16");
            GREEN = c("#4CC38A");
            GREEN_BG = c("#16302A");
            VIOLET = c("#B197FC");
            VIOLET_BG = c("#2B2345");
            TEAL = c("#3CCBC2");
            TEAL_BG = c("#15302E");
            INK = c("#303036");
            INK_ACTION = c("#A9B6FF");
            SHADOW = Color.BLACK;
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
        sheet.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 18));
        sheet.setBackground(topRound(c, CARD, 26));
        android.widget.FrameLayout grip = new android.widget.FrameLayout(c);
        View handle = new View(c);
        handle.setBackground(round(c, dark ? MUTE : LINE, 3));
        grip.addView(handle, new android.widget.FrameLayout.LayoutParams(dp(c, 36), dp(c, 4), android.view.Gravity.CENTER));
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
            w.setDimAmount(dark ? 0.6f : 0.4f);
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

    /** 卡片底：浅色用白卡 + 柔和投影，深色用亮一档的卡 + 细描边（深色下投影看不见）。 */
    static void cardSurface(View v, float radiusDp) {
        Context c = v.getContext();
        GradientDrawable face = round(c, CARD, radiusDp);
        if (dark) face.setStroke(1, LINE);
        else face.setStroke(1, withAlpha(LINE, 150));
        v.setBackground(ripple(c, face, press(), radiusDp));
        if (!dark) {
            v.setElevation(dp(c, 1.5f));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                v.setOutlineAmbientShadowColor(withAlpha(SHADOW, 60));
                v.setOutlineSpotShadowColor(withAlpha(SHADOW, 90));
            }
        }
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

    /** 小标签：浅底深字，11sp 中粗。 */
    static TextView tag(Context c, String text, int textColor, int bgColor) {
        TextView t = new TextView(c);
        t.setText(text == null ? "" : text);
        t.setTextSize(11);
        t.setTypeface(medium());
        t.getPaint().setTextSkewX(0); t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setTextColor(textColor);
        t.setIncludeFontPadding(false);
        t.setBackground(round(c, bgColor, 6));
        t.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), dp(c, 3));
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
