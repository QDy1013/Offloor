package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 研修室：按需查询、手动确认后提交或取消。写请求不重试，不后台抢约。 */
public class StudyRoomActivity extends Activity {
    /** 校方每页最多返回这么多条。 */
    private static final int PAGE_SIZE = 40;
    /** 房间卡片上直接摆几个可点时段，其余收进「更多时段」。 */
    private static final int CARD_SLOTS = 3;
    /** 日期快选最多铺几天，再远就交给日期选择器。 */
    private static final int QUICK_DAYS = 7;
    /** 允许选择的日期范围（天）。具体能不能约由校方规则再判一次。 */
    private static final int MAX_DAYS_AHEAD = 30;

    private StudyRoomSession session;
    private CampusData campus;
    private Timetable timetable;
    private boolean ready, busy, mine, active, reconciled;
    private int page = 1, epoch, count = -1;
    private long updatedAt;
    private String date = CampusClassroomQuery.today(System.currentTimeMillis()), error = "", message = "";
    private JSONArray rows;
    /** 本人已有预约，用来提示和自己的安排撞车；本页只读一次。 */
    private JSONArray myBookings;
    /** 校方受理成功后才写入首页的提醒内容。 */
    private JSONObject reminderRow;
    private String reminderTheme = "";
    private int reminderStart, reminderEnd;
    private LinearLayout body;
    private Dialog form;
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    private interface Result { void accept(JSONObject result); }

    private android.content.SharedPreferences writeState() { return getSharedPreferences("study_room_writes", MODE_PRIVATE); }
    private boolean pendingWrite() { return campus != null && writeState().getBoolean(campus.owner, false); }
    private boolean markWrite(boolean pending) { return campus!=null && writeState().edit().putBoolean(campus.owner,pending).commit(); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Ui.apply(this);
        if (state != null) {
            date = state.getString("date", date);
            mine = state.getBoolean("mine");
            page = Math.max(1, state.getInt("page", 1));
        }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putString("date", date); state.putBoolean("mine", mine); state.putInt("page", page);
        super.onSaveInstanceState(state);
    }

    @Override public void onStart() {
        super.onStart(); active=true;
        // 页面可能在后台放了一夜，恢复时把日期拉回今天，别停在一个约不到的昨天。
        String today = CampusClassroomQuery.today(System.currentTimeMillis());
        if (!StudyRoomPlan.validDate(date) || date.compareTo(today) < 0) date = today;
        render(); int token=++epoch;
        io.execute(() -> {
            CampusData loaded = null; Timetable table = null;
            try { loaded = CampusStore.load(getApplicationContext()); } catch (Exception ignored) { }
            // 课表只用来提示“会不会撞课”，读不到也不影响预约。
            try { table = TimetableStore.load(getApplicationContext()); } catch (Exception ignored) { }
            CampusData value = loaded; Timetable schedule = table;
            runOnUiThread(() -> {
                if (!active || token != epoch) return;
                campus = value; timetable = schedule;
                if (pendingWrite()) { mine = true; message = "上次请求结果尚未核对，请先查询我的预约，勿重复操作。"; }
                connect();
            });
        });
    }

    @Override public void onStop() {
        active=false; ++epoch; ready=false; busy=false; rows=null; reconciled=false;
        if (form != null) { form.dismiss(); form = null; }
        if (session != null) { session.stop(); session = null; }
        super.onStop();
    }

    @Override public void onDestroy() { io.shutdownNow(); super.onDestroy(); }

    // ---------- 小控件 ----------

    private int dp(int n) { return Ui.dp(this, n); }
    private TextView text(String value, int size, int color, boolean bold) { return CourseUi.text(this, value, size, color, bold); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    private LinearLayout card() {
        LinearLayout c = CourseUi.column(this);
        c.setPadding(dp(18), dp(16), dp(18), dp(18)); Ui.cardSurface(c, 18);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); body.addView(c, p);
        return c;
    }

    /** 面板内的浅色分组，沿用应用的深浅色主题。 */
    private LinearLayout formSection(LinearLayout parent, String label) {
        LinearLayout section = CourseUi.column(this);
        section.setPadding(dp(16), dp(14), dp(16), dp(16));
        section.setBackground(Ui.roundStroke(this, Ui.CARD, Ui.withAlpha(Ui.SUB, 24), 18, 1f));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(14);
        parent.addView(section, p);
        section.addView(text(label, 14, Ui.TEXT, true));
        CourseUi.gap(section, 8);
        return section;
    }

    private TextView action(LinearLayout target, String label, Runnable run) {
        TextView button = CourseUi.action(this, label, false, run); button.setEnabled(!busy);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.topMargin = dp(8);
        target.addView(button, p); return button;
    }

    /** 胶囊按钮：选中是强调色底，未选中是浅填充。 */
    private TextView chip(LinearLayout parent, String label, boolean selected, Runnable run) {
        TextView view = text(label, 14, selected ? Ui.ON_ACCENT : Ui.TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(9), dp(14), dp(9));
        view.setMinHeight(dp(48));
        view.setBackground(Ui.ripple(this, Ui.round(this, selected ? Ui.ACCENT : Ui.FILL, 99), Ui.press(), 99));
        view.setContentDescription(label + (selected ? "，已选" : ""));
        view.setOnClickListener(v -> run.run());
        return view;
    }

    /** 轨道里的分段按钮：选中是强调色，未选中是白卡，压在灰底上分得清。 */
    private TextView trackChip(LinearLayout parent, String label, boolean selected, Runnable run) {
        TextView view = chip(parent, label, selected, run);
        if (!selected) view.setBackground(Ui.ripple(this, Ui.round(this, Ui.CARD, 99), Ui.press(), 99));
        return view;
    }

    private void slotChip(LinearLayout parent, String label, Runnable run) {
        TextView view = text(label, 13, Ui.ACCENT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(Ui.ripple(this, Ui.roundStroke(this, Ui.ACCENT_BG, Ui.withAlpha(Ui.ACCENT, 40), 99, 1f), Ui.press(), 99));
        view.setMinHeight(dp(48));
        view.setContentDescription("更多时段".equals(label) ? "查看并选择更多时段" : "预约 " + label);
        view.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8);
        parent.addView(view, p);
    }

