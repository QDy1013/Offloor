package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 待办 / 课表 / 考试成绩 / 校园四个主页面之间的底部导航。
 * 悬浮圆角底座，图标与文字上下排列；选中图标衬浅靛蓝小胶囊，
 * 四项等宽，长标签与大字体也有独立空间；
 * 切换用 150ms 淡入淡出，不再整页横滑，像同一个 App 里换页而不是开新窗口。
 */
final class AppNavigation {
    private static Bundle timetableState;

    static void rememberTimetable(int week, int scrollY) {
        timetableState = new Bundle();
        timetableState.putInt("week", week);
        timetableState.putInt("scrollY", scrollY);
    }

    static final int TODO = 0, TIMETABLE = 1, ACADEMIC = 2, CAMPUS = 3;
    static final String[] TITLES = {"待办", "课表", "考试成绩", "校园"};

    static void openTimetable(Activity from) {
        Intent intent = new Intent(from, TimetableActivity.class);
        if (timetableState != null) intent.putExtra("navigation_state", new Bundle(timetableState));
        openPage(from, intent);
    }

    static void openAcademic(Activity from) { openPage(from, new Intent(from, AcademicActivity.class)); }
    static void openCampus(Activity from) { openPage(from, new Intent(from, CampusActivity.class)); }

    /** 待办是栈底；课表和考试成绩互相切换时替换当前页，返回键总是回到待办。 */
    private static void openPage(Activity from, Intent intent) {
        from.startActivity(intent);
        if (!(from instanceof MainActivity)) from.finish();
        fade(from);
    }

