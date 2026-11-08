package com.nopalou;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * 桌面小组件「今日课程与待办」。构建脚本不生成 R 类，布局和控件 id 按名字在运行时查。
 * 点课程区打开课表，点其余位置打开待办首页。
 */
public class TodayWidget extends AppWidgetProvider {
    static final int MAX_COURSES = 4, MAX_TODOS = 3;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle options) {
        render(context, manager, new int[]{id});
    }

    /** 数据变了（卡片保存、课表保存）时调用；没放小组件就什么都不做。总在主线程画，避免和引擎并发。 */
    static void refresh(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AppWidgetManager m = AppWidgetManager.getInstance(app);
                if (m == null) return;
                int[] ids = m.getAppWidgetIds(new ComponentName(app, TodayWidget.class));
                if (ids != null && ids.length > 0) render(app, m, ids);
            } catch (Throwable ignored) {
                // 桌面不支持小组件等：忽略
            }
        });
    }

    /** 请求桌面把小组件钉上（Android 8+，桌面会弹确认框）。桌面不支持时返回 false。 */
    static boolean requestPin(Context c) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return false;
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            if (m == null || !m.isRequestPinAppWidgetSupported()) return false;
            return m.requestPinAppWidget(new ComponentName(c, TodayWidget.class), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void render(Context c, AppWidgetManager manager, int[] ids) {
        try {
            for (int id : ids) {
                android.os.Bundle o = manager.getAppWidgetOptions(id);
                int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
                manager.updateAppWidget(id, build(c, h));
            }
        } catch (Throwable t) {
            EngineHolder.diag(c, "出错", "小组件刷新失败：" + t.getClass().getSimpleName());
        }
    }

    private static int res(Context c, String type, String name) {
        Resources r = c.getResources();
        return r.getIdentifier(name, type, c.getPackageName());
    }

    /** 按小组件高度算能放几行：去掉边距、日期和两个小标题，每行约 19dp；课程优先，待办至少一行。 */
    static int[] capacity(int heightDp) {
        if (heightDp <= 0) return new int[]{2, 2};
        int rows = Math.max(2, (heightDp - 28 - 20 - 2 * 22) / 19);
        int todos = Math.max(1, Math.min(MAX_TODOS, rows / 3));
        int courses = Math.max(1, Math.min(MAX_COURSES, rows - todos));
        return new int[]{courses, todos};
    }

    static RemoteViews build(Context c, int heightDp) {
        int[] cap = capacity(heightDp);
        String pkg = c.getPackageName();
        long now = System.currentTimeMillis();

        Timetable table = null;
        try { table = TimetableStore.load(c); } catch (Exception ignored) { }
        int week = 0;
        List<TodayPlan.Course> today = new ArrayList<>(), tomorrow = new ArrayList<>();
        if (table != null) {
            week = table.currentWeek();
            Calendar cal = Calendar.getInstance();
            int weekday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
            today = courses(table, week, weekday);
            tomorrow = weekday == 7 ? courses(table, week + 1, 1) : courses(table, week, weekday + 1);
            if (week < 1 || week > table.maxWeek) week = 0;
        }
        Engine eng = EngineHolder.get(c);
        List<Engine.Card> cards = eng == null ? Collections.<Engine.Card>emptyList() : new ArrayList<>(eng.all());
        TodayPlan.Result plan = TodayPlan.plan(table != null, week, today, tomorrow, cards, now, cap[0], cap[1]);

        RemoteViews v = new RemoteViews(pkg, res(c, "layout", "widget_today"));
        int row = res(c, "layout", "widget_row"), left = res(c, "id", "w_left"), text = res(c, "id", "w_text");
        int courseList = res(c, "id", "w_courses"), todoList = res(c, "id", "w_todos");
        int accent = c.getResources().getColor(res(c, "color", "widget_accent"), null);
        int sub = c.getResources().getColor(res(c, "color", "widget_sub"), null);

        v.setTextViewText(res(c, "id", "w_header"), plan.header);
        v.removeAllViews(courseList);
        for (TodayPlan.Line l : plan.courses) v.addView(courseList, line(pkg, row, left, text, l, accent, sub));
        note(v, res(c, "id", "w_course_note"), plan.courseNote);
        v.removeAllViews(todoList);
        for (TodayPlan.Line l : plan.todos) v.addView(todoList, line(pkg, row, left, text, l, accent, sub));
        note(v, res(c, "id", "w_todo_note"), plan.todoNote);

        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent home = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent timetable = new Intent(c, TimetableActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(res(c, "id", "w_root"), PendingIntent.getActivity(c, 7501, home, flags));
        v.setOnClickPendingIntent(res(c, "id", "w_todo_area"), PendingIntent.getActivity(c, 7501, home, flags));
        v.setOnClickPendingIntent(res(c, "id", "w_course_area"), PendingIntent.getActivity(c, 7502, timetable, flags));
        return v;
    }

    private static RemoteViews line(String pkg, int row, int left, int text, TodayPlan.Line l, int accent, int sub) {
        RemoteViews r = new RemoteViews(pkg, row);
        r.setTextViewText(left, l.left);
        r.setTextColor(left, l.highlight ? accent : sub);
        r.setTextViewText(text, l.text);
        return r;
    }

    private static void note(RemoteViews v, int id, String value) {
        v.setTextViewText(id, value == null ? "" : value);
        v.setViewVisibility(id, value == null || value.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    static List<TodayPlan.Course> courses(Timetable t, int week, int weekday) {
        List<TodayPlan.Course> out = new ArrayList<>();
        if (week < 1) return out;
        for (Timetable.Lesson l : t.lessons) {
            if (l.weekday != weekday || !l.weeks.contains(week)) continue;
            String[] clock = t.clock(l, week);
            out.add(new TodayPlan.Course(l.name, l.room, clock[0], clock[1], l.start));
        }
        return out;
    }
}
