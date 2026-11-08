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
 * 待办 / 课表 / 考试成绩三个主页面之间的底部导航。
 * 样式：白底细线分隔，每个标签是「图标 + 文字」，选中项图标后面衬一枚浅靛蓝胶囊、文字变强调色；
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

    static View bar(Activity from, int current) {
        LinearLayout shell = CourseUi.column(from);
        shell.setBackgroundColor(Ui.CARD);
        shell.addView(Ui.hairline(from));
        LinearLayout row = new LinearLayout(from);
        row.setPadding(Ui.dp(from, 16), Ui.dp(from, 6), Ui.dp(from, 16), Ui.dp(from, 6));
        shell.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(from, 62)));
        for (int i = 0; i < TITLES.length; i++) {
            final int target = i;
            final boolean selected = current == target;
            String title = TITLES[i];
            LinearLayout tab = CourseUi.column(from);
            tab.setGravity(Gravity.CENTER);
            tab.setContentDescription(title + (selected ? "，当前页面" : ""));
            tab.setSelected(selected);
            tab.setFocusable(true);
            tab.setBackground(Ui.ripple(from, null, Ui.press(), 16));

            FrameLayout iconWrap = new FrameLayout(from);
            if (selected) iconWrap.setBackground(Ui.round(from, Ui.ACCENT_BG, 14));
            ImageView icon = new ImageView(from);
            icon.setImageDrawable(new TabIcon(from, target, selected ? Ui.ACCENT : Ui.SUB));
            iconWrap.addView(icon, new FrameLayout.LayoutParams(Ui.dp(from, 22), Ui.dp(from, 22), Gravity.CENTER));
            tab.addView(iconWrap, new LinearLayout.LayoutParams(Ui.dp(from, 56), Ui.dp(from, 28)));

            TextView label = CourseUi.text(from, title, 11, selected ? Ui.ACCENT : Ui.SUB, selected);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
            labelLp.topMargin = Ui.dp(from, 4);
            tab.addView(label, labelLp);

            row.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
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