    static void openTodo(Activity from) {
        from.startActivity(new Intent(from, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        from.finish();
        fade(from);
    }

    /** 两个主页面之间用淡入淡出切换。 */
    static void fade(Activity a) {
        a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /**
     * 把导航胶囊浮在页面上：page 放进一个 FrameLayout，导航贴底悬浮，背后没有底色，内容可以滚到它下面。
     * reserve 里的可滚动视图会自动在底部让出导航的高度，滚到最后一行也不会被挡住。
     */
    static FrameLayout host(Activity a, View page, int current, View... reserve) {
        FrameLayout frame = new FrameLayout(a);
        frame.setBackgroundColor(Ui.BG);
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        View nav = bar(a, current);
        frame.addView(nav, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        reserve(nav, reserve);
        return frame;
    }

    /** 让 targets 的底部留白始终等于原留白 + dock 的高度；dock 隐藏时只留原留白。 */
    static void reserve(View dock, View... targets) {
        final int[] base = new int[targets.length];
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] == null) continue;
            base[i] = targets[i].getPaddingBottom();
            if (targets[i] instanceof android.view.ViewGroup) ((android.view.ViewGroup) targets[i]).setClipToPadding(false);
        }
        dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = v.getVisibility() == View.GONE ? 0 : b - t;
            for (int i = 0; i < targets.length; i++) {
                View x = targets[i];
                if (x == null) continue;
                int want = base[i] + h;
                if (x.getPaddingBottom() != want) {
                    x.post(() -> x.setPadding(x.getPaddingLeft(), x.getPaddingTop(), x.getPaddingRight(), want));
                }
            }
        });
    }

    static View bar(Activity from, int current) {
        // 外层透明，只有一枚悬浮的胶囊：圆角、柔和投影，离屏幕底边留一点距离，背后不铺白底。
        FrameLayout shell = new FrameLayout(from);
        shell.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        shell.setClipToPadding(false);
        shell.setClipChildren(false);
        shell.setPadding(Ui.dp(from,16),Ui.dp(from,6),Ui.dp(from,16),Ui.dp(from,12));
        LinearLayout row = new LinearLayout(from);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(from,8),Ui.dp(from,4),Ui.dp(from,8),Ui.dp(from,4));
        android.graphics.drawable.GradientDrawable capsule = Ui.roundStroke(from, Ui.CARD, Ui.LINE, 24, 0.75f);
        row.setBackground(capsule);
        Ui.lift(row, 8f);
        // Let font scaling increase the bar instead of clipping the longer label.
        row.setMinimumHeight(Ui.dp(from,48));
        shell.addView(row, new FrameLayout.LayoutParams(-1, -2));
        for (int i = 0; i < TITLES.length; i++) {
            final int target = i;
            final boolean selected = current == target;
            String title = TITLES[i];
            // 图标与标题纵向分层，让四项保持相同节奏。
            LinearLayout tab = new LinearLayout(from);
            tab.setGravity(Gravity.CENTER);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setPadding(0, 0, 0, 0);
            tab.setMinimumHeight(Ui.dp(from,48));
            tab.setContentDescription(title + (selected ? "，当前页面" : ""));
            tab.setSelected(selected);
            tab.setFocusable(true);
            tab.setBackground(Ui.ripple(from, null, Ui.press(), 14));
            Ui.pressScale(tab, .96f);
            FrameLayout iconWell = new FrameLayout(from);
            if (selected) iconWell.setBackground(Ui.round(from, Ui.ACCENT_BG, 11));
            tab.addView(iconWell, new LinearLayout.LayoutParams(Ui.dp(from,44),Ui.dp(from,24)));

            ImageView icon = new ImageView(from);
            icon.setImageDrawable(new TabIcon(from, target, selected ? Ui.ACCENT : Ui.SUB));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            iconWell.addView(icon, new FrameLayout.LayoutParams(Ui.dp(from,20),Ui.dp(from,20),Gravity.CENTER));

            TextView label = CourseUi.text(from, title, 11, selected ? Ui.ACCENT : Ui.SUB, selected);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setIncludeFontPadding(false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
            labelLp.topMargin=Ui.dp(from,1);
            tab.addView(label, labelLp);

            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) tabLp.leftMargin = Ui.dp(from, 4);
            row.addView(tab, tabLp);
            tab.setOnClickListener(v -> {
                if (selected) return;
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (target == TIMETABLE) openTimetable(from);
                else if (target == ACADEMIC) openAcademic(from);
                else if (target == CAMPUS) openCampus(from);
                else openTodo(from);
            });
        }
        return shell;
    }

    /** 线性小图标：待办 = 带对勾的清单，课表 = 日历格。纯 Canvas，随主题上色。 */
    static final class TabIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean calendar;
        private final int kind;
        private final int size;
        private final RectF r = new RectF();

        TabIcon(Context c, boolean calendar, int color) { this(c, calendar ? TIMETABLE : TODO, color); }
        TabIcon(Context c, int kind, int color) {
            this.kind = kind;
            this.calendar = kind == TIMETABLE;
            this.size = Ui.dp(c, 22);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            float s = getBounds().width() / 24f;
            canvas.scale(s, s);
            paint.setStrokeWidth(1.8f);
            if (kind == CAMPUS) {
                android.graphics.Path roof = new android.graphics.Path(); roof.moveTo(2, 9); roof.lineTo(12, 3); roof.lineTo(22, 9);
                canvas.drawPath(roof, paint); canvas.drawLine(4, 10, 20, 10, paint);
                for (int x = 6; x <= 18; x += 6) canvas.drawLine(x, 12, x, 19, paint);
                canvas.drawLine(3, 21, 21, 21, paint);
            } else if (kind == ACADEMIC) {
                // 学士帽：菱形帽顶 + 弧形帽身 + 右侧流苏
                android.graphics.Path top = new android.graphics.Path();
                top.moveTo(12f, 4.5f); top.lineTo(22f, 9.5f); top.lineTo(12f, 14.5f); top.lineTo(2f, 9.5f); top.close();
                canvas.drawPath(top, paint);
                android.graphics.Path body = new android.graphics.Path();
                body.moveTo(6f, 11.6f); body.lineTo(6f, 16.2f);
                body.quadTo(12f, 20.6f, 18f, 16.2f); body.lineTo(18f, 11.6f);
                canvas.drawPath(body, paint);
                canvas.drawLine(22f, 9.5f, 22f, 15f, paint);
            } else if (calendar) {
                r.set(3.5f, 5f, 20.5f, 20.5f);
                canvas.drawRoundRect(r, 3.5f, 3.5f, paint);
                canvas.drawLine(3.5f, 9.5f, 20.5f, 9.5f, paint);
                canvas.drawLine(8f, 3f, 8f, 6.5f, paint);
                canvas.drawLine(16f, 3f, 16f, 6.5f, paint);
                paint.setStyle(Paint.Style.FILL);
                for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++)
                    canvas.drawCircle(8f + x * 4f, 13.5f + y * 3.8f, 1.05f, paint);
                paint.setStyle(Paint.Style.STROKE);
            } else {
                r.set(3.5f, 3.5f, 20.5f, 20.5f);
                canvas.drawRoundRect(r, 5f, 5f, paint);
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(7.8f, 12.2f);
                p.lineTo(10.8f, 15.2f);
                p.lineTo(16.4f, 9f);
                canvas.drawPath(p, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }
}
