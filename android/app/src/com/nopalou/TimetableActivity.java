package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import java.util.Calendar;
import java.util.List;

/** A compact seven-day timetable. Course details and management stay behind taps. */
public class TimetableActivity extends Activity {
    // 课程色：浅色是淡底深字，深色是暗底亮字；同一门课在两种模式下是同一个色相。
    private static final int[] FILLS = {0xFFECE8FA, 0xFFE0EFEB, 0xFFF8EADD, 0xFFE3EBF9, 0xFFF5E4EB, 0xFFEBEDDC};
    private static final int[] INKS = {0xFF5D498F, 0xFF306E61, 0xFF935B34, 0xFF3D6095, 0xFF984F70, 0xFF64703A};
    private static final int[] FILLS_DARK = {0xFF2A2440, 0xFF183029, 0xFF35281B, 0xFF1C2638, 0xFF35202B, 0xFF282B1A};
    private static final int[] INKS_DARK = {0xFFC3B4F5, 0xFF8FD6C3, 0xFFF0B98E, 0xFF9DBBF0, 0xFFF0A3C4, 0xFFC7D28E};
    static int fill(int i) { return (Ui.dark ? FILLS_DARK : FILLS)[i]; }
    static int ink(int i) { return (Ui.dark ? INKS_DARK : INKS)[i]; }
    static int colorIndex(String name) { return (name.hashCode() & Integer.MAX_VALUE) % FILLS.length; }
    Timetable table;
    int week;
    int savedScrollY;
    ScrollView timetableScroll;
    LinearLayout swipeBoard;
    View swipeStart;
    float downX, downY;
    int swipeAxis;
    boolean swipeCandidate, changingWeek;
    VelocityTracker velocity;
    static final int GUTTER = 34;
    final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean resumed;
    private final Runnable reloadTask=this::reload;
    private final Runnable dataChanged=() -> { if (resumed) { this.uiTick.removeCallbacks(reloadTask); this.uiTick.postDelayed(reloadTask,250); } };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.apply(this);
        Bundle state = b != null ? b : getIntent().getBundleExtra("navigation_state");
        if (state != null) { week = state.getInt("week", 0); savedScrollY = state.getInt("scrollY", 0); }
    }
    @Override public void onResume() {
        super.onResume();
        resumed=true; CampusStore.observe(dataChanged);
        CampusAutoRefresh.maybeRefresh(this);
        reload();
    }
    private void reload() {
        if (isFinishing() || isDestroyed() || io.isShutdown()) return;
        if (timetableScroll!=null) savedScrollY=timetableScroll.getScrollY();
        io.execute(() -> {
            try {
                Timetable loaded = TimetableStore.load(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    table = loaded;
                    if (table != null) week = Math.max(1, Math.min(table.maxWeek, week == 0 ? table.currentWeek() : week));
                    render();
                });
            } catch (Exception e) { runOnUiThread(() -> { table = null; render(); toast("课表存档无法读取，请重新导入"); }); }
        });
    }
    @Override public void onSaveInstanceState(Bundle b) { b.putInt("week", week); b.putInt("scrollY", timetableScroll == null ? savedScrollY : timetableScroll.getScrollY()); super.onSaveInstanceState(b); }
    @Override public void onPause() {
        resumed=false; CampusStore.unobserve(dataChanged); uiTick.removeCallbacks(reloadTask);
        CampusAutoRefresh.pause(this);
        if (timetableScroll != null) savedScrollY = timetableScroll.getScrollY();
        AppNavigation.rememberTimetable(week, savedScrollY);
        super.onPause();
    }
    @Override public void onDestroy() { CampusSilentRefresh.hostDestroyed(this); uiTick.removeCallbacksAndMessages(null); if (velocity != null) velocity.recycle(); io.shutdown(); super.onDestroy(); }
    /** 返回待办时同样淡出，和底部导航的切换手感一致。 */
    @Override public void finish() { super.finish(); AppNavigation.fade(this); }
    final android.os.Handler uiTick = new android.os.Handler(android.os.Looper.getMainLooper());
    AlertDialog.Builder alert() { return new AlertDialog.Builder(this, Ui.dialogTheme()); }
    int dp(float n) { return Ui.dp(this, n); }
    TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this, s, size, color, bold); }
    ImageView icon(String s, String description, Runnable action) {
        return CourseUi.icon(this, s, description, action);
    }
    void importTable(boolean reuse) {
        Intent intent = new Intent(this, NwpuImportActivity.class).putExtra("reuse_session", reuse);
        if (reuse && table != null) intent.putExtra("school_id", table.school.id);
        startActivity(intent);
    }
    String date(int day) {
        String label = table.dayLabel(week, day);
        return label.length() >= 10 ? label.substring(5) : "--/--";
    }
    void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout root = CourseUi.column(this); root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(12), dp(4), dp(12), dp(4));
        setContentView(root);
        Ui.systemBars(this, root);
        getWindow().setNavigationBarColor(Ui.BG);   // 和悬浮导航外的页面底色连成一片
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(toolbar, CourseUi.row(dp(46)));
        // Compact header leaves the screen to the timetable; system back still returns to tasks.
        TextView title = text("我的课表", 21, Ui.TEXT, true);
        title.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        title.setPadding(dp(8), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (getPackageName().equals("com.nopalou.preview") || getPackageName().equals("com.nopalou.timetabletest"))
            toolbar.addView(Ui.tag(this, "测试版", Ui.SUB, Ui.FILL));
        CampusSyncBanner.add(this,toolbar);
        if (table != null) toolbar.addView(icon("sync", "重新同步课表", () -> importTable(true)));
        toolbar.addView(icon("more", "课表设置", this::settings));
        if (table == null) { empty(root); addNavigation(root); return; }

        TextView school = text(table.school.name + "  /  " + table.termName, 11, Ui.SUB, false);
        school.setPadding(dp(8),0,dp(8),dp(6)); school.setSingleLine(true); school.setEllipsize(android.text.TextUtils.TruncateAt.END); root.addView(school);
        swipeStart = school;
        LinearLayout navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(navigation);
        LinearLayout weekInfo = CourseUi.column(this); weekInfo.setPadding(dp(8),0,0,0); weekInfo.setMinimumHeight(dp(44)); weekInfo.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout weekTitle = new LinearLayout(this); weekTitle.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = text("第 " + week + " 周", 20, Ui.TEXT, true);
        number.setContentDescription("第" + week + "周，点击选择教学周");
        weekTitle.addView(number);
        ImageView down = new ImageView(this); down.setImageDrawable(CourseUi.glyph(this, "down"));
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(17), dp(17)); arrow.leftMargin = dp(8); weekTitle.addView(down, arrow);
        weekInfo.addView(weekTitle); weekInfo.setOnClickListener(v -> chooseWeek()); weekInfo.setFocusable(true);
        String date = date(1).replace('-', '.') + " — " + date(7).replace('-', '.');
        TextView range = text(date, 11, Ui.SUB, false); range.setPadding(0,dp(2),0,0); weekInfo.addView(range);
        navigation.addView(weekInfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView today = text("本周", 12, week == table.currentWeek() ? Ui.SUB : Ui.ACCENT, true);
        today.setPadding(dp(10), dp(10), dp(10), dp(10));
        today.setMinHeight(dp(44)); today.setMinWidth(dp(48)); today.setGravity(Gravity.CENTER);
        today.setBackground(Ui.ripple(this, Ui.round(this, Ui.CARD, 22), Ui.press(), 22));
        boolean inTerm = table.currentWeek() >= 1 && table.currentWeek() <= table.maxWeek;
        today.setText(inTerm ? "本周" : "非教学周"); today.setEnabled(inTerm);
        today.setContentDescription(inTerm ? "回到本周" : "当前日期不在已导入的教学周内");
        today.setOnClickListener(v -> changeWeek(table.currentWeek(), true));
        navigation.addView(today);
        ImageView previous = icon("back", "上一周", () -> changeWeek(week - 1, true));
        previous.setEnabled(week > 1); previous.setAlpha(week > 1 ? 1f : .25f); navigation.addView(previous);
        ImageView next = icon("next", "下一周", () -> changeWeek(week + 1, true));
        next.setEnabled(week < table.maxWeek); next.setAlpha(week < table.maxWeek ? 1f : .25f); navigation.addView(next);
        CourseUi.gap(root,8);
        swipeBoard = CourseUi.column(this); root.addView(swipeBoard, new LinearLayout.LayoutParams(-1, 0, 1));
        weekdayHeader(swipeBoard);

        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        timetableScroll = scroll; swipeBoard.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        List<TimetableLayout.Block> blocks = TimetableLayout.blocks(table, week);
        int units = 12;
        for (Timetable.Lesson l : table.lessons) units = Math.max(units, l.end);
        Grid grid = new Grid(blocks, units); scroll.addView(grid, new ScrollView.LayoutParams(-1, dp(grid.top(units) + 55)));
        final int restoreY = savedScrollY;
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (scroll.getHeight() <= 0 || grid.getHeight() <= 0) return;
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                scroll.scrollTo(0, restoreY);
            }
        });
        int meetings = 0; for (TimetableLayout.Block b : blocks) meetings += b.lessons.size();
        TextView foot = text(meetings == 0 ? "本周没有已排课课程 · 左右滑动换周" : "左右滑动换周  ·  本周 " + meetings + " 次课程  ·  点课程看详情", 10, Ui.SUB, false);
        foot.setGravity(Gravity.CENTER); foot.setPadding(0, dp(9), 0, dp(8)); root.addView(foot);
        addNavigation(root);
    }
    void addNavigation(LinearLayout root) {
        if (getPackageName().equals("com.nopalou.timetabletest")) return;
        // 页面有 12dp 左右留白；导航栏用负边距铺满整屏宽、贴住底边，和待办页完全一致。
        root.setClipToPadding(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.leftMargin = -dp(12); p.rightMargin = -dp(12); p.bottomMargin = -dp(4);
        root.addView(AppNavigation.bar(this, AppNavigation.TIMETABLE), p);
    }
    void weekdayHeader(LinearLayout root) {
        FrameLayout days = new FrameLayout(this);
        root.addView(days,CourseUi.row(dp(46)));
        days.post(() -> {
        days.removeAllViews(); float columnWidth = (days.getWidth() - dp(GUTTER)) / 7f;
        TextView month = text(date(1).substring(0, 2) + "\n月", 10, Ui.SUB, true);
        month.setGravity(Gravity.CENTER); days.addView(month, new FrameLayout.LayoutParams(dp(GUTTER), dp(44)));
        Calendar now = Calendar.getInstance();
        String today = String.format(java.util.Locale.ROOT, "%02d-%02d", now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        for (int d = 1; d <= 7; d++) {
            String date = date(d);
            boolean active = week == table.currentWeek() && date.equals(today);
            LinearLayout column = CourseUi.column(this); column.setGravity(Gravity.CENTER);
            int left = dp(GUTTER) + Math.round((d - 1) * columnWidth), right = dp(GUTTER) + Math.round(d * columnWidth);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(right - left - dp(4), dp(44)); p.leftMargin = left + dp(2);
            days.addView(column, p);
            if (active) column.setBackground(Ui.round(this, Ui.ACCENT, 12));
            TextView label = text("一二三四五六日".substring(d - 1, d), 10, active ? Ui.ON_ACCENT : d >= 6 ? Ui.MUTE : Ui.SUB, false);
            TextView dateLabel = text(date.substring(3), 12, active ? Ui.ON_ACCENT : Ui.TEXT, true);
            label.setGravity(Gravity.CENTER); dateLabel.setGravity(Gravity.CENTER);
            column.addView(label); dateLabel.setPadding(0, dp(5), 0, 0); column.addView(dateLabel);
        }
        });
    }
    final class Grid extends FrameLayout {
        final List<TimetableLayout.Block> blocks;
        final int units;
        Grid(List<TimetableLayout.Block> blocks, int units) { super(TimetableActivity.this); this.blocks = blocks; this.units = units; }
        int top(int u) { return (u - 1) * 53 + (u > 4 ? 12 : 0) + (u > 8 ? 12 : 0); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); post(this::paintGrid); }
        void paintGrid() {
            removeAllViews(); if (getWidth() == 0) return;
            float col = (getWidth() - dp(GUTTER)) / 7f;
            if (week == table.currentWeek()) {
                int day = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
                View today = new View(TimetableActivity.this); today.setBackgroundColor(Ui.withAlpha(Ui.ACCENT, Ui.dark ? 16 : 9));
                FrameLayout.LayoutParams stripe = new FrameLayout.LayoutParams(Math.round((day + 1) * col) - Math.round(day * col), -1);
                stripe.leftMargin = dp(GUTTER) + Math.round(day * col); addView(today, stripe);
            }
            for (int u = 1; u <= units; u++) {
                LinearLayout unit = CourseUi.column(TimetableActivity.this); unit.setGravity(Gravity.CENTER_HORIZONTAL); unit.setPadding(0, dp(9), 0, 0);
                TextView label = text(String.format(java.util.Locale.ROOT, "%02d", u), 10, Ui.SUB, true); label.setGravity(Gravity.CENTER); unit.addView(label);
                org.json.JSONArray times = table.json.optJSONArray("units"); String start = "";
                for (int i = 0; times != null && i < times.length(); i++) { org.json.JSONObject time = times.optJSONObject(i); if (time != null && time.optInt("number") == u) start = time.optString("start"); }
                if (!start.isEmpty()) { TextView clock = text(start, 8, Ui.MUTE, false); clock.setGravity(Gravity.CENTER); clock.setPadding(0, dp(4), 0, 0); unit.addView(clock); }
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(GUTTER), dp(48)); p.topMargin = dp(top(u)); addView(unit, p);
                View line = new View(TimetableActivity.this); line.setBackgroundColor(Ui.withAlpha(Ui.LINE, 125));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, 1); lp.topMargin = dp(top(u)); lp.leftMargin = dp(GUTTER); addView(line, lp);
            }
            int index = 0;
            boolean firstPaint = !painted;
            painted = true;
            for (TimetableLayout.Block block : blocks) {
                Timetable.Lesson first = block.lessons.get(0);
                int color = colorIndex(first.name);
                LinearLayout card = CourseUi.column(TimetableActivity.this);
                card.setPadding(dp(6), dp(7), dp(3), dp(5));
                card.setBackground(Ui.ripple(TimetableActivity.this, courseFace(fill(color), ink(color)), Ui.press(), 9));
                Ui.pressScale(card, 0.95f);
                boolean overlap = block.lessons.size() > 1;
                TextView name = text(overlap ? block.lessons.size() + "门课程\n时段重叠" : first.name, 10, ink(color), true);
                name.setLineSpacing(dp(1), 1f); name.setMaxLines(block.end == block.start ? 2 : 5); name.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(name, new LinearLayout.LayoutParams(-1, 0, 1));
                if (block.end > block.start) {
                    TextView place = text(overlap ? "点击查看" : (first.room.isEmpty() ? "地点待定" : first.room), 9, Ui.withAlpha(ink(color), 200), false);
                    place.setMaxLines(2); place.setEllipsize(TextUtils.TruncateAt.END); place.setPadding(0, dp(3), 0, 0); card.addView(place);
                }
                card.setContentDescription(overlap ? block.lessons.size() + "门课程时段重叠，点击查看" : first.name + "，" + table.time(first, week) + "，" + first.room);
                card.setFocusable(true); card.setOnClickListener(v -> details(block));
                int left = dp(GUTTER) + Math.round((block.weekday - 1) * col), right = dp(GUTTER) + Math.round(block.weekday * col);
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Math.max(1, right - left - dp(4)), dp(top(block.end) + 53 - top(block.start)) - dp(4));
                p.leftMargin = left + dp(2);
                p.topMargin = dp(top(block.start)) + dp(2); addView(card, p);
                if (firstPaint) {
                    // 首次打开：课程块按顺序轻轻浮现，不是一下子全部蹦出来。
                    card.setAlpha(0f); card.setScaleX(0.92f); card.setScaleY(0.92f);
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(Math.min(index, 14) * 18L)
                            .setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
                index++;
            }
            paintNowLine(col);
        }

        /** 今天这一列画一条「现在」红线：一眼看到下一节课还有多久。每分钟挪一次。 */
        View nowLine;
        void paintNowLine(float col) {
            if (nowLine != null) { removeView(nowLine); nowLine = null; }
            if (week != table.currentWeek()) return;
            Calendar now = Calendar.getInstance();
            float y = nowY(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
            if (y < 0) return;
            int day = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            int left = dp(GUTTER) + Math.round(day * col), right = dp(GUTTER) + Math.round((day + 1) * col);
            FrameLayout line = new FrameLayout(TimetableActivity.this);
            View bar = new View(TimetableActivity.this); bar.setBackgroundColor(Ui.RED);
            line.addView(bar, new FrameLayout.LayoutParams(-1, dp(2), Gravity.CENTER_VERTICAL));
            View dot = new View(TimetableActivity.this); dot.setBackground(Ui.disc(TimetableActivity.this, Ui.RED));
            line.addView(dot, new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER_VERTICAL));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(right - left + dp(4), dp(8));
            p.leftMargin = left - dp(4); p.topMargin = Math.round(y) - dp(4);
            line.setElevation(dp(2));
            line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(line, p); nowLine = line;
            uiTick.removeCallbacksAndMessages(null);
            uiTick.postDelayed(() -> { if (getWidth() > 0) paintNowLine((getWidth() - dp(GUTTER)) / 7f); },
                    60_000L - System.currentTimeMillis() % 60_000L);
        }

        /** 把当前分钟换算成网格里的 y 像素：在第 u 节开始到第 u+1 节开始之间按比例插值；不在上课时段内返回 -1。 */
        float nowY(int minute) {
            org.json.JSONArray times = table.json.optJSONArray("units");
            if (times == null) return -1;
            int[] start = new int[units + 2];
            java.util.Arrays.fill(start, -1);
            for (int i = 0; i < times.length(); i++) {
                org.json.JSONObject t = times.optJSONObject(i);
                if (t == null) continue;
                int n = t.optInt("number");
                if (n >= 1 && n <= units) start[n] = clock(t.optString("start"));
            }
            for (int u = 1; u <= units; u++) {
                if (start[u] < 0) continue;
                int next = u < units && start[u + 1] > start[u] ? start[u + 1] : start[u] + 50;
                if (minute >= start[u] && minute < next) {
                    float f = (minute - start[u]) / (float) (next - start[u]);
                    int y0 = dp(top(u)), y1 = u < units ? dp(top(u + 1)) : dp(top(u) + 53);
                    return y0 + f * (y1 - y0);
                }
            }
            return -1;
        }
    }
    boolean painted;

    static int clock(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return -1; }
    }

    /** 课程块：淡色底 + 左侧 3dp 同色细条，色块之间更容易分清。 */
    android.graphics.drawable.Drawable courseFace(int fill, int ink) {
        android.graphics.drawable.GradientDrawable base = Ui.round(this, fill, 9);
        android.graphics.drawable.GradientDrawable stripe = new android.graphics.drawable.GradientDrawable();
        stripe.setColor(Ui.withAlpha(ink, Ui.dark ? 200 : 170));
        float r = dp(9);
        stripe.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{base, stripe});
        layer.setLayerWidth(1, dp(3));
        layer.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        return layer;
    }
    void details(TimetableLayout.Block block) {
        if (block.lessons.size() == 1) { detail(block.lessons.get(0)); return; }
        String[] names = new String[block.lessons.size()];
        for (int i = 0; i < names.length; i++) names[i] = block.lessons.get(i).name + " · " + table.time(block.lessons.get(i), week);
        alert().setTitle("同一时段的课程").setItems(names, (d, i) -> detail(block.lessons.get(i))).setNegativeButton("关闭", null).show();
    }
    /** 课程详情：和待办详情同一种底部面板。顶部是课程色的时间块，下面逐行列地点、教师、周次。 */
    void detail(Timetable.Lesson l) {
        int color = colorIndex(l.name);
        LinearLayout body = CourseUi.column(this);
        TextView name = text(l.name, 22, Ui.TEXT, false);
        name.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        name.setLineSpacing(dp(4), 1f); name.setTextIsSelectable(true);
        name.setPadding(dp(2), dp(14), 0, 0);
        body.addView(name);

        LinearLayout when = CourseUi.column(this);
        when.setPadding(dp(16), dp(14), dp(16), dp(14));
        when.setBackground(Ui.round(this, fill(color), 16));
        when.addView(text("上课时间", 12, Ui.withAlpha(ink(color), 200), true));
        TextView whenValue = text(table.dayLabel(week, l.weekday) + "  " + table.time(l, week), 17, ink(color), true);
        whenValue.setPadding(0, dp(6), 0, 0); whenValue.setLineSpacing(dp(3), 1f);
        when.addView(whenValue);
        LinearLayout.LayoutParams whenLp = new LinearLayout.LayoutParams(-1, -2); whenLp.topMargin = dp(16);
        body.addView(when, whenLp);

        String[][] fields = {{"地点", l.room.isEmpty() ? "地点待定" : l.room},
                {"教师", l.teachers.isEmpty() ? "待确认" : l.teachers}, {"周次", l.weekText()}};
        CourseUi.gap(body, 6);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) body.addView(Ui.hairline(this));
            LinearLayout row = new LinearLayout(this);
            row.setPadding(dp(2), dp(13), dp(2), dp(13));
            row.addView(text(fields[i][0], 14, Ui.MUTE, false), new LinearLayout.LayoutParams(dp(56), -2));
            TextView v = text(fields[i][1], 14, Ui.TEXT, false);
            v.setLineSpacing(dp(3), 1f); v.setTextIsSelectable(true);
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(row);
        }
        LinearLayout footer = CourseUi.column(this);
        footer.setPadding(0, dp(10), 0, 0);
        android.app.Dialog[] holder = new android.app.Dialog[1];
        footer.addView(CourseUi.action(this, "知道了", false, () -> holder[0].dismiss()), CourseUi.row(dp(48)));
        holder[0] = Ui.sheet(this, body, footer);
    }
    void empty(LinearLayout root) {
        LinearLayout center = CourseUi.column(this); center.setGravity(Gravity.CENTER); center.setPadding(dp(20), 0, dp(20), 0);
        root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout mark = new FrameLayout(this);
        mark.setBackground(Ui.disc(this, Ui.ACCENT_BG));
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(new AppNavigation.TabIcon(this, true, Ui.ACCENT));
        mark.addView(glyph, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        center.addView(mark, new LinearLayout.LayoutParams(dp(84), dp(84)));
        CourseUi.gap(center, 22); center.addView(text("把这一学期，装进口袋", 21, Ui.TEXT, true));
        CourseUi.gap(center, 12); TextView tip = text("选择学校，输入账号密码，导入本学期课表", 13, Ui.SUB, false);
        tip.setGravity(Gravity.CENTER); center.addView(tip); CourseUi.gap(center, 28);
        center.addView(CourseUi.action(this, "一键导入课表", true, () -> importTable(false)), CourseUi.row(dp(50)));
    }
    void chooseWeek() {
        String[] weeks = new String[table.maxWeek]; for (int i = 0; i < weeks.length; i++) weeks[i] = "第 " + (i + 1) + " 周";
        alert().setTitle("选择教学周").setSingleChoiceItems(weeks, week - 1, (d, i) -> { d.dismiss(); changeWeek(i + 1, true); }).setNegativeButton("取消", null).show();
    }
    void changeWeek(int target, boolean animate) {
        if (table == null || changingWeek || target < 1 || target > table.maxWeek || target == week) {
            if (swipeBoard != null) swipeBoard.animate().translationX(0).alpha(1).setDuration(140).start();
            return;
        }
        savedScrollY = timetableScroll == null ? 0 : timetableScroll.getScrollY();
        if (!animate || swipeBoard == null) { week = target; render(); return; }
        changingWeek = true; int direction = target > week ? -1 : 1;
        swipeBoard.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        swipeBoard.animate().translationX(dp(25) * direction).alpha(.45f).setDuration(100).withEndAction(() -> {
            changingWeek = false; if (isFinishing() || isDestroyed()) return;
            week = target; render(); swipeBoard.setAlpha(.5f); swipeBoard.setTranslationX(dp(-12) * direction);
            swipeBoard.animate().translationX(0).alpha(1).setDuration(150).start();
        }).start();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (velocity != null) velocity.recycle(); velocity = VelocityTracker.obtain();
            downX = event.getRawX(); downY = event.getRawY(); swipeAxis = 0;
            int[] start = new int[2]; if (swipeStart != null) swipeStart.getLocationOnScreen(start);
            swipeCandidate = table != null && swipeBoard != null && swipeStart != null && downY >= start[1] && !changingWeek;
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            swipeCandidate = false;
            if (swipeAxis == 1 && swipeBoard != null) swipeBoard.animate().translationX(0).setDuration(140).start();
            swipeAxis = 0;
        }
        if (swipeCandidate && action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
            if (swipeAxis == 0 && Math.max(Math.abs(dx), Math.abs(dy)) > ViewConfiguration.get(this).getScaledTouchSlop()) {
                if (Math.abs(dx) > Math.abs(dy) * 1.35f) {
                    swipeAxis = 1; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel); cancel.recycle();
                } else swipeAxis = 2;
            }
            if (swipeAxis == 1) { swipeBoard.setTranslationX(Math.max(-dp(60), Math.min(dp(60), dx * .24f))); return true; }
        }
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && swipeAxis == 1) {
            float dx = event.getRawX() - downX;
            if (velocity != null) velocity.computeCurrentVelocity(1000);
            float speed = velocity == null ? 0 : velocity.getXVelocity();
            boolean committed = action == MotionEvent.ACTION_UP && (Math.abs(dx) >= dp(42) || (Math.abs(dx) >= dp(20) && Math.abs(speed) > dp(600)));
            swipeCandidate = false; swipeAxis = 0; if (velocity != null) { velocity.recycle(); velocity = null; }
            if (committed) changeWeek(week + (dx < 0 ? 1 : -1), true);
            else swipeBoard.animate().translationX(0).setDuration(140).start();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swipeCandidate = false; swipeAxis = 0; if (velocity != null) { velocity.recycle(); velocity = null; }
        }
        return super.dispatchTouchEvent(event);
    }
    void settings() {
        if (table == null) { importTable(false); return; }
        alert().setTitle("课表设置")
                .setItems(new String[]{"重新同步当前账号", "使用其他账号导入", "校准开学日期", "未排课课程", "删除本机课表、成绩与校园数据"}, (d, which) -> {
                    if (which == 0) importTable(true);
                    if (which == 1) importTable(false);
                    if (which == 2) chooseMonday();
                    if (which == 3) {
                        org.json.JSONArray a = table.json.optJSONArray("unarranged"); StringBuilder s = new StringBuilder();
                        for (int i = 0; a != null && i < a.length(); i++) s.append(a.optString(i)).append("\n");
                        alert().setTitle("未排课课程").setMessage(s.length() == 0 ? "没有读到未排课课程" : s + "\n时间地点以教务通知为准").setPositiveButton("知道了", null).show();
                    }
                    if (which == 4) alert().setTitle("删除本机课表？").setMessage("删除后可重新导入。")
                            .setNegativeButton("取消", null).setPositiveButton("删除", (x, y) -> io.execute(() -> {
                                CampusStore.clearAll(getApplicationContext()); runOnUiThread(() -> { table = null; week = 0; render(); });
                            })).show();
                }).setNegativeButton("关闭", null).show();
    }
    void chooseMonday() {
        Calendar c = Timetable.calendar(table.firstMonday); if (c == null) c = Calendar.getInstance();
        DatePickerDialog picker = new DatePickerDialog(this, (v, y, m, d) -> {
            String chosen = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
            if (!chosen.equals(Timetable.monday(chosen))) { toast("请选择第1周的周一"); return; }
            Timetable updated = table; String previous = updated.firstMonday; updated.firstMonday = chosen;
            io.execute(() -> {
                try { TimetableStore.save(getApplicationContext(), updated); runOnUiThread(this::render); }
                catch (Exception e) { runOnUiThread(() -> { updated.firstMonday = previous; toast("保存失败，请重试"); }); }
            });
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        picker.setTitle("选择第1周周一"); picker.show();
    }
    void toast(String s) { if (!isFinishing() && !isDestroyed()) android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }
}
