package com.nopalou;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 存档写完之后调用：比较新旧成绩 / 考试 / 课表，有变化就发一条通知；
 * 教务系统的考试安排同步成待办里的考试卡；电费跌破阈值提醒一次。
 * 任何异常都吞掉——提醒失败不能影响保存。
 */
final class CampusChanges {
    static final String PREFS = "campus-alerts";
    static final String CHANNEL = "campus";
    static final int ID_GRADES = 8501, ID_EXAMS = 8502, ID_TIMETABLE = 8503, ID_ELECTRIC = 8504;
    static final float DEFAULT_ELECTRIC = 20f;

    private CampusChanges() { }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean on(Context c, String key) { return prefs(c).getBoolean(key, true); }

    // ---------- 成绩、考试 ----------

    static void academic(Context ctx, Academic before, Academic after) {
        if (after == null) return;
        try {
            Context c = ctx.getApplicationContext();
            if (after.hasGrades && before != null && before.hasGrades && on(c, "grades")) {
                List<ChangeDiff.Change> all = ChangeDiff.addedOrChanged(ChangeDiff.diff(grades(before), grades(after)));
                List<ChangeDiff.Change> shown = new ArrayList<>();
                for (ChangeDiff.Change ch : all) {
                    // 新出现但还没有分数的（未发布）不算“出成绩”
                    if (ch.after != null && ch.after.signature.startsWith("|") && ch.kind == ChangeDiff.Change.ADDED) continue;
                    shown.add(ch);
                }
                if (!shown.isEmpty()) {
                    post(c, ID_GRADES, "成绩有更新", ChangeDiff.summary(shown, true), AcademicActivity.class);
                }
            }
            if (after.hasExams && before != null && before.hasExams && on(c, "exams")) {
                List<ChangeDiff.Change> all = ChangeDiff.addedOrChanged(ChangeDiff.diff(exams(before, true), exams(after, true)));
                if (!all.isEmpty()) {
                    post(c, ID_EXAMS, "考试安排有更新", ChangeDiff.summary(all, true), AcademicActivity.class);
                }
            }
            if (after.hasExams && on(c, "exam_cards")) syncExamCards(c, after);
        } catch (Throwable t) {
            EngineHolder.diag(ctx, "出错", "成绩考试变化检测：" + t.getClass().getSimpleName());
        }
    }

    static List<ChangeDiff.Item> grades(Academic a) {
        List<ChangeDiff.Item> out = new ArrayList<>();
        for (Academic.Grade g : a.grades) out.add(ChangeDiff.grade(g.semesterId, g.name, g.code, g.score, g.gp, g.credits));
        return out;
    }

    /** onlyUpcoming：已考完的不参与比较，免得“状态变成已结束”也提醒。 */
    static List<ChangeDiff.Item> exams(Academic a, boolean onlyUpcoming) {
        List<ChangeDiff.Item> out = new ArrayList<>();
        for (Academic.Exam e : a.exams) {
            if (onlyUpcoming && e.finished) continue;
            out.add(ChangeDiff.exam(e.course, e.date, e.start, e.end, e.location));
        }
        return out;
    }

    static void syncExamCards(Context c, Academic a) {
        final List<Engine.SchoolExam> list = new ArrayList<>();
        for (Academic.Exam e : a.exams) {
            Engine.SchoolExam x = new Engine.SchoolExam();
            x.course = e.course;
            x.place = e.location;
            x.finished = e.finished;
            x.start = millis(e.date, e.start);
            x.dateOnly = x.start > 0 && e.start.isEmpty();
            if (x.dateOnly) x.start = millis(e.date, "08:00");
            list.add(x);
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Engine eng = EngineHolder.get(c);
                if (eng == null) return;
                int n = eng.syncSchoolExams(list, System.currentTimeMillis());
                if (n > 0) {
                    EngineHolder.save(c);
                    EngineHolder.diag(c, "收到", "教务考试安排 · " + n + " 张考试卡");
                }
            } catch (Throwable t) {
                EngineHolder.diag(c, "出错", "考试卡同步：" + t.getClass().getSimpleName());
            }
        });
    }

    static long millis(String date, String clock) {
        if (date == null || date.isEmpty()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
            f.setLenient(false);
            return f.parse(date + " " + (clock == null || clock.isEmpty() ? "00:00" : clock)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 课表 ----------

    static void timetable(Context ctx, Timetable before, Timetable after) {
        if (before == null || after == null) return;                  // 第一次导入：没有基线，不提醒
        try {
            Context c = ctx.getApplicationContext();
            if (!on(c, "timetable")) return;
            // 换了学期是整体替换，不算“课表有更新”
            if (!ChangeDiff.norm(before.termName).equals(ChangeDiff.norm(after.termName))) return;
            List<ChangeDiff.Change> all = ChangeDiff.diff(slots(before), slots(after));
            if (all.isEmpty()) return;
            post(c, ID_TIMETABLE, "课表有更新", ChangeDiff.summary(all, false) + " 的上课安排有变化", TimetableActivity.class);
        } catch (Throwable t) {
            EngineHolder.diag(ctx, "出错", "课表变化检测：" + t.getClass().getSimpleName());
        }
    }

    static List<ChangeDiff.Item> slots(Timetable t) {
        List<ChangeDiff.Slot> s = new ArrayList<>();
        for (Timetable.Lesson l : t.lessons) s.add(new ChangeDiff.Slot(l.name, l.weekday, l.start, l.end, l.weeks, l.room, l.teachers));
        return ChangeDiff.courses(s);
    }

    // ---------- 电费 ----------

    static void campus(Context ctx, CampusData data) {
        if (data == null) return;
        try {
            Context c = ctx.getApplicationContext();
            SharedPreferences p = prefs(c);
            if (!p.getBoolean("electric", true)) return;
            Double value = ChangeDiff.firstNumber(CampusSummary.field(data.module("electric"), "剩余电量|当前电量|电量余额"));
            boolean[] r = ChangeDiff.lowAlert(value, p.getFloat("electric_threshold", DEFAULT_ELECTRIC), p.getBoolean("electric_low", false));
            p.edit().putBoolean("electric_low", r[1]).apply();
            if (r[0]) {
                post(c, ID_ELECTRIC, "宿舍电量不足",
                        "剩余 " + trim(value) + " 度，低于 " + trim((double) p.getFloat("electric_threshold", DEFAULT_ELECTRIC)) + " 度，记得充电费",
                        CampusActivity.class);
            }
        } catch (Throwable t) {
            EngineHolder.diag(ctx, "出错", "电费提醒：" + t.getClass().getSimpleName());
        }
    }

    static String trim(Double d) {
        if (d == null) return "";
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.format(Locale.ROOT, "%.1f", d);
    }

    // ---------- 通知 ----------

    static void post(Context c, int id, String title, String text, Class<?> target) {
        if (!ReminderReceiver.canNotify(c)) return;
        NotificationManager m = c.getSystemService(NotificationManager.class);
        if (m == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            m.createNotificationChannel(new NotificationChannel(CHANNEL, "成绩、考试、课表与电费提醒",
                    NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            m.notify(id, b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build());
        } catch (SecurityException ignored) {
            // 用户关了通知权限
        }
    }
}
