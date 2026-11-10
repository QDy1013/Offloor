package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 考试成绩主页：考试安排（下一场 + 时间线 + 已结束）与成绩绩点（总绩点 + 学期走势 + 分学期成绩）。只读。 */
public class AcademicActivity extends Activity {
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Academic data;
    private boolean loaded, showGrades, finishedOpen;
    private final Set<String> openTerms = new HashSet<>();
    private boolean termsInitialised;
    private ScrollView scroll;
    private int keepScroll = -1;
    private boolean resumed;
    private final android.os.Handler updates=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh=this::reload;
    private final Runnable changed=() -> { if (resumed) { updates.removeCallbacks(refresh); updates.postDelayed(refresh,250); } };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b); Ui.apply(this);
        showGrades = b != null ? b.getBoolean("grades") : getIntent().getBooleanExtra("grades", false);
        if (b != null) {
            finishedOpen = b.getBoolean("finishedOpen");
            java.util.ArrayList<String> t = b.getStringArrayList("openTerms");
            if (t != null) { openTerms.addAll(t); termsInitialised = true; }
        }
        render();
    }
    @Override public void onResume() {
        super.onResume();
        resumed=true; CampusStore.observe(changed); CampusAutoRefresh.maybeRefresh(this); reload();
    }
    private void reload() {
        if (isDestroyed() || io.isShutdown()) return;
        io.execute(() -> {
            Academic a = null; boolean broken = false;
            try { a = AcademicStore.load(getApplicationContext()); } catch (Exception e) { broken = true; }
            Academic result = a; boolean bad = broken;
            runOnUiThread(() -> { if (isDestroyed() || isFinishing()) return; if (scroll!=null) keepScroll=scroll.getScrollY(); data = result; loaded = true; render(); if (bad) toast("考试成绩存档无法读取"); });
        });
    }
    @Override public void onSaveInstanceState(Bundle b) {
        b.putBoolean("grades", showGrades); b.putBoolean("finishedOpen", finishedOpen);
        b.putStringArrayList("openTerms", new ArrayList<>(openTerms)); super.onSaveInstanceState(b);
    }
    @Override public void onDestroy() { CampusSilentRefresh.hostDestroyed(this); io.shutdown(); super.onDestroy(); }
    @Override public void onPause() { resumed=false; CampusStore.unobserve(changed); updates.removeCallbacksAndMessages(null); CampusAutoRefresh.pause(this); super.onPause(); }
    /** 返回待办时同样淡出，和底部导航的切换手感一致。 */
    @Override public void finish() { super.finish(); AppNavigation.fade(this); }

    private int dp(float n) { return Ui.dp(this, n); }
    private TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this, s, size, color, bold); }
    private void sync() {
        startActivity(new Intent(this, NwpuImportActivity.class).putExtra("reuse_session", data != null).putExtra("school_id", "nwpu"));
    }
    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }

    /* ======================================================================= page */

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout root = CourseUi.column(this); root.setBackgroundColor(Ui.BG);
        setContentView(root); Ui.systemBars(this, root);
        getWindow().setNavigationBarColor(Ui.BG);   // 和悬浮导航外的页面底色连成一片

        LinearLayout head = new LinearLayout(this); head.setBaselineAligned(false); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(14), dp(8), 0); root.addView(head);
        LinearLayout titles = CourseUi.column(this); head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = text("考试与成绩", 26, Ui.TEXT, true); title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titles.addView(title);
        String sub = subtitle();
        if (sub != null) { TextView s = text(sub, 12, Ui.MUTE, false); s.setPadding(0, dp(4), 0, 0); titles.addView(s); }

        CampusSyncBanner.add(this,head);
        if (!loaded) { root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1)); addNavigation(root); return; }
        if (data == null) { empty(root); addNavigation(root); return; }

        root.addView(segmented(), segmentParams());
        scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = CourseUi.column(this); list.setPadding(dp(16), dp(4), dp(16), dp(28));
        list.setClipChildren(false); list.setClipToPadding(false);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (showGrades) grades(list); else exams(list);
        if (keepScroll >= 0) { int y = keepScroll; keepScroll = -1; scroll.post(() -> scroll.scrollTo(0, y)); }
        addNavigation(root);
    }
    /** 局部展开 / 收起后重绘，保持滚动位置。 */
    private void rerender() { keepScroll = scroll == null ? -1 : scroll.getScrollY(); render(); }

    private String subtitle() {
        if (data == null) return loaded ? "西北工业大学教务系统" : null;
        long t = Math.max(data.updated("exams"), Math.max(data.updated("grades"), data.updated("gpa")));
        return t == 0 ? "西北工业大学" : "西北工业大学 · " + new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(t)) + " 同步";
    }
    private void addNavigation(LinearLayout root) { root.addView(AppNavigation.bar(this, AppNavigation.ACADEMIC), new LinearLayout.LayoutParams(-1, -2)); }

    private LinearLayout.LayoutParams segmentParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(42));
        p.setMargins(dp(16), dp(16), dp(16), dp(12)); return p;
    }
    /** 一体式分段控件：浅灰槽 + 白色滑块。 */
    private View segmented() {
        LinearLayout track = new LinearLayout(this); track.setPadding(dp(3), dp(3), dp(3), dp(3));
        // 浅色：灰槽白滑块；深色：卡片色槽 + 更亮的滑块，保持“选中更突出”。
        track.setBackground(Ui.round(this, Ui.dark ? Ui.CARD : Ui.FILL, 13));
        String[] labels = {"考试安排", "成绩绩点"};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == showGrades; final boolean grades = i == 1;
            TextView t = text(labels[i], 14, on ? Ui.TEXT : Ui.SUB, on); t.setGravity(Gravity.CENTER);
            if (on) { t.setBackground(Ui.round(this, Ui.dark ? Ui.FILL : Ui.CARD, 10)); if (!Ui.dark) t.setElevation(dp(1)); }
            else t.setBackground(Ui.ripple(this, null, Ui.press(), 10));
            t.setSelected(on); t.setContentDescription(labels[i] + (on ? "，已选中" : ""));
            t.setOnClickListener(v -> { if (showGrades != grades) { showGrades = grades; render(); } });
            track.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return track;
    }

    private void empty(LinearLayout root) {
        LinearLayout box = CourseUi.column(this); box.setGravity(Gravity.CENTER); box.setPadding(dp(32), 0, dp(32), dp(40));
        root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout mark = new FrameLayout(this); mark.setBackground(Ui.disc(this, Ui.ACCENT_BG));
        ImageView glyph = new ImageView(this); glyph.setImageDrawable(new AppNavigation.TabIcon(this, AppNavigation.ACADEMIC, Ui.ACCENT));
        mark.addView(glyph, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        box.addView(mark, new LinearLayout.LayoutParams(dp(84), dp(84)));
        CourseUi.gap(box, 22); box.addView(text("考试和成绩，一处看全", 21, Ui.TEXT, true));
        CourseUi.gap(box, 12);
        TextView d = text("登录教务系统一次，自动读取考试安排、\n各学期成绩和总绩点", 13, Ui.SUB, false);
        d.setGravity(Gravity.CENTER); d.setLineSpacing(dp(4), 1f); box.addView(d);
        CourseUi.gap(box, 28); box.addView(CourseUi.action(this, "一键导入", true, this::sync), CourseUi.row(dp(50)));
    }
    private void section(LinearLayout list, String left, String right, int topDp) {
        LinearLayout h = new LinearLayout(this); h.setGravity(Gravity.BOTTOM); h.setPadding(dp(4), dp(topDp), dp(4), dp(10));
        h.addView(text(left, 15, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (right != null) h.addView(text(right, 12, Ui.MUTE, false));
        list.addView(h);
    }
    private void note(LinearLayout list, String s) {
        TextView t = text(s, 11, Ui.MUTE, false); t.setGravity(Gravity.CENTER); t.setPadding(dp(12), dp(20), dp(12), 0);
        t.setLineSpacing(dp(3), 1f); list.addView(t);
    }
    private LinearLayout card(LinearLayout parent, int bottomDp) {
        LinearLayout c = CourseUi.column(this); Ui.cardSurface(c, 20);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(bottomDp); parent.addView(c, p);
        return c;
    }
    private ImageView chevron(boolean open) {
        ImageView v = new ImageView(this); v.setImageDrawable(CourseUi.glyph(this, "down")); v.setAlpha(.55f);
        v.setRotation(open ? 180 : 0); return v;
    }

    /* ======================================================================= exams */

    private void exams(LinearLayout list) {
        if (!data.hasExams) { infoCard(list, "这次没有读到考试安排", "点右上角重新同步试试"); return; }
        String today = today();
        List<Academic.Exam> upcoming = new ArrayList<>(), done = new ArrayList<>();
        for (Academic.Exam e : data.exams) {
            int d = e.daysFrom(today);
            if (e.finished || d != Integer.MIN_VALUE && d < 0) done.add(e); else upcoming.add(e);
        }
        if (upcoming.isEmpty()) infoCard(list, "近期没有待考科目", "考试安排公布后，同步一下就能看到");
        else {
            hero(list, upcoming.get(0), today);
            if (upcoming.size() > 1) {
                section(list, "接下来", upcoming.size() - 1 + " 场", 22);
                for (int i = 1; i < upcoming.size(); i++) timelineRow(list, upcoming.get(i), today);
            }
        }
        if (!done.isEmpty()) finished(list, done);
        note(list, "以教务系统“我的考试”为准 · 时间地点调整后请重新同步");
    }
    private void infoCard(LinearLayout list, String title, String detail) {
        LinearLayout c = card(list, 4); c.setGravity(Gravity.CENTER); c.setPadding(dp(20), dp(28), dp(20), dp(28));
        c.addView(text(title, 16, Ui.TEXT, true), wrap()); CourseUi.gap(c, 6); c.addView(text(detail, 12, Ui.SUB, false), wrap());
    }
    private static String countdown(int d) { return d == 0 ? "今天" : d == 1 ? "明天" : d == 2 ? "后天" : String.valueOf(d); }

    /** 下一场考试：和待办页「今日概览」同一张深墨渐变卡 + 大号倒计时。 */
    private void hero(LinearLayout list, Academic.Exam e, String today) {
        LinearLayout c = CourseUi.column(this); c.setPadding(dp(22), dp(20), dp(22), dp(20));
        c.setBackground(Ui.heroSurface(this, 26));
        Ui.lift(c, 8f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(2); list.addView(c, p);
        int on = Ui.ON_HERO, soft = Ui.HERO_SUB;
        c.addView(text("下一场考试", 12, soft, true));
        CourseUi.gap(c, 10);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.BOTTOM); c.addView(row);
        LinearLayout left = CourseUi.column(this); row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text(e.course, 22, on, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); left.addView(name);
        int d = e.daysFrom(today);
        if (d != Integer.MIN_VALUE) {
            LinearLayout count = new LinearLayout(this); count.setGravity(Gravity.BOTTOM); count.setPadding(dp(14), 0, 0, 0);
            TextView big = text(countdown(d), d <= 2 ? 30 : 40, on, true); big.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            big.setIncludeFontPadding(false); count.addView(big);
            if (d > 2) { TextView unit = text("天后", 13, soft, true); unit.setPadding(dp(4), 0, 0, dp(4)); count.addView(unit); }
            row.addView(count);
        }
        CourseUi.gap(c, 16);
        View line = new View(this); line.setBackgroundColor(Ui.withAlpha(android.graphics.Color.WHITE, 22)); c.addView(line, new LinearLayout.LayoutParams(-1, 1));
        CourseUi.gap(c, 14);
        c.addView(infoLine(e.date.isEmpty() ? (e.time.isEmpty() ? "时间待定" : e.time) : dateLong(e.date, today) + "   " + e.start + "–" + e.end, 15, on, true));
        if (!e.location.isEmpty()) { CourseUi.gap(c, 6); c.addView(infoLine(place(e.location), 13, soft, false)); }
        // “未结束 / 未开始”在待考卡上是废话；只显示真正有信息量的状态（如缓考、待安排）。
        if (!e.status.isEmpty() && !e.status.matches("未(结束|开始)")) {
            CourseUi.gap(c, 12); c.addView(Ui.tag(this, e.status, on, Ui.withAlpha(android.graphics.Color.WHITE, 30)), new LinearLayout.LayoutParams(-2, -2));
        }
    }
    private TextView infoLine(String s, float size, int color, boolean bold) {
        TextView t = text(s, size, color, bold); t.setLineSpacing(dp(2), 1f); return t;
    }
    /** 待考时间线：左侧日期块，右侧课程卡。 */
    private void timelineRow(LinearLayout list, Academic.Exam e, String today) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.bottomMargin = dp(10); list.addView(row, rp);
        LinearLayout date = CourseUi.column(this); date.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(date, new LinearLayout.LayoutParams(dp(52), -2));
        java.util.Calendar cal = Timetable.calendar(e.date);
        if (cal != null) {
            date.addView(text((cal.get(java.util.Calendar.MONTH) + 1) + "月", 11, Ui.SUB, true), wrap());
            TextView day = text(String.valueOf(cal.get(java.util.Calendar.DAY_OF_MONTH)), 24, Ui.TEXT, true);
            day.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); day.setIncludeFontPadding(false); date.addView(day, wrap());
            date.addView(text(weekday(e.date), 11, Ui.MUTE, false), wrap());
        } else date.addView(text("待定", 12, Ui.MUTE, true), wrap());

        LinearLayout c = CourseUi.column(this); Ui.cardSurface(c, 16); c.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = dp(6); row.addView(c, cp);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); c.addView(top);
        TextView name = text(e.course, 15, Ui.TEXT, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        int d = e.daysFrom(today);
        if (d != Integer.MIN_VALUE) {
            boolean soon = d <= 3;
            TextView pill = Ui.tag(this, d <= 2 ? countdown(d) : d + " 天后", soon ? Ui.RED : Ui.VIOLET, soon ? Ui.RED_BG : Ui.VIOLET_BG);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2); pp.leftMargin = dp(8); top.addView(pill, pp);
        }
        CourseUi.gap(c, 6);
        c.addView(text(e.date.isEmpty() ? e.time : e.start + "–" + e.end + (e.date.startsWith(today.substring(0, 4)) ? "" : " · " + e.date.substring(0, 4) + "年"), 13, Ui.TEXT, false));
        if (!e.location.isEmpty()) { CourseUi.gap(c, 3); TextView l = text(place(e.location), 12, Ui.SUB, false); l.setLineSpacing(dp(2), 1f); c.addView(l); }
    }
    /** 已结束：默认收起成一行，展开后是紧凑列表。 */
    private void finished(LinearLayout list, List<Academic.Exam> done) {
        LinearLayout c = card(list, 4);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams(); lp.topMargin = dp(18);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(18), dp(16), dp(14), dp(16));
        head.setBackground(Ui.ripple(this, null, Ui.press(), 18));
        head.addView(text("已结束", 15, Ui.TEXT, true));
        TextView count = text(done.size() + " 场", 13, Ui.MUTE, false); count.setPadding(dp(8), 0, 0, 0);
        head.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(chevron(finishedOpen), new LinearLayout.LayoutParams(dp(18), dp(18)));
        head.setContentDescription("已结束考试 " + done.size() + " 场，" + (finishedOpen ? "点击收起" : "点击展开"));
        head.setOnClickListener(v -> { finishedOpen = !finishedOpen; rerender(); });
        c.addView(head);
        if (!finishedOpen) return;
        for (int i = done.size() - 1; i >= 0; i--) {
            Academic.Exam e = done.get(i);
            View div = Ui.hairline(this); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 1); dl.leftMargin = dp(18); c.addView(div, dl);
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(18), dp(13), dp(18), dp(13));
            java.util.Calendar cal = Timetable.calendar(e.date);
            String when = cal == null ? "—" : (cal.get(java.util.Calendar.MONTH) + 1) + "/" + cal.get(java.util.Calendar.DAY_OF_MONTH);
            r.addView(text(when, 13, Ui.MUTE, true), new LinearLayout.LayoutParams(dp(52), -2));
            LinearLayout mid = CourseUi.column(this); r.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
            TextView n = text(e.course, 14, Ui.SUB, true); n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END); mid.addView(n);
            if (!e.location.isEmpty()) { TextView l = text(place(e.location), 11, Ui.MUTE, false); l.setSingleLine(true); l.setEllipsize(TextUtils.TruncateAt.END); l.setPadding(0, dp(2), 0, 0); mid.addView(l); }
            c.addView(r);
        }
        CourseUi.gap(c, 6);
    }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private static String place(String s) { return s.replaceAll("\\s*[,，]\\s*", " · "); }
    private static String weekday(String date) {
        java.util.Calendar c = Timetable.calendar(date);
        return c == null ? "" : "周" + "日一二三四五六".charAt(c.get(java.util.Calendar.DAY_OF_WEEK) - 1);
    }
    private static String dateLong(String date, String today) {
        String s = Integer.parseInt(date.substring(5, 7)) + "月" + Integer.parseInt(date.substring(8)) + "日 " + weekday(date);
        return date.startsWith(today.substring(0, 4)) ? s : date.substring(0, 4) + "年" + s;
    }

    /* ======================================================================= grades */

    private void grades(LinearLayout list) {
        Map<String, List<Academic.Grade>> terms = data.hasGrades ? data.bySemester() : new java.util.LinkedHashMap<>();
        gpaHero(list, terms);
        if (!data.hasGrades) { CourseUi.gap(list, 12); infoCard(list, "成绩暂未读到", "应用会自动重试，无需手动同步"); return; }
        if (data.incomplete("grades")) note(list,"部分学期暂未读到，已保留其他学期成绩，稍后会自动重试。");
        if (terms.isEmpty()) { CourseUi.gap(list, 12); infoCard(list, "教务系统暂未公布成绩", "出分后同步一下就能看到"); return; }
        if (!termsInitialised) { openTerms.add(terms.keySet().iterator().next()); termsInitialised = true; }
        section(list, "各学期成绩", terms.size() + " 个学期", 24);
        for (Map.Entry<String, List<Academic.Grade>> t : terms.entrySet()) term(list, t.getKey(), t.getValue());
        note(list, "学期绩点按“课程绩点 × 学分”在本机计算，仅供参考，以教务系统为准");
    }
    private void gpaHero(LinearLayout list, Map<String, List<Academic.Grade>> terms) {
        LinearLayout c = card(list, 0); c.setPadding(dp(20), dp(18), dp(20), dp(16));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams(); lp.topMargin = dp(2);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.BOTTOM); c.addView(top);
        LinearLayout left = CourseUi.column(this); top.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(text("总绩点", 12, Ui.SUB, true));
        TextView big = text(data.gpa == null ? "—" : formatGpa(data.gpa), 44, Ui.TEXT, true);
        big.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); big.setIncludeFontPadding(false); big.setPadding(0, dp(6), 0, 0);
        left.addView(big);
        double credits = Academic.credits(data.grades); int courses = data.grades.size();
        LinearLayout stats = new LinearLayout(this); top.addView(stats);
        stats.addView(stat(trim(credits), "已修学分"));
        View gap = new View(this); stats.addView(gap, new LinearLayout.LayoutParams(dp(18), 1));
        stats.addView(stat(String.valueOf(courses), "门课程"));

        List<String> labels = new ArrayList<>(); List<Double> values = new ArrayList<>();
        List<String> keys = new ArrayList<>(terms.keySet());
        for (int i = keys.size() - 1; i >= 0; i--) { Double w = Academic.weighted(terms.get(keys.get(i))); if (w != null) { labels.add(shortTerm(keys.get(i))); values.add(w); } }
        if (values.size() >= 2) {
            CourseUi.gap(c, 18);
            LinearLayout cap = new LinearLayout(this); cap.setGravity(Gravity.CENTER_VERTICAL); c.addView(cap);
            cap.addView(text("学期绩点走势", 12, Ui.SUB, true), new LinearLayout.LayoutParams(0, -2, 1));
            double delta = values.get(values.size() - 1) - values.get(values.size() - 2);
            if (Math.abs(delta) >= 0.0005) cap.addView(Ui.tag(this, (delta > 0 ? "↑ " : "↓ ") + formatGpa(Math.abs(delta)),
                    delta > 0 ? Ui.GREEN : Ui.ORANGE, delta > 0 ? Ui.GREEN_BG : Ui.ORANGE_BG));
            CourseUi.gap(c, 10);
            Trend trend = new Trend(this, labels, values);
            trend.setContentDescription("学期绩点走势，" + TextUtils.join("，", describe(labels, values)));
            c.addView(trend, new LinearLayout.LayoutParams(-1, dp(values.size() > 4 ? 112 : 96)));
        }
        if (data.gpa != null) { CourseUi.gap(c, 12); c.addView(text("总绩点来自教务系统学生画像", 11, Ui.MUTE, false)); }
    }
    private static List<String> describe(List<String> l, List<Double> v) {
        List<String> out = new ArrayList<>(); for (int i = 0; i < l.size(); i++) out.add(l.get(i) + " " + formatGpa(v.get(i))); return out;
    }
    private View stat(String value, String label) {
        LinearLayout s = CourseUi.column(this); s.setGravity(Gravity.END);
        TextView v = text(value, 18, Ui.TEXT, true); v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); s.addView(v);
        s.addView(text(label, 11, Ui.MUTE, false)); return s;
    }
    /** "2025-2026秋" → "25秋"；"2025-2026春" → "26春"（春夏学期在后一年）。 */
    static String shortTerm(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})\\s*-\\s*(\\d{4})\\s*(春|夏|秋|冬)").matcher(name);
        if (!m.find()) return name.length() > 4 ? name.substring(name.length() - 4) : name;
        String year = "秋".equals(m.group(3)) || "冬".equals(m.group(3)) ? m.group(1) : m.group(2);
        return year.substring(2) + m.group(3);
    }
    private void term(LinearLayout list, String name, List<Academic.Grade> grades) {
        boolean open = openTerms.contains(name);
        LinearLayout c = card(list, 10);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(18), dp(15), dp(14), dp(15));
        head.setBackground(Ui.ripple(this, null, Ui.press(), 18));
        LinearLayout t = CourseUi.column(this); head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        t.addView(text(name, 15, Ui.TEXT, true));
        Double w = Academic.weighted(grades);
        TextView meta = text(grades.size() + " 门 · " + trim(Academic.credits(grades)) + " 学分", 12, Ui.MUTE, false); meta.setPadding(0, dp(3), 0, 0); t.addView(meta);
        if (w != null) {
            LinearLayout gp = CourseUi.column(this); gp.setGravity(Gravity.END); gp.setPadding(0, 0, dp(10), 0);
            TextView v = text(formatGpa(w), 17, Ui.ACCENT, true); v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); gp.addView(v);
            gp.addView(text("学期绩点", 10, Ui.MUTE, false)); head.addView(gp);
        }
        head.addView(chevron(open), new LinearLayout.LayoutParams(dp(18), dp(18)));
        head.setContentDescription(name + "，" + (open ? "点击收起" : "点击展开"));
        head.setOnClickListener(v -> { if (!openTerms.remove(name)) openTerms.add(name); rerender(); });
        c.addView(head);
        if (!open) return;
        for (Academic.Grade g : grades) {
            View div = Ui.hairline(this); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 1); dl.leftMargin = dp(18); c.addView(div, dl);
            gradeRow(c, g);
        }
        CourseUi.gap(c, 4);
    }
    private void gradeRow(LinearLayout c, Academic.Grade g) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(18), dp(13), dp(16), dp(13));
        LinearLayout left = CourseUi.column(this); row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView n = text(g.name, 15, Ui.TEXT, false); n.setTypeface(Ui.medium()); n.setMaxLines(2); n.setEllipsize(TextUtils.TruncateAt.END); left.addView(n);
        List<String> meta = new ArrayList<>();
        if (!g.type.isEmpty()) meta.add(g.type);
        if (g.credits != null) meta.add(trim(g.credits) + " 学分");
        if (g.gp != null) meta.add("绩点 " + formatGpa(g.gp));
        if (!meta.isEmpty()) { TextView m = text(TextUtils.join(" · ", meta), 12, Ui.SUB, false); m.setPadding(0, dp(3), 0, 0); left.addView(m); }
        int[] band = band(g.score);
        TextView score = text(g.score.isEmpty() ? "—" : g.score, g.score.length() > 3 ? 13 : 16, band[0], true);
        score.setGravity(Gravity.CENTER); score.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        score.setBackground(Ui.round(this, band[1], 11)); score.setPadding(dp(8), 0, dp(8), 0); score.setMinWidth(dp(54));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(36)); sp.leftMargin = dp(12); row.addView(score, sp);
        row.setBackground(Ui.ripple(this, null, Ui.press(), 0));
        row.setContentDescription(g.name + "，成绩 " + (g.score.isEmpty() ? "暂无" : g.score) + "，点击查看详情");
        row.setOnClickListener(v -> detail(g));
        c.addView(row);
    }
    /** 分数段配色：优 绿 / 良 靛蓝 / 中 中性 / 不及格 红。返回 {文字色, 底色}。 */
    private static int[] band(String score) {
        double v = -1;
        try { v = Double.parseDouble(score); } catch (Exception ignored) { }
        if (v < 0) {
            if (score.contains("不及格") || score.contains("不通过") || score.equals("F")) v = 0;
            else if (score.contains("优") || score.startsWith("A")) v = 95;
            else if (score.contains("良") || score.startsWith("B")) v = 85;
            else if (score.contains("中") || score.contains("及格") || score.contains("通过") || score.startsWith("C") || score.startsWith("D")) v = 70;
        }
        if (v >= 90) return new int[]{Ui.GREEN, Ui.GREEN_BG};
        if (v >= 80) return new int[]{Ui.ACCENT, Ui.ACCENT_BG};
        if (v >= 60) return new int[]{Ui.TEXT, Ui.FILL};
        if (v >= 0) return new int[]{Ui.RED, Ui.RED_BG};
        return new int[]{Ui.SUB, Ui.FILL};
    }
    /** 课程详情：底部弹层，大号成绩 + 指标格 + 成绩构成条。 */
    private void detail(Academic.Grade g) {
        LinearLayout body = CourseUi.column(this); body.setPadding(dp(4), dp(4), dp(4), dp(12));
        TextView title = text(g.name, 20, Ui.TEXT, true); title.setLineSpacing(dp(2), 1f); body.addView(title);
        List<String> sub = new ArrayList<>(); if (!g.type.isEmpty()) sub.add(g.type); if (!g.code.isEmpty()) sub.add(g.code); sub.add(g.semester);
        CourseUi.gap(body, 6); body.addView(text(TextUtils.join(" · ", sub), 12, Ui.SUB, false));
        CourseUi.gap(body, 20);
        LinearLayout tiles = new LinearLayout(this); body.addView(tiles);
        int[] b = band(g.score);
        tiles.addView(tile("成绩", g.score.isEmpty() ? "—" : g.score, b[0], b[1]), new LinearLayout.LayoutParams(0, dp(78), 1));
        tiles.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        tiles.addView(tile("学分", g.credits == null ? "—" : trim(g.credits), Ui.TEXT, Ui.FILL), new LinearLayout.LayoutParams(0, dp(78), 1));
        tiles.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        tiles.addView(tile("绩点", g.gp == null ? "—" : formatGpa(g.gp), Ui.TEXT, Ui.FILL), new LinearLayout.LayoutParams(0, dp(78), 1));
        if (!g.detail.isEmpty()) {
            CourseUi.gap(body, 24); body.addView(text("成绩构成", 13, Ui.SUB, true)); CourseUi.gap(body, 4);
            for (String part : g.detail.split("；")) component(body, part.trim());
        }
        TextView close = CourseUi.action(this, "知道了", false, null);
        close.setBackground(Ui.ripple(this, Ui.round(this, Ui.FILL, 14), Ui.press(), 14));   // 弹层本身是白底
        LinearLayout footer = CourseUi.column(this); footer.setPadding(0, dp(8), 0, 0); footer.addView(close, CourseUi.row(dp(48)));
        android.app.Dialog dialog = Ui.sheet(this, body, footer);
        close.setOnClickListener(v -> dialog.dismiss());
    }
    private View tile(String label, String value, int fg, int bg) {
        LinearLayout t = CourseUi.column(this); t.setGravity(Gravity.CENTER); t.setBackground(Ui.round(this, bg, 16));
        TextView v = text(value, value.length() > 3 ? 16 : 24, fg, true); v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); t.addView(v, wrap());
        TextView l = text(label, 11, fg == Ui.TEXT ? Ui.SUB : Ui.withAlpha(fg, 200), true); l.setPadding(0, dp(3), 0, 0); t.addView(l, wrap());
        return t;
    }
    /** "期末成绩:96" → 标签 + 数值 + 进度条；无法识别的原样一行。 */
    private void component(LinearLayout body, String part) {
        if (part.isEmpty()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)[:：]\\s*(\\d+(?:\\.\\d+)?)\\s*$").matcher(part);
        LinearLayout row = CourseUi.column(this); row.setPadding(0, dp(12), 0, 0); body.addView(row);
        if (!m.find()) { row.addView(text(part, 14, Ui.TEXT, false)); return; }
        double v = Double.parseDouble(m.group(2));
        LinearLayout top = new LinearLayout(this); row.addView(top);
        top.addView(text(m.group(1).trim(), 14, Ui.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(text(m.group(2), 14, Ui.TEXT, true));
        if (v >= 0 && v <= 100) {
            CourseUi.gap(row, 7);
            FrameLayout track = new FrameLayout(this); track.setBackground(Ui.round(this, Ui.FILL, 3));
            View fill = new View(this); fill.setBackground(Ui.round(this, band(m.group(2))[0], 3));
            track.addView(fill, new FrameLayout.LayoutParams(0, -1));
            row.addView(track, new LinearLayout.LayoutParams(-1, dp(6)));
            track.post(() -> { fill.getLayoutParams().width = (int) (track.getWidth() * v / 100); fill.requestLayout(); });
        }
    }
    static String formatGpa(double gpa) {
        return String.format(Locale.ROOT, "%.3f", gpa);
    }
    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
    private void toast(String s) { if (!isFinishing() && !isDestroyed()) android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    /** 学期绩点柱状图：最新学期用强调色，其余淡色；柱顶标数值，柱底标学期。纯 Canvas。 */
    static final class Trend extends View {
        private final List<String> labels; private final List<Double> values;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), value = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        Trend(Context c, List<String> labels, List<Double> values) {
            super(c); this.labels = labels; this.values = values;
            label.setTextSize(Ui.dp(c, 10)); label.setColor(Ui.MUTE); label.setTextAlign(Paint.Align.CENTER);
            value.setTextSize(Ui.dp(c, 11)); value.setTextAlign(Paint.Align.CENTER); value.setTypeface(Ui.medium());
        }
        @Override protected void onDraw(Canvas canvas) {
            int n = values.size(); if (n == 0) return;
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float labelH = 18 * d, valueH = 18 * d, chartH = h - labelH - valueH;
            double max = 0, min = 5; for (double v : values) { max = Math.max(max, v); min = Math.min(min, v); }
            double floor = Math.max(0, Math.min(min - 0.6, max - 1.2));      // 拉开差异，但不夸大
            float slot = w / n, bw = Math.min(28 * d, slot * 0.46f);
            for (int i = 0; i < n; i++) {
                boolean last = i == n - 1;
                float cx = slot * i + slot / 2;
                float frac = (float) ((values.get(i) - floor) / Math.max(0.01, max - floor));
                float top = valueH + chartH * (1 - Math.max(0.08f, frac));
                r.set(cx - bw / 2, top, cx + bw / 2, valueH + chartH);
                bar.setColor(last ? Ui.ACCENT : Ui.withAlpha(Ui.ACCENT, Ui.dark ? 90 : 60));
                canvas.drawRoundRect(r, 7 * d, 7 * d, bar);
                value.setColor(last ? Ui.ACCENT : Ui.SUB);
                canvas.drawText(formatGpa(values.get(i)), cx, top - 6 * d, value);
                label.setColor(last ? Ui.TEXT : Ui.MUTE);
                canvas.drawText(labels.get(i), cx, h - 4 * d, label);
            }
        }
    }
}
