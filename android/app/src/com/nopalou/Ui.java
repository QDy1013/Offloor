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
 * 界面设计规范（v0.21「纸与陶」）。
 *
 * 方向：一本手边的旧笔记本。暖调羊皮纸做底、带一点肉眼几乎看不见的纸纹；
 * 米白卡片像夹进本子里的便笺，只用一圈细线收边，不靠投影；
 * 标题和数字用衬线体，正文用无衬线，读起来像排过版的书页。
 * 唯一的强调色是烧制过的陶土色，主按钮、选中态、「今天」都用它。
 * 首页顶部是一幅随时辰变化的小风景（清晨、午后、傍晚、夜里），给工具一点温度。
 *
 * 颜色语义不变：红 / 橙 / 琥珀只表达时间压力，紫 / 青只表达卡片类型，绿表示完成。
 * 形状：卡片 18dp 圆角、胶囊按钮全圆角、底部导航是一枚悬浮胶囊，空状态用手捏的陶土色圆团。
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
            // 浅色：暖调羊皮纸 + 米白卡片 + 暖墨文字；强调色是烧制过的陶土色。
            BG = c("#F4EEE3");
            CARD = c("#FCF9F3");
            FILL = c("#ECE4D6");
            LINE = c("#E2D8C7");
            TEXT = c("#2B2520");
            SUB = c("#6F665B");
            MUTE = c("#9C9284");
            RING = c("#C9BDAB");
            ACCENT = c("#BF5B37");
            ACCENT_BG = c("#F4E2D6");
            ON_ACCENT = c("#FFF9F2");
            RED = c("#B3261E");
            RED_BG = c("#F6E0DB");
            ORANGE = c("#B26B12");
            ORANGE_BG = c("#F5E7D0");
            AMBER_TX = c("#8A6212");
            AMBER_BG = c("#F3E8CC");
            GREEN = c("#577A52");
            GREEN_BG = c("#E3EADB");
            VIOLET = c("#7A5683");
            VIOLET_BG = c("#EEE3EE");
            TEAL = c("#3D7A73");
            TEAL_BG = c("#DFEBE6");
            INK = c("#2E2823");
            INK_ACTION = c("#F2B08F");
            SHADOW = c("#6B4E2E");
            HERO_TOP = c("#F0E5D3");
            HERO_BOTTOM = c("#EADCC6");
            ON_HERO = c("#2B2520");
            HERO_SUB = c("#7A6E5F");
            HERO_GLOW = c("#E9A877");
        } else {
            // 深色：炭火余温。暖黑底、深陶土卡，文字是旧纸的米色，强调色提亮成橘陶。
            BG = c("#1B1916");
            CARD = c("#25221E");
            FILL = c("#2F2B26");
            LINE = c("#3A342D");
            TEXT = c("#EFE7DA");
            SUB = c("#B3A897");
            MUTE = c("#7F7567");
            RING = c("#5E564B");
            ACCENT = c("#E28B65");
            ACCENT_BG = c("#3E2A20");
            ON_ACCENT = c("#1B1916");
            RED = c("#F2786B");
            RED_BG = c("#3D201C");
            ORANGE = c("#E5A653");
            ORANGE_BG = c("#3A2C18");
            AMBER_TX = c("#E2BC63");
            AMBER_BG = c("#352C17");
            GREEN = c("#93B784");
            GREEN_BG = c("#232D1F");
            VIOLET = c("#C7A4CF");
            VIOLET_BG = c("#30242F");
            TEAL = c("#86C2B8");
            TEAL_BG = c("#1C2D2A");
            INK = c("#36302A");
            INK_ACTION = c("#F2A882");
            SHADOW = Color.BLACK;
            HERO_TOP = c("#2A2520");
            HERO_BOTTOM = c("#221E1A");
            ON_HERO = c("#F1E9DC");
            HERO_SUB = c("#A99C8A");
            HERO_GLOW = c("#E28B65");
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
        if (root.getBackground() instanceof android.graphics.drawable.ColorDrawable
                && ((android.graphics.drawable.ColorDrawable) root.getBackground()).getColor() == BG) {
            // 页面纯底色统一换成带纸纹的纸。
            root.setBackground(paper(root.getContext()));
        }
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
        sheet.setBackground(sheetPaper(c, 28));
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
     * 卡片底：像夹进本子里的米白便笺，一圈很细的暖色描边收边；
     * 浅色下再垫一层极轻的暖棕投影，只让边缘和纸面分开，不显得“浮”。
     */
    static void cardSurface(View v, float radiusDp) {
        Context c = v.getContext();
        GradientDrawable face = round(c, CARD, radiusDp);
        face.setStroke(Math.max(1, dp(c, 0.8f)), LINE);
        v.setBackground(ripple(c, face, press(), radiusDp));
        if (!dark) lift(v, 0.8f);
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
     * 概览卡的底：比页面深一档的暖沙色渐变 + 纸纹 + 一圈细描边，像一张厚一点的卡纸。
     */
    static Drawable heroSurface(Context c, float radiusDp) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{HERO_TOP, HERO_BOTTOM});
        base.setCornerRadius(dp(c, radiusDp));
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(dp(c, radiusDp));
        edge.setColor(Color.TRANSPARENT);
        edge.setStroke(Math.max(1, dp(c, 0.8f)), dark ? LINE : withAlpha(SHADOW, 34));
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{base, grain(c), edge});
    }

    // ---------- 字体 ----------

    /** 展示用衬线体：大标题、数字、栏目名。系统没有衬线中文时会自动回落到无衬线。 */
    static Typeface serif() {
        return Typeface.create("serif", Typeface.NORMAL);
    }

    static Typeface serifBold() {
        return Typeface.create("serif", Typeface.BOLD);
    }

    // ---------- 纸纹 ----------

    private static android.graphics.Bitmap grainLight;
    private static android.graphics.Bitmap grainDark;

    /**
     * 纸纹：一块 120px 的随机细点平铺。浅色是极淡的暖棕点，深色是极淡的米色点，
     * 透明度只有 3%～6%，肉眼几乎看不出，但整块底色会从“塑料”变成“纸”。
     * 种子固定，每次打开都是同一张纸。
     */
    static Drawable grain(Context c) {
        android.graphics.Bitmap bmp = dark ? grainDark : grainLight;
        if (bmp == null) {
            int n = 120;
            bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888);
            java.util.Random r = new java.util.Random(20261108L);
            int speck = dark ? Color.rgb(255, 244, 225) : Color.rgb(92, 64, 36);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float v = r.nextFloat();
                    int alpha = v < 0.18f ? (int) (r.nextFloat() * (dark ? 12 : 15)) : 0;
                    bmp.setPixel(x, y, withAlpha(speck, alpha));
                }
            }
            if (dark) grainDark = bmp; else grainLight = bmp;
        }
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(c.getResources(), bmp);
        d.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        return d;
    }

    /** 页面底：纸色 + 纸纹。所有主页面、底部面板都用它。 */
    static Drawable paper(Context c) {
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{
                new android.graphics.drawable.ColorDrawable(BG), grain(c)});
    }

    /** 底部面板的纸：卡片色 + 纸纹，顶部两个圆角。 */
    static Drawable sheetPaper(Context c, float radiusDp) {
        GradientDrawable face = topRound(c, CARD, radiusDp);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new Drawable[]{face, grain(c)});
        return l;
    }

    // ---------- 插画 ----------

    /**
     * 一团手捏的陶土：不是正圆，而是由四段贝塞尔曲线拼成、边缘微微起伏的圆团。
     * 空状态、图标底都用它代替几何圆，看起来像手做的东西。seed 不同形状不同。
     */
    static Drawable blob(int color, int seed) {
        return new Blob(color, seed);
    }

    private static final class Blob extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float[] k = new float[8];

        Blob(int color, int seed) {
            paint.setColor(color);
            java.util.Random r = new java.util.Random(seed * 7919L + 13);
            for (int i = 0; i < k.length; i++) k[i] = 0.9f + r.nextFloat() * 0.16f;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float rx = b.width() / 2f, ry = b.height() / 2f;
            int n = 8;
            float[] px = new float[n], py = new float[n];
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n - Math.PI / 2;
                px[i] = cx + (float) Math.cos(a) * rx * k[i];
                py[i] = cy + (float) Math.sin(a) * ry * k[i];
            }
            // Catmull-Rom 转贝塞尔，得到一条处处光滑、但不规则的闭合曲线。
            path.reset();
            path.moveTo(px[0], py[0]);
            for (int i = 0; i < n; i++) {
                int i0 = (i - 1 + n) % n, i1 = i, i2 = (i + 1) % n, i3 = (i + 2) % n;
                float c1x = px[i1] + (px[i2] - px[i0]) / 6f, c1y = py[i1] + (py[i2] - py[i0]) / 6f;
                float c2x = px[i2] - (px[i3] - px[i1]) / 6f, c2y = py[i2] - (py[i3] - py[i1]) / 6f;
                path.cubicTo(c1x, c1y, c2x, c2y, px[i2], py[i2]);
            }
            path.close();
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 首页顶部的小风景：远山、近丘、一轮随时辰移动的日头。
     * 清晨日头低悬在左、天色发粉；午后高挂偏右、天色是暖沙；傍晚沉到右边、天色转橘；
     * 夜里换成一弯新月和几点星，山色压暗。全部是纯色块叠出来的，像剪纸或丝网版画。
     */
    static Drawable scenery(Context c, int hour) {
        return new Scenery(c, hour);
    }

    private static final class Scenery extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int hour;
        private final float density;

        Scenery(Context c, int hour) {
            this.hour = hour;
            this.density = c.getResources().getDisplayMetrics().density;
        }

        private boolean night() { return hour < 5 || hour >= 19; }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            canvas.save();
            canvas.translate(b.left, b.top);
            boolean night = night();
            boolean morning = hour >= 5 && hour < 11;
            boolean evening = hour >= 16 && hour < 19;
            // 天空：上下两色的柔和渐变。
            int skyTop, skyBottom;
            if (night) { skyTop = Color.parseColor(dark ? "#1E2230" : "#2F3448"); skyBottom = Color.parseColor(dark ? "#3A3142" : "#5B4A5E"); }
            else if (morning) { skyTop = Color.parseColor(dark ? "#3B3036" : "#F3DCCF"); skyBottom = Color.parseColor(dark ? "#4A3A33" : "#F6E7D4"); }
            else if (evening) { skyTop = Color.parseColor(dark ? "#3E2B26" : "#F2C9A8"); skyBottom = Color.parseColor(dark ? "#553528" : "#F4DDBF"); }
            else { skyTop = Color.parseColor(dark ? "#2F2B26" : "#F1E3CB"); skyBottom = Color.parseColor(dark ? "#3A322A" : "#F6ECDC"); }
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, skyTop, skyBottom, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // 日头 / 月亮：沿一条弧线按时辰移动。
            float t;
            if (night) t = 0.78f;
            else t = Math.max(0f, Math.min(1f, (hour - 5) / 14f));
            float sx = w * (0.12f + 0.76f * t);
            float sy = h * (0.70f - 0.48f * (float) Math.sin(Math.PI * t));
            float sr = 17 * density;
            if (night) {
                // 新月：一个圆减去偏移的另一个圆，真正挖空，透出后面的渐变夜空。
                Path moon = new Path();
                moon.addCircle(sx, sy, sr * 0.8f, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(sx + sr * 0.38f, sy - sr * 0.22f, sr * 0.72f, Path.Direction.CW);
                moon.op(bite, Path.Op.DIFFERENCE);
                p.setColor(withAlpha(Color.parseColor("#F3E6C8"), 40));
                canvas.drawCircle(sx, sy, sr * 1.5f, p);
                p.setColor(Color.parseColor("#F3E6C8"));
                canvas.drawPath(moon, p);
                java.util.Random r = new java.util.Random(7);
                for (int i = 0; i < 14; i++) {
                    p.setAlpha(80 + r.nextInt(150));
                    canvas.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.55f, density * (0.6f + r.nextFloat() * 0.9f), p);
                }
                p.setAlpha(255);
            } else {
                int sun = Color.parseColor(evening ? "#E0794A" : morning ? "#E8936B" : "#E9A35C");
                // 一圈很淡的光晕，再叠实心日头。
                p.setColor(withAlpha(sun, 46));
                canvas.drawCircle(sx, sy, sr * 1.9f, p);
                p.setColor(withAlpha(sun, 80));
                canvas.drawCircle(sx, sy, sr * 1.35f, p);
                p.setColor(sun);
                canvas.drawCircle(sx, sy, sr, p);
            }

            // 三层山：远山淡、近丘深；夜里整体压暗。
            int far = Color.parseColor(night ? "#4A4458" : dark ? "#5A4A3E" : "#D9BFA0");
            int mid = Color.parseColor(night ? "#3A3446" : dark ? "#6B4A38" : "#C98C68");
            int near = Color.parseColor(night ? "#2A2633" : dark ? "#3E4A36" : "#8A9A72");
            ridge(canvas, w, h, 0.58f, 0.10f, 1.3f, 0.2f, far);
            ridge(canvas, w, h, 0.70f, 0.09f, 2.1f, 1.7f, mid);
            ridge(canvas, w, h, 0.82f, 0.07f, 1.6f, 3.1f, near);
            canvas.restore();
        }

        /** 一道山脊：两个不同频率的正弦叠出起伏，底边封到画面最下方。 */
        private void ridge(Canvas canvas, float w, float h, float base, float amp, float freq, float phase, int color) {
            path.reset();
            path.moveTo(0, h);
            int steps = 48;
            for (int i = 0; i <= steps; i++) {
                float x = w * i / steps;
                double u = (double) i / steps * Math.PI * 2;
                float y = h * (base - amp * (float) (0.65 * Math.sin(u * freq + phase) + 0.35 * Math.sin(u * freq * 2.3 + phase * 1.7)));
                path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.close();
            p.setColor(color);
            canvas.drawPath(path, p);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 分组小标题：12sp 中粗、弱化色、略加字距，像杂志的栏目名。 */
    static TextView sectionLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(medium());
        t.setTextColor(MUTE);
        t.setLetterSpacing(0.12f);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 设置类列表的分组底板：和页面同色系、比卡片低一档，组内各行用细线分开。 */
    static android.widget.LinearLayout group(Context c) {
        android.widget.LinearLayout g = new android.widget.LinearLayout(c);
        g.setOrientation(android.widget.LinearLayout.VERTICAL);
        GradientDrawable face = round(c, dark ? FILL : BG, 18);
        face.setStroke(Math.max(1, dp(c, 0.8f)), LINE);
        g.setBackground(face);
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
