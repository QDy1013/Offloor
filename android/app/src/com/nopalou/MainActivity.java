package com.nopalou;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * 「Offloor」主界面：一屏出结论。
 * 卡片 = 事由 + 截止 + 方式 + 来源；点开能回到原文和上下文。
 */
public class MainActivity extends Activity implements Engine.SelectedListener {
    private static final int PAGE_SIZE = 30;
    private static final int REQUEST_REMINDER_PERMISSION = 8420;

    Engine engine;
    LinearLayout cardList;
    TextView stats;
    /** 顶部副标题（日期 + 问候），render() 时按小时段刷新。 */
    TextView sub;
    /** 撞车提醒横幅：几天内几件事挤在一起时显示在统计句下方。 */
    TextView clashBanner;
    private TextView updateBanner;
    private final Runnable updateNoticeChanged = this::showUpdateNotice;
    private void showUpdateNotice() {
        if (isFinishing() || isDestroyed() || updateBanner==null) return;
        UpdateCatalog next=UpdateNotice.available(this);
        updateBanner.setVisibility(next==null?View.GONE:View.VISIBLE);
        if (next!=null) {
            String notes=next.notes.trim().replace('\n',' ');
            updateBanner.setText("发现新版本 "+next.versionName+" · 点击更新  ›"+(notes.isEmpty()?"":"\n"+notes));
        }
    }
    TextView notifStatus;
    TextView empty;
    boolean accessChecked;
    private boolean retryNotifOnResume;
    private static String storageIssueShown;
    private DemoScenario demo;
    private Button demoButton;
    private Button demoUpdate;
    private LinearLayout demoTools;
    private LinearLayout liveTools;
    /** 底部栏唯一的整宽主按钮：开启自动读取（通知读取权限）。 */
    private Button autoReadButton;
    private LinearLayout liveFilters;
    private TextView stateFilter;
    /** 顶部日期筛选：默认「今天起」，直接看到今天及以后还没做的事。 */
    private static final String[] WINDOW_LABELS = {"今天起", "今天", "明天", "待确认", "全部"};
    private static final Engine.CardWindow[] WINDOW_TABS = {Engine.CardWindow.UPCOMING, Engine.CardWindow.TODAY,
            Engine.CardWindow.TOMORROW, Engine.CardWindow.NEEDS_CONFIRM, Engine.CardWindow.ANY};
    private final TextView[] windowTabs = new TextView[WINDOW_TABS.length];
    private TextView scanChip;
    /** 已经提示过的自动扫描时间，避免每次回到前台都重复提示。 */
    private static long scanNoticeShownAt = 0L;
    private EditText searchField;
    private TextView searchClear;
    private ScrollView listScroll;
    private TextView headline;
    /** Compact daily overview; task content remains the main focus. */
    private LinearLayout hero;
    private AlertDialog usageNoticeDialog;
    private boolean restoreUsageNotice;
    private boolean startupNoticeHandled;
    private LinearLayout emptyBox;
    private View emptyIcon;
    private TextView emptyTitle;
    private LinearLayout undoBar;
    private TextView undoText;
    private TextView undoAction;
    /** 来源 App 图标缓存：只存 ConstantState，每张卡各自 newDrawable，避免共享可变对象。 */
    private static final Map<String, Drawable.ConstantState> sourceIconCache = new HashMap<>();
    /** 查询失败也缓存，避免每次 render 反复向 PackageManager 要图标。 */
    private static final java.util.Set<String> sourceIconMiss = new java.util.HashSet<>();
    private String keywords = "";
    private Engine.CardScope scope = Engine.CardScope.ALL;
    private Engine.CardWindow window = Engine.CardWindow.UPCOMING;
    private int visibleLimit = PAGE_SIZE;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean renderQueued;
    private int restoreScrollY = -1;
    private final Runnable queuedRender = () -> {
        renderQueued = false;
        if (resumed) render();
    };
    private final Runnable searchRefresh = this::render;
    private final Runnable clockRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            tickClock();
            UpdateNotice.check(MainActivity.this, updateNoticeChanged);
            uiHandler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L);
        }
    };
    /** 首次打开时卡片逐张浮入；之后的刷新不再做入场动画，避免每次都闪。 */
    private boolean firstRender;
    /** 撤销 / 恢复后要淡入的那张卡。 */
    private Engine.Card animateInCard;
    /** 当前屏上卡片的倒计时行：每分钟只改文字，不重建整页。 */
    private final List<DueBinding> dueBindings = new ArrayList<>();
    /** 滚过大标题后出现的紧凑顶栏。 */
    private LinearLayout compactBar;
    private TextView compactTitle;
    private boolean compactShown;
    private boolean emptyShown;
    /** 正在播完成动画、还没真正标记的卡。 */
    private final java.util.Set<Engine.Card> pendingDone = new java.util.HashSet<>();
    /** 上次整页渲染时的本地日期（年*1000+年内第几天），跨午夜就整页重排。 */
    private int renderedDay;

    private static final class DueBinding {
        final Engine.Card card;
        final TextView due;
        final String bucket;
        final int urgency;

        DueBinding(Engine.Card card, TextView due, String bucket, int urgency) {
            this.card = card;
            this.due = due;
            this.bucket = bucket;
            this.urgency = urgency;
        }
    }

    /** 常驻的待办页：校园后台更新的浏览器挂在它下面，切换底部标签时不会被打断。 */
    private static java.lang.ref.WeakReference<MainActivity> liveRef;

    static MainActivity live() {
        MainActivity m = liveRef == null ? null : liveRef.get();
        return m == null || m.isFinishing() || m.isDestroyed() ? null : m;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        liveRef = new java.lang.ref.WeakReference<>(this);
        Ui.apply(this);
        firstRender = b == null;
        if (b != null) {
            accessChecked = b.getBoolean("accessChecked", false);
            restoreUsageNotice = b.getBoolean("usageNoticeShowing", false);
            keywords = b.getString("keywords", "");
            Engine.CardScope[] scopes = Engine.CardScope.values();
            int selected = b.getInt("scope", 0);
            scope = scopes[Math.max(0, Math.min(selected, scopes.length - 1))];
            visibleLimit = Math.max(PAGE_SIZE, b.getInt("visibleLimit", PAGE_SIZE));
            int savedWindow = b.getInt("window", Engine.CardWindow.UPCOMING.ordinal());
            window = Engine.CardWindow.values()[Math.max(0, Math.min(savedWindow, Engine.CardWindow.values().length - 1))];
            restoreScrollY = b.getInt("scrollY", 0);
        }
        engine = EngineHolder.get(this);
        if (engine == null) {
            Toast.makeText(this, "模型加载失败", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        engine.listener = this;
        buildUi();
        // 每次打开 App 都确保看门狗已登记（已存在时不会重复登记，不影响原有周期）。
        WatchdogJob.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CampusAutoRefresh.maybeRefresh(this);
        TodayWidget.refresh(this);       // 跨天、下课后回到应用时让桌面小组件跟上
        if (engine == null) return;
        resumed = true;
        showUpdateNotice();
        UpdateNotice.check(this, updateNoticeChanged);
        if (EngineHolder.storageIssue != null && !EngineHolder.storageIssue.equals(storageIssueShown)) {
            storageIssueShown = EngineHolder.storageIssue;
            uiHandler.post(this::showStorageState);
        }
        // Only recover an authorized, disconnected listener; returning to the UI must not reset an active recovery.
        NotifRecovery.request(this, retryNotifOnResume);
        retryNotifOnResume = false;
        if (engine != null) {
            engine.listener = this;
            ReminderReceiver.refresh(this, engine);
            render();
            // 服务连上时会自动扫一遍通知栏；有新事项就在这里轻提示一次。
            NotifListener.ScanResult auto = NotifListener.lastScan;
            if (auto != null && demo == null && NotifListener.lastScanAt > scanNoticeShownAt) {
                scanNoticeShownAt = NotifListener.lastScanAt;
                showScanResult(auto, false);
            }
        }
        uiHandler.removeCallbacks(clockRefresh);
        uiHandler.postDelayed(clockRefresh, 60_000L - System.currentTimeMillis() % 60_000L);
        if (restoreScrollY >= 0) {
            int y = restoreScrollY;
            listScroll.post(() -> {
                if (resumed && restoreScrollY == y) {
                    listScroll.scrollTo(0, y);
                    restoreScrollY = -1;
                }
            });
        }
        // 使用须知优先于自动权限引导，避免首次打开时两个弹窗叠在一起。
        showStartupDialogs();
    }

    private void showStartupDialogs() {
        if (setupDialog != null && setupDialog.isShowing()) {
            fillSetup();
            return;
        }
        if (usageNoticeDialog != null && usageNoticeDialog.isShowing()) return;
        if (demo == null && (restoreUsageNotice || (!startupNoticeHandled && HomeUsageNotice.shouldAutoShow(this)))) {
            uiHandler.postDelayed(() -> {
                if (resumed && !isFinishing() && !isDestroyed() && demo == null
                        && (setupDialog == null || !setupDialog.isShowing())) showUsageNotice();
            }, 350);
            return;
        }
        if (!accessChecked) {
            accessChecked = true;
            if (SetupGuide.shouldAutoShow(this)) {
                uiHandler.postDelayed(() -> {
                    if (resumed && !isFinishing() && !isDestroyed()
                            && (usageNoticeDialog == null || !usageNoticeDialog.isShowing())
                            && (setupDialog == null || !setupDialog.isShowing())) showSetupGuide();
                }, 350);
            }
        }
    }

    void showUsageNotice() {
        if (isFinishing() || isDestroyed()) return;
        if (usageNoticeDialog != null && usageNoticeDialog.isShowing()) return;
        startupNoticeHandled = true;
        restoreUsageNotice = false;
        usageNoticeDialog = HomeUsageNotice.create(this, this::showSetupGuide, this::showHealthCheck);
        usageNoticeDialog.setOnDismissListener(dialog -> usageNoticeDialog = null);
        usageNoticeDialog.show();
    }

    @Override
    protected void onPause() {
        CampusAutoRefresh.pause(this);
        resumed = false;
        uiHandler.removeCallbacks(queuedRender);
        uiHandler.removeCallbacks(searchRefresh);
        uiHandler.removeCallbacks(clockRefresh);
        renderQueued = false;
        // 完成动画还没播完就离开页面（切到课表、锁屏、深浅色切换重建）：直接落定，不丢这次点击。
        if (!pendingDone.isEmpty()) {
            Engine shown=demo==null?engine:demo.engine;
            if(shown!=null)for(Engine.Card c:pendingDone)shown.setCardDone(c,true,System.currentTimeMillis());
            pendingDone.clear();
            if (demo == null) EngineHolder.saveAsync(this);
        }
        if (demo == null) EngineHolder.flush(400);
        if (engine != null && engine.listener == this) engine.listener = null;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("accessChecked", accessChecked);
        state.putBoolean("usageNoticeShowing", usageNoticeDialog != null && usageNoticeDialog.isShowing());
        state.putString("keywords", keywords);
        state.putInt("scope", scope.ordinal());
        state.putInt("window", window.ordinal());
        state.putInt("visibleLimit", visibleLimit);
        state.putInt("scrollY", listScroll == null ? 0 : listScroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        CampusSilentRefresh.hostDestroyed(this);
        uiHandler.removeCallbacksAndMessages(null);
        if (usageNoticeDialog != null) {
            usageNoticeDialog.setOnDismissListener(null);
            usageNoticeDialog.dismiss();
            usageNoticeDialog = null;
        }
        if (engine != null && engine.listener == this) engine.listener = null;
        super.onDestroy();
    }

    /** 首次进入如果没给通知读取权限，直接引导过去，不然界面永远是空的。 */
    void ensureNotificationAccess() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null || !flat.contains(getPackageName())) {
                alert()
                        .setTitle("还差一步")
                        .setMessage("在「通知使用权」里打开「Offloor」，我才能读到群消息。\n\n"
                                + "只在本机读取和处理，飞行模式下照常工作。")
                        .setPositiveButton("去开启", (d, w) -> startActivity(
                                new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")))
                        .setNegativeButton("稍后", null)
                        .show();
            }
        } catch (Exception ignored) { }
    }

    /**
     * 顶栏右侧的读取状态胶囊：已连接 = 绿点「自动整理中」，未连接 = 琥珀「未开启读取」。
     * 点开都是自检；演示态由 render() 单独处理。
     */
    void updateNotifStatus() {
        if (notifStatus == null) return;
        boolean on = demo == null && EngineHolder.listenerConnected;
        if (on) {
            long last = EngineHolder.lastReceivedAt;
            notifStatus.setText(statusText("服务已连接", Ui.GREEN));
            notifStatus.setTextColor(Ui.SUB);
            notifStatus.setBackground(Ui.ripple(this, Ui.round(this, Ui.CARD, 15), Ui.press(), 15));
            notifStatus.setContentDescription(last == 0 ? "自动读取已连接，等待新消息"
                    : "自动读取已连接，最近收到 " + relativeTime(last));
        } else {
            boolean access = WatchdogJob.accessEnabled(this);
            boolean reconnecting = access && NotifRecovery.running();
            notifStatus.setText(statusText(!access ? "未开启读取" : reconnecting ? "正在重连" : "读取未连接", Ui.AMBER_TX));
            notifStatus.setTextColor(Ui.AMBER_TX);
            notifStatus.setBackground(Ui.ripple(this, Ui.round(this, Ui.AMBER_BG, 15), Ui.press(), 15));
            notifStatus.setContentDescription(reconnecting ? "通知读取正在重连，点按查看" : "通知读取未连接，点按查看恢复方法");
        }
    }

    /** 「● 文字」：圆点单独上色并缩小一点，比整行彩字更克制。 */
    CharSequence statusText(String label, int dotColor) {
        SpannableString s = new SpannableString("● " + label);
        s.setSpan(new ForegroundColorSpan(dotColor), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(0.75f), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /**
     * 相对时间：不到 1 分钟「刚刚」，不到 60 分钟「N 分钟前」，
     * 不到 24 小时「N 小时前」，再久就是「N 天前」。手机时间被回拨时不出现负数。
     */
    String relativeTime(long when) {
        long diff = System.currentTimeMillis() - when;
        if (diff < 0) diff = 0;
        long minutes = diff / 60000L;
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        return (hours / 24) + " 天前";
    }

    void showNotifHint() {
        alert()
                .setTitle("收不到群消息")
                .setMessage("系统断开了 Offloor 的通知读取（部分手机重启后会这样）。"
                        + "请在「通知使用权」里把 Offloor 关掉再打开；\n"
                        + "如果仍不行，到手机管家里允许 Offloor 自启动和后台运行。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour <= 11) return "早上好";
        if (hour >= 12 && hour <= 17) return "下午好";
        return "晚上好";
    }

    /** 顶栏日期：「11月5日 周四 · 晚上好」。 */
    String subLine() {
        Calendar c = Calendar.getInstance();
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        String day = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
        return day + " 周" + week[c.get(Calendar.DAY_OF_WEEK) - 1] + " · " + greeting();
    }

    LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    AlertDialog.Builder alert() {
        return new AlertDialog.Builder(this, Ui.dialogTheme());
    }

    /**
     * 页面结构：整页一个滚动区（顶栏、大标题、搜索、筛选、卡片一起滚，小屏也不会被头部占满），
     * 底部固定待办/课表主导航；未开启读取时增加授权按钮；最上层叠一条「已完成 · 撤销」提示。
     */
    void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(this, 18), dp(this, 4), dp(this, 18), dp(this, 80));
        listScroll.addView(page, new ViewGroup.LayoutParams(-1, -2));

        // 顶栏：品牌字标 · 读取状态 · 更多
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = uiText("", 22, Ui.TEXT, false);
        SpannableString word = new SpannableString("Offloor.");
        word.setSpan(new ForegroundColorSpan(Ui.ACCENT), word.length() - 1, word.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        brand.setText(word);
        brand.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        brand.setLetterSpacing(-0.02f);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        CampusSyncBanner.add(this,top);
        notifStatus = uiText("", 12, Ui.SUB, true);
        notifStatus.setGravity(Gravity.CENTER);
        notifStatus.setPadding(dp(this, 10), 0, dp(this, 10), 0);
        notifStatus.setOnClickListener(v -> showHealthCheck());
        top.addView(notifStatus, lp(-2, dp(this, 32)));
        ImageView more = new ImageView(this);
        more.setImageDrawable(CourseUi.glyph(this, "more"));
        int morePad = dp(this, 9);
        more.setPadding(morePad, morePad, morePad, morePad);
        more.setContentDescription("更多");
        more.setBackground(Ui.ripple(this, Ui.roundStroke(this, Ui.CARD, Ui.LINE, 14, 0.75f), Ui.press(), 14));
        more.setOnClickListener(v -> showMoreMenu());
        LinearLayout.LayoutParams moreLp = lp(dp(this, 48), dp(this, 48));
        moreLp.leftMargin = dp(this, 8);
        top.addView(more, moreLp);
        page.addView(top, lp(-1, dp(this, 60)));
        updateBanner=uiText("",13,Ui.ACCENT,true);
        updateBanner.setPadding(dp(this,16),dp(this,13),dp(this,16),dp(this,13));
        updateBanner.setBackground(Ui.ripple(this,Ui.round(this,Ui.ACCENT_BG,18),Ui.press(),18));
        updateBanner.setMaxLines(3); updateBanner.setEllipsize(TextUtils.TruncateAt.END);
        updateBanner.setLineSpacing(dp(this,3),1f);
        updateBanner.setOnClickListener(v -> startActivity(new Intent(this,UpdateActivity.class)));
        updateBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams updateLp=lp(-1,-2); updateLp.bottomMargin=dp(this,10);
        page.addView(updateBanner,updateLp);
        showUpdateNotice();

        // Compact overview with a date masthead and a separate, quieter stats band.
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(this,20),dp(this,10),dp(this,20),dp(this,10));
        hero.setBackground(Ui.heroSurface(this, 20));
        Ui.lift(hero, 3f);
        LinearLayout heroMasthead = new LinearLayout(this);
        heroMasthead.setGravity(Gravity.CENTER_VERTICAL);
        sub = uiText(subLine(), 12, Ui.HERO_SUB, true);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setLetterSpacing(0.02f);
        heroMasthead.addView(sub, new LinearLayout.LayoutParams(0, -2, 1));
        TextView todayLabel = Ui.tag(this, "今日概览", Ui.ON_HERO, Ui.withAlpha(Ui.HERO_GLOW, 38));
        LinearLayout.LayoutParams todayLp = lp(-2, -2);
        todayLp.leftMargin = dp(this, 10);
        heroMasthead.addView(todayLabel, todayLp);
        hero.addView(heroMasthead, lp(-1, -2));
        headline = uiText("",22,Ui.ON_HERO,false);
        headline.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        headline.setLineSpacing(dp(this, 2), 1f);
        headline.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams hlLp = lp(-1, -2);
        hlLp.topMargin = dp(this,7);
        hero.addView(headline, hlLp);
        stats = uiText("", 13, Ui.HERO_SUB, false);
        stats.setLineSpacing(dp(this, 3), 1f);
        stats.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
        stats.setBackground(Ui.round(this, Ui.withAlpha(Ui.ON_HERO, 12), 10));
        LinearLayout.LayoutParams stLp = lp(-1, -2);
        stLp.topMargin = dp(this,7);
        hero.addView(stats, stLp);
        LinearLayout.LayoutParams heroLp = lp(-1, -2);
        heroLp.topMargin = dp(this, 6);
        page.addView(hero, heroLp);

        // 撞车提醒
        clashBanner = uiText("", 13, Ui.ORANGE, true);
        clashBanner.setPadding(dp(this, 16), dp(this, 13), dp(this, 16), dp(this, 13));
        clashBanner.setBackground(Ui.ripple(this, Ui.round(this, Ui.ORANGE_BG, 18), Ui.press(), 18));
        clashBanner.setLineSpacing(dp(this, 2), 1f);
        clashBanner.setMaxLines(2);
        clashBanner.setEllipsize(TextUtils.TruncateAt.END);
        clashBanner.setOnClickListener(v -> showClashDetail());
        clashBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams clashLp = lp(-1, -2);
        clashLp.topMargin = dp(this, 16);
        page.addView(clashBanner, clashLp);

        // 离线演示条
        demoTools = new LinearLayout(this);
        demoTools.setGravity(Gravity.CENTER_VERTICAL);
        demoTools.setVisibility(View.GONE);
        demoTools.setPadding(dp(this, 18), dp(this, 12), dp(this, 12), dp(this, 12));
        demoTools.setBackground(Ui.round(this, Ui.ACCENT_BG, 20));
        LinearLayout demoText = new LinearLayout(this);
        demoText.setOrientation(LinearLayout.VERTICAL);
        demoText.addView(uiText("离线演示", 14, Ui.ACCENT, true));
        TextView demoHint = uiText("样例卡片，不进日常列表", 12, Ui.withAlpha(Ui.ACCENT, 190), false);
        demoHint.setSingleLine(true);
        demoHint.setEllipsize(TextUtils.TruncateAt.END);
        demoHint.setPadding(0, dp(this, 4), 0, 0);
        demoText.addView(demoHint);
        demoTools.addView(demoText, new LinearLayout.LayoutParams(0, -2, 1));
        demoUpdate = stylePrimary(button("播放更新", v -> {
            if (demo != null) {
                demo.applyUpdates();
                demoUpdate.setVisibility(View.GONE);
                render();
            }
        }));
        demoUpdate.setTextSize(13);
        demoUpdate.setVisibility(View.GONE);
        demoTools.addView(demoUpdate, lp(-2, dp(this, 40)));
        demoButton = button("退出", v -> toggleDemo());
        demoButton.setBackground(Ui.ripple(this, Ui.round(this, Ui.CARD, 20), Ui.press(), 20));
        demoButton.setTextSize(13);
        LinearLayout.LayoutParams exitLp = lp(-2, dp(this, 40));
        exitLp.leftMargin = dp(this, 8);
        demoTools.addView(demoButton, exitLp);
        LinearLayout.LayoutParams demoLp = lp(-1, -2);
        demoLp.topMargin = dp(this, 16);
        page.addView(demoTools, demoLp);

        liveFilters = buildFilters();
        page.addView(liveFilters);

        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        // 卡片的柔和投影和按压缩放会画到自身边界外，不能被父布局裁掉。
        cardList.setClipChildren(false);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        LinearLayout.LayoutParams listLp = lp(-1, -2);
        listLp.topMargin = dp(this, 4);
        page.addView(cardList, listLp);

        // 空状态
        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyBox.setPadding(dp(this, 24), dp(this, 56), dp(this, 24), dp(this, 40));
        emptyIcon = new View(this);
        emptyBox.addView(emptyIcon, lp(dp(this, 52), dp(this, 52)));
        emptyTitle = uiText("", 17, Ui.TEXT, true);
        emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etLp = lp(-2, -2);
        etLp.topMargin = dp(this, 18);
        emptyBox.addView(emptyTitle, etLp);
        empty = uiText("", 14, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(this, 4), 1f);
        LinearLayout.LayoutParams eLp = lp(-2, -2);
        eLp.topMargin = dp(this, 8);
        emptyBox.addView(empty, eLp);

        root.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部主按钮：只在没开读取时出现
        liveTools = new LinearLayout(this);
        liveTools.setPadding(dp(this, 20), dp(this, 10), dp(this, 20), dp(this, 4));
        liveTools.setBackgroundColor(Ui.BG);
        autoReadButton = stylePrimary(button("开启自动读取", v -> {
            if (WatchdogJob.accessEnabled(this)) showHealthCheck();
            else showSetupGuide();
        }));
        liveTools.addView(autoReadButton, new LinearLayout.LayoutParams(-1, dp(this, 52)));
        root.addView(liveTools);
        root.addView(AppNavigation.bar(this, AppNavigation.TODO));

        // 撤销提示条
        undoBar = new LinearLayout(this);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setPadding(dp(this, 18), 0, dp(this, 6), 0);
        undoBar.setBackground(Ui.round(this, Ui.INK, 18));
        undoBar.setElevation(dp(this, 6));
        undoText = uiText("", 14, Color.WHITE, false);
        undoText.setSingleLine(true);
        undoText.setEllipsize(TextUtils.TruncateAt.END);
        undoBar.addView(undoText, new LinearLayout.LayoutParams(0, -2, 1));
        undoAction = uiText("撤销", 14, Ui.INK_ACTION, true);
        undoAction.setGravity(Gravity.CENTER);
        undoAction.setPadding(dp(this, 14), 0, dp(this, 14), 0);
        undoAction.setBackground(Ui.ripple(this, null, Ui.withAlpha(Color.WHITE, 40), 10));
        undoBar.addView(undoAction, lp(-2, dp(this, 40)));
        undoBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams undoLp = new FrameLayout.LayoutParams(-1, dp(this, 52), Gravity.BOTTOM);
        undoLp.setMargins(dp(this, 16), 0, dp(this, 16), dp(this, 90));
        frame.addView(undoBar, undoLp);

        buildCompactBar(frame);
        listScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateCompactBar(y));

        setContentView(frame);
        Ui.systemBars(this, frame);
        getWindow().setNavigationBarColor(Ui.BG);   // 和悬浮导航外的页面底色连成一片
        root.requestFocus();
    }

    /**
     * 紧凑顶栏：大标题滚出屏幕后，从顶部淡入一条「结论 + 读取状态」，
     * 往下翻长列表时也知道今天几件；点它回到顶部。
     */
    private void buildCompactBar(FrameLayout frame) {
        compactBar = new LinearLayout(this);
        compactBar.setOrientation(LinearLayout.VERTICAL);
        compactBar.setBackgroundColor(Ui.BG);
        // 浅色用柔和投影分层；深色下投影看不见，改用一条细线。
        if (!Ui.dark) compactBar.setElevation(dp(this, 3));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(this, 20), 0, dp(this, 12), 0);
        compactTitle = uiText("", 16, Ui.TEXT, true);
        compactTitle.setSingleLine(true);
        compactTitle.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(compactTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView up = uiText("回到顶部", 13, Ui.ACCENT, true);
        up.setGravity(Gravity.CENTER);
        up.setPadding(dp(this, 12), 0, dp(this, 12), 0);
        up.setBackground(Ui.ripple(this, null, Ui.press(), 16));
        up.setOnClickListener(v -> listScroll.smoothScrollTo(0, 0));
        row.addView(up, lp(-2, dp(this, 36)));
        compactBar.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        if (Ui.dark) compactBar.addView(Ui.hairline(this));
        compactBar.setOnClickListener(v -> listScroll.smoothScrollTo(0, 0));
        compactBar.setVisibility(View.INVISIBLE);
        compactBar.setAlpha(0f);
        frame.addView(compactBar, new FrameLayout.LayoutParams(-1, dp(this, 52), Gravity.TOP));
    }

    private void updateCompactBar(int scrollY) {
        if (compactBar == null || hero == null) return;
        boolean show = scrollY > hero.getBottom() - dp(this, 24);
        if (show == compactShown) return;
        compactShown = show;
        compactBar.animate().cancel();
        if (show) {
            compactBar.setVisibility(View.VISIBLE);
            compactBar.setTranslationY(-dp(this, 8));
            compactBar.animate().alpha(1f).translationY(0f).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            compactBar.animate().alpha(0f).translationY(-dp(this, 8)).setDuration(120)
                    .withEndAction(() -> { if (!compactShown) compactBar.setVisibility(View.INVISIBLE); }).start();
        }
    }

    TextView uiText(String text, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(Ui.medium());
        return v;
    }

    /** 搜索框 + 一排筛选胶囊（全部 / 今天 / 明天 / 待确认 / 状态）。 */
    private LinearLayout buildFilters() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable searchBg = Ui.roundStroke(this, Ui.CARD, Ui.LINE, 14, 0.75f);
        searchRow.setBackground(searchBg);
        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(15);
        searchField.setTypeface(Typeface.DEFAULT);
        searchField.setHint("搜索事项、群名、来源");
        searchField.setContentDescription("搜索待办卡片");
        searchField.setBackground(null);
        searchField.setTextColor(Ui.TEXT);
        searchField.setHintTextColor(Ui.SUB);
        searchField.setPadding(dp(this, 14), 0, dp(this, 4), 0);
        searchField.setCompoundDrawablesWithIntrinsicBounds(Ui.searchGlyph(this, Ui.MUTE, 17), null, null, null);
        searchField.setCompoundDrawablePadding(dp(this, 10));
        searchField.setText(keywords);
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0, dp(this, 46), 1));
        searchClear = uiText("×", 20, Ui.SUB, false);
        searchClear.setGravity(Gravity.CENTER);
        searchClear.setContentDescription("清空搜索");
        searchClear.setBackground(Ui.rippleCircle(this, Ui.press()));
        searchClear.setVisibility(keywords.isEmpty() ? View.GONE : View.VISIBLE);
        searchClear.setOnClickListener(v -> searchField.setText(""));
        searchRow.addView(searchClear, lp(dp(this, 44), dp(this, 44)));
        LinearLayout.LayoutParams searchLp = lp(-1, -2);
        searchLp.topMargin = dp(this, 14);
        wrap.addView(searchRow, searchLp);
        searchField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                keywords = s.toString();
                searchClear.setVisibility(keywords.isEmpty() ? View.GONE : View.VISIBLE);
                visibleLimit = PAGE_SIZE;
                uiHandler.removeCallbacks(searchRefresh);
                uiHandler.postDelayed(searchRefresh, 180);
            }
            public void afterTextChanged(Editable s) {}
        });

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipScroll.setClipToPadding(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < WINDOW_TABS.length; i++) {
            final int index = i;
            TextView tab = chip(WINDOW_LABELS[i]);
            tab.setOnClickListener(v -> {
                scope = Engine.CardScope.ALL;
                window = WINDOW_TABS[index];
                resetCardPage();
            });
            windowTabs[i] = tab;
            LinearLayout.LayoutParams tlp = lp(-2, dp(this, 36));
            tlp.rightMargin = dp(this, 8);
            tabs.addView(tab, tlp);
        }
        stateFilter = chip("状态");
        stateFilter.setContentDescription("待办状态筛选");
        stateFilter.setOnClickListener(v -> showFilterMenu());
        LinearLayout.LayoutParams stateLp = lp(-2, dp(this, 36));
        stateLp.rightMargin = dp(this, 8);
        tabs.addView(stateFilter, stateLp);
        scanChip = chip("扫描历史");
        scanChip.setContentDescription("扫描通知栏里的历史消息");
        scanChip.setOnClickListener(v -> scanHistory());
        tabs.addView(scanChip, lp(-2, dp(this, 36)));
        chipScroll.addView(tabs);
        LinearLayout.LayoutParams tabsLp = lp(-1, -2);
        tabsLp.topMargin = dp(this, 12);
        wrap.addView(chipScroll, tabsLp);
        return wrap;
    }

    private TextView chip(String text) {
        TextView t = uiText(text, 13, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 15), 0, dp(this, 15), 0);
        return t;
    }

    private void styleChip(TextView chip, CharSequence label, String count, boolean selected) {
        SpannableString s = new SpannableString(count == null ? label : label + "  " + count);
        if (count != null) {
            int start = s.length() - count.length();
            s.setSpan(new ForegroundColorSpan(selected ? Ui.withAlpha(Ui.ON_ACCENT, 210) : Ui.SUB),
                    start, s.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // 文字和选中态没变就不重设背景：每次刷新都换 Drawable 会让涟漪中途被打断。
        String key = s.toString() + (selected ? "#1" : "#0") + (Ui.dark ? "d" : "l");
        if (key.equals(chip.getTag())) return;
        boolean wasSelected = chip.isSelected();
        chip.setTag(key);
        chip.setText(s);
        chip.setTextColor(selected ? Ui.ON_ACCENT : Ui.SUB);
        // Brand color connects the active filter to navigation; quiet outlined chips stay secondary.
        GradientDrawable bg = selected ? Ui.round(this, Ui.ACCENT, 12)
                : Ui.roundStroke(this, Ui.CARD, Ui.LINE, 12, 0.75f);
        chip.setBackground(Ui.ripple(this, bg, Ui.press(), 12));
        chip.setSelected(selected);
        if (selected && !wasSelected && chip.isAttachedToWindow()) {
            // 选中时轻轻弹一下，确认点到了。
            chip.setScaleX(0.94f);
            chip.setScaleY(0.94f);
            chip.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
        }
    }

    private void showFilterMenu() {
        String[] labels = {"全部事项", "未完成", "已过期", "已完成"};
        alert().setTitle("按状态查看").setSingleChoiceItems(labels,
                window == Engine.CardWindow.ANY ? scope.ordinal() : -1, (d, which) -> {
                    scope = Engine.CardScope.values()[which];
                    window = Engine.CardWindow.ANY;
                    d.dismiss();
                    resetCardPage();
                }).setNegativeButton("取消", null).show();
    }

    private void updateWindowTabs(Engine shown, long now) {
        // 与 WINDOW_TABS 同序：今天起、今天、明天、待确认、全部。
        int[] counts = new int[WINDOW_TABS.length];
        for (Engine.Card c : shown.all()) {
            counts[4]++;
            String bucket = TodayView.bucket(c.deadline, c.needsConfirm, c.done, now);
            if ("今天".equals(bucket)) counts[1]++;
            else if ("明天".equals(bucket)) counts[2]++;
            else if ("待确认".equals(bucket)) counts[3]++;
            if (Engine.isUpcoming(c, now)) counts[0]++;
        }
        for (int i = 0; i < windowTabs.length; i++) {
            boolean selected = scope == Engine.CardScope.ALL && window == WINDOW_TABS[i];
            styleChip(windowTabs[i], WINDOW_LABELS[i], String.valueOf(counts[i]), selected);
        }
        if (scanChip != null) {
            scanChip.setVisibility(demo == null ? View.VISIBLE : View.GONE);
            styleChip(scanChip, "↻ 扫描历史", null, false);
        }
        boolean scoped = scope != Engine.CardScope.ALL;
        String stateLabel = scoped ? new String[]{"状态", "未完成", "已过期", "已完成"}[scope.ordinal()] : "状态";
        styleChip(stateFilter, stateLabel + " ▾", null, scoped);
    }

    /** 概览卡上的提亮色：红 / 橙 / 墨水蓝在深墨底上的对应色。 */
    private static final int HERO_RED = Color.parseColor("#FF8A8C");
    private static final int HERO_ORANGE = Color.parseColor("#FFB46B");
    private static final int HERO_ACCENT = Color.parseColor("#AEB6FF");

    /** 大标题和副标题：先说最要紧的一件事，再补充其余数量。 */
    private void updateHeadline(Engine shown, long now) {
        int total = 0, open = 0, today = 0, tomorrow = 0, overdue = 0, confirm = 0, urgent = 0, soon = 0;
        for (Engine.Card c : shown.all()) {
            total++;
            if (c.done) continue;
            open++;
            String b = TodayView.bucket(c.deadline, c.needsConfirm, false, now);
            if ("今天".equals(b)) today++;
            else if ("明天".equals(b)) tomorrow++;
            else if ("已过期".equals(b)) overdue++;
            else if ("待确认".equals(b)) confirm++;
            int u = TodayView.urgency(c.deadline, c.needsConfirm, false, now);
            if (u >= 2) urgent++;
            if (u >= 1) soon++;
        }
        String head;
        String num = null;
        int numColor = Ui.ACCENT;
        String lead = "";
        if (total == 0) {
            head = "还没有待办";
        } else if (open == 0) {
            head = "全部搞定了";
        } else if (today > 0) {
            num = String.valueOf(today);
            head = "今天有 " + num + " 件要截止";
            numColor = urgent > 0 ? Ui.RED : Ui.ORANGE;
            lead = "today";
        } else if (overdue > 0) {
            num = String.valueOf(overdue);
            head = "有 " + num + " 件已经过期";
            numColor = Ui.RED;
            lead = "overdue";
        } else if (tomorrow > 0) {
            num = String.valueOf(tomorrow);
            head = "明天有 " + num + " 件要截止";
            numColor = soon > 0 ? Ui.ORANGE : Ui.ACCENT;
            lead = "tomorrow";
        } else {
            head = "今天没有截止";
        }
        SpannableString s = new SpannableString(head);
        SpannableString onHero = new SpannableString(head);
        if (num != null) {
            int i = head.indexOf(num);
            s.setSpan(new ForegroundColorSpan(numColor), i, i + num.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 深墨卡上同一种含义换成提亮的那一档，保证对比度。
            int heroColor = numColor == Ui.RED ? HERO_RED : numColor == Ui.ORANGE ? HERO_ORANGE : HERO_ACCENT;
            onHero.setSpan(new ForegroundColorSpan(heroColor), i, i + num.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        headline.setText(onHero);
        if (compactTitle != null) compactTitle.setText(s);

        List<String> parts = new ArrayList<>();
        if (tomorrow > 0 && !"tomorrow".equals(lead)) parts.add("明天 " + tomorrow + " 件");
        if (overdue > 0 && !"overdue".equals(lead)) parts.add("已过期 " + overdue + " 件");
        if (confirm > 0) parts.add("待确认 " + confirm + " 件");
        if (open > 0) parts.add("共 " + open + " 件未完成");
        else if (total > 0) parts.add("已完成 " + total + " 件");
        else parts.add(demo == null && !EngineHolder.listenerConnected
                ? "开启自动读取后，群里的作业、考试、接龙会自动整理到这里"
                : "群里出现作业、考试、接龙时，会自动整理到这里");
        stats.setText(TextUtils.join(" · ", parts));
    }

    /** 「扫描历史」：把通知栏里还挂着的消息（含会话通知里折叠的旧消息）重新过一遍，并告诉用户结果。 */
    private void scanHistory() {
        if (demo != null) return;
        if (!NotifListener.canScan()) {
            alert().setTitle("还不能扫描")
                    .setMessage("通知读取还没连上，扫描需要先在「通知使用权」里打开「Offloor」。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                        } catch (Exception e) {
                            Toast.makeText(this, "请到系统设置里搜索「通知使用权」", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null).show();
            return;
        }
        NotifListener.ScanResult r = NotifListener.scanNow();
        if (r == null) {
            Toast.makeText(this, "扫描失败，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        scanNoticeShownAt = NotifListener.lastScanAt;
        showScanResult(r, true);
    }

    /** 扫描结果：有新事项时列出标题并切到「全部」方便核对；没有时说明原因。 */
    private void showScanResult(NotifListener.ScanResult r, boolean manual) {
        int added = r.newCardIds.size();
        if (!manual) {
            if (added > 0) {
                Toast.makeText(this, "已从通知栏补上 " + added + " 个事项", Toast.LENGTH_LONG).show();
            }
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("读了通知栏里 ").append(r.notifications).append(" 条通知、")
                .append(r.messages).append(" 条消息");
        if (r.messages > r.fresh) msg.append("（").append(r.messages - r.fresh).append(" 条以前处理过，已跳过）");
        msg.append("。\n\n");
        if (added > 0) {
            msg.append("新增 ").append(added).append(" 个事项：\n");
            int listed = 0;
            for (String id : r.newCardIds) {
                Engine.Card c = findCard(id);
                if (c == null) continue;
                if (listed == 6) {
                    msg.append("…\n");
                    break;
                }
                msg.append("· ").append(c.displayTitle());
                if (c.deadline > 0 && c.deadline <= System.currentTimeMillis()) msg.append("（已过期）");
                msg.append("\n");
                listed++;
            }
        } else if (r.notifications == 0) {
            msg.append("通知栏里没有来自微信、QQ 等聊天软件的通知。\n已经清掉的通知、免打扰群的消息扫不到。");
        } else {
            msg.append("没有发现新的作业、考试或接龙。");
        }
        alert().setTitle(added > 0 ? "扫描完成" : "扫描完成，没有新事项")
                .setMessage(msg.toString().trim())
                .setPositiveButton(added > 0 ? "查看" : "好", (d, w) -> {
                    if (added > 0) {
                        // 历史消息里的事项可能已经过期，切到「全部」才看得全。
                        scope = Engine.CardScope.ALL;
                        window = Engine.CardWindow.ANY;
                        resetCardPage();
                    }
                })
                .show();
        render();
    }

    private Engine.Card findCard(String id) {
        if (id == null || engine == null) return null;
        for (Engine.Card c : engine.all()) if (id.equals(c.id)) return c;
        return null;
    }

    /**
     * 切换筛选：列表淡入并轻微上浮，替代原来的“瞬间换一屏”；
     * 已经往下翻过的话只滚回筛选栏的位置，不把整页甩回顶部。
     */
    private void resetCardPage() {
        visibleLimit = PAGE_SIZE;
        render();
        if (cardList != null) {
            cardList.animate().cancel();
            cardList.setAlpha(0f);
            cardList.setTranslationY(dp(this, 10));
            cardList.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (listScroll != null && liveFilters != null) {
            int anchor = Math.max(0, liveFilters.getTop() - dp(this, 8));
            if (listScroll.getScrollY() > anchor) listScroll.smoothScrollTo(0, anchor);
        }
    }

    private void showAllCards() {
        scope = Engine.CardScope.ALL;
        window = Engine.CardWindow.ANY;
        keywords = "";

        searchField.setText("");
        resetCardPage();
    }

    /** 次级按钮：浅灰填充、墨色文字，按压只加深一点。 */
    Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setTypeface(Ui.medium());
        btn.setTextColor(Ui.TEXT);
        btn.setBackground(Ui.ripple(this, Ui.round(this, Ui.FILL, 14), Ui.press(), 14));
        btn.setMinHeight(0);
        btn.setMinimumHeight(dp(this, 44));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setStateListAnimator(null);
        btn.setPadding(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 8);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    /** 主按钮：靛蓝实心 + 白字。一个界面只放一个。 */
    Button stylePrimary(Button b) {
        b.setBackground(Ui.ripple(this, Ui.round(this, Ui.ACCENT, 16), Ui.withAlpha(Ui.ON_ACCENT, 60), 16));
        Ui.lift(b, 3f);
        b.setTextColor(Ui.ON_ACCENT);
        Ui.pressScale(b, 0.97f);
        b.setTextSize(15);
        b.setTypeface(Ui.medium());
        return b;
    }

    void toggleDemo() {
        if (demo == null) {
            try (InputStream model = getAssets().open("model.tsv")) {
                demo = new DemoScenario(model, System.currentTimeMillis());
            } catch (Exception e) {
                Toast.makeText(this, "演示样例加载失败", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            demo = null;
        }
        hideUndo();
        boolean showingDemo = demo != null;
        demoTools.setVisibility(showingDemo ? View.VISIBLE : View.GONE);
        demoButton.setText("退出");
        demoUpdate.setVisibility(showingDemo ? View.VISIBLE : View.GONE);
        liveTools.setVisibility(showingDemo ? View.GONE : View.VISIBLE);
        liveFilters.setVisibility(showingDemo ? View.GONE : View.VISIBLE);
        render();
        listScroll.scrollTo(0, 0);
    }

    void startReplay() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.nopalou.replay");
            if (i == null) {
                Toast.makeText(this, "回放器没装", Toast.LENGTH_SHORT).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "启动回放器失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 「更多」：底部面板，按用途分三组，危险操作单独放最后并标红。 */
    void showMoreMenu() {
        android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(sheetTitle("更多"));
        menuSection(body, "自动读取");
        menuRow(body, holder, "使用须知", "查看通知读取、后台运行和识别限制", Ui.TEXT, this::showUsageNotice);
        menuRow(body, holder, "后台保活设置", SetupGuide.allRecommendedDone(this) ? "已完成 · 通知、后台运行、自启动"
                : "还有步骤没完成，可能收不到群消息", SetupGuide.allRecommendedDone(this) ? Ui.TEXT : Ui.AMBER_TX, this::showSetupGuide);
        menuRow(body, holder, "自动读取自检", "检查权限、连接和后台限制", Ui.TEXT, this::showHealthCheck);
        menuRow(body, holder, "通知读取权限", "系统设置里的通知使用权", Ui.TEXT,
                () -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        menuRow(body, holder, "诊断日志", "最近 30 条读取记录", Ui.TEXT, this::showDiagLog);
        menuRow(body, holder, "存档与恢复", EngineHolder.storageIssue == null ? "本机存档与有效备份"
                : EngineHolder.storageIssue, EngineHolder.storageBlocked() ? Ui.RED : Ui.TEXT, this::showStorageState);
        menuSection(body, "识别");
        menuRow(body, holder, "AI 识别", AiConfig.load(this).enabled ? AiConfig.summary(this)
                        : "未开启 · 可选，帮规则找回漏掉的待办、补全时间地点", Ui.TEXT,
                () -> startActivity(new Intent(this, AiActivity.class)));
        menuRow(body, holder, "截图识别", "群里只发了图片？选截图，逐条转写成待办", Ui.TEXT,
                () -> startActivity(new Intent(this, ShotActivity.class).putExtra(ShotActivity.EXTRA_PICK, true)));
        menuSection(body, "事项");
        menuRow(body, holder, "到期提醒", ReminderReceiver.isEnabled(this) ? "已开启 · 到期前两小时提醒" : "已关闭",
                Ui.TEXT, this::showDeadlineReminderSettings);
        menuRow(body, holder, "按群管理", groupMenuHint(), Ui.TEXT, this::showGroupManager);
        menuRow(body, holder, "已移除的卡片", "误删的卡片可以在这里恢复", Ui.TEXT, this::showRemovedCards);
        menuRow(body, holder, "我的档案", "姓名、班级、课程，只存在本机", Ui.TEXT,
                () -> startActivity(new Intent(this, ProfileActivity.class)));
        menuRow(body, holder, "我的课表", "本科课表一键导入，离线查看每周课程", Ui.TEXT,
                () -> AppNavigation.openTimetable(this));
        menuRow(body, holder, "成绩与课表提醒", "出成绩、考试改期、课表变化、电量不足时通知", Ui.TEXT,
                this::showCampusAlerts);
        menuRow(body, holder, "桌面小组件", "把今天的课和最近的待办放到桌面", Ui.TEXT, () -> {
            if (!TodayWidget.requestPin(this)) {
                alert().setTitle("手动添加小组件")
                        .setMessage("这台手机的桌面不支持应用直接添加。请长按桌面空白处 → 小组件（或“安卓组件”），找到“楼外 / Offloor · 今日课程与待办”，拖到桌面。")
                        .setPositiveButton("知道了", null).show();
            }
        });
        menuSection(body, "演示");
        menuRow(body,holder,"情境演示","逐步体验改期、歧义核对和取消 · 独立构造场景",Ui.TEXT,
                () -> startActivity(new Intent(this,ShowcaseActivity.class)));
        menuRow(body, holder, demo == null ? "离线演示" : "退出演示", "用样例群消息体验作业、考试和接龙", Ui.TEXT, this::toggleDemo);
        menuRow(body, holder, "通知回放", "打开消息回放器，模拟真实通知", Ui.TEXT, this::startReplay);
        menuSection(body, "应用");
        menuRow(body, holder, "下载最新版", "当前版本 "+UpdateActivity.version(this)+" · 点击开始下载", Ui.TEXT,
                () -> startActivity(new Intent(this, UpdateActivity.class)));
        menuSection(body, null);
        menuRow(body, holder, "清空本机数据", "清空全部待办、已移除卡片和近期消息", Ui.RED, () ->
                alert()
                        .setTitle("清空待办和近期消息？")
                        .setMessage("会清空本机的全部待办、已移除卡片，以及这次运行中用于识别的近期消息。")
                        .setPositiveButton("清空", (dialog, w) -> {
                            engine.clearLocalData();
                            try { AiQueue.clear(this); }
                            catch (RuntimeException error) { EngineHolder.diag(this, "队列", "旧请求已失效，队列文件清理未成功"); }
                            boolean saved = EngineHolder.storageBlocked() ? EngineHolder.rebuildStorage(this) : EngineHolder.save(this);
                            if (!saved) Toast.makeText(this, "当前列表已清空，但存档未保存，请检查存档与恢复", Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());
        holder[0] = showSheet(body, null);
    }

    /** 「成绩与课表提醒」：同步后比较新旧数据，有变化才通知；第一次导入不提醒。 */
    void showStorageState() {
        android.app.AlertDialog.Builder dialog = alert().setTitle("存档与恢复")
                .setMessage(EngineHolder.storageIssue == null
                        ? "每次成功保存会保留上一份有效存档。读取时会核对完整性，损坏时尝试有效备份；没有可用备份时会暂停覆盖并保留原文件。"
                        : EngineHolder.storageIssue);
        if (EngineHolder.storageBlocked()) {
            dialog.setPositiveButton("保留副本并重建", (d, w) -> alert()
                    .setTitle("以当前列表建立新存档？")
                    .setMessage("会先保留原文件副本，再保存当前列表。当前未恢复的旧事项不会自动出现在新列表中。")
                    .setPositiveButton("保留副本并保存", (confirm, which) -> {
                        boolean ok = EngineHolder.rebuildStorage(this);
                        Toast.makeText(this, ok ? "已保存当前列表，原文件副本保留" : "保存未成功，原文件仍保留", Toast.LENGTH_LONG).show();
                    }).setNegativeButton("返回", null).show());
        }
        dialog.setNegativeButton("关闭", null).show();
    }

    void showCampusAlerts() {
        android.content.SharedPreferences p = CampusChanges.prefs(this);
        String[] keys = {"grades", "exams", "exam_cards", "timetable", "electric"};
        String[] labels = {"出新成绩或成绩变动", "考试安排新增或改期", "教务系统的考试自动放进待办", "课表有调整（同一学期内）", "宿舍电量不足"};
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = p.getBoolean(keys[i], true);
        android.widget.EditText threshold = new android.widget.EditText(this);
        threshold.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        threshold.setHint("电量提醒阈值（度）");
        threshold.setText(CampusChanges.trim((double) p.getFloat("electric_threshold", CampusChanges.DEFAULT_ELECTRIC)));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(this, 22), 0, dp(this, 22), 0);
        box.addView(threshold, lp(-1, -2));
        alert()
                .setTitle("成绩与课表提醒")
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    android.content.SharedPreferences.Editor e = p.edit();
                    for (int i = 0; i < keys.length; i++) e.putBoolean(keys[i], checked[i]);
                    Double v = ChangeDiff.firstNumber(threshold.getText().toString());
                    if (v != null && v >= 0 && v < 10000) e.putFloat("electric_threshold", v.floatValue()).putBoolean("electric_low", false);
                    e.apply();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    TextView sheetTitle(String text) {
        TextView t = uiText(text, 20, Ui.TEXT, false);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setPadding(dp(this, 4), dp(this, 10), 0, dp(this, 6));
        return t;
    }

    /** 当前正在往里放行的分组底板；menuSection 开新组，menuRow 往里加。 */
    private LinearLayout menuGroup;

    /** 开一个新分组：可选的栏目名 + 一块圆角底板。name 为 null 时只开底板（用来把危险操作单独隔开）。 */
    void menuSection(LinearLayout body, String name) {
        if (name != null) {
            TextView t = Ui.sectionLabel(this, name);
            t.setPadding(dp(this, 6), dp(this, 20), 0, dp(this, 8));
            body.addView(t);
        }
        menuGroup = Ui.group(this);
        LinearLayout.LayoutParams gLp = lp(-1, -2);
        if (name == null) gLp.topMargin = dp(this, 16);
        body.addView(menuGroup, gLp);
    }

    void menuRow(LinearLayout body, android.app.Dialog[] holder, String label, String hint, int color, Runnable action) {
        LinearLayout target = menuGroup != null && menuGroup.getParent() == body ? menuGroup : body;
        if (target != body && target.getChildCount() > 0) {
            View rule = new View(this);
            rule.setBackgroundColor(Ui.LINE);
            LinearLayout.LayoutParams ruleLp = lp(-1, 1);
            ruleLp.leftMargin = dp(this, 16);
            target.addView(rule, ruleLp);
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(this, 58));
        row.setPadding(dp(this, 16), dp(this, 11), dp(this, 12), dp(this, 11));
        row.setBackground(Ui.ripple(this, null, Ui.press(), 0));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView main = uiText(label, 15, color, true);
        text.addView(main);
        if (!TextUtils.isEmpty(hint)) {
            TextView h = uiText(hint, 12, color == Ui.TEXT ? Ui.MUTE : Ui.withAlpha(color, 200), false);
            h.setPadding(0, dp(this, 5), 0, 0);
            h.setLineSpacing(dp(this, 1), 1f);
            text.addView(h);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chev = new ImageView(this);
        chev.setImageDrawable(Ui.chevron(this, Ui.RING));
        LinearLayout.LayoutParams cLp = lp(dp(this, 16), dp(this, 16));
        cLp.leftMargin = dp(this, 8);
        row.addView(chev, cLp);
        row.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            action.run();
        });
        target.addView(row, lp(-1, -2));
    }

    /**
     * 通用底部面板：顶部圆角、从下往上滑入、最高占屏 88%，内容超出时可滚动，footer 固定在底部。
     */
    android.app.Dialog showSheet(View content, View footer) {
        return Ui.sheet(this, content, footer);
    }

    /**
     * 「自动读取自检」：把 HealthCheck 的结论原样摆在用户面前。
     * 没问题就给一句让人放心的确认；有问题就逐条列出，并只给最该点的那一个按钮。
     */
    void showHealthCheck() {
        // 四项输入全部来自真实状态：权限、服务连接、电池放行、最近一次收到消息的时间。
        HealthCheck.Result r = HealthCheck.evaluate(WatchdogJob.accessEnabled(this),
                EngineHolder.listenerConnected, WatchdogJob.batteryIgnored(this),
                EngineHolder.lastReceivedAt, System.currentTimeMillis());

        // 正文按「结论 + 保活说明」两个部分拼，先建好 StringBuilder。
        StringBuilder body = new StringBuilder();
        // 没有问题：用一句话说清三项都正常。
        if (r.issues.isEmpty()) {
            body.append("通知读取权限已开，服务已连接，电池不受限。");
        } else {
            // 有问题：每个问题占一行，前面带「· 」，顺序就是规则顺序。
            for (int i = 0; i < r.issues.size(); i++) {
                // 第一条不加换行，后面每条先换行再写。
                if (i > 0) body.append("\n");
                body.append("· ").append(r.issues.get(i).text);
            }
        }
        // 收到过消息才补一行「最近一次自动收到」，没收到过就不提，避免显示 0。
        if (EngineHolder.lastReceivedAt > 0) {
            body.append("\n最近一次自动收到：").append(ago(EngineHolder.lastReceivedAt));
        } else {
            body.append("\n尚未收到可读取的消息，可以发一条测试通知检查。");
        }
        body.append("\n\n仅能整理系统实际展示的通知文字。免打扰、折叠或被隐藏的消息，以及历史聊天和图片内部文字，不保证自动获取；可主动分享文字或截图补充。");
        // 保活说明固定接在正文末尾，原「让它一直在后台运行」的四行内容一字不改。
        body.append("\n\n保持自动读取不被系统杀掉：\n")
                .append("1. 在系统设置里允许「Offloor」自启动和后台运行（vivo：设置 > 应用与权限 > 权限管理 > 自启动）。\n")
                .append("2. 电池设置里把「Offloor」设为不限制或允许后台高耗电。\n")
                .append("3. 在最近任务里不要上滑清掉它；可以长按卡片锁定。\n")
                .append("4. 重启手机后打开一次 App，确认状态是「已连接」。");

        if (!EngineHolder.listenerConnected && WatchdogJob.accessEnabled(this)) {
            if (NotifRecovery.failed()) {
                body.append("\n\n自动恢复尚未成功。请在系统「通知使用权」里关闭再开启 Offloor，然后返回应用；无需清空数据或重新安装。");
            } else if (NotifRecovery.running()) {
                body.append("\n\n正在检查系统连接结果，请稍等；发出请求不代表已经连接。");
            }
        }
        // 三个问题标记先取出来，再决定两个按钮分别放哪个动作。
        boolean needAccess = hasFix(r, "NOTIF_ACCESS");
        boolean needRebind = hasFix(r, "REBIND");
        boolean needBattery = hasFix(r, "BATTERY");

        // 标题直接用 HealthCheck 给的一句话结论（正常 / 发现 N 个问题）。
        AlertDialog.Builder builder = alert()
                .setTitle(r.summary())
                .setMessage(body.toString())
                // 负按钮固定是「知道了」，只关窗不做动作。
                .setNegativeButton("知道了", null);

        // 正按钮只放一个，按权限 > 重连 > 电池的优先级依次降级。
        if (needAccess) {
            builder.setPositiveButton("去开启", (d, w) -> {
                try {
                    // 跳到系统的通知使用权页面，用户在那里打开本 App 的开关。
                    startActivity(new Intent(
                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Exception ignored) {
                    // 个别设备没有这个页面，给提示不让点击崩掉。
                    Toast.makeText(this, "这台手机没有通知使用权页面，请到系统设置里手动找",
                            Toast.LENGTH_LONG).show();
                }
            });
        } else if (needRebind) {
            if (NotifRecovery.failed()) {
                builder.setPositiveButton("重开通知权限", (d, w) -> {
                    retryNotifOnResume = true;
                    try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
                    catch (Exception e) {
                        Toast.makeText(this, "请到系统设置的通知使用权中，关闭再开启 Offloor", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                builder.setPositiveButton("重新连接", (d, w) -> {
                    NotifRecovery.request(this, true);
                    Toast.makeText(this, "正在恢复通知读取，将检查连接结果", Toast.LENGTH_SHORT).show();
                });
            }
        } else if (needBattery) {
            builder.setPositiveButton("去电池设置", (d, w) -> openBatterySettings());
        }

        // 电池问题不是正按钮时，降级放到中性按钮，保证两个按钮以内还能处理它。
        if (needBattery && (needAccess || needRebind)) {
            builder.setNeutralButton("电池设置", (d, w) -> openBatterySettings());
        }

        // 一次性弹出完整结论。
        builder.show();
    }

    /** 结果里是否含某个处理标记；fix 可能是 null，这里一律按字符串比较避免空指针。 */
    boolean hasFix(HealthCheck.Result r, String fix) {
        // 逐条比对 fix 字段，命中就返回 true。
        for (int i = 0; i < r.issues.size(); i++) {
            // equals 的方向固定成常量在前，null 也不会抛异常。
            if (fix.equals(r.issues.get(i).fix)) return true;
        }
        // 一条都没命中说明不需要这个动作。
        return false;
    }

    /** 打开系统的电池优化页面；没有这个页面的设备给一句提示，不让点击崩掉。 */
    void openBatterySettings() {
        // 直接弹系统授权框；不支持时 SetupGuide 会退回电池优化列表或应用详情页。
        SetupGuide.requestBattery(this);
    }

    /** 相对时间：自检里沿用状态条那套写法，两处口径保持一致。 */
    String ago(long ms) {
        // 直接复用主界面的 relativeTime，避免出现两种说法。
        return relativeTime(ms);
    }

    /**
     * 「诊断日志」：最近 30 条自动读取的流水，配合顶部累计计数，
     * 用户和评委不用连电脑就能看出是哪一步卡住了。
     */
    void showDiagLog() {
        // 最近 30 条，最新的在前。
        List<DiagLog.Entry> list = DiagLog.recent(30);
        // 时间只显示到秒，日志看的是顺序不是日期。
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // 正文整体放进一个 TextView，用等宽字保证每行对得齐。
        StringBuilder body = new StringBuilder();
        // 第一行固定是各类累计数，不受 50 条容量限制。
        body.append("累计 收到 ").append(DiagLog.count("收到"))
                .append(" / 跳过 ").append(DiagLog.count("跳过"))
                .append(" / 出错 ").append(DiagLog.count("出错"))
                .append(" / 连接 ").append(DiagLog.count("连接"))
                .append(" / 断开 ").append(DiagLog.count("断开"))
                .append(" / 重连 ").append(DiagLog.count("重连"));
        // 一条日志都没有时只留一句说明，不留空白页。
        if (list.isEmpty()) {
            body.append("\n\n还没有记录");
        } else {
            // 逐条拼成「时间 类别 明细」的等宽一行。
            for (int i = 0; i < list.size(); i++) {
                // 取出这一条日志。
                DiagLog.Entry e = list.get(i);
                // 明细里的换行会把一行撑成多行，先压成一个空格。
                String detail = e.detail == null ? "" : e.detail.replace('\n', ' ').replace('\r', ' ');
                // 太长就截断，后面加省略号，保证一行能读完。
                if (detail.length() > 40) detail = detail.substring(0, 40) + "…";
                // 每条日志单独占一行，时间、类别、明细之间各空一格。
                body.append('\n').append(fmt.format(new java.util.Date(e.time)))
                        .append(' ').append(e.kind).append(' ').append(detail);
            }
        }

        // 等宽小字：日志对齐才好扫，字号压到 12 不抢注意力。
        TextView text = new TextView(this);
        text.setText(body.toString());
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        // 四周 20dp 内边距，和别的弹窗保持同一种呼吸感。
        text.setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 20));
        // 日志可能很长，套一层 ScrollView 让它自己滚。
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        // 学习平台通知的完整原文：用来校准解析规则，可一键复制发给开发者。只存本机。
        StringBuilder samples = new StringBuilder();
        for (DiagLog.Entry e : DiagLog.recent(50)) {
            if (!"平台".equals(e.kind)) continue;
            if (samples.length() > 0) samples.append("\n\n");
            samples.append(new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date(e.time)))
                    .append('\n').append(e.detail);
        }
        if (samples.length() > 0) {
            text.append("\n\n—— 学习平台通知原文 ——\n" + samples);
        }

        AlertDialog.Builder b = alert()
                .setTitle("诊断日志")
                .setView(scroll)
                .setPositiveButton("关闭", null);
        if (samples.length() > 0) {
            String copied = samples.toString();
            b.setNeutralButton("复制平台原文", (d, w) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("学习平台通知原文", copied));
                    Toast.makeText(this, "已复制学习平台通知原文", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }

    @Override
    public void onCardsChanged() {
        runOnUiThread(() -> {
            if (!resumed || renderQueued) return;
            renderQueued = true;
            uiHandler.postDelayed(queuedRender, 80);
        });
    }

    void render() {
        if (cardList == null) return;
        uiHandler.removeCallbacks(queuedRender);
        uiHandler.removeCallbacks(searchRefresh);
        renderQueued = false;
        long now = System.currentTimeMillis();
        if (sub != null) sub.setText(subLine());
        cardList.removeAllViews();
        dueBindings.clear();
        boolean stagger = firstRender;
        firstRender = false;
        Engine.Card enter = animateInCard;
        animateInCard = null;
        Engine shown = demo == null ? engine : demo.engine;
        updateHeadline(shown, now);
        updateWindowTabs(shown, now);
        // 开发者统计：长按大标题查看。
        View.OnLongClickListener devStats = v -> {
            Toast.makeText(MainActivity.this, "处理 " + shown.nIn + " 条 · 丢弃噪声 " + shown.nNoise
                            + " · 进模型 " + shown.nLevel3 + " · 待办 " + shown.all().size()
                            + (shown.nPromoted > 0 ? (" · 回复潮提升 " + shown.nPromoted) : ""),
                    Toast.LENGTH_LONG).show();
            return true;
        };
        headline.setOnLongClickListener(devStats);
        stats.setOnLongClickListener(devStats);
        renderedDay = localDay(now);
        updateClashBanner(shown, now);
        if (demo == null) {
            updateNotifStatus();
        } else {
            notifStatus.setText(statusText("离线演示", Ui.ACCENT));
            notifStatus.setTextColor(Ui.ACCENT);
            notifStatus.setBackground(Ui.ripple(this, Ui.round(this, Ui.ACCENT_BG, 15), Ui.press(), 15));
        }
        if (liveTools != null) {
            boolean needSetup = demo == null && !EngineHolder.listenerConnected;
            liveTools.setVisibility(needSetup ? View.VISIBLE : View.GONE);
            if (needSetup) autoReadButton.setText(WatchdogJob.accessEnabled(this) ? "检查自动读取" : "开启自动读取");
        }

        List<Engine.Card> list = demo == null
                ? shown.queryCards(keywords, scope, window, visibleLimit + 1, now)
                : shown.ranked(PAGE_SIZE, now);
        if (list.isEmpty()) {
            boolean upcomingOnly = keywords.trim().isEmpty() && scope == Engine.CardScope.ALL
                    && window == Engine.CardWindow.UPCOMING;
            // 默认的「今天起」不算用户主动筛选：一张卡都没有时照常显示开启读取/等待消息的说明。
            boolean filtered = !keywords.trim().isEmpty() || scope != Engine.CardScope.ALL
                    || (window != Engine.CardWindow.ANY && !(upcomingOnly && shown.all().isEmpty()));
            if (demo != null) {
                emptyIcon.setBackground(new Ui.CheckCircle(this, Ui.ACCENT, Ui.ACCENT, 52, true));
                emptyTitle.setText("演示卡片已清理");
                empty.setText("退出演示后可以重新体验。");
            } else if (upcomingOnly && filtered) {
                emptyIcon.setBackground(new Ui.CheckCircle(this, Ui.GREEN, Ui.GREEN, 52, true));
                emptyTitle.setText("今天起没有要做的事");
                empty.setText("已过期、没写日期和已完成的事项在「全部」里。\n漏了消息可以点「扫描历史」补一遍。");
            } else if (filtered) {
                emptyIcon.setBackground(emptySearchIcon());
                emptyTitle.setText("没有找到");
                empty.setText("换个关键词，或者切回「全部」看看。");
            } else if (EngineHolder.listenerConnected) {
                emptyIcon.setBackground(new Ui.CheckCircle(this, Ui.GREEN, Ui.GREEN, 52, true));
                emptyTitle.setText("暂时没有要做的事");
                empty.setText("自动读取已开启。\n群里有作业、考试、接龙时，会自动出现在这里。\n通知栏里已有的消息可以点「扫描历史」读一遍。");
            } else {
                emptyIcon.setBackground(emptySearchIcon());
                emptyTitle.setText("还没有待办");
                empty.setText("开启自动读取后，群里的作业、考试、接龙\n会自动整理到这里，不用再爬楼。");
            }
            cardList.addView(emptyBox);
            if (!emptyShown) {
                // 只在从“有卡”变成“空”时淡入，后台刷新不让空状态反复闪。
                emptyBox.setAlpha(0f);
                emptyBox.setTranslationY(dp(this, 8));
                emptyBox.animate().alpha(1f).translationY(0f).setDuration(240)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
            emptyShown = true;
            return;
        }
        emptyShown = false;
        int displayed = Math.min(visibleLimit, list.size());
        Map<String, List<Engine.Card>> groups = new HashMap<>();
        for (int i = 0; i < displayed; i++) {
            Engine.Card card = list.get(i);
            String name = TodayView.bucket(card.deadline, card.needsConfirm, card.done, now);
            groups.computeIfAbsent(name, key -> new ArrayList<>()).add(card);
        }
        int order = 0;
        for (int g = 0; g < TodayView.ORDER.length; g++) {
            String groupName = TodayView.ORDER[g];
            List<Engine.Card> group = groups.get(groupName);
            if (group == null) continue;
            View header = groupHeader(groupName, group.size());
            cardList.addView(header);
            if (stagger) enterAnim(header, order++);
            for (Engine.Card card : group) {
                View v = cardView(card, now);
                cardList.addView(v);
                if (stagger) enterAnim(v, order++);
                else if (card == enter) popIn(v);
            }
        }
        if (list.size() > displayed) {
            Button moreBtn = button("显示更多", v -> {
                int y = listScroll.getScrollY();
                visibleLimit += PAGE_SIZE;
                render();
                listScroll.post(() -> listScroll.scrollTo(0, y));
            });
            LinearLayout.LayoutParams moreLp = lp(-1, dp(this, 46));
            moreLp.topMargin = dp(this, 8);
            cardList.addView(moreBtn, moreLp);
        }
    }

    private Drawable emptySearchIcon() {
        GradientDrawable bg = Ui.disc(this, Ui.FILL);
        Drawable glyph = Ui.searchGlyph(this, Ui.SUB, 24);
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(new Drawable[]{bg, glyph});
        int inset = dp(this, 14);
        layer.setLayerInset(1, inset, inset, inset, inset);
        return layer;
    }

    /** 点击撞车横幅：逐行列出这一组里每张卡的标题和截止时间。 */
    void showClashDetail() {
        // 用和 render() 相同的数据源（演示模式取 demo 引擎），结果与横幅一致。
        Engine shown = demo == null ? engine : demo.engine;
        ClashPlan.Clash clash = ClashPlan.nearest(shown.all(), System.currentTimeMillis());
        // 卡片可能在渲染之后被删掉或改了时间，这时已经不存在撞车，收掉横幅即可。
        if (clash == null) {
            clashBanner.setVisibility(View.GONE);
            return;
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < clash.cards.size(); i++) {
            Engine.Card c = clash.cards.get(i);
            // 每张卡一行：标题 + 两个空格 + 自然语言截止时间。
            if (i > 0) body.append("\n");
            body.append("· ").append(c.displayTitle()).append("  ")
                    .append(Engine.humanDeadline(c.deadline));
        }
        alert()
                .setTitle("这几件事挤在一起")
                .setMessage(body.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * 分组标题：组名 + 数量 + 一条延伸到右边的细线，像杂志里的栏目分隔。
     * 「已过期」标红、「待确认」标琥珀、「今天」前加一个墨水蓝小圆点，其余墨色。
     */
    View groupHeader(String name, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int color = "已过期".equals(name) ? Ui.RED : "待确认".equals(name) ? Ui.AMBER_TX
                : "已完成".equals(name) ? Ui.MUTE : Ui.TEXT;
        if ("今天".equals(name) || "已过期".equals(name) || "待确认".equals(name)) {
            View dot = new View(this);
            dot.setBackground(Ui.disc(this, "今天".equals(name) ? Ui.ACCENT : color));
            LinearLayout.LayoutParams dotLp = lp(dp(this, 6), dp(this, 6));
            dotLp.rightMargin = dp(this, 8);
            row.addView(dot, dotLp);
        }
        TextView label = uiText(name, 15, color, true);
        row.addView(label);
        TextView num = uiText(String.valueOf(count), 13, Ui.MUTE, true);
        LinearLayout.LayoutParams numLp = lp(-2, -2);
        numLp.leftMargin = dp(this, 7);
        row.addView(num, numLp);
        View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(0, 1, 1);
        ruleLp.leftMargin = dp(this, 12);
        row.addView(rule, ruleLp);
        row.setContentDescription(name + "，" + count + " 件");
        LinearLayout.LayoutParams lp = lp(-1, -2);
        lp.leftMargin = dp(this, 4);
        lp.topMargin = dp(this, 26);
        lp.bottomMargin = dp(this, 12);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 一张事项卡：左边完成圈（颜色 = 时间压力），右边三行信息：
     * 标题 / 截止（类型标签 + 倒计时）/ 来源（应用图标 · 群名 · 发送者），必要时补一行提交方式、地点、携带物。
     * 点卡片看详情，长按修正，点圆圈完成（带动画和撤销）。
     */
    View cardView(Engine.Card c, long now) {
        int urgency = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);
        boolean unsure = c.needsConfirm || c.deadline == 0 || c.deadlineHint() != null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(this, 6), dp(this, 12), dp(this, 16), dp(this, 12));
        if (c.done) {
            // 已完成的卡退到背景里：不浮起、不投影，只留一层淡底。
            GradientDrawable face = Ui.round(this, Ui.withAlpha(Ui.CARD, Ui.dark ? 140 : 150), 20);
            card.setBackground(Ui.ripple(this, face, Ui.press(), 20));
        } else {
            Ui.cardSurface(card, 20);
        }
        Ui.pressScale(card, 0.985f);

        int ringColor = c.done ? Ui.MUTE : unsure ? Ui.AMBER_TX
                : urgency >= 1 ? Ui.urgencyColor(urgency) : Ui.RING;
        Ui.CheckCircle circle = new Ui.CheckCircle(this, ringColor, c.done ? Ui.MUTE : Ui.GREEN, 22, c.done);
        FrameLayout checkZone = new FrameLayout(this);
        View check = new View(this);
        check.setBackground(circle);
        checkZone.addView(check, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        checkZone.setBackground(Ui.rippleCircle(this, Ui.press()));
        checkZone.setContentDescription((c.done ? "恢复待办：" : "标记完成：") + c.displayTitle());
        LinearLayout.LayoutParams checkLp = lp(dp(this, 48), dp(this, 48));
        // Align the circle with the title without drawing the touch target outside the card.
        checkLp.topMargin = 0;
        card.addView(checkZone, checkLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        if (c.done) body.setAlpha(0.6f);

        TextView title = uiText(c.displayTitle(), 16, c.done ? Ui.SUB : Ui.TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(dp(this, 2), 1f);
        title.setLetterSpacing(0.005f);
        if (c.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        body.addView(title);

        if (!c.done) {
            LinearLayout dueRow = new LinearLayout(this);
            dueRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView kindTag = kindTag(c.kind);
            if (kindTag != null) {
                LinearLayout.LayoutParams kLp = lp(-2, -2);
                kLp.rightMargin = dp(this, 7);
                dueRow.addView(kindTag, kLp);
            }
            TextView due = uiText(dueLabel(c, now), 13, dueColor(c, now), true);
            due.setSingleLine(true);
            due.setEllipsize(TextUtils.TruncateAt.END);
            dueRow.addView(due, new LinearLayout.LayoutParams(0, -2, 1));
            if (!unsure && c.deadline > 0) {
                dueBindings.add(new DueBinding(c, due,
                        TodayView.bucket(c.deadline, c.needsConfirm, c.done, now), urgency));
            }
            if (c.updated) {
                TextView upd = Ui.tag(this, "已更新", Ui.ACCENT, Ui.ACCENT_BG);
                LinearLayout.LayoutParams uLp = lp(-2, -2);
                uLp.leftMargin = dp(this, 6);
                dueRow.addView(upd, uLp);
            }
            LinearLayout.LayoutParams dueLp = lp(-1, -2);
            dueLp.topMargin = dp(this, 6);
            body.addView(dueRow, dueLp);
        }

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.addView(sourceMark(c, 15));
        TextView metaLine = uiText(joinDistinct(sourceName(c), c.groupName, c.sender), 12, Ui.MUTE, false);
        metaLine.setSingleLine(true);
        metaLine.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(0, -2, 1);
        mtLp.leftMargin = dp(this, 6);
        meta.addView(metaLine, mtLp);
        LinearLayout.LayoutParams metaLp = lp(-1, -2);
        metaLp.topMargin = dp(this, 8);
        body.addView(meta, metaLp);

        List<String> extra = new ArrayList<>();
        if (!TextUtils.isEmpty(c.channel) && !"jielong".equals(c.kind)) extra.add("提交到 " + c.channel);
        if ("exam".equals(c.kind) && !TextUtils.isEmpty(c.place)) extra.add("地点 " + c.place);
        if ("exam".equals(c.kind) && !TextUtils.isEmpty(c.bring)) extra.add("带 " + c.bring);
        if (!extra.isEmpty() && !c.done) {
            TextView ex = uiText(TextUtils.join("  ·  ", extra), 12, Ui.SUB, false);
            ex.setMaxLines(2);
            ex.setEllipsize(TextUtils.TruncateAt.END);
            ex.setLineSpacing(dp(this, 2), 1f);
            LinearLayout.LayoutParams exLp = lp(-1, -2);
            exLp.topMargin = dp(this, 4);
            body.addView(ex, exLp);
        }
        if (!c.done && !TextUtils.isEmpty(c.reason) && c.deadlineHint() == null) {
            TextView reason = uiText(c.reason, 12, Ui.AMBER_TX, false);
            reason.setMaxLines(2);
            reason.setEllipsize(TextUtils.TruncateAt.END);
            reason.setPadding(dp(this, 10), dp(this, 5), dp(this, 10), dp(this, 5));
            reason.setBackground(Ui.round(this, Ui.AMBER_BG, 10));
            LinearLayout.LayoutParams rLp = lp(-2, -2);
            rLp.topMargin = dp(this, 6);
            body.addView(reason, rLp);
        }
        card.addView(body, new LinearLayout.LayoutParams(0, -2, 1));

        checkZone.setOnClickListener(v -> {
            if (c.done) toggleDone(c);
            else animateComplete(card, circle, body, c);
        });
        card.setOnClickListener(v -> showSource(c));
        card.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showCardEdit(c);
            return true;
        });
        LinearLayout.LayoutParams cardLp = lp(-1, -2);
        cardLp.bottomMargin = dp(this, 8);
        card.setLayoutParams(cardLp);
        return card;
    }

    /** 卡片类型标签：考试紫、接龙青，普通作业不加标签。 */
    TextView kindTag(String kind) {
        if ("exam".equals(kind)) return Ui.tag(this, "考试", Ui.VIOLET, Ui.VIOLET_BG);
        if ("jielong".equals(kind)) return Ui.tag(this, "接龙", Ui.TEAL, Ui.TEAL_BG);
        if (Engine.PlatformNotice.KIND_SIGNIN.equals(kind)) return Ui.tag(this, "签到", Ui.ORANGE, Ui.ORANGE_BG);
        if (Engine.PlatformNotice.KIND_GRADE.equals(kind)) return Ui.tag(this, "成绩", Ui.GREEN, Ui.GREEN_BG);
        if ("media".equals(kind)) return Ui.tag(this, "图片/语音", Ui.ORANGE, Ui.ORANGE_BG);
        return null;
    }

    /** 卡片第二行的时间文字：与详情面板同一套说法。 */
    private String dueLabel(Engine.Card c, long now) {
        if ("media".equals(c.kind)) return "内容读不到 · " + (c.sourceText != null && c.sourceText.contains("[图片]")
                ? "截图分享给楼外可识别" : "请到原群查看");
        return c.deadlineHint() != null ? c.deadlineHint() + " · 时刻待确认"
                : c.deadline == 0 ? "时间待确认 · 点开补充"
                : Engine.humanDeadline(c.deadline) + " · " + TodayView.countdown(c.deadline, now);
    }

    private int dueColor(Engine.Card c, long now) {
        boolean unsure = c.needsConfirm || c.deadline == 0 || c.deadlineHint() != null;
        int urgency = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);
        return unsure ? Ui.AMBER_TX : urgency >= 1 ? Ui.urgencyColor(urgency) : Ui.SUB;
    }

    /**
     * 每分钟一次的轻量刷新：只改倒计时文字、顶部日期和大标题。
     * 某张卡跨了分组（今天 → 已过期）或紧急程度变了，才整页重排。
     */
    private void tickClock() {
        if (cardList == null) return;
        long now = System.currentTimeMillis();
        for (DueBinding b : dueBindings) {
            Engine.Card c = b.card;
            if (c.done || c.deadline <= 0
                    || !b.bucket.equals(TodayView.bucket(c.deadline, c.needsConfirm, c.done, now))
                    || b.urgency != TodayView.urgency(c.deadline, c.needsConfirm, c.done, now)) {
                onCardsChanged();
                return;
            }
        }
        if (localDay(now) != renderedDay) {
            // 跨午夜：今天/明天分组、筛选结果都变了，整页重排。
            onCardsChanged();
            return;
        }
        for (DueBinding b : dueBindings) {
            String text = dueLabel(b.card, now);
            if (!text.contentEquals(b.due.getText())) b.due.setText(text);
        }
        if (sub != null) sub.setText(subLine());
        Engine shown = demo == null ? engine : demo.engine;
        updateHeadline(shown, now);
        updateWindowTabs(shown, now);
        updateClashBanner(shown, now);
        if (demo == null) updateNotifStatus();
    }

    private static int localDay(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
    }

    private void updateClashBanner(Engine shown, long now) {
        ClashPlan.Clash clash = ClashPlan.nearest(shown.all(), now);
        if (clash != null) {
            clashBanner.setText("撞车提醒 · " + clash.text() + "  ›");
            clashBanner.setVisibility(View.VISIBLE);
        } else {
            clashBanner.setVisibility(View.GONE);
        }
    }

    /** 首屏入场：逐张从下方 14dp 浮入，间隔 28ms，最多错开 8 张。 */
    private void enterAnim(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(dp(this, 14));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(index, 8) * 28L)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** 撤销 / 恢复回来的卡：从略小淡入，提示“它回来了”。 */
    private void popIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
        // 回来的卡不在屏内时滚过去，免得用户以为撤销没生效。
        v.post(() -> {
            if (listScroll == null) return;
            int[] at = new int[2];
            int[] sc = new int[2];
            v.getLocationInWindow(at);
            listScroll.getLocationInWindow(sc);
            int top = at[1] - sc[1];
            if (top < 0 || top + v.getHeight() > listScroll.getHeight()) {
                listScroll.smoothScrollBy(0, top - listScroll.getHeight() / 3);
            }
        });
    }

    /**
     * 点完成圈：圆圈填充 + 对勾描出（280ms）→ 整张卡向右淡出并收起高度（260ms），
     * 下面的卡顺势补上来，再真正标记并给出撤销。不再“啪”地整屏重排。
     */
    void animateComplete(View card, Ui.CheckCircle circle, View body, Engine.Card c) {
        if (Boolean.TRUE.equals(card.getTag())) return;
        card.setTag(Boolean.TRUE);
        card.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        pendingDone.add(c);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(280);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> circle.setProgress((float) a.getAnimatedValue()));
        anim.start();
        body.animate().alpha(0.45f).setDuration(240).start();
        Runnable finish = () -> {
            pendingDone.remove(c);
            if (isFinishing() || isDestroyed()) return;
            if (!c.done) toggleDone(c);
            else render();   // 已在 onPause 里落定：重排一次，收掉压扁的卡
        };
        uiHandler.postDelayed(() -> {
            if (c.done || !card.isAttachedToWindow()) finish.run();
            else collapse(card, finish);
        }, 330);
    }

    /** 卡片向右滑出 + 高度和下边距收到 0，结束后执行 end。 */
    private void collapse(View card, Runnable end) {
        final int h = card.getHeight();
        final ViewGroup.LayoutParams raw = card.getLayoutParams();
        final LinearLayout.LayoutParams p = raw instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) raw : null;
        final int margin = p == null ? 0 : p.bottomMargin;
        card.animate().alpha(0f).translationX(dp(this, 36)).setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
        ValueAnimator shrink = ValueAnimator.ofFloat(1f, 0f);
        shrink.setStartDelay(90);
        shrink.setDuration(240);
        shrink.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        shrink.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            raw.height = Math.max(1, (int) (h * f));
            if (p != null) p.bottomMargin = (int) (margin * f);
            card.setLayoutParams(raw);
        });
        shrink.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean done;
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (done) return;
                done = true;
                end.run();
            }
        });
        shrink.start();
    }

    /** 来源小图标：应用真实图标优先，取不到用品牌色文字徽标。 */
    View sourceMark(Engine.Card c, float sizeDp) {
        return sourceMark(sourceName(c), c.sourcePackageName(), sizeDp);
    }

    /** 包名对应的来源名称（与卡片上的显示一致）。 */
    String appNameFor(String pkg) {
        Engine.Card probe = new Engine.Card();
        probe.sourcePackage = pkg;
        return sourceName(probe);
    }

    View sourceMark(String name, String pkg, float sizeDp) {
        pkg = pkg == null ? "" : pkg.trim();
        int size = dp(this, sizeDp);
        Drawable icon = sourceIcon(pkg);
        if (icon != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(Ui.round(this, Ui.CARD, 4));
            image.setClipToOutline(true);
            image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            return image;
        }
        TextView badge = new TextView(this);
        badge.setText(sourceBadge(name, pkg));
        badge.setTextSize(sizeDp * 0.55f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setBackground(Ui.round(this, sourceBadgeColor(name, pkg), 4));
        badge.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return badge;
    }

    /**
     * 来源应用名：只信引擎/main 侧给出的真实名称，空值统一显示「来源待确认」，
     * 不按包名猜应用名，避免把系统通知误标成某个具体平台。
     */
    /** “来源 · 群 · 发送者”：空的和与前面重复的跳过（教务系统卡三处都是“教务系统”）。 */
    static String joinDistinct(String... parts) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (!out.contains(p.trim())) out.add(p.trim());
        }
        return TextUtils.join(" · ", out);
    }

    String sourceName(Engine.Card c) {
        String name = c.sourceAppName();
        return TextUtils.isEmpty(name) || name.trim().isEmpty() ? "来源待确认" : name.trim();
    }

    /**
     * 图标读取：成功只缓存 ConstantState，每张卡各自 newDrawable().mutate()；
     * 取不到图标或包名无效也记入 miss，后续 render 不再重复查询 PackageManager。
     */
    Drawable sourceIcon(String pkg) {
        if (TextUtils.isEmpty(pkg)) return null;
        Drawable.ConstantState state = sourceIconCache.get(pkg);
        if (state == null && !sourceIconMiss.contains(pkg)) {
            try {
                Drawable appIcon = getPackageManager().getApplicationIcon(pkg);
                state = appIcon == null ? null : appIcon.getConstantState();
            } catch (Exception ignored) {
                state = null;
            }
            if (state == null) {
                sourceIconMiss.add(pkg);
                return null;
            }
            sourceIconCache.put(pkg, state);
        }
        if (state == null) return null;
        try {
            Drawable drawable = state.newDrawable(getResources());
            return drawable == null ? null : drawable.mutate();
        } catch (Exception ignored) {
            // 缓存里的 ConstantState 失效时清掉并记 miss，下一轮不再反复失败。
            sourceIconCache.remove(pkg);
            sourceIconMiss.add(pkg);
            return null;
        }
    }

    /** 图标不可用时的文字徽标：微信/QQ/飞书/钉钉/TIM 用各自品牌字，其余取名称首字。 */
    String sourceBadge(String name, String pkg) {
        String key = (name + " " + pkg).toLowerCase(Locale.ROOT);
        if (key.contains("微信") || key.contains("wechat") || key.contains("com.tencent.mm")) return "微";
        if (key.contains("飞书") || key.contains("lark") || key.contains("feishu")) return "飞";
        if (key.contains("钉钉") || key.contains("dingtalk") || key.contains("rimet")) return "钉";
        if (key.contains("tim") || key.contains("com.tencent.tim")) return "T";
        if (key.contains("qq") || key.contains("com.tencent.mobileqq")) return "Q";
        if (key.contains("学习通") || key.contains("chaoxing")) return "学";
        if (key.contains("知到") || key.contains("wisdomtree")) return "知";
        if (key.contains("雨课堂") || key.contains("xuetangx")) return "雨";
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "源" : trimmed.substring(0, 1);
    }

    /** 徽标品牌色：只管图标缺失时的辨识度，不参与正文配色。 */
    int sourceBadgeColor(String name, String pkg) {
        String key = (name + " " + pkg).toLowerCase(Locale.ROOT);
        if (key.contains("微信") || key.contains("wechat") || key.contains("com.tencent.mm")) return Ui.GREEN;
        if (key.contains("飞书") || key.contains("lark") || key.contains("feishu")) return Color.parseColor("#5B5BD6");
        if (key.contains("钉钉") || key.contains("dingtalk") || key.contains("rimet")) return Color.parseColor("#2E7CF6");
        if (key.contains("tim") || key.contains("com.tencent.tim")) return Color.parseColor("#0FA9C9");
        if (key.contains("qq") || key.contains("com.tencent.mobileqq")) return Color.parseColor("#2B7DE9");
        if (key.contains("学习通") || key.contains("chaoxing")) return Color.parseColor("#E8452C");
        if (key.contains("知到") || key.contains("wisdomtree")) return Color.parseColor("#1A9E6A");
        if (key.contains("雨课堂") || key.contains("xuetangx")) return Color.parseColor("#2F6BFF");
        return Ui.BLUE;
    }

    /**
     * 事项详情（底部面板）：来源 → 标题 → 截止时间块 → 关键信息 → 快捷操作 → 原始消息，
     * 底部固定一个「标记完成」主按钮。
     */
    void showSource(Engine.Card c) {
        android.app.Dialog[] holder = new android.app.Dialog[1];
        long now = System.currentTimeMillis();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(sourceMark(c, 18));
        TextView from = uiText(joinDistinct(sourceName(c), c.groupName), 13, Ui.SUB, true);
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fromLp = new LinearLayout.LayoutParams(0, -2, 1);
        fromLp.leftMargin = dp(this, 8);
        head.addView(from, fromLp);
        TextView close = uiText("×", 22, Ui.SUB, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭详情");
        close.setBackground(Ui.ripple(this, Ui.disc(this, Ui.FILL), Ui.press(), 18));
        close.setOnClickListener(v -> holder[0].dismiss());
        head.addView(close, lp(dp(this, 36), dp(this, 36)));
        LinearLayout.LayoutParams headLp = lp(-1, -2);
        headLp.topMargin = dp(this, 12);
        content.addView(head, headLp);

        TextView kindTag = kindTag(c.kind);
        if (kindTag != null) {
            LinearLayout.LayoutParams kLp = lp(-2, -2);
            kLp.topMargin = dp(this, 18);
            content.addView(kindTag, kLp);
        }
        TextView title = uiText(c.displayTitle(), 22, Ui.TEXT, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLineSpacing(dp(this, 4), 1f);
        title.setTextIsSelectable(true);
        LinearLayout.LayoutParams titleLp = lp(-1, -2);
        titleLp.topMargin = dp(this, kindTag == null ? 18 : 10);
        content.addView(title, titleLp);

        // 截止时间块
        String deadline = c.deadlineHint() != null ? c.deadlineHint() + " · 时刻待确认"
                : c.deadline == 0 ? "时间待确认" : (c.needsConfirm ? "候选时间：" : "") + dateTimeLabel(c.deadline);
        boolean unsure = c.needsConfirm || c.deadline == 0 || c.deadlineHint() != null;
        int urgency = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);
        int tone = c.done ? Ui.SUB : unsure ? Ui.AMBER_TX : urgency >= 1 ? Ui.urgencyColor(urgency) : Ui.ACCENT;
        int toneBg = c.done ? Ui.FILL : unsure ? Ui.AMBER_BG : urgency >= 1 ? Ui.urgencyBg(urgency) : Ui.ACCENT_BG;
        LinearLayout dueBox = new LinearLayout(this);
        dueBox.setOrientation(LinearLayout.VERTICAL);
        dueBox.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        dueBox.setBackground(Ui.round(this, toneBg, 16));
        dueBox.addView(uiText(c.done ? "已完成" : "exam".equals(c.kind) ? "考试时间" : "截止时间", 12, Ui.withAlpha(tone, 200), true));
        TextView dueValue = uiText(deadline, 18, tone, true);
        dueValue.setPadding(0, dp(this, 6), 0, 0);
        dueBox.addView(dueValue);
        if (!c.done && !unsure && c.deadline > 0) {
            TextView cd = uiText(TodayView.countdown(c.deadline, now), 13, tone, false);
            cd.setPadding(0, dp(this, 4), 0, 0);
            dueBox.addView(cd);
        }
        if (unsure && !TextUtils.isEmpty(c.reason)) {
            TextView why = uiText(c.reason, 12, tone, false);
            why.setLineSpacing(dp(this, 2), 1f);
            why.setPadding(0, dp(this, 6), 0, 0);
            dueBox.addView(why);
        }
        LinearLayout.LayoutParams dueLp = lp(-1, -2);
        dueLp.topMargin = dp(this, 18);
        content.addView(dueBox, dueLp);

        Engine shown = demo == null ? engine : demo.engine;
        if (unsure && shown.all().contains(c)) {
            Button editNow = button("核对标题和时间", v -> { holder[0].dismiss(); showCardEdit(c); });
            LinearLayout.LayoutParams editLp = lp(-1, dp(this, 44)); editLp.topMargin = dp(this, 10);
            content.addView(editNow, editLp);
            if (c.deadline > 0 && !c.changes.hasPending()) {
                Button confirmTime = button("确认这个候选时间", v -> {
                    if (shown.editCard(c, c.displayTitle(), c.deadline)) {
                        if (demo == null) EngineHolder.saveAsync(this);
                        holder[0].dismiss(); showSource(c);
                    }
                });
                content.addView(confirmTime, lp(-1, dp(this, 44)));
            }
        }
        for (Engine.Msg change : c.changes.pending()) {
            TextView changeText = uiText("待关联的变更 · " + (change.sender == null ? "来源未知" : change.sender)
                    + "\n" + change.text, 13, Ui.AMBER_TX, false);
            changeText.setPadding(0, dp(this, 16), 0, dp(this, 8));
            changeText.setTextIsSelectable(true); content.addView(changeText);
            Button applyChange = button("revoke".equals(Engine.detectRevision(change.text))
                    ? "按这条通知取消本事项" : "这条变更对应本事项", v -> {
                if (shown.resolvePendingChange(c, change.id)) {
                    if (demo == null) EngineHolder.saveAsync(this);
                    holder[0].dismiss();
                    if (shown.all().contains(c)) showSource(c);
                    else Toast.makeText(this, "已归入已移除卡片，可查看记录或恢复", Toast.LENGTH_LONG).show();
                } else Toast.makeText(this, "原文时间仍不明确，请手动核对", Toast.LENGTH_LONG).show();
            });
            content.addView(applyChange, lp(-1, dp(this, 44)));
            Button ignoreChange = button("忽略这条变更", v -> {
                if (shown.ignorePendingChange(c, change.id)) {
                    if (demo == null) EngineHolder.saveAsync(this);
                    holder[0].dismiss(); showSource(c);
                }
            });
            content.addView(ignoreChange, lp(-1, dp(this, 44)));
        }

        // 关键信息
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        infoRow(info, "提交到", c.channel);
        infoRow(info, "地点", c.place);
        infoRow(info, "携带", c.bring);
        infoRow(info, "发送者", TextUtils.isEmpty(c.sender) ? "未知" : c.sender);
        LinearLayout.LayoutParams infoLp = lp(-1, -2);
        infoLp.topMargin = dp(this, 8);
        content.addView(info, infoLp);

        // 快捷操作
        LinearLayout actions = new LinearLayout(this);
        Intent sourceIntent = SourceLaunch.intentFor(this, c.sourcePackageName());
        if (sourceIntent != null) {
            Button open = button("打开" + sourceName(c), v -> {
                try {
                    holder[0].dismiss();
                    startActivity(sourceIntent);
                } catch (Exception unavailable) {
                    Toast.makeText(this, "来源应用暂时无法打开", Toast.LENGTH_SHORT).show();
                }
            });
            actions.addView(open, new LinearLayout.LayoutParams(0, dp(this, 46), 1));
        }
        Button copy = button("复制", v -> {
            String summary = c.displayTitle() + "\n截止：" + deadline + (TextUtils.isEmpty(c.channel) ? "" : "\n提交到：" + c.channel)
                    + "\n来源：" + sourceName(c) + " · " + c.groupName;
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("事项摘要", summary));
                Toast.makeText(this, "已复制事项摘要", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(this, 46), 1);
        copyLp.leftMargin = sourceIntent == null ? 0 : dp(this, 8);
        actions.addView(copy, copyLp);
        Button calendar = button("加入日历", v -> {
            holder[0].dismiss();
            addToCalendar(c);
        });
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(0, dp(this, 46), 1);
        calLp.leftMargin = dp(this, 8);
        actions.addView(calendar, calLp);
        for (int i = 0; i < actions.getChildCount(); i++) {
            ((Button) actions.getChildAt(i)).setTextSize(13);
            actions.getChildAt(i).setPadding(dp(this, 6), 0, dp(this, 6), 0);
        }
        LinearLayout.LayoutParams actionsLp = lp(-1, -2);
        actionsLp.topMargin = dp(this, 12);
        content.addView(actions, actionsLp);

        // 原始消息
        TextView section = uiText("来源与处理记录", 13, Ui.SUB, true);
        section.setPadding(dp(this, 2), dp(this, 26), 0, dp(this, 10));
        content.addView(section);
        StringBuilder original = new StringBuilder(TaskTrace.text(c));
        original.append("\n\n记录范围：当前来源与最近 20 条处理记录；旧版本未记录的操作不补写。");
        if (c.context != null && !c.context.isEmpty()) {
            original.append("\n\n相关上下文");
            for (String line : c.context) original.append("\n· ").append(line);
        }
        LinearLayout quote = new LinearLayout(this);
        quote.setBackground(Ui.round(this, Ui.BG, 14));
        quote.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        View bar = new View(this);
        bar.setBackground(Ui.round(this, Ui.LINE, 2));
        quote.addView(bar, lp(dp(this, 3), -1));
        TextView source = uiText(original.toString(), 14, Ui.TEXT, false);
        source.setTextIsSelectable(true);
        source.setLineSpacing(dp(this, 5), 1.05f);
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(0, -2, 1);
        srcLp.leftMargin = dp(this, 12);
        quote.addView(source, srcLp);
        content.addView(quote, lp(-1, -2));
        if (sourceIntent != null) {
            TextView note = uiText("打开" + sourceName(c) + "后，到对应群聊查看上下文", 12, Ui.MUTE, false);
            note.setPadding(dp(this, 2), dp(this, 8), 0, 0);
            content.addView(note);
        }

        // 次要操作：文字链接
        LinearLayout links = new LinearLayout(this);
        links.setGravity(Gravity.CENTER);
        TextView edit = linkText("修正信息", Ui.ACCENT);
        edit.setOnClickListener(v -> {
            holder[0].dismiss();
            showCardEdit(c);
        });
        links.addView(edit, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        TextView remove = linkText("移除事项", Ui.RED);
        remove.setOnClickListener(v -> alert().setTitle("移除这条事项？").setMessage("可在「更多 → 已移除的卡片」中恢复。")
                .setNegativeButton("保留", null).setPositiveButton("移除", (d, w) -> {
                    if (shown.dismissCard(c, System.currentTimeMillis())) {
                        holder[0].dismiss();
                        render();
                        if (demo == null) EngineHolder.saveAsync(this);
                    }
                }).show());
        links.addView(remove, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        String gid = engine.groupKeyOf(c);
        if (demo == null && !TextUtils.isEmpty(gid)) {
            boolean platform = Engine.PlatformNotice.isPlatform(c.sourcePackageName());
            String gname = platform ? sourceName(c) : (TextUtils.isEmpty(c.groupName) ? sourceName(c) : c.groupName);
            String mode = engine.groupMode(gid);
            TextView manage = linkText(Engine.MODE_NORMAL.equals(mode) ? "管理这个群" : "群：" + modeLabel(mode), Ui.SUB);
            manage.setOnClickListener(v -> {
                holder[0].dismiss();
                chooseGroupMode(gid, gname, c.sourcePackageName(), mode);
            });
            links.addView(manage, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        }
        LinearLayout.LayoutParams linksLp = lp(-1, -2);
        linksLp.topMargin = dp(this, 14);
        content.addView(links, linksLp);

        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(0, dp(this, 12), 0, 0);
        Button complete = stylePrimary(button(c.done ? "恢复为待办" : "标记完成", v -> {
            holder[0].dismiss();
            toggleDone(c);
        }));
        footer.addView(complete, new LinearLayout.LayoutParams(-1, dp(this, 52)));
        holder[0] = showSheet(content, footer);
    }

    private void infoRow(LinearLayout parent, String label, String value) {
        if (TextUtils.isEmpty(value) || value.trim().isEmpty()) return;
        if (parent.getChildCount() > 0) parent.addView(Ui.hairline(this));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(this, 2), dp(this, 12), dp(this, 2), dp(this, 12));
        TextView l = uiText(label, 14, Ui.MUTE, false);
        row.addView(l, lp(dp(this, 64), -2));
        TextView v = uiText(value.trim(), 14, Ui.TEXT, false);
        v.setLineSpacing(dp(this, 3), 1f);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row, lp(-1, -2));
    }

    private TextView linkText(String text, int color) {
        TextView t = uiText(text, 14, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 4), 0, dp(this, 4), 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(Ui.ripple(this, null, Ui.press(), 10));
        return t;
    }

    /** 标记完成 / 恢复成待办；标记完成时底部给 4 秒撤销。 */
    void toggleDone(Engine.Card c) {
        Engine shown=demo==null?engine:demo.engine;
        boolean completing=!c.done;
        if(shown==null || !shown.setCardDone(c,completing,System.currentTimeMillis()))return;
        if (!completing) {
            animateInCard = c;
            hideUndo();
        } else {
            showUndo(c);
        }
        render();
        if (demo == null) EngineHolder.saveAsync(this);
    }

    private final Runnable hideUndoRunnable = this::hideUndo;

    void showUndo(Engine.Card c) {
        if (undoBar == null) return;
        undoText.setText("已完成「" + c.displayTitle() + "」");
        undoAction.setOnClickListener(v -> {
            hideUndo();
            if (c.done) {
                Engine shown=demo==null?engine:demo.engine;
                if(shown==null || !shown.setCardDone(c,false,System.currentTimeMillis()))return;
                animateInCard = c;
                render();
                if (demo == null) EngineHolder.saveAsync(this);
            }
        });
        uiHandler.removeCallbacks(hideUndoRunnable);
        undoBar.animate().cancel();
        if (undoBar.getVisibility() != View.VISIBLE) {
            undoBar.setAlpha(0f);
            undoBar.setTranslationY(dp(this, 24));
            undoBar.setVisibility(View.VISIBLE);
        }
        undoBar.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
        uiHandler.postDelayed(hideUndoRunnable, 4000);
    }

    void hideUndo() {
        if (undoBar == null || undoBar.getVisibility() != View.VISIBLE) return;
        uiHandler.removeCallbacks(hideUndoRunnable);
        undoBar.animate().cancel();
        undoBar.animate().alpha(0f).translationY(dp(this, 24)).setDuration(160)
                .withEndAction(() -> undoBar.setVisibility(View.GONE)).start();
    }

    void showRemovedCards() {
        Engine shown = demo == null ? engine : demo.engine;
        List<Engine.Card> removed = shown.removedCards();
        if (removed.isEmpty()) {
            alert().setTitle("已移除的卡片")
                    .setMessage("这里是空的。误移除的卡片可以从这里恢复。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] titles = new String[removed.size()];
        for (int i = 0; i < removed.size(); i++) {
            Engine.Card card = removed.get(i);
            titles[i] = card.displayTitle() + "\n" + sourceName(card) + " · " + card.groupName;
            if (card.cancelled) titles[i] = "已被来源取消 · " + titles[i];
        }
        alert()
                .setTitle("已移除的卡片（" + removed.size() + "）")
                .setItems(titles, (dialog, which) -> {
                    Engine.Card card = removed.get(which);
                    alert()
                            .setTitle(card.displayTitle())
                            .setMessage("来源：" + sourceName(card) + " · " + card.groupName + "\n截止时间：" + dateTimeLabel(card.deadline)
                                    + "\n移除时间：" + dateTimeLabel(card.removedAt)
                                    + "\n\n原消息：\n" + card.sourceText
                                    + "\n\n恢复后请核对时间，移除期间的群消息变更可能未应用到这张卡。")
                            .setPositiveButton("恢复", (d, w) -> {
                                if (shown.restoreCard(card)) {
                                    if (demo == null) EngineHolder.saveAsync(this);
                                    showAllCards();
                                    Toast.makeText(this, "已恢复，请核对事项时间", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(this, "当前已有同一张卡或更新的接龙，保留当前版本", Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("返回列表", (d, w) -> showRemovedCards())
                            .show();
                })
                .setNeutralButton("清空已移除", (dialog, which) -> alert()
                        .setTitle("清空已移除的卡片？")
                        .setMessage("这些卡片将无法恢复。")
                        .setPositiveButton("清空", (d, w) -> {
                            shown.clearRemovedCards();
                            if (demo == null) EngineHolder.saveAsync(this);
                        })
                        .setNegativeButton("取消", (d, w) -> showRemovedCards())
                        .show())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showDeadlineReminderSettings() {
        boolean enabled = ReminderReceiver.isEnabled(this);
        String state = enabled
                ? (ReminderReceiver.canNotify(this) ? "已开启" : "已开启，系统通知权限待确认")
                : "已关闭";
        alert()
                .setTitle("到期提醒 · " + state)
                .setMessage("给时间已确认的未完成事项安排提醒。提前两小时进入提醒窗口，实际送达时间受系统调度影响。通知栏只提示有待办即将到期。")
                .setPositiveButton(enabled ? "关闭提醒" : "开启提醒", (dialog, which) -> {
                    if (enabled) {
                        ReminderReceiver.setEnabled(this, engine, false);
                    } else if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_REMINDER_PERMISSION);
                    } else {
                        enableDeadlineReminder();
                    }
                })
                .setNeutralButton("系统设置", (dialog, which) -> openAppNotifications())
                .setNegativeButton("返回", null)
                .show();
    }

    private void openAppNotifications() {
        Intent settings;
        if (Build.VERSION.SDK_INT >= 26) {
            settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        }
        startActivity(settings);
    }

    private void enableDeadlineReminder() {
        ReminderReceiver.setEnabled(this, engine, true);
        Toast.makeText(this, ReminderReceiver.canNotify(this) ? "已开启到期提醒" : "请在系统设置中允许通知",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_REMINDER_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableDeadlineReminder();
        } else {
            Toast.makeText(this, "系统未授予通知权限", Toast.LENGTH_SHORT).show();
        }
        if (setupDialog != null && setupDialog.isShowing()) fillSetup();
    }

    // ---------- 按群管理 ----------
    private android.app.Dialog groupDialog;
    private LinearLayout groupBody;

    String groupMenuHint() {
        int focus = 0, ignore = 0;
        List<Engine.GroupInfo> list = engine.groupList();
        for (Engine.GroupInfo g : list) {
            if (Engine.MODE_FOCUS.equals(g.mode)) focus++;
            else if (Engine.MODE_IGNORE.equals(g.mode)) ignore++;
        }
        if (list.isEmpty()) return "收到群消息后，可以设重点关注或不用管";
        return list.size() + " 个群 · 重点关注 " + focus + " · 不用管 " + ignore;
    }

    static String modeLabel(String mode) {
        return Engine.MODE_FOCUS.equals(mode) ? "重点关注" : Engine.MODE_IGNORE.equals(mode) ? "不用管" : "普通";
    }

    /** 群列表：每行是来源图标、群名、来源 · 卡片数 · 最近消息，右侧是当前处理方式；点一行改设置。 */
    void showGroupManager() {
        if (groupDialog != null && groupDialog.isShowing()) {
            fillGroups();
            return;
        }
        groupBody = new LinearLayout(this);
        groupBody.setOrientation(LinearLayout.VERTICAL);
        fillGroups();
        groupDialog = showSheet(groupBody, null);
        groupDialog.setOnDismissListener(d -> groupDialog = null);
    }

    void fillGroups() {
        if (groupBody == null) return;
        groupBody.removeAllViews();
        TextView title = uiText("按群管理", 21, Ui.TEXT, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LinearLayout.LayoutParams tLp = lp(-1, -2);
        tLp.topMargin = dp(this, 16);
        groupBody.addView(title, tLp);
        TextView intro = uiText("重点关注：放宽识别，同学发的作业通知、讨论里定下的时间也会出卡。\n"
                + "不用管：这个群的消息不再出卡，已有的卡移到「已移除的卡片」。", 13, Ui.SUB, false);
        intro.setLineSpacing(dp(this, 4), 1f);
        LinearLayout.LayoutParams iLp = lp(-1, -2);
        iLp.topMargin = dp(this, 8);
        iLp.bottomMargin = dp(this, 8);
        groupBody.addView(intro, iLp);

        List<Engine.GroupInfo> list = engine.groupList();
        if (list.isEmpty()) {
            TextView none = uiText("还没有收到过群消息。开启自动读取后，收到消息的群会出现在这里。", 14, Ui.MUTE, false);
            none.setLineSpacing(dp(this, 4), 1f);
            none.setPadding(0, dp(this, 24), 0, dp(this, 24));
            groupBody.addView(none);
            return;
        }
        long now = System.currentTimeMillis();
        String lastSection = null;
        for (Engine.GroupInfo g : list) {
            String section = modeLabel(g.mode);
            if (!section.equals(lastSection)) {
                TextView s = uiText(section, 12, Ui.MUTE, true);
                s.setPadding(dp(this, 2), dp(this, 18), 0, dp(this, 4));
                groupBody.addView(s);
                lastSection = section;
            }
            groupBody.addView(groupRow(g, now), lp(-1, -2));
        }
    }

    private View groupRow(Engine.GroupInfo g, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(this, 60));
        row.setPadding(dp(this, 2), dp(this, 10), dp(this, 2), dp(this, 10));
        row.setBackground(Ui.ripple(this, null, Ui.press(), 12));
        String app = appNameFor(g.pkg);
        row.addView(sourceMark(app, g.pkg, 22));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = uiText(g.name, 15, Engine.MODE_IGNORE.equals(g.mode) ? Ui.MUTE : Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        int cards = engine.cardCount(g.id);
        StringBuilder sub = new StringBuilder(g.isPlatform() ? "学习平台通知" : app);
        if (cards > 0) sub.append(" · ").append(cards).append(" 张卡");
        if (g.lastTs > 0) sub.append(" · ").append(relativeTime(Math.min(g.lastTs, now)));
        TextView s = uiText(sub.toString(), 12, Ui.MUTE, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setPadding(0, dp(this, 5), 0, 0);
        col.addView(s);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1);
        cLp.leftMargin = dp(this, 12);
        cLp.rightMargin = dp(this, 10);
        row.addView(col, cLp);
        int fg = Engine.MODE_FOCUS.equals(g.mode) ? Ui.ACCENT : Engine.MODE_IGNORE.equals(g.mode) ? Ui.MUTE : Ui.SUB;
        int bg = Engine.MODE_FOCUS.equals(g.mode) ? Ui.ACCENT_BG : Ui.FILL;
        row.addView(Ui.tag(this, modeLabel(g.mode) + " ▾", fg, bg));
        row.setOnClickListener(v -> chooseGroupMode(g.id, g.name, g.pkg, g.mode));
        return row;
    }

    /** 改一个群的处理方式；设成不用管时先确认，因为已有的卡会一起移走。 */
    void chooseGroupMode(String gid, String name, String pkg, String current) {
        String[] modes = {Engine.MODE_FOCUS, Engine.MODE_NORMAL, Engine.MODE_IGNORE};
        String[] labels = {"重点关注 · 放宽识别，宁可多出卡",
                "普通 · 按默认规则识别",
                "不用管 · 这个群不再出卡"};
        int checked = Engine.MODE_FOCUS.equals(current) ? 0 : Engine.MODE_IGNORE.equals(current) ? 2 : 1;
        alert().setTitle(name).setSingleChoiceItems(labels, checked, (d, which) -> {
            d.dismiss();
            String mode = modes[which];
            if (mode.equals(current)) return;
            int existing = engine.cardCount(gid);
            if (Engine.MODE_IGNORE.equals(mode) && existing > 0) {
                alert().setTitle("「" + name + "」不用管？")
                        .setMessage("这个群现有的 " + existing + " 张卡会移到「已移除的卡片」，之后的消息不再出卡。随时可以改回来。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("不用管", (dd, w) -> applyGroupMode(gid, name, pkg, mode))
                        .show();
            } else {
                applyGroupMode(gid, name, pkg, mode);
            }
        }).setNegativeButton("取消", null).show();
    }

    private void applyGroupMode(String gid, String name, String pkg, String mode) {
        int removed = engine.setGroupMode(gid, name, pkg, mode, System.currentTimeMillis());
        EngineHolder.saveAsync(this);
        render();
        if (groupDialog != null && groupDialog.isShowing()) fillGroups();
        String msg = Engine.MODE_IGNORE.equals(mode)
                ? (removed > 0 ? "已设为不用管，移走 " + removed + " 张卡" : "已设为不用管")
                : Engine.MODE_FOCUS.equals(mode) ? "已设为重点关注" : "已恢复普通";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private android.app.Dialog setupDialog;
    private LinearLayout setupBody;
    private Button setupDone;

    /**
     * 首次安装引导：「让自动整理一直在线」清单。
     * 通知使用权是必需项；电池不受限、自启动是推荐项（国产系统默认会杀后台）；到期提醒可选。
     * 每一步点了就跳到对应系统页面，回到 App 时自动刷新完成状态。
     */
    void showSetupGuide() {
        if (setupDialog != null && setupDialog.isShowing()) {
            fillSetup();
            return;
        }
        setupBody = new LinearLayout(this);
        setupBody.setOrientation(LinearLayout.VERTICAL);
        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(0, dp(this, 12), 0, 0);
        setupDone = stylePrimary(button("稍后再说", v -> {
            if (!SetupGuide.allRecommendedDone(this)) SetupGuide.snooze(this);
            if (setupDialog != null) setupDialog.dismiss();
        }));
        footer.addView(setupDone, new LinearLayout.LayoutParams(-1, dp(this, 52)));
        fillSetup();
        setupDialog = showSheet(setupBody, footer);
        setupDialog.setOnDismissListener(d -> {
            setupDialog = null;
            render();
        });
    }

    void fillSetup() {
        if (setupBody == null) return;
        setupBody.removeAllViews();
        boolean access = WatchdogJob.accessEnabled(this);
        boolean battery = WatchdogJob.batteryIgnored(this);
        boolean autostart = SetupGuide.autostartVisited(this);
        boolean remind = ReminderReceiver.isEnabled(this) && ReminderReceiver.canNotify(this);
        int done = (access ? 1 : 0) + (battery ? 1 : 0) + (autostart ? 1 : 0);

        TextView title = uiText("让自动整理一直在线", 20, Ui.TEXT, false);
        title.setTypeface(Ui.medium());
        LinearLayout.LayoutParams tLp = lp(-1, -2);
        tLp.topMargin = dp(this, 16);
        setupBody.addView(title, tLp);
        TextView intro = uiText("手机会在后台悄悄关掉不常用的应用。做完下面三步，群里的作业、考试和接龙才能一直自动整理。",
                13, Ui.SUB, false);
        intro.setLineSpacing(dp(this, 4), 1f);
        LinearLayout.LayoutParams iLp = lp(-1, -2);
        iLp.topMargin = dp(this, 8);
        setupBody.addView(intro, iLp);

        // 进度条
        LinearLayout progress = new LinearLayout(this);
        for (int i = 0; i < 3; i++) {
            View seg = new View(this);
            seg.setBackground(Ui.round(this, i < done ? Ui.GREEN : Ui.FILL, 3));
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(this, 6), 1);
            if (i > 0) sLp.leftMargin = dp(this, 6);
            progress.addView(seg, sLp);
        }
        LinearLayout.LayoutParams pLp = lp(-1, -2);
        pLp.topMargin = dp(this, 18);
        setupBody.addView(progress, pLp);
        TextView count = uiText(done == 3 ? "已全部完成" : "已完成 " + done + " / 3", 12, done == 3 ? Ui.GREEN : Ui.MUTE, true);
        count.setPadding(0, dp(this, 8), 0, dp(this, 6));
        setupBody.addView(count);

        setupStep(1, "打开通知使用权", "必需 · 只在本机读取群消息通知，不联网", access, "已开启", "去开启",
                () -> {
                    try {
                        startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    } catch (Exception e) {
                        Toast.makeText(this, "请到系统设置里搜索「通知使用权」", Toast.LENGTH_LONG).show();
                    }
                });
        setupStep(2, "允许后台运行", "推荐 · 不受电池优化限制，锁屏后也能收到", battery, "已允许", "允许",
                () -> SetupGuide.requestBattery(this));
        setupStep(3, "允许自启动", "推荐 · " + SetupGuide.autostartHint(), autostart, "已去设置", "去设置",
                () -> SetupGuide.openAutostart(this));
        setupStep(4, "填写我的档案", "可选 · 姓名、班级、课程，用来判断哪条消息和你有关", ProfileStore.exists(this),
                "已填写", "去填写", () -> startActivity(new Intent(this, ProfileActivity.class)));
        setupStep(5, "到期提醒", "可选 · 截止前两小时发一条提醒", remind, "已开启", "开启", () -> {
            if (ReminderReceiver.isEnabled(this) && ReminderReceiver.canNotify(this)) {
                showDeadlineReminderSettings();
            } else if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_REMINDER_PERMISSION);
            } else {
                enableDeadlineReminder();
                fillSetup();
            }
        });
        setupStep(6, "AI 复核", "可选 · 帮规则找回漏掉的待办、补全时间地点，可以完全在本机运行", AiConfig.ready(this),
                "已开启", "了解", () -> startActivity(new Intent(this, AiActivity.class)));
        if (setupDone != null) setupDone.setText(done == 3 ? "全部就绪" : access ? "稍后再说" : "稍后再说（暂时收不到群消息）");
    }

    private void setupStep(int n, String title, String desc, boolean ok, String okLabel, String action, Runnable run) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(this, 2), dp(this, 14), dp(this, 2), dp(this, 14));
        row.setBackground(Ui.ripple(this, null, Ui.press(), 12));
        View mark;
        if (ok) {
            mark = new View(this);
            mark.setBackground(new Ui.CheckCircle(this, Ui.GREEN, Ui.GREEN, 28, true));
        } else {
            TextView num = uiText(String.valueOf(n), 14, Ui.TEXT, true);
            num.setGravity(Gravity.CENTER);
            GradientDrawable ring = Ui.disc(this, Ui.CARD);
            ring.setStroke(dp(this, 1.5f), Ui.LINE);
            num.setBackground(ring);
            mark = num;
        }
        row.addView(mark, lp(dp(this, 28), dp(this, 28)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(uiText(title, 16, Ui.TEXT, true));
        TextView d = uiText(desc, 12, Ui.SUB, false);
        d.setLineSpacing(dp(this, 2), 1f);
        d.setPadding(0, dp(this, 5), 0, 0);
        col.addView(d);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1);
        cLp.leftMargin = dp(this, 14);
        cLp.rightMargin = dp(this, 10);
        row.addView(col, cLp);
        TextView btn = uiText(ok ? okLabel : action, 13, ok ? Ui.GREEN : Ui.ACCENT, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(this, 14), 0, dp(this, 14), 0);
        if (!ok) btn.setBackground(Ui.round(this, Ui.ACCENT_BG, 16));
        row.addView(btn, lp(-2, dp(this, 34)));
        row.setOnClickListener(v -> run.run());
        if (n > 1) setupBody.addView(Ui.hairline(this));
        setupBody.addView(row, lp(-1, -2));
    }

    void showCardEdit(Engine.Card card) {
        LinearLayout form = dialogForm();
        EditText title = input(form, "事项标题", false);
        title.setText(card.displayTitle());
        Long[] deadline = {null};
        Button time = button("时间：" + (card.deadlineHint() == null ? dateTimeLabel(card.deadline)
                : card.deadlineHint() + "（时刻待确认）"), v -> {
            long initial = deadline[0] == null ? card.deadline : deadline[0];
            chooseDateTime(initial, value -> {
                deadline[0] = value;
                ((Button) v).setText("时间：" + dateTimeLabel(value));
            });
        });
        form.addView(time);
        form.addView(button("标为时间待确认", v -> {
            deadline[0] = 0L;
            time.setText("时间：待确认");
        }));
        AlertDialog dialog = alert()
                .setTitle("修正卡片")
                .setView(scrollForm(form))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = title.getText().toString().trim();
            if (label.isEmpty()) {
                title.setError("请输入事项标题");
                return;
            }
            Engine shown = demo == null ? engine : demo.engine;
            if (shown.editCard(card, label, deadline[0])) {
                if (demo == null) EngineHolder.saveAsync(this);
                render();
            } else {
                Toast.makeText(this, "这张卡片已移除", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(this, 22), dp(this, 6), dp(this, 22), dp(this, 8));
        return form;
    }

    private ScrollView scrollForm(LinearLayout form) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return scroll;
    }

    private EditText input(LinearLayout form, String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multiline) {
            field.setMinLines(3);
            field.setMaxLines(6);
        } else {
            field.setSingleLine(true);
        }
        form.addView(field);
        return field;
    }

    private String dateTimeLabel(long value) {
        return value == 0 ? "待确认" : android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", value).toString();
    }

    private void chooseDateTime(long initial, LongConsumer selected) {
        Calendar value = Calendar.getInstance();
        if (initial > 0) value.setTimeInMillis(initial);
        new DatePickerDialog(this, (date, year, month, day) ->
                new TimePickerDialog(this, (time, hour, minute) -> {
                    value.set(year, month, day, hour, minute, 0);
                    value.set(Calendar.MILLISECOND, 0);
                    selected.accept(value.getTimeInMillis());
                }, value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE), true).show(),
                value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show();
    }

    void addToCalendar(Engine.Card c) {
        if (c.deadlineHint() != null || c.deadline == 0) {
            Toast.makeText(this, "请先确认具体截止时间，再加入日历", Toast.LENGTH_LONG).show();
            showCardEdit(c);
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_INSERT);
            i.setData(CalendarContract.Events.CONTENT_URI);
            i.putExtra(CalendarContract.Events.TITLE, "【Offloor】" + c.displayTitle());
            // 描述不写群消息原文，避免系统日历云同步把原文外泄；只写来源与考试要点。
            StringBuilder desc = new StringBuilder();
            desc.append("来源：").append(joinDistinct(sourceName(c), c.groupName, c.sender));
            if ("exam".equals(c.kind)) {
                if (!TextUtils.isEmpty(c.place)) desc.append("\n地点：").append(c.place);
                if (!TextUtils.isEmpty(c.bring)) desc.append("\n带：").append(c.bring);
            }
            i.putExtra(CalendarContract.Events.DESCRIPTION, desc.toString());
            if (c.deadline == 0) {
                // 没确定时间就不塞"明天此刻"，让日历应用自己让用户选时间。
                Toast.makeText(this, "这张卡时间还没确定，请在日历里自己选时间", Toast.LENGTH_LONG).show();
            } else {
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.calendarStart());
                if (!"exam".equals(c.kind)) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.deadline);
            }
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有日历应用", Toast.LENGTH_SHORT).show();
        }
    }
}