    /** 一行可以横着划的胶囊；返回里面那层容器。 */
    private LinearLayout horizontalScroll(LinearLayout parent, int bottomMargin) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false);
        LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(line);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(bottomMargin);
        parent.addView(scroll, p);
        return line;
    }

    private TextView tag(String label, int color, int background) {
        return Ui.tag(this, label, color, background);
    }

    // ---------- 主界面 ----------

    private void render() {
        if (!active || isFinishing() || isDestroyed()) return;
        LinearLayout root = CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this, root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.addView(CourseUi.icon(this, "prev", "返回校园", this::finish));
        header.addView(text("研修室", 24, Ui.TEXT, true));
        if (ready) {
            View spacer = new View(this); header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            header.addView(CourseUi.icon(this, "sync", "刷新校方数据", () -> { if (!busy) { rows = null; query(); } }));
        }
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        body = CourseUi.column(this); body.setPadding(dp(16), dp(4), dp(16), dp(28));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        statusCard();
        if (!ready) return;
        modeRow();
        if (mine) mineHint(); else dateStrip();
        list();
        paging();
    }

    /** 顶部状态卡：连接状态、错误、未核对保护，都集中在这一张卡里。 */
    private void statusCard() {
        LinearLayout intro = card();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("西工大图书馆研修室", 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(tag(busy ? "连接中" : ready ? "已连接" : "未连接",
                busy ? Ui.AMBER_TX : ready ? Ui.GREEN : Ui.SUB,
                busy ? Ui.AMBER_BG : ready ? Ui.GREEN_BG : Ui.FILL));
        intro.addView(head);
        CourseUi.gap(intro, 8);
        intro.addView(text(ready ? "借用已连接的校园账号 · 可能需要校园网 · 只在手动确认后提交或取消"
                : "先连接校园账号；校方要求安全验证时会打开官方页面。", 12, Ui.SUB, false));
        if (ready && updatedAt > 0) {
            CourseUi.gap(intro, 6);
            intro.addView(text("数据更新于 " + clockOf(updatedAt), 12, Ui.MUTE, false));
        }
        if (busy) {
            CourseUi.gap(intro, 10);
            intro.addView(text("正在读取校方数据…", 13, Ui.ACCENT, false));
        }
        if (!error.isEmpty()) {
            CourseUi.gap(intro, 10);
            TextView problem = text(error, 13, Ui.AMBER_TX, false); problem.setLineSpacing(dp(3), 1);
            intro.addView(problem);
        }
        if (!message.isEmpty()) {
            CourseUi.gap(intro, 10);
            TextView note = text(message, 13, Ui.SUB, false); note.setLineSpacing(dp(3), 1);
            intro.addView(note);
        }
        if (!ready) {
            action(intro, busy ? "正在连接…" : "重新连接", this::connect);
            if (session != null) action(intro, "打开校方页面完成验证", () -> session.openOfficial(this::reconnectFromOfficial));
            action(intro, "连接或更换校园账号",
                    () -> startActivity(new Intent(this, NwpuImportActivity.class).putExtra("school_id", "nwpu")));
            return;
        }
        if (pendingWrite()) {
            CourseUi.gap(intro, 10);
            TextView lock = text("上次预约或取消的结果尚未核对。先查「我的预约」，确认校方状态后再操作。", 13, Ui.AMBER_TX, false);
            lock.setLineSpacing(dp(3), 1); intro.addView(lock);
            if (mine && reconciled) action(intro, "我已核对，解除重复操作保护", this::release);
            else action(intro, "去核对我的预约", () -> { if (busy) return; mine = true; page = 1; rows = null; query(); });
        }
    }

    private void release() {
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("已核对校方预约状态？")
                .setMessage("请确认上次提交或取消的结果；有疑问请稍后刷新，不要重复提交。")
                .setNegativeButton("继续核对", null).setPositiveButton("已核对", (d, w) -> {
                    if (!active || busy) return;
                    if (markWrite(false)) { message = "已核对上次操作结果"; render(); }
                    else toast("保护状态未能保存，请稍后重试");
                }).show();
    }

    /** 空闲房间 / 我的预约 两段切换。 */
    private void modeRow() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Ui.round(this, Ui.FILL, 99)); row.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); body.addView(row, p);
        row.addView(trackChip(row, "看空闲房间", !mine, () -> switchMode(false)), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(trackChip(row, "我的预约", mine, () -> switchMode(true)), new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void switchMode(boolean toMine) {
        if (busy || mine == toMine) return;
        mine = toMine; page = 1; rows = null; count = -1; error = ""; message = ""; query();
    }

    /** 快选日期条：今天到第 7 天，再远交给日期选择器。 */
    private void dateStrip() {
        LinearLayout line = horizontalScroll(body, 12);
        String today = CampusClassroomQuery.today(System.currentTimeMillis());
        boolean listed = false;
        for (int i = 0; i < QUICK_DAYS; i++) {
            String day = StudyRoomPlan.addDays(today, i);
            if (day.equals(date)) listed = true;
            addDayChip(line, day, today);
        }
        // 选的是快选条以外的日期时，把这一天补在最前面，免得看不出来在看哪天。
        if (!listed) {
            TextView extra = chip(line, date.substring(5).replace('-', '/') + " " + StudyRoomPlan.weekday(date), true, () -> { });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8);
            line.addView(extra, 0, p);
        }
        TextView more = chip(line, "更多日期", false, this::chooseDate);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2); mp.leftMargin = dp(4);
        line.addView(more, mp);
    }

    private void addDayChip(LinearLayout line, String day, String today) {
        String label = (day.equals(today) ? "今天" : StudyRoomPlan.relative(day, System.currentTimeMillis()))
                + " " + day.substring(5).replace('-', '/');
        TextView view = chip(line, label, day.equals(date), () -> {
            if (busy || day.equals(date)) return;
            date = day; page = 1; rows = null; count = -1; error = ""; query();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8);
        line.addView(view, p);
    }

    private void mineHint() {
        LinearLayout hint = card();
        hint.addView(text("最近 7 天到未来 30 天，包含本人参与的研修室预约", 12, Ui.SUB, false));
        CourseUi.gap(hint, 4);
        hint.addView(text("只有本人发起、且校方状态允许的预约才能在这里取消", 12, Ui.MUTE, false));
    }

    private void list() {
        if (rows == null) {
            if (!busy) body.addView(text("暂时没有数据，点右上角刷新重试", 13, Ui.SUB, false));
            return;
        }
        if (rows.length() == 0) { emptyCard(); return; }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            if (mine) bookingCard(row); else roomCard(row);
        }
    }

    private void emptyCard() {
        LinearLayout c = card();
        if (mine) {
            c.addView(text("这段时间还没有你的研修室预约", 15, Ui.TEXT, true));
            CourseUi.gap(c, 6);
            c.addView(text("本页为空只代表这一页；翻页或刷新后再判断。", 12, Ui.SUB, false));
            action(c, "去找一间空房间", () -> switchMode(false));
        } else {
            c.addView(text("这一天没有查到可显示的研修室", 15, Ui.TEXT, true));
            CourseUi.gap(c, 6);
            c.addView(text("本页为空不代表其他日期也满，可以换个日期或刷新再试。", 12, Ui.SUB, false));
            action(c, "换个日期", this::chooseDate);
        }
    }

    // ---------- 房间卡片 ----------

    private void roomCard(JSONObject row) {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(row.optString("name", "研修室"), 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        boolean available = row.optBoolean("available");
        head.addView(tag(available ? "可预约" : "暂不可约", available ? Ui.GREEN : Ui.SUB,
                available ? Ui.GREEN_BG : Ui.FILL));
        c.addView(head);
        CourseUi.gap(c, 6);
        String where = join(row.optString("location"), row.optString("kind"));
        if (!where.isEmpty()) c.addView(text(where, 12, Ui.SUB, false));

        JSONObject rule = row.optJSONObject("rules");
        StringBuilder meta = new StringBuilder();
        if (!row.isNull("min") || !row.isNull("max"))
            meta.append("人数 ").append(row.isNull("min") ? "?" : row.optString("min"))
                    .append("–").append(row.isNull("max") ? "?" : row.optString("max")).append(" 人");
        if (rule != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("时长 ").append(rule.optString("minMinutes")).append("–").append(rule.optString("maxMinutes"))
                    .append(" 分钟 · 间隔 ").append(rule.optString("interval")).append(" 分钟");
        }
        if (meta.length() > 0) { CourseUi.gap(c, 4); c.addView(text(meta.toString(), 12, Ui.SUB, false)); }

        JSONArray periods = row.optJSONArray("periods");
        StringBuilder open = new StringBuilder();
        for (int i = 0; periods != null && i < periods.length(); i++) {
            JSONObject p = periods.optJSONObject(i); if (p == null) continue;
            if (open.length() > 0) open.append("、");
            open.append(p.optString("start")).append("–").append(p.optString("end"));
        }
        if (open.length() > 0) { CourseUi.gap(c, 2); c.addView(text("开放 " + open, 12, Ui.MUTE, false)); }

        // 占用没读全时校方数据里是 null，这里必须先判空再取长度，否则整页直接崩掉。
        JSONArray occupied = row.optJSONArray("occupied");
        if (occupied == null) {
            if (available) c.addView(text("占用信息尚未读全，暂不能预约", 12, Ui.AMBER_TX, false));
        } else if (occupied.length() > 0) {
            CourseUi.gap(c, 2);
            c.addView(text("已占用 " + occupied.length() + " 段 · " + occupiedText(occupied), 12, Ui.MUTE, false));
        }

        if (!available) {
            String reason = row.optString("reason", "");
            CourseUi.gap(c, 8);
            c.addView(text(reason.isEmpty() ? "该房间当前不可预约，可能是规则未读全或仅可查看" : reason, 12, Ui.AMBER_TX, false));
            return;
        }

        StudyRoomPlan.Room plan = plan(row);
        List<StudyRoomPlan.Busy> busySlots = occupied(row);
        List<StudyRoomPlan.Slot> slots = plan == null ? new ArrayList<>()
                : StudyRoomPlan.slots(plan, date, busySlots, System.currentTimeMillis(), 0);
        CourseUi.gap(c, 10);
        if (slots.isEmpty()) {
            c.addView(text("这一天已经没有可约的时段，换个日期试试", 13, Ui.SUB, false));
            return;
        }
        LinearLayout line = horizontalScroll(c, 0);
        for (int i = 0; i < Math.min(CARD_SLOTS, slots.size()); i++) {
            StudyRoomPlan.Slot slot = slots.get(i);
            slotChip(line, slot.label(), () -> booking(row, slot.start, slot.end));
        }
        slotChip(line, "更多时段", () -> booking(row, -1, -1));
    }

    private String occupiedText(JSONArray occupied) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < occupied.length() && i < 2; i++) {
            JSONObject p = occupied.optJSONObject(i); if (p == null) continue;
            if (text.length() > 0) text.append("、");
            text.append(hhmm(p.optString("start"))).append("–").append(hhmm(p.optString("end")));
        }
        if (occupied.length() > 2) text.append(" 等");
        return text.toString();
    }

    // ---------- 我的预约卡片 ----------

    private void bookingCard(JSONObject row) {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(row.optString("name", "研修室"), 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        int state = row.optInt("state", -1);
        String status = row.optString("status");
        head.addView(tag(status.isEmpty() ? "状态待确认" : status, stateColor(state), stateBg(state)));
        c.addView(head);
        String begin = row.optString("begin"), end = row.optString("end");
        long beginsAt = StudyRoomPlan.stamp(begin);
        String day = beginsAt > 0 ? StudyRoomPlan.day(beginsAt) : (begin.length() >= 10 ? begin.substring(0, 10) : date);
        CourseUi.gap(c, 8);
        c.addView(text(StudyRoomPlan.relative(day, System.currentTimeMillis()) + " " + hhmm(begin) + "–" + hhmm(end), 14, Ui.TEXT, false));
        String where = row.optString("location");
        if (!where.isEmpty()) c.addView(text(where, 12, Ui.SUB, false));
        String title = row.optString("title");
        if (!title.isEmpty()) c.addView(text(title, 12, Ui.SUB, false));
        String hint = countdown(begin, end);
        if (!hint.isEmpty()) { CourseUi.gap(c, 6); c.addView(text(hint, 12, state == 4 ? Ui.GREEN : Ui.MUTE, false)); }
        CourseUi.gap(c, 10);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        boolean canCancel = row.optBoolean("canCancel");
        if (canCancel) {
            TextView cancel = chip(actions, "取消这次预约", false, () -> confirmCancel(row));
            actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView copy = chip(actions, "复制预约信息", false, () -> copy(row, day, begin, end));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(canCancel ? 0 : -1, -2, canCancel ? 1 : 0);
        if (canCancel) cp.leftMargin = dp(8);
        actions.addView(copy, cp);
        c.addView(actions);
    }

    private void copy(JSONObject row, String day, String begin, String end) {
        String value = row.optString("name", "研修室") + "\n" + day + " " + hhmm(begin) + "–" + hhmm(end)
                + (row.optString("location").isEmpty() ? "" : "\n" + row.optString("location"))
                + (row.optString("title").isEmpty() ? "" : "\n主题：" + row.optString("title"));
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("研修室预约", value));
            toast("预约信息已复制");
        } catch (RuntimeException e) { toast("复制失败，请手动记录"); }
    }

    private int stateColor(int state) {
        if (state == 4) return Ui.GREEN;
        if (state == 128) return Ui.SUB;
        if (state == 512) return Ui.RED;
        if (state == 8192) return Ui.AMBER_TX;
        return Ui.ACCENT;
    }

    private int stateBg(int state) {
        if (state == 4) return Ui.GREEN_BG;
        if (state == 128) return Ui.FILL;
        if (state == 512) return Ui.RED_BG;
        if (state == 8192) return Ui.AMBER_BG;
        return Ui.ACCENT_BG;
    }

    /** 一句“还有多久”，只在还没开始或正在使用时给。 */
    private String countdown(String begin, String end) {
        long start = StudyRoomPlan.stamp(begin), finish = StudyRoomPlan.stamp(end), now = System.currentTimeMillis();
        if (start <= 0 || finish <= 0) return "";
        if (now >= finish) return "已结束，可在校方系统查看记录";
        if (now >= start) return "正在使用，剩 " + Math.max(1, Math.round((finish - now) / 60000.0)) + " 分钟";
        long minutes = Math.max(1, Math.round((start - now) / 60000.0));
        if (minutes < 60) return "还有 " + minutes + " 分钟开始";
        long hours = minutes / 60;
        return hours < 24 ? "还有 " + hours + " 小时开始" : "还有 " + (hours / 24) + " 天开始";
    }

    // ---------- 分页 ----------

    private void paging() {
        if (rows == null || (page == 1 && rows.length() == 0)) return;
        LinearLayout card = card();
        String summary = count > 0 ? "第 " + page + " 页 · 校方共 " + count + " 条" : "第 " + page + " 页";
        card.addView(text(summary + " · 每页最多 " + PAGE_SIZE + " 条", 12, Ui.SUB, false));
        if (!mine && rows.length() < PAGE_SIZE) {
            CourseUi.gap(card, 4);
            card.addView(text("这一页没满，通常说明已经是最后一页", 12, Ui.MUTE, false));
        }
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        if (page > 1) {
            TextView prev = chip(actions, "上一页", false, () -> { if (busy) return; --page; rows = null; query(); });
            actions.addView(prev, new LinearLayout.LayoutParams(0, -2, 1));
        }
        // 校方没给总条数时，只有这一页真的排满才认为后面还有，避免一直点“下一页”。
        boolean more = rows.length() >= PAGE_SIZE && (count <= 0 || page * PAGE_SIZE < count);
        if (more) {
            TextView next = chip(actions, "下一页", false, () -> { if (busy) return; ++page; rows = null; query(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(page > 1 ? 0 : -1, -2, page > 1 ? 1 : 0);
            if (page > 1) p.leftMargin = dp(8);
            actions.addView(next, p);
        }
        if (actions.getChildCount() > 0) { CourseUi.gap(card, 10); card.addView(actions); }
    }

    // ---------- 会话 ----------

    private void connect() {
        if (busy || !active) return;
        if (session != null) session.stop();
        ready = false; rows = null; reconciled = false; error = ""; busy = true; int token = ++epoch;
        session = new StudyRoomSession(this); render();
        session.start(campus, (result, problem) -> {
            if (!active || token != epoch) return;
            busy = false; error = problem; ready = problem.isEmpty(); render(); if (ready) query();
        });
    }

    private void reconnectFromOfficial() {
        if (!active) return;
        if (session == null) { connect(); return; }
        ready = false; rows = null; reconciled = false; error = ""; busy = true; int token = ++epoch;
        render();
        session.reconnect(campus, (result, problem) -> {
            if (!active || token != epoch) return;
            busy = false; error = problem; ready = problem.isEmpty(); render(); if (ready) query();
        });
    }

    private boolean call(String method, JSONObject args, Result done) {
        return call(method, args, done, null);
    }

    /** 返回是否真的发出去了；写请求据此判断要不要回滚“未核对”标记。 */
    private boolean call(String method, JSONObject args, Result done, Runnable failed) {
        if (busy || !ready || session == null) return false;
        busy = true; error = ""; render(); int token = epoch;
        session.run(method, args, (result, problem) -> {
            if (!active || token != epoch) return;
            busy = false; ready = session != null && session.isReady();
            if (!problem.isEmpty()) {
                error = problem; render(); toast(problem);
                if (failed != null) failed.run();
                return;
            }
            done.accept(result); render();
        });
        return true;
    }

    private static JSONObject args(Object... pairs) {
        JSONObject out = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return out;
    }

    private void query() {
        call(mine ? "mine" : "query", args("date", date, "page", page), result -> {
            rows = result.optJSONArray(mine ? "bookings" : "rooms");
            if (mine && rows != null) reconciled = true;
            count = result.has("count") ? result.optInt("count", -1) : -1;
            updatedAt = System.currentTimeMillis();
        });
    }

    private void chooseDate() {
        long now = System.currentTimeMillis();
        String[] parts = date.split("-");
        DatePickerDialog dialog = new DatePickerDialog(this, (view, y, m, d) -> {
            date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
            page = 1; rows = null; count = -1; error = ""; query();
        }, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
        try {
            // 只能选今天到未来 30 天；具体能不能约仍由校方规则判断。
            dialog.getDatePicker().setMinDate(StudyRoomPlan.at(StudyRoomPlan.day(now), 0));
            dialog.getDatePicker().setMaxDate(StudyRoomPlan.at(StudyRoomPlan.addDays(StudyRoomPlan.day(now), MAX_DAYS_AHEAD), 0));
        } catch (RuntimeException ignored) { }
        dialog.show();
    }

    // ---------- 从校方数据还原成计划结构 ----------

    private StudyRoomPlan.Room plan(JSONObject row) {
        JSONObject rule = row.optJSONObject("rules");
        if (rule == null) return null;
        StudyRoomPlan.Room plan = new StudyRoomPlan.Room(row.optInt("min", 0), row.optInt("max", 0),
                (int) rule.optDouble("minMinutes", 0), (int) rule.optDouble("maxMinutes", 0),
                (int) rule.optDouble("interval", 0));
        JSONArray periods = row.optJSONArray("periods");
        for (int i = 0; periods != null && i < periods.length(); i++) {
            JSONObject p = periods.optJSONObject(i); if (p == null) continue;
            plan.period(StudyRoomPlan.minutes(p.optString("start")), StudyRoomPlan.minutes(p.optString("end")));
        }
        return plan.complete() && !plan.periods.isEmpty() ? plan : null;
    }

    private List<StudyRoomPlan.Busy> occupied(JSONObject row) {
        List<StudyRoomPlan.Busy> out = new ArrayList<>();
        JSONArray items = row.optJSONArray("occupied");
        JSONObject rule = row.optJSONObject("rules");
        int fallback = rule == null ? 0 : (int) rule.optDouble("freezing", 0);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject p = items.optJSONObject(i); if (p == null) continue;
            long start = StudyRoomPlan.stamp(p.optString("start")), end = StudyRoomPlan.stamp(p.optString("end"));
            if (start <= 0 || end <= start) continue;
            out.add(new StudyRoomPlan.Busy(start, end, "已占用", (int) p.optDouble("buffer", fallback)));
        }
        return out;
    }

    /** 当天的课，用已导入的课表算；没导入就是空的。 */
    private List<StudyRoomPlan.Busy> classesOn(String day) {
        List<StudyRoomPlan.Busy> out = new ArrayList<>();
        Timetable table = timetable;
        if (table == null) return out;
        java.util.Calendar first = Timetable.calendar(table.firstMonday), target = Timetable.calendar(day);
        if (first == null || target == null) return out;
        int week = (int) Math.floorDiv(target.getTimeInMillis() - first.getTimeInMillis(), 604800000L) + 1;
        int weekday = ((target.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1;
        for (Timetable.Lesson lesson : table.lessons) {
            if (lesson.weekday != weekday || !lesson.weeks.contains(week)) continue;
            String[] span = table.clock(lesson, week);
            if (span.length < 2) continue;
            long start = StudyRoomPlan.at(day, span[0]), end = StudyRoomPlan.at(day, span[1]);
            if (start < 0 || end <= start) continue;
            out.add(new StudyRoomPlan.Busy(start, end, lesson.name));
        }
        return out;
    }

    /** 本人当天已有的预约（含被拉进同行人的）。 */
    private List<StudyRoomPlan.Busy> reservedOn(String day) {
        List<StudyRoomPlan.Busy> out = new ArrayList<>();
        for (int i = 0; myBookings != null && i < myBookings.length(); i++) {
            JSONObject row = myBookings.optJSONObject(i); if (row == null) continue;
            long start = StudyRoomPlan.stamp(row.optString("begin")), end = StudyRoomPlan.stamp(row.optString("end"));
            if (start <= 0 || end <= start || !day.equals(StudyRoomPlan.day(start))) continue;
            out.add(new StudyRoomPlan.Busy(start, end, "已约 " + row.optString("name", "研修室")));
        }
        return out;
    }

    // ---------- 预约面板 ----------

    private void booking(JSONObject row, int start, int end) {
        if (busy) { toast("上一请求尚未完成，请稍候"); return; }
        if (myBookings != null) { prepare(row, start, end); return; }
        // 先读一次本人预约，用来提示“和自己的安排撞车”；读不到也照样可以约。
        boolean sent = call("mine", args("page", 1), result -> {
            myBookings = result.optJSONArray("bookings");
            if (myBookings == null) myBookings = new JSONArray();
            prepare(row, start, end);
        }, () -> { myBookings = new JSONArray(); prepare(row, start, end); });
        if (!sent) { myBookings = new JSONArray(); prepare(row, start, end); }
    }

    /** 每次打开面板都重新读一次验证码配置和须知，避免拿着过期的图片提交。 */
    private void prepare(JSONObject row, int start, int end) {
        if (busy) { toast("上一请求尚未完成，请稍候"); return; }
        boolean sent = call("captcha", new JSONObject(), config -> {
            if ("2".equals(config.optString("mode"))) {
                message = config.optString("message", "校方要求滑块验证，请在官方页面完成");
                toast(message);
                if (session != null) session.openOfficial(this::connect);
                return;
            }
            if (config.optString("notice").trim().isEmpty()) { toast("还没读到预约须知，请稍后重试"); return; }
            showBooking(row, start, end, config);
        });
        if (!sent) toast("会话还没就绪，请稍后重试");
    }

    private void showBooking(JSONObject row, int presetStart, int presetEnd, final JSONObject config) {
        if (!active) return;
        final StudyRoomPlan.Room plan = plan(row);
        if (plan == null) { toast("该房间的规则还没读全，请刷新后再试"); return; }
        final List<StudyRoomPlan.Busy> busySlots = occupied(row);
        final List<StudyRoomPlan.Slot> slots = StudyRoomPlan.slots(plan, date, busySlots, System.currentTimeMillis(), 0);
        if (slots.isEmpty()) { toast("这一天已经没有可约的时段"); return; }

        final int[] chosen = new int[]{presetStart, presetEnd};
        if (chosen[0] < 0 || !StudyRoomPlan.inside(plan, chosen[0], Math.max(chosen[1], chosen[0] + plan.minMinutes))) {
            chosen[0] = slots.get(0).start; chosen[1] = slots.get(0).end;
        } else if (chosen[1] <= chosen[0]) chosen[1] = slots.get(0).end;

        final List<StudyRoomPlan.Busy> clashPool = new ArrayList<>(classesOn(date));
        clashPool.addAll(reservedOn(date));
        final String mode = config.optString("mode"), image = config.optString("image");

        LinearLayout content = CourseUi.column(this);
        content.addView(tag("研修室预约", Ui.ACCENT, Ui.ACCENT_BG));
        CourseUi.gap(content, 10);
        content.addView(text(row.optString("name", "研修室"), 24, Ui.TEXT, true));
        CourseUi.gap(content, 4);
        content.addView(text(date + " " + StudyRoomPlan.weekday(date)
                + (row.optString("location").isEmpty() ? "" : " · " + row.optString("location")), 13, Ui.SUB, false));

        // ---- 时段 ----
        LinearLayout schedule = formSection(content, "01  选择时段");
        LinearLayout times = new LinearLayout(this); times.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams timeRow = new LinearLayout.LayoutParams(-1, -2); timeRow.topMargin = dp(6);
        schedule.addView(times, timeRow);
        final TextView startView = chip(times, "", true, () -> { });
        final TextView endView = chip(times, "", true, () -> { });
        times.addView(startView, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(0, -2, 1); endParams.leftMargin = dp(8);
        times.addView(endView, endParams);
        final TextView summary = text("", 12, Ui.SUB, false);
        summary.setPadding(0, dp(8), 0, 0); summary.setLineSpacing(dp(3), 1);
        schedule.addView(summary);
        final TextView clash = text("", 12, Ui.AMBER_TX, false);
        clash.setPadding(0, dp(6), 0, 0); clash.setLineSpacing(dp(3), 1);
        schedule.addView(clash);

        // ---- 参与者 ----
        LinearLayout participants = formSection(content, "02  参与者");
        LinearLayout peopleHead = new LinearLayout(this); peopleHead.setGravity(Gravity.CENTER_VERTICAL);
        peopleHead.addView(text("本人已计入人数", 12, Ui.SUB, false), new LinearLayout.LayoutParams(0, -2, 1));
        final TextView peopleCount = text("", 12, Ui.MUTE, false);
        peopleHead.addView(peopleCount);
        participants.addView(peopleHead);
        final LinearLayout people = CourseUi.column(this); participants.addView(people);
        final LinkedHashMap<String, JSONObject> selected = new LinkedHashMap<>();
        final String[] problem = {""};
        final Runnable[] repaint = new Runnable[1];

        // ---- 主题与备注 ----
        LinearLayout details = formSection(content, "03  研修内容");
        final EditText title = Ui.field(details, "研修主题", "如：小组讨论、论文写作", null, InputType.TYPE_CLASS_TEXT);
        CourseUi.gap(details, 10);
        details.addView(text("备注（可选）", 12, Ui.SUB, false));
        final EditText memo = new EditText(this);
        memo.setHint("需要的设备、联系人等"); memo.setHintTextColor(Ui.MUTE); memo.setTextColor(Ui.TEXT); memo.setTextSize(15);
        memo.setBackground(Ui.round(this, Ui.FILL, 14));
        memo.setPadding(dp(14), dp(12), dp(14), dp(12)); memo.setMinLines(2); memo.setMaxLines(4);
        memo.setGravity(Gravity.TOP | Gravity.START);
        memo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams memoParams = new LinearLayout.LayoutParams(-1, -2); memoParams.topMargin = dp(6);
        details.addView(memo, memoParams);

        // ---- 验证码 ----
        final EditText[] captcha = {null};
        if ("1".equals(mode)) {
            android.graphics.Bitmap bitmap = decode(image);
            if (bitmap == null) { toast("验证码图片无法读取，请关闭后重新选择房间"); return; }
            ImageView view = new ImageView(this); view.setImageBitmap(bitmap);
            view.setAdjustViewBounds(true); view.setContentDescription("校方图片验证码");
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-2, -2); ip.topMargin = dp(12);
            content.addView(view, ip);
            captcha[0] = Ui.field(content, "图片验证码", "看不清就关掉重选房间换一张", null, InputType.TYPE_CLASS_TEXT);
        } else if ("2".equals(mode)) {
            CourseUi.gap(content, 12);
            content.addView(text("校方要求滑块验证，需要先在官方页面完成。", 12, Ui.AMBER_TX, false));
        }

        // ---- 须知与提醒 ----
        LinearLayout consent = formSection(content, "04  须知与提醒");
        final CheckBox agreement = new CheckBox(this);
        agreement.setText("我已阅读预约须知，并确认参与者真实有效");
        agreement.setTextColor(Ui.TEXT); agreement.setTextSize(13);
        consent.addView(agreement);
        final CheckBox remind = new CheckBox(this);
        remind.setText("预约成功后，在首页加一条开始前 2 小时的提醒");
        remind.setTextColor(Ui.SUB); remind.setTextSize(13); remind.setChecked(true);
        consent.addView(remind);
        TextView notice = text(config.optString("notice"), 12, Ui.MUTE, false);
        notice.setLineSpacing(dp(3), 1); notice.setPadding(0, dp(8), 0, 0);
        consent.addView(text("校方预约须知 · 点按正文展开或收起", 12, Ui.SUB, true));
        notice.setMaxLines(4); notice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        notice.setOnClickListener(v -> {
            boolean expanded = notice.getMaxLines() != Integer.MAX_VALUE;
            notice.setMaxLines(expanded ? Integer.MAX_VALUE : 4);
            notice.setEllipsize(expanded ? null : android.text.TextUtils.TruncateAt.END);
        });
        consent.addView(notice);

        // ---- 实时校验 ----
        repaint[0] = () -> {
            startView.setText("开始  " + StudyRoomPlan.clock(chosen[0]));
            endView.setText("结束  " + StudyRoomPlan.clock(chosen[1]));
            int peopleAll = selected.size() + 1;
            peopleCount.setText("含本人共 " + peopleAll + " 人");
            problem[0] = check(plan, busySlots, chosen[0], chosen[1], peopleAll);
            summary.setText(problem[0].isEmpty()
                    ? "共 " + (chosen[1] - chosen[0]) + " 分钟 · 该房间需 " + plan.minUser + "–" + plan.maxUser
                    + " 人 · 按 " + plan.interval + " 分钟间隔"
                    : problem[0]);
            summary.setTextColor(problem[0].isEmpty() ? Ui.SUB : Ui.RED);
            List<StudyRoomPlan.Busy> clashes = StudyRoomPlan.overlaps(clashPool,
                    StudyRoomPlan.at(date, chosen[0]), StudyRoomPlan.at(date, chosen[1]));
            clash.setText(clashes.isEmpty() ? "" : "时间冲突：" + StudyRoomPlan.describe(clashes, date) + "，请确认是否仍要预约");
        };

        final Runnable[] showMembers = new Runnable[1];
        showMembers[0] = () -> {
            people.removeAllViews();
            for (final JSONObject member : new ArrayList<>(selected.values())) {
                LinearLayout pill = new LinearLayout(this); pill.setGravity(Gravity.CENTER_VERTICAL);
                pill.setBackground(Ui.round(this, Ui.FILL, 99)); pill.setPadding(dp(14), dp(8), dp(14), dp(8));
                pill.addView(text(member.optString("name") + " · " + member.optString("account"), 13, Ui.TEXT, false),
                        new LinearLayout.LayoutParams(0, -2, 1));
                TextView remove = text("移除", 13, Ui.ACCENT, true);
                remove.setPadding(dp(10), dp(2), dp(2), dp(2));
                remove.setContentDescription("移除同行人 " + member.optString("name"));
                remove.setOnClickListener(v -> {
                    selected.remove(member.optString("id"));
                    showMembers[0].run();
                });
                pill.addView(remove);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8);
                people.addView(pill, p);
            }
            if (selected.isEmpty()) {
                TextView none = text("还没有添加同行人；本人已计入人数", 12, Ui.MUTE, false);
                none.setPadding(0, dp(8), 0, 0); people.addView(none);
            }
            repaint[0].run();
        };
        repaint[0].run();
        showMembers[0].run();
        action(participants, "＋ 添加同行人（校方通讯录）", () -> searchMember(selected, showMembers[0]));

        startView.setOnClickListener(v -> pickStart(plan, busySlots, slots, chosen, repaint[0]));
        endView.setOnClickListener(v -> pickEnd(plan, busySlots, chosen, repaint[0]));

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(10), 0, 0);
        TextView back = CourseUi.action(this, "返回", false, () -> { if (form != null) form.dismiss(); });
        TextView confirm = CourseUi.action(this, "核对并提交", true,
                () -> submit(row, plan, busySlots, chosen, selected, title, memo, captcha[0], agreement, remind, problem[0], mode));
        footer.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(48), 2); confirmParams.leftMargin = dp(10);
        footer.addView(confirm, confirmParams);

        form = Ui.sheet(this, content, footer);
        if (form.getWindow() != null) form.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        final Dialog opened = form;
        form.setOnDismissListener(d -> { if (form == opened) form = null; });
    }

    private android.graphics.Bitmap decode(String encoded) {
        try {
            if (encoded == null || !encoded.startsWith("data:image/") || encoded.length() > 700000) return null;
            byte[] bytes = android.util.Base64.decode(encoded.substring(encoded.indexOf(',') + 1), android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) { return null; }
    }

    private void pickStart(StudyRoomPlan.Room plan, List<StudyRoomPlan.Busy> busySlots,
                           List<StudyRoomPlan.Slot> slots, final int[] chosen, final Runnable refresh) {
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();
        for (StudyRoomPlan.Slot slot : slots) unique.add(slot.start);
        final List<Integer> values = new ArrayList<>(unique);
        String[] labels = new String[values.size()];
        for (int i = 0; i < values.size(); i++) labels[i] = StudyRoomPlan.clock(values.get(i));
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("选择开始时间").setItems(labels, (d, index) -> {
            int previous = chosen[0];
            chosen[0] = values.get(index);
            List<Integer> ends = StudyRoomPlan.ends(plan, date, busySlots, chosen[0]);
            if (ends.isEmpty()) {
                // 这个开始时间凑不出合法时长（比如后面紧跟着占用），退回原来的选择并说清楚。
                chosen[0] = previous; toast("这个时间没有可用时长，请换一个开始时间"); refresh.run(); return;
            }
            // 尽量保留原来的时长，留不住就取最接近的那个结束时间。
            int wanted = Math.max(plan.minMinutes, chosen[1] - chosen[0]);
            int best = ends.get(0);
            for (int end : ends) if (Math.abs(end - chosen[0] - wanted) < Math.abs(best - chosen[0] - wanted)) best = end;
            chosen[1] = best;
            refresh.run();
        }).setNegativeButton("返回", null).show();
    }

    private void pickEnd(StudyRoomPlan.Room plan, List<StudyRoomPlan.Busy> busySlots, final int[] chosen, final Runnable refresh) {
        List<Integer> ends = StudyRoomPlan.ends(plan, date, busySlots, chosen[0]);
        if (ends.isEmpty()) { toast("这个开始时间没有可用的结束时间"); return; }
        String[] labels = new String[ends.size()];
        for (int i = 0; i < ends.size(); i++) labels[i] = StudyRoomPlan.clock(ends.get(i)) + "（" + (ends.get(i) - chosen[0]) + " 分钟）";
        final List<Integer> values = ends;
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("选择结束时间").setItems(labels, (d, index) -> {
            chosen[1] = values.get(index); refresh.run();
        }).setNegativeButton("返回", null).show();
    }

    /** 提交前的本地校验；校方还会再判一次，这里只是不让人白填。 */
    private String check(StudyRoomPlan.Room plan, List<StudyRoomPlan.Busy> busySlots, int start, int end, int people) {
        if (end <= start) return "结束时间要晚于开始时间";
        long begin = StudyRoomPlan.at(date, start);
        if (begin <= System.currentTimeMillis()) return "开始时间已经过去，请换一个时段";
        if (!StudyRoomPlan.inside(plan, start, end)) return "所选时段不在房间开放时间内";
        int minutes = end - start;
        if (minutes < plan.minMinutes || minutes > plan.maxMinutes)
            return "预约时长须为 " + plan.minMinutes + "–" + plan.maxMinutes + " 分钟";
        if (people < plan.minUser || people > plan.maxUser)
            return "该房间需要 " + plan.minUser + "–" + plan.maxUser + " 位参与者（含本人）";
        if (!StudyRoomPlan.overlaps(busySlots, begin, StudyRoomPlan.at(date, end)).isEmpty())
            return "所选时段已被占用，请换一个时间";
        return "";
    }

    private void searchMember(final LinkedHashMap<String, JSONObject> selected, final Runnable refresh) {
        LinearLayout content = CourseUi.column(this); content.setPadding(dp(18), dp(8), dp(18), dp(8));
        final EditText key = Ui.field(content, "姓名或学号（至少两个字符）", "输入后点搜索", null, InputType.TYPE_CLASS_TEXT);
        final AlertDialog search = new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("搜索真实同行人")
                .setView(content).setNegativeButton("返回", null).setPositiveButton("搜索", null).create();
        search.setOnShowListener(d -> search.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            final String value = key.getText().toString().trim();
            if (value.length() < 2) { toast("请输入至少两个字符"); return; }
            search.dismiss(); call("members", args("key", value), result -> {
                JSONArray found = result.optJSONArray("members");
                if (found == null || found.length() == 0) { toast("未找到可添加的同行人"); return; }
                String[] labels = new String[found.length()];
                for (int i = 0; i < labels.length; i++) {
                    JSONObject m = found.optJSONObject(i); labels[i] = m.optString("name") + " · " + m.optString("account");
                }
                new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("选择同行人").setItems(labels, (dialog, index) -> {
                    JSONObject member = found.optJSONObject(index);
                    if (selected.size() >= 30) { toast("最多添加 30 位同行人"); return; }
                    selected.put(member.optString("id"), member); refresh.run();
                }).setNegativeButton("返回", null).show();
            });
        }));
        search.show();
    }

    private void submit(JSONObject row, StudyRoomPlan.Room plan, List<StudyRoomPlan.Busy> busySlots, int[] chosen,
                        LinkedHashMap<String, JSONObject> selected, EditText title, EditText memo,
                        EditText captcha, CheckBox agreement, CheckBox remind, String problem, String mode) {
        if (!active || busy) { toast("上一请求尚未完成，请稍候"); return; }
        if (!agreement.isChecked()) { toast("请先阅读并同意预约须知"); return; }
        if (title.getText().toString().trim().isEmpty()) { toast("请填写研修主题"); return; }
        if (!problem.isEmpty()) { toast(problem); return; }
        if ("1".equals(mode) && (captcha == null || captcha.getText().toString().trim().isEmpty())) {
            toast("请输入图片验证码"); return;
        }
        JSONArray ids = new JSONArray(); StringBuilder names = new StringBuilder("本人");
        for (JSONObject member : selected.values()) {
            ids.put(member.opt("id"));
            names.append("、").append(member.optString("name")).append("（").append(member.optString("account")).append("）");
        }
        final String theme = title.getText().toString().trim();
        final JSONObject payload = args("id", row.opt("id"), "date", date,
                "start", StudyRoomPlan.clock(chosen[0]), "end", StudyRoomPlan.clock(chosen[1]),
                "title", theme, "memo", memo.getText().toString(), "members", ids,
                "captcha", captcha == null ? "" : captcha.getText().toString(),
                "agreed", true, "confirmed", true);
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("确认提交预约？")
                .setMessage(row.optString("name", "研修室") + "\n" + date + " " + StudyRoomPlan.clock(chosen[0])
                        + "–" + StudyRoomPlan.clock(chosen[1]) + "\n主题：" + theme + "\n参与者：" + names
                        + "\n\n以校方最终受理结果为准；提交后请在我的预约核对状态。")
                .setNegativeButton("返回修改", null)
                .setPositiveButton("确认提交", (d, w) -> {
                    if (busy) { toast("上一请求尚未完成，请稍候"); return; }
                    // 只有请求真的发出去了才关掉面板；会话失效时保留填写内容，改完能再提交。
                    boolean sent = write("submit", payload,
                            () -> { if (remind.isChecked()) queueReminder(row, theme, chosen[0], chosen[1]); });
                    if (sent && form != null) { form.dismiss(); form = null; }
                }).show();
    }

    /** 提交成功后要在首页留的提醒，先暂存，等校方受理确认了再写。 */
    private void queueReminder(JSONObject row, String theme, int start, int end) {
        reminderRow = row; reminderTheme = theme; reminderStart = start; reminderEnd = end;
    }

    private void flushReminder() {
        if (reminderRow == null) return;
        JSONObject row = reminderRow; String theme = reminderTheme;
        int start = reminderStart, end = reminderEnd;
        reminderRow = null; reminderTheme = "";
        remindLater(row, theme, start, end);
    }

    /** 在首页留一条任务，复用现有的到期提醒；写失败不影响预约结果。 */
    private void remindLater(JSONObject row, String theme, int start, int end) {
        long begin = StudyRoomPlan.at(date, start);
        if (begin <= 0) return;
        try {
            Engine engine = EngineHolder.get(this);
            if (engine == null) { toast("首页还没准备好，可稍后在首页手动加一条提醒"); return; }
            String name = row.optString("name", "研修室");
            String place = row.optString("location");
            engine.addManualCard("研修室 · " + name, begin, place.isEmpty() ? name : place,
                    date + " " + StudyRoomPlan.clock(start) + "–" + StudyRoomPlan.clock(end) + "\n主题：" + theme,
                    System.currentTimeMillis());
            EngineHolder.saveAsync(this);
            toast("已在首页加入提醒，开始前 2 小时提示");
        } catch (RuntimeException e) {
            EngineHolder.diag(this, "出错", "研修室提醒写入失败");
            toast("提醒没能写入首页，可手动添加");
        }
    }

    private void confirmCancel(JSONObject row) {
        long beginsAt = StudyRoomPlan.stamp(row.optString("begin"));
        String when = beginsAt > 0
                ? StudyRoomPlan.relative(StudyRoomPlan.day(beginsAt), System.currentTimeMillis())
                + " " + hhmm(row.optString("begin")) + "–" + hhmm(row.optString("end"))
                : row.optString("begin") + " — " + row.optString("end");
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("确认取消本人预约？")
                .setMessage(row.optString("name", "研修室") + "\n" + when
                        + "\n\n取消只针对本人发起的预约，最终结果以校方系统为准。")
                .setNegativeButton("保留预约", null)
                .setPositiveButton("确认取消", (d, w) -> write("cancel", args("uuid", row.optString("uuid"), "confirmed", true)))
                .show();
    }

    private void write(String method, JSONObject payload) { write(method, payload, null); }

    /** 返回请求是否真的发出去了；没发出去时调用的面板要留着，别把用户填的东西一起丢掉。 */
    private boolean write(String method, JSONObject payload, Runnable staged) {
        if (!active || busy || !ready || session == null) { toast("会话还没就绪，请稍后重试"); return false; }
        if (pendingWrite()) { mine = true; rows = null; page = 1; toast("请先核对上次操作结果，勿重复提交"); query(); return false; }
        final boolean submit = "submit".equals(method);
        // 只认本次提交暂存的提醒：取消不能把上一次没提交成功的提醒顺手写进首页。
        if (!submit) reminderRow = null;
        // Persist before dispatch, including process death and activity recreation.
        if (!markWrite(true)) { toast("无法保存操作保护状态，本次未提交"); return false; }
        if (staged != null) staged.run();
        reconciled = false;
        boolean sent = call(method,payload,result -> {
            // 只有校方明确受理后才把提醒写进首页；结果未知时宁可少一条提醒。
            if (result.optBoolean("success")) {
                markWrite(false);
                if (submit) flushReminder(); else reminderRow = null;
            } else reminderRow = null;
            myBookings = null;
            message = result.optString("message"); toast(message); mine = true; page = 1; rows = null;
            // Do not retry a write, even if the server outcome is unknown.
            query();
        }, () -> reminderRow = null);
        // 请求没发出去（会话刚失效）就把保护标记撤回，否则用户会被永久挡住。
        if (!sent || !ready) { markWrite(false); reminderRow = null; toast("会话已失效，请重新连接后再提交"); return false; }
        return true;
    }

    // ---------- 杂项 ----------

    private static String join(String first, String second) {
        if (first == null || first.isEmpty()) return second == null ? "" : second;
        if (second == null || second.isEmpty()) return first;
        return first + " · " + second;
    }

    /** "2026-11-12 09:30:00" 只要 "09:30"。 */
    private static String hhmm(String stamp) {
        long value = StudyRoomPlan.stamp(stamp);
        if (value <= 0) return stamp == null ? "" : stamp;
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT);
        format.setTimeZone(StudyRoomPlan.CHINA);
        return format.format(new java.util.Date(value));
    }

    private static String clockOf(long millis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT);
        format.setTimeZone(StudyRoomPlan.CHINA);
        return format.format(new java.util.Date(millis));
    }
}
