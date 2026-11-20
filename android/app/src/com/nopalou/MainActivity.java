package com.nopalou;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
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
import android.text.SpannableStringBuilder;
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
    static final int PAGE_SIZE = 30;
    static final int REQUEST_REMINDER_PERMISSION = 8420;

    Engine engine;
    LinearLayout cardList;
    /** 大标题下的统计副标（明天/过期/待确认/未完成计数，有冲突时同行轻附可点计数）。 */
    TextView stats;
    /** 顶部副标题（日期 + 问候），render() 时按小时段刷新。 */
    TextView sub;
    /** 当前 7 天内的冲突状态管理器：不插横幅，统一供给统计行、卡片第二行与详情抽屉。 */
    private final HomeConflictState conflicts = new HomeConflictState();
    private Timetable timetable;
    private int timetableLoadVersion;
    private final Runnable timetableChanged = () -> { if (this.resumed) loadTimetableAsync(); };
    private final java.util.concurrent.ExecutorService timetableIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "offloor-timetable-sync");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
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
    /** 顶栏的 AI 复核状态胶囊；没开 AI 时隐藏。 */
    TextView aiPill;
    /** 本进程是否已经做过一次 AI 补审。 */
    private static boolean aiBackfilled;
    TextView empty;
    boolean accessChecked;
    boolean retryNotifOnResume;
    private static String storageIssueShown;
    DemoScenario demo;
    private Button demoButton;
    private Button demoUpdate;
    private LinearLayout demoTools;
    private LinearLayout liveTools;
    /** 底部栏主按钮：仅未开启自动读取时提示开启。 */
    private Button autoReadButton;
    private LinearLayout liveFilters;
    private LinearLayout searchWrap;
    private ImageView searchToggle;
    private TextView tabAll;
    private TextView tabToday;
    private TextView tabConfirm;
    private TextView stateFilter;
    boolean newCardsOnly;
    private NewCardTracker newCardTracker;
    /** 已经提示过的自动扫描时间，避免每次回到前台都重复提示。 */
    static long scanNoticeShownAt = 0L;
    EditText searchField;
    private TextView searchClear;
    ScrollView listScroll;
    private TextView headline;
    /** 顶部编辑风概览区：作为滚动收起的锚点。 */
    private LinearLayout headerArea;
    private Button emptyAction;
    private AlertDialog usageNoticeDialog;
    android.app.Dialog manualCardDialog;
    java.util.function.Supplier<Bundle> manualDraftState;
    private Bundle restoreManualDraft;
    private boolean restoreUsageNotice;
    private boolean startupNoticeHandled;
    private LinearLayout emptyBox;
    private View emptyIcon;
    private TextView emptyTitle;
    private LinearLayout undoBar;
    private TextView undoText;
    private TextView undoAction;
    String keywords = "";
    Engine.CardScope scope = Engine.CardScope.ALL;
    Engine.CardWindow window = Engine.CardWindow.ANY;
    int visibleLimit = PAGE_SIZE;
    android.app.Dialog groupDialog;
    LinearLayout groupBody;
    android.app.Dialog setupDialog;
    LinearLayout setupBody;
    Button setupDone;
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
        final TextView timeView;
        final TextView subView;
        final String bucket;
        final int urgency;

        DueBinding(Engine.Card card, TextView timeView, TextView subView, String bucket, int urgency) {
            this.card = card;
            this.timeView = timeView;
            this.subView = subView;
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
            restoreManualDraft = b.getBundle("manualCardDraft");
            keywords = b.getString("keywords", "");
            newCardsOnly = b.getBoolean("newCardsOnly", false);
            Engine.CardScope[] scopes = Engine.CardScope.values();
            int selected = b.getInt("scope", 0);
            scope = scopes[Math.max(0, Math.min(selected, scopes.length - 1))];
            visibleLimit = Math.max(PAGE_SIZE, b.getInt("visibleLimit", PAGE_SIZE));
            int savedWindow = b.getInt("window", Engine.CardWindow.ANY.ordinal());
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
        newCardTracker = NewCardTracker.get(this);
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
        CampusStore.observe(timetableChanged);
        updateAiPill();
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
            loadTimetableAsync();
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
        if (restoreManualDraft != null) {
            Bundle draft = restoreManualDraft; restoreManualDraft = null;
            String id = draft.getString("cardId", "");
            Engine.Card card = id.isEmpty() ? null : engine.cardById(id);
            if (!id.isEmpty() && card == null) Toast.makeText(this, "草稿对应的卡片已移除", Toast.LENGTH_LONG).show();
            else showManualCardForm(card, draft);
        } else if (manualCardDialog == null) showStartupDialogs();
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
        CampusStore.unobserve(timetableChanged);
        ++timetableLoadVersion;
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
        state.putBoolean("newCardsOnly", newCardsOnly);
        state.putInt("scope", scope.ordinal());
        state.putInt("window", window.ordinal());
        state.putInt("visibleLimit", visibleLimit);
        state.putInt("scrollY", listScroll == null ? 0 : listScroll.getScrollY());
        if (manualDraftState != null && demo == null) state.putBundle("manualCardDraft", manualDraftState.get());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        CampusSilentRefresh.hostDestroyed(this);
        CampusStore.unobserve(timetableChanged);
        ++timetableLoadVersion;
        timetableIo.shutdownNow();
        uiHandler.removeCallbacksAndMessages(null);
        if (manualCardDialog != null) { manualCardDialog.dismiss(); manualCardDialog = null; manualDraftState = null; }
        if (groupDialog != null) { groupDialog.dismiss(); groupDialog = null; groupBody = null; }
        if (setupDialog != null) { setupDialog.dismiss(); setupDialog = null; setupBody = null; setupDone = null; }
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
            notifStatus.setText(statusText("已连接", Ui.GREEN));
            notifStatus.setTextColor(Ui.MUTE);
            notifStatus.setBackground(Ui.ripple(this, Ui.round(this, Color.TRANSPARENT, 12), Ui.press(), 12));
            notifStatus.setContentDescription(last == 0 ? "自动读取已连接，等待新消息"
                    : "自动读取已连接，最近收到 " + relativeTime(last));
        } else {
            boolean access = WatchdogJob.accessEnabled(this);
            boolean reconnecting = access && NotifRecovery.running();
            notifStatus.setText(statusText(!access ? "未开启" : reconnecting ? "重连中" : "未连接", Ui.AMBER_TX));
            notifStatus.setTextColor(Ui.AMBER_TX);
            notifStatus.setBackground(Ui.ripple(this, Ui.round(this, Ui.AMBER_BG, 12), Ui.press(), 12));
            notifStatus.setContentDescription(reconnecting ? "通知读取正在重连，点按查看" : "通知读取未连接，点按查看恢复方法");
        }
    }

    /**
     * 顶栏的 AI 胶囊：开了 AI 复核才出现。就绪是赤陶色「AI」，开了但没设置好是琥珀「AI 待设」。
     * 只在回到首页时读一次配置（要解密密钥），不跟着每次刷新重读。
     */
    void updateAiPill() {
        if (aiPill == null) return;
        AiConfig cfg = AiConfig.load(this);
        if (!cfg.enabled || demo != null) { aiPill.setVisibility(View.GONE); return; }
        boolean ready = cfg.ready();
        if (ready && !aiBackfilled && engine != null) {
            // 每次启动补审一次：AI 开启前就出的、时间待确认的群消息卡也交给 AI 看一眼（队列按卡去重）。
            aiBackfilled = true;
            try { if (engine.backfillReviews() > 0) AiJob.schedule(this); }
            catch (RuntimeException e) { EngineHolder.diag(this, "出错", "AI 补审排队失败"); }
        }
        aiPill.setText(statusText(ready ? "AI" : "AI 待设", ready ? Ui.ACCENT : Ui.AMBER_TX));
        aiPill.setTextColor(ready ? Ui.ACCENT : Ui.AMBER_TX);
        aiPill.setBackground(Ui.ripple(this, Ui.round(this, ready ? Ui.ACCENT_BG : Ui.AMBER_BG, 12), Ui.press(), 12));
        aiPill.setContentDescription(ready ? "AI 复核已开启，点按查看设置" : "AI 复核已开启但还没设置好，点按继续设置");
        aiPill.setVisibility(View.VISIBLE);
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
        page.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 24));
        listScroll.addView(page, new ViewGroup.LayoutParams(-1, -2));

        // 顶栏：极简衬线字标 + 轻状态点 + 搜索 / 新建 / 更多
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = uiText("", 22, Ui.TEXT, false);
        SpannableString word = new SpannableString("Offloor.");
        word.setSpan(new ForegroundColorSpan(Ui.ACCENT), word.length() - 1, word.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        brand.setText(word);
        brand.setTypeface(Ui.editorialBold());
        brand.setLetterSpacing(-0.02f);
        brand.setSingleLine(true);
        brand.setEllipsize(null);
        top.addView(brand, new LinearLayout.LayoutParams(-2, -2));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        CampusSyncBanner.add(this, top);

        searchToggle = new ImageView(this);
        searchToggle.setImageDrawable(Ui.searchGlyph(this, Ui.SUB, 18));
        searchToggle.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));
        searchToggle.setContentDescription("搜索待办");
        searchToggle.setBackground(Ui.rippleCircle(this, Ui.press()));
        searchToggle.setOnClickListener(v -> toggleSearch());
        top.addView(searchToggle, lp(dp(this, 42), dp(this, 42)));

        TextView addBtn = uiText("＋", 20, Ui.TEXT, false);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setContentDescription("手动填写并添加任务卡片");
        addBtn.setBackground(Ui.rippleCircle(this, Ui.press()));
        addBtn.setOnClickListener(v -> showManualCardForm(null));
        top.addView(addBtn, lp(dp(this, 42), dp(this, 42)));

        ImageView more = new ImageView(this);
        more.setImageDrawable(CourseUi.glyph(this, "more"));
        int morePad = dp(this, 10);
        more.setPadding(morePad, morePad, morePad, morePad);
        more.setContentDescription("更多");
        more.setBackground(Ui.rippleCircle(this, Ui.press()));
        more.setOnClickListener(v -> showMoreMenu());
        top.addView(more, lp(dp(this, 48), dp(this, 48)));
        page.addView(top, lp(-1, dp(this, 52)));

        updateBanner = uiText("", 13, Ui.ACCENT, true);
        updateBanner.setPadding(dp(this, 14), dp(this, 11), dp(this, 14), dp(this, 11));
        updateBanner.setBackground(Ui.ripple(this, Ui.roundStroke(this, Ui.ACCENT_BG, Ui.withAlpha(Ui.ACCENT, 80), 12, 0.75f), Ui.press(), 12));
        updateBanner.setMaxLines(3); updateBanner.setEllipsize(TextUtils.TruncateAt.END);
        updateBanner.setLineSpacing(dp(this, 3), 1f);
        updateBanner.setOnClickListener(v -> startActivity(new Intent(this, UpdateActivity.class)));
        updateBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams updateLp = lp(-1, -2); updateLp.topMargin = dp(this, 8);
        page.addView(updateBanner, updateLp);
        showUpdateNotice();

        // 编辑感主标题区：直接在暖白纸面上呼吸留白，去掉厚重深色方块和“今日概览”装饰胶囊
        headerArea = new LinearLayout(this);
        headerArea.setOrientation(LinearLayout.VERTICAL);
        headerArea.setClickable(true);
        headerArea.setFocusable(true);
        headerArea.setPadding(dp(this, 2), dp(this, 8), dp(this, 2), dp(this, 12));
        headerArea.setBackground(Ui.ripple(this, null, Ui.press(), 12));
        LinearLayout headerMeta = new LinearLayout(this);
        headerMeta.setGravity(Gravity.CENTER_VERTICAL);
        sub = uiText(subLine(), 12, Ui.MUTE, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setLetterSpacing(0.03f);
        headerMeta.addView(sub, new LinearLayout.LayoutParams(0, -2, 1));
        notifStatus = uiText("", 11, Ui.MUTE, true);
        notifStatus.setGravity(Gravity.CENTER);
        notifStatus.setSingleLine(true);
        notifStatus.setPadding(dp(this, 6), 0, dp(this, 6), 0);
        notifStatus.setOnClickListener(v -> showHealthCheck());
        LinearLayout.LayoutParams statusLp = lp(-2, dp(this, 26));
        statusLp.leftMargin = dp(this, 8);
        headerMeta.addView(notifStatus, statusLp);
        aiPill = uiText("", 11, Ui.ACCENT, true);
        aiPill.setGravity(Gravity.CENTER);
        aiPill.setSingleLine(true);
        aiPill.setPadding(dp(this, 8), 0, dp(this, 8), 0);
        aiPill.setVisibility(View.GONE);
        aiPill.setOnClickListener(v -> startActivity(new Intent(this, AiActivity.class)));
        LinearLayout.LayoutParams aiLp = lp(-2, dp(this, 26));
        aiLp.leftMargin = dp(this, 6);
        headerMeta.addView(aiPill, aiLp);
        headerArea.addView(headerMeta, lp(-1, -2));
        headline = uiText("", 26, Ui.TEXT, false);
        headline.setTypeface(Ui.editorialBold());
        headline.setLineSpacing(dp(this, 3), 1f);
        headline.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams hlLp = lp(-1, -2);
        hlLp.topMargin = dp(this, 6);
        headerArea.addView(headline, hlLp);
        stats = uiText("", 13, Ui.SUB, false);
        stats.setLineSpacing(dp(this, 3), 1f);
        LinearLayout.LayoutParams stLp = lp(-1, -2);
        stLp.topMargin = dp(this, 6);
        headerArea.addView(stats, stLp);
        page.addView(headerArea, lp(-1, -2));

        // 离线演示条
        demoTools = new LinearLayout(this);
        demoTools.setGravity(Gravity.CENTER_VERTICAL);
        demoTools.setVisibility(View.GONE);
        demoTools.setPadding(dp(this, 16), dp(this, 10), dp(this, 12), dp(this, 10));
        demoTools.setBackground(Ui.roundStroke(this, Ui.FILL, Ui.LINE, 14, 0.75f));
        demoTools.addView(uiText("离线演示", 14, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        demoUpdate = stylePrimary(button("播放更新", v -> {
            if (demo != null) {
                demo.applyUpdates();
                demoUpdate.setVisibility(View.GONE);
                render();
            }
        }));
        demoUpdate.setTextSize(13);
        demoUpdate.setVisibility(View.GONE);
        demoTools.addView(demoUpdate, lp(-2, dp(this, 38)));
        demoButton = button("退出", v -> toggleDemo());
        demoButton.setBackground(Ui.ripple(this, Ui.roundStroke(this, Ui.CARD, Ui.LINE, 12, 0.75f), Ui.press(), 12));
        demoButton.setTextSize(13);
        LinearLayout.LayoutParams exitLp = lp(-2, dp(this, 38));
        exitLp.leftMargin = dp(this, 8);
        demoTools.addView(demoButton, exitLp);
        LinearLayout.LayoutParams demoLp = lp(-1, -2);
        demoLp.topMargin = dp(this, 8);
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
        emptyAction = button("", null);
        emptyAction.setBackground(Ui.ripple(this, Ui.roundStroke(this, Ui.CARD, Ui.LINE, 12, 0.75f), Ui.press(), 12));
        emptyAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams eaLp = lp(-2, dp(this, 40));
        eaLp.topMargin = dp(this, 16);
        emptyBox.addView(emptyAction, eaLp);

        root.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部提示按钮：仅未开启自动读取时以不透明纸底停靠在底部导航上方
        liveTools = new LinearLayout(this);
        liveTools.setOrientation(LinearLayout.VERTICAL);
        liveTools.setBackgroundColor(Ui.BG);
        liveTools.addView(Ui.hairline(this), new LinearLayout.LayoutParams(-1, 1));
        LinearLayout liveToolsInner = new LinearLayout(this);
        liveToolsInner.setPadding(dp(this, 20), dp(this, 10), dp(this, 20), dp(this, 10));
        autoReadButton = stylePrimary(button("开启自动读取", v -> {
            if (WatchdogJob.accessEnabled(this)) showHealthCheck();
            else showSetupGuide();
        }));
        liveToolsInner.addView(autoReadButton, new LinearLayout.LayoutParams(-1, dp(this, 44)));
        liveTools.addView(liveToolsInner, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.addView(liveTools);
        dock.addView(AppNavigation.bar(this, AppNavigation.TODO));
        frame.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        AppNavigation.reserve(dock, listScroll);

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
        FrameLayout.LayoutParams undoLp = new FrameLayout.LayoutParams(-1, dp(this, 50), Gravity.BOTTOM);
        undoLp.setMargins(dp(this, 16), 0, dp(this, 16), dp(this, 72));
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
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(this, 20), 0, dp(this, 12), 0);
        compactTitle = uiText("", 16, Ui.TEXT, false);
        compactTitle.setTypeface(Ui.editorialBold());
        compactTitle.setSingleLine(true);
        compactTitle.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(compactTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView up = uiText("回到顶部", 13, Ui.ACCENT, true);
        up.setGravity(Gravity.CENTER);
        up.setPadding(dp(this, 12), 0, dp(this, 12), 0);
        up.setBackground(Ui.ripple(this, null, Ui.press(), 12));
        up.setOnClickListener(v -> listScroll.smoothScrollTo(0, 0));
        row.addView(up, lp(-2, dp(this, 36)));
        compactBar.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        compactBar.addView(Ui.hairline(this));
        compactBar.setOnClickListener(v -> listScroll.smoothScrollTo(0, 0));
        compactBar.setVisibility(View.GONE);
        compactBar.setAlpha(0f);
        frame.addView(compactBar, new FrameLayout.LayoutParams(-1, dp(this, 50), Gravity.TOP));
    }

    private void updateCompactBar(int scrollY) {
        if (compactBar == null || headerArea == null) return;
        boolean show = scrollY > headerArea.getBottom() - dp(this, 20);
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
                    .withEndAction(() -> { if (!compactShown) compactBar.setVisibility(View.GONE); }).start();
        }
    }

    private void toggleSearch() {
        if (searchWrap == null) return;
        boolean show = searchWrap.getVisibility() != View.VISIBLE;
        if (show) {
            searchWrap.setVisibility(View.VISIBLE);
            searchField.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } else {
            searchWrap.setVisibility(View.GONE);
            if (!keywords.isEmpty()) searchField.setText("");
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
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

    /** 折叠式搜索框 + 4 项极简分段筛选栏（全部 / 今天 / 待确认 / 更多）。 */
    private LinearLayout buildFilters() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        searchWrap = new LinearLayout(this);
        searchWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(Ui.roundStroke(this, Ui.CARD, Ui.LINE, 12, 0.75f));
        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(15);
        searchField.setTypeface(Typeface.DEFAULT);
        searchField.setHint("搜索事项、群名、来源");
        searchField.setContentDescription("搜索待办卡片");
        searchField.setBackground(null);
        searchField.setTextColor(Ui.TEXT);
        searchField.setHintTextColor(Ui.MUTE);
        searchField.setPadding(dp(this, 14), 0, dp(this, 4), 0);
        searchField.setCompoundDrawablesWithIntrinsicBounds(Ui.searchGlyph(this, Ui.MUTE, 16), null, null, null);
        searchField.setCompoundDrawablePadding(dp(this, 10));
        searchField.setText(keywords);
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        searchClear = uiText("×", 20, Ui.SUB, false);
        searchClear.setGravity(Gravity.CENTER);
        searchClear.setContentDescription("清空搜索");
        searchClear.setBackground(Ui.rippleCircle(this, Ui.press()));
        searchClear.setVisibility(keywords.isEmpty() ? View.GONE : View.VISIBLE);
        searchClear.setOnClickListener(v -> {
            if (!keywords.isEmpty()) searchField.setText("");
            else toggleSearch();
        });
        searchRow.addView(searchClear, lp(dp(this, 44), dp(this, 44)));
        LinearLayout.LayoutParams srLp = lp(-1, -2);
        srLp.topMargin = dp(this, 6);
        srLp.bottomMargin = dp(this, 10);
        searchWrap.addView(searchRow, srLp);
        searchWrap.setVisibility(keywords.isEmpty() ? View.GONE : View.VISIBLE);
        wrap.addView(searchWrap, lp(-1, -2));

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

        LinearLayout track = new LinearLayout(this);
        track.setGravity(Gravity.CENTER_VERTICAL);
        track.setPadding(dp(this, 3), dp(this, 3), dp(this, 3), dp(this, 3));
        track.setBackground(Ui.round(this, Ui.FILL, 12));

        tabAll = segTab("全部");
        tabAll.setOnClickListener(v -> {
            newCardsOnly = false;
            scope = Engine.CardScope.ALL;
            window = Engine.CardWindow.ANY;
            resetCardPage();
        });
        track.addView(tabAll, new LinearLayout.LayoutParams(0, dp(this, 36), 1));

        tabToday = segTab("今天");
        tabToday.setOnClickListener(v -> {
            newCardsOnly = false;
            scope = Engine.CardScope.ALL;
            window = Engine.CardWindow.TODAY;
            resetCardPage();
        });
        track.addView(tabToday, new LinearLayout.LayoutParams(0, dp(this, 36), 1));

        tabConfirm = segTab("待确认");
        tabConfirm.setOnClickListener(v -> {
            newCardsOnly = false;
            scope = Engine.CardScope.ALL;
            window = Engine.CardWindow.NEEDS_CONFIRM;
            resetCardPage();
        });
        track.addView(tabConfirm, new LinearLayout.LayoutParams(0, dp(this, 36), 1));

        stateFilter = segTab("筛选 ▾");
        stateFilter.setContentDescription("更多状态与时间筛选");
        stateFilter.setOnClickListener(v -> showFilterMenu());
        track.addView(stateFilter, new LinearLayout.LayoutParams(0, dp(this, 36), 1));

        LinearLayout.LayoutParams trackLp = lp(-1, -2);
        trackLp.topMargin = dp(this, 6);
        trackLp.bottomMargin = dp(this, 6);
        wrap.addView(track, trackLp);
        return wrap;
    }

    private TextView segTab(String text) {
        TextView t = uiText(text, 13, Ui.SUB, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(this, 6), 0, dp(this, 6), 0);
        return t;
    }

    private void styleSegTab(TextView tab, String label, String count, boolean selected, boolean warn) {
        SpannableString s = new SpannableString(count == null ? label : label + " " + count);
        if (count != null) {
            int start = s.length() - count.length();
            s.setSpan(new ForegroundColorSpan(selected ? Ui.ACCENT : warn ? Ui.AMBER_TX : Ui.MUTE),
                    start, s.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String key = s.toString() + (selected ? "#1" : "#0") + (warn ? "w" : "") + (Ui.dark ? "d" : "l");
        if (key.equals(tab.getTag())) return;
        tab.setTag(key);
        tab.setText(s);
        tab.setTextColor(selected ? Ui.TEXT : warn ? Ui.AMBER_TX : Ui.SUB);
        GradientDrawable bg = selected ? Ui.roundStroke(this, Ui.CARD, Ui.LINE, 9, 0.75f) : null;
        tab.setBackground(Ui.ripple(this, bg, Ui.press(), 9));
        tab.setSelected(selected);
    }

    private void showFilterMenu() {
        String[] labels = {"全部事项", "未完成", "今天起", "明天", "已过期", "已完成", "本次新增"};
        int checked = newCardsOnly ? 6
                : scope == Engine.CardScope.OPEN ? 1
                : scope == Engine.CardScope.OVERDUE ? 4
                : scope == Engine.CardScope.COMPLETED ? 5
                : window == Engine.CardWindow.UPCOMING ? 2
                : window == Engine.CardWindow.TOMORROW ? 3
                : window == Engine.CardWindow.ANY && scope == Engine.CardScope.ALL ? 0 : -1;
        alert().setTitle("筛选视图").setSingleChoiceItems(labels, checked, (d, which) -> {
            newCardsOnly = which == 6;
            scope = which == 1 ? Engine.CardScope.OPEN
                    : which == 4 ? Engine.CardScope.OVERDUE
                    : which == 5 ? Engine.CardScope.COMPLETED : Engine.CardScope.ALL;
            window = which == 2 ? Engine.CardWindow.UPCOMING
                    : which == 3 ? Engine.CardWindow.TOMORROW : Engine.CardWindow.ANY;
            d.dismiss();
            resetCardPage();
        }).setNegativeButton("取消", null).show();
    }

    private void updateWindowTabs(Engine shown, long now) {
        int allCount = 0, todayCount = 0, confirmCount = 0;
        for (Engine.Card c : shown.all()) {
            allCount++;
            String bucket = TodayView.bucket(c.deadline, c.needsConfirm, c.done, now);
            if ("今天".equals(bucket)) todayCount++;
            else if ("待确认".equals(bucket)) confirmCount++;
        }
        boolean standardAll = !newCardsOnly && scope == Engine.CardScope.ALL && window == Engine.CardWindow.ANY;
        boolean standardToday = !newCardsOnly && scope == Engine.CardScope.ALL && window == Engine.CardWindow.TODAY;
        boolean standardConfirm = !newCardsOnly && scope == Engine.CardScope.ALL && window == Engine.CardWindow.NEEDS_CONFIRM;
        styleSegTab(tabAll, "全部", String.valueOf(allCount), standardAll, false);
        styleSegTab(tabToday, "今天", String.valueOf(todayCount), standardToday, false);
        styleSegTab(tabConfirm, "待确认", String.valueOf(confirmCount), standardConfirm, confirmCount > 0);
        boolean extraSelected = !(standardAll || standardToday || standardConfirm);
        String moreLabel = newCardsOnly ? "新增 ▾"
                : scope == Engine.CardScope.OPEN ? "未完成 ▾"
                : scope == Engine.CardScope.OVERDUE ? "已过期 ▾"
                : scope == Engine.CardScope.COMPLETED ? "已完成 ▾"
                : window == Engine.CardWindow.UPCOMING ? "今天起 ▾"
                : window == Engine.CardWindow.TOMORROW ? "明天 ▾"
                : "更多 ▾";
        styleSegTab(stateFilter, moreLabel, null, extraSelected, false);
    }

    /** 大标题和副标题：直接写在暖白纸面上，先说最要紧的一件事。 */
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
        } else if (confirm > 0) {
            num = String.valueOf(confirm);
            head = "有 " + num + " 件时间待确认";
            numColor = Ui.AMBER_TX;
            lead = "confirm";
        } else {
            head = "今天没有截止";
        }
        SpannableString s = new SpannableString(head);
        if (num != null) {
            int i = head.indexOf(num);
            s.setSpan(new ForegroundColorSpan(numColor), i, i + num.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        headline.setText(s);
        if (compactTitle != null) compactTitle.setText(s);

        if (sub != null) sub.setText(subLine());
        List<String> parts = new ArrayList<>();
        if (tomorrow > 0 && !"tomorrow".equals(lead)) parts.add("明天 " + tomorrow + " 件");
        if (overdue > 0 && !"overdue".equals(lead)) parts.add("已过期 " + overdue + " 件");
        if (confirm > 0 && !"confirm".equals(lead)) parts.add("待确认 " + confirm + " 件");
        if (open > 0) parts.add("共 " + open + " 件未完成");
        else if (total > 0) parts.add("已完成 " + total + " 件");
        else parts.add(demo == null && !EngineHolder.listenerConnected
                ? "开启自动读取后，群消息里的作业与通知会整理到这里"
                : "群里有作业、考试或接龙时，会自动整理到这里");
        String baseStats = TextUtils.join(" · ", parts);
        String conflictSummary = conflicts.summaryLabel();
        if (conflictSummary == null) {
            stats.setText(baseStats);
            stats.setOnClickListener(null);
            stats.setClickable(false);
        } else {
            SpannableStringBuilder sb = new SpannableStringBuilder(baseStats);
            sb.append(" · ");
            int cs = sb.length();
            sb.append(conflictSummary);
            sb.setSpan(new ForegroundColorSpan(Ui.ORANGE), cs, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), cs, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            stats.setText(sb);
            stats.setOnClickListener(v -> showClashDetail());
        }
        final String targetLead = lead;
        final int confirmCount = confirm;
        final int openCount = open;
        final int totalCount = total;
        headerArea.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            Engine.CardScope nextScope;
            Engine.CardWindow nextWindow;
            if ("confirm".equals(targetLead) || (targetLead.isEmpty() && confirmCount > 0)) {
                nextScope = Engine.CardScope.ALL;
                nextWindow = Engine.CardWindow.NEEDS_CONFIRM;
            } else if ("today".equals(targetLead)) {
                nextScope = Engine.CardScope.ALL;
                nextWindow = Engine.CardWindow.TODAY;
            } else if ("overdue".equals(targetLead)) {
                nextScope = Engine.CardScope.OVERDUE;
                nextWindow = Engine.CardWindow.ANY;
            } else if ("tomorrow".equals(targetLead)) {
                nextScope = Engine.CardScope.ALL;
                nextWindow = Engine.CardWindow.TOMORROW;
            } else if (openCount == 0 && totalCount > 0) {
                nextScope = Engine.CardScope.COMPLETED;
                nextWindow = Engine.CardWindow.ANY;
            } else {
                nextScope = Engine.CardScope.ALL;
                nextWindow = Engine.CardWindow.ANY;
            }
            if (!newCardsOnly && scope == nextScope && window == nextWindow) {
                scope = Engine.CardScope.ALL;
                window = Engine.CardWindow.ANY;
            } else {
                scope = nextScope;
                window = nextWindow;
            }
            newCardsOnly = false;
            resetCardPage();
        });
    }

    /** 「扫描历史」：委托 HomeOpsSheets 重新读取通知栏挂起消息并弹窗反馈。 */
    void scanHistory() { HomeOpsSheets.scanHistory(this); }

    private void showScanResult(NotifListener.ScanResult r, boolean manual) { HomeOpsSheets.showScanResult(this, r, manual); }

    /**
     * 切换筛选：列表淡入并轻微上浮，替代原来的“瞬间换一屏”；
     * 已经往下翻过的话只滚回筛选栏的位置，不把整页甩回顶部。
     */
    void resetCardPage() {
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

    void showAllCards() {
        newCardsOnly = false;
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
        btn.setBackground(Ui.ripple(this, Ui.round(this, Ui.FILL, 12), Ui.press(), 12));
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

    /** 主按钮：暖赤陶实心，无厚重阴影。 */
    Button stylePrimary(Button b) {
        b.setBackground(Ui.ripple(this, Ui.round(this, Ui.ACCENT, 12), Ui.withAlpha(Ui.ON_ACCENT, 60), 12));
        b.setElevation(0f);
        b.setTextColor(Ui.ON_ACCENT);
        Ui.pressScale(b, 0.98f);
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
    /** 「更多」：精简到 7 项核心入口，低频诊断与演示收进二级面板。 */
    void showMoreMenu() {
        android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(sheetTitle("更多"));
        menuSection(body, "核心设置");
        menuRow(body, holder, "读取与后台设置", SetupGuide.allRecommendedDone(this)
                ? "已就绪 · 通知读取、后台保活、自启动"
                : "还有步骤没完成，可能收不到群消息",
                SetupGuide.allRecommendedDone(this) ? Ui.TEXT : Ui.AMBER_TX, this::showSetupGuide);
        AiConfig aiCfg = AiConfig.load(this);
        menuRow(body, holder, "AI 与截图识别", aiCfg.enabled
                ? (aiCfg.ready() ? "已开启 AI 复核 · 支持截图转写待办" : "AI 已开启但待完善设置 · 支持截图识别")
                : "可选 AI 复核、截图逐条转写待办", Ui.TEXT, this::showRecognitionMenu);
        menuRow(body, holder, "提醒与按群管理", ReminderReceiver.isEnabled(this)
                ? "到期与学业提醒 · 按群过滤 · 已移除卡片"
                : "提醒开关 · 按群管理 · 已移除卡片", Ui.TEXT, this::showManageMenu);
        menuRow(body, holder, "我的档案", "姓名、班级、课程，仅存在本机", Ui.TEXT,
                () -> startActivity(new Intent(this, ProfileActivity.class)));
        menuSection(body, "维护与关于");
        menuRow(body, holder, "使用须知", "查看通知读取、后台运行和识别限制", Ui.TEXT, this::showUsageNotice);
        menuRow(body, holder, "诊断、备份与演示", "自检、扫描历史、日志、存档与演示场景", Ui.TEXT, this::showToolsMenu);
        menuRow(body, holder, "检查更新", "当前版本 " + UpdateActivity.version(this), Ui.TEXT,
                () -> startActivity(new Intent(this, UpdateActivity.class)));
        holder[0] = showSheet(body, null);
    }

    private void showRecognitionMenu() { HomeOpsSheets.showRecognitionMenu(this); }

    private void showManageMenu() { HomeOpsSheets.showManageMenu(this); }

    private void showToolsMenu() { HomeOpsSheets.showToolsMenu(this); }

    /** 存档状态、成绩与余额提醒设置：委托 HomeOpsSheets 构建。 */
    void showStorageState() { HomeOpsSheets.showStorageState(this); }

    void showCampusAlerts() { HomeOpsSheets.showCampusAlerts(this); }

    TextView sheetTitle(String text) {
        TextView t = uiText(text, 20, Ui.TEXT, false);
        t.setTypeface(Ui.editorialBold());
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

    /** 「自动读取自检」：委托 HomeOpsSheets 展示检查结果与处理入口。 */
    void showHealthCheck() { HomeOpsSheets.showHealthCheck(this); }

    /** 「诊断日志」：委托 HomeOpsSheets 展示读取计数与流水。 */
    void showDiagLog() { HomeOpsSheets.showDiagLog(this); }

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
        refreshConflicts(shown, now);
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
        if (demo == null) {
            updateNotifStatus();
        } else {
            notifStatus.setText(statusText("演示", Ui.ACCENT));
            notifStatus.setTextColor(Ui.ACCENT);
            notifStatus.setBackground(Ui.ripple(this, Ui.round(this, Ui.ACCENT_BG, 12), Ui.press(), 12));
        }
        if (liveTools != null) {
            boolean needSetup = demo == null && !EngineHolder.listenerConnected;
            liveTools.setVisibility(needSetup ? View.VISIBLE : View.GONE);
            if (needSetup) autoReadButton.setText(WatchdogJob.accessEnabled(this) ? "检查自动读取" : "开启自动读取");
        }

        List<Engine.Card> list = demo != null ? shown.ranked(PAGE_SIZE, now)
                : newCardsOnly ? newCardTracker.observe(engine).queryCards(shown, keywords, scope, visibleLimit + 1, now)
                : shown.queryCards(keywords, scope, window, visibleLimit + 1, now);
        if (list.isEmpty()) {
            boolean upcomingOnly = keywords.trim().isEmpty() && scope == Engine.CardScope.ALL
                    && window == Engine.CardWindow.UPCOMING;
            boolean filtered = newCardsOnly || !keywords.trim().isEmpty() || scope != Engine.CardScope.ALL
                    || (window != Engine.CardWindow.ANY && !(upcomingOnly && shown.all().isEmpty()));
            emptyAction.setVisibility(View.GONE);
            if (demo != null) {
                emptyIcon.setBackground(new Ui.CheckCircle(this, Ui.ACCENT, Ui.ACCENT, 48, true));
                emptyTitle.setText("演示卡片已清理");
                empty.setText("退出演示后可以重新体验。");
            } else if (newCardsOnly && keywords.trim().isEmpty()) {
                emptyIcon.setBackground(new Ui.CheckCircle(this, Ui.GREEN, Ui.GREEN, 48, true));
                emptyTitle.setText("本次没有新增卡片");
                empty.setText("这里只显示打开应用以来新收到的事项。");
            } else if (upcomingOnly && filtered) {
                emptyIcon.setBackground(new Ui.CheckCircle(this, Ui.GREEN, Ui.GREEN, 48, true));
                emptyTitle.setText("今天起没有要做的事");
                empty.setText("已过期和已完成的事项在「全部」里。");
            } else if (filtered) {
                emptyIcon.setBackground(emptySearchIcon());
                emptyTitle.setText("没有找到");
                empty.setText("换个关键词，或切回「全部」看看。");
            } else if (EngineHolder.listenerConnected) {
                emptyIcon.setBackground(new Ui.CheckCircle(this, Ui.GREEN, Ui.GREEN, 48, true));
                emptyTitle.setText("暂时没有待办");
                empty.setText("已开启自动读取，群里的作业、考试、接龙会自动出现在这里。");
                emptyAction.setText("扫描通知栏历史消息");
                emptyAction.setOnClickListener(v -> scanHistory());
                emptyAction.setVisibility(View.VISIBLE);
            } else {
                emptyIcon.setBackground(emptySearchIcon());
                emptyTitle.setText("还没有待办");
                empty.setText("开启自动读取后，群消息里的事项会自动整理到这里。");
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
            LinearLayout groupCard = Ui.group(this);
            if ("已完成".equals(groupName)) {
                groupCard.setBackground(Ui.roundStroke(this, Ui.FILL, Ui.LINE, 16, 0.75f));
            }
            for (int idx = 0; idx < group.size(); idx++) {
                Engine.Card card = group.get(idx);
                if (idx > 0) {
                    View rule = new View(this);
                    rule.setBackgroundColor(Ui.withAlpha(Ui.LINE, 180));
                    LinearLayout.LayoutParams rLp = lp(-1, 1);
                    rLp.leftMargin = dp(this, 48);
                    rLp.rightMargin = dp(this, 16);
                    groupCard.addView(rule, rLp);
                }
                View v = cardView(card, groupName, now);
                groupCard.addView(v);
                if (!stagger && card == enter) popIn(v);
            }
            LinearLayout.LayoutParams gLp = lp(-1, -2);
            gLp.bottomMargin = dp(this, 6);
            cardList.addView(groupCard, gLp);
            if (stagger) enterAnim(groupCard, order++);
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

    /** 点击统计行冲突计数：逐行列出近期事挤在一起或与课表上课撞课的详情。 */
    void showClashDetail() {
        long now = System.currentTimeMillis();
        Engine shown = demo == null ? engine : demo.engine;
        refreshConflicts(shown, now);
        if (conflicts.isEmpty()) return;
        alert()
                .setTitle(conflicts.detailTitle())
                .setMessage(conflicts.detailBody())
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
                : "今天".equals(name) ? Ui.ACCENT : "已完成".equals(name) ? Ui.MUTE : Ui.SUB;
        TextView label = uiText(name, 12, color, true);
        label.setLetterSpacing(0.05f);
        row.addView(label);
        TextView num = uiText(String.valueOf(count), 12, Ui.MUTE, false);
        LinearLayout.LayoutParams numLp = lp(-2, -2);
        numLp.leftMargin = dp(this, 6);
        row.addView(num, numLp);
        View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(0, 1, 1);
        ruleLp.leftMargin = dp(this, 10);
        row.addView(rule, ruleLp);
        row.setContentDescription(name + "，" + count + " 件");
        LinearLayout.LayoutParams lp = lp(-1, -2);
        lp.leftMargin = dp(this, 2);
        lp.topMargin = dp(this, 20);
        lp.bottomMargin = dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 极简两行任务行（Things / 滴答清单风格，放在日期分组白底大卡内以细线分隔）：
     * 左完成圈，主体第 1 行「左标题 + 右等宽时间」，第 2 行仅留 1 个最关键行动线索（地点/提交渠道/冲突）；
     * 来源平台、群名、发送者与原文轨迹全收进点击后的详情底抽屉。
     */
    View cardView(Engine.Card c, String groupName, long now) {
        int urgency = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);
        boolean unsure = c.needsConfirm || c.deadline == 0 || c.deadlineHint() != null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(this, 4), 0, dp(this, 16), 0);
        card.setBackground(Ui.ripple(this, null, Ui.press(), 0));
        Ui.pressScale(card, 0.99f);

        int ringColor = c.done ? Ui.MUTE : unsure ? Ui.AMBER_TX
                : urgency >= 1 ? Ui.urgencyColor(urgency) : Ui.RING;
        Ui.CheckCircle circle = new Ui.CheckCircle(this, ringColor, c.done ? Ui.MUTE : Ui.GREEN, 20, c.done);
        FrameLayout checkZone = new FrameLayout(this);
        View check = new View(this);
        check.setBackground(circle);
        FrameLayout.LayoutParams ckLp = new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ckLp.topMargin = dp(this, 10);
        checkZone.addView(check, ckLp);
        checkZone.setBackground(Ui.rippleCircle(this, Ui.press()));
        checkZone.setContentDescription((c.done ? "恢复待办：" : "标记完成：") + c.displayTitle());
        LinearLayout.LayoutParams checkLp = lp(dp(this, 44), dp(this, 44));
        checkLp.topMargin = 0;
        card.addView(checkZone, checkLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(this, 10), 0, dp(this, 10));
        if (c.done) body.setAlpha(0.58f);

        LinearLayout topLine = new LinearLayout(this);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.TOP);

        TextView title = uiText(c.displayTitle(), 15.5f, c.done ? Ui.SUB : Ui.TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(dp(this, 2), 1f);
        if (c.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        topLine.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView timeView = null;
        TextView subView = null;
        if (!c.done) {
            timeView = uiText(compactDueLabel(c, groupName), 13, compactDueColor(c, now), true);
            timeView.setSingleLine(true);
            timeView.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams tLp = lp(-2, -2);
            tLp.leftMargin = dp(this, 14);
            tLp.topMargin = dp(this, 2);
            topLine.addView(timeView, tLp);
        }
        body.addView(topLine, lp(-1, -2));

        if (!c.done) {
            SpannableStringBuilder subText = compactSubLine(c);
            subView = uiText("", 12.5f, Ui.MUTE, false);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sLp = lp(-1, -2);
            sLp.topMargin = dp(this, 3);
            if (subText.length() > 0) {
                subView.setText(subText);
                subView.setVisibility(View.VISIBLE);
            } else {
                subView.setVisibility(View.GONE);
            }
            body.addView(subView, sLp);
            if (!unsure && c.deadline > 0) {
                dueBindings.add(new DueBinding(c, timeView, subView, groupName, urgency));
            }
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
        card.setLayoutParams(lp(-1, -2));
        return card;
    }

    /** 卡片类型标签：考试紫、接龙青，普通作业不加标签（供详情底抽屉使用）。 */
    TextView kindTag(String kind) {
        if ("exam".equals(kind)) return Ui.tag(this, "考试", Ui.VIOLET, Ui.VIOLET_BG);
        if ("jielong".equals(kind)) return Ui.tag(this, "接龙", Ui.TEAL, Ui.TEAL_BG);
        if (Engine.PlatformNotice.KIND_SIGNIN.equals(kind)) return Ui.tag(this, "签到", Ui.ORANGE, Ui.ORANGE_BG);
        if (Engine.PlatformNotice.KIND_GRADE.equals(kind)) return Ui.tag(this, "成绩", Ui.GREEN, Ui.GREEN_BG);
        if ("media".equals(kind)) return Ui.tag(this, "图片/语音", Ui.ORANGE, Ui.ORANGE_BG);
        return null;
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
        Engine shown = demo == null ? engine : demo.engine;
        refreshConflicts(shown, now);
        refreshDueBindings(now);
        updateHeadline(shown, now);
        updateWindowTabs(shown, now);
        if (demo == null) updateNotifStatus();
    }

    private void refreshDueBindings(long now) {
        for (DueBinding b : dueBindings) {
            if (b.timeView != null) {
                String t = compactDueLabel(b.card, b.bucket);
                if (!t.contentEquals(b.timeView.getText())) b.timeView.setText(t);
                b.timeView.setTextColor(compactDueColor(b.card, now));
            }
            if (b.subView != null) {
                SpannableStringBuilder next = compactSubLine(b.card);
                if (next.length() > 0) {
                    if (!TextUtils.equals(next, b.subView.getText())) b.subView.setText(next);
                    b.subView.setVisibility(View.VISIBLE);
                } else {
                    b.subView.setVisibility(View.GONE);
                }
            }
        }
    }

    /** 右侧精简时间：同在「今天/明天」分组内直接给 HH:mm，不重复日期；更晚/已过期给 M/d HH:mm。 */
    private static String compactDueLabel(Engine.Card c, String groupName) {
        if ("media".equals(c.kind)) {
            return c.sourceText != null && c.sourceText.contains("[图片]") ? "截图识别" : "看原群";
        }
        if (c.deadlineHint() != null || c.deadline == 0 || c.needsConfirm) return "待确认";
        if ("今天".equals(groupName) || "明天".equals(groupName)) {
            return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new java.util.Date(c.deadline));
        }
        if ("这几天".equals(groupName)) return Engine.humanDeadline(c.deadline);
        return new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(new java.util.Date(c.deadline));
    }

    private static int compactDueColor(Engine.Card c, long now) {
        boolean unsure = c.needsConfirm || c.deadline == 0 || c.deadlineHint() != null;
        int urgency = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);
        if (unsure) return Ui.AMBER_TX;
        if (urgency >= 1) return Ui.urgencyColor(urgency);
        return Ui.MUTE;
    }

    /** 精简教务多级地点：如「长安校区, 教学西楼D座, 教西D-204」只取「教西D-204」。 */
    private static String shortPlace(String raw) {
        if (raw == null) return "";
        String[] segs = raw.split("[,，]");
        List<String> clean = new ArrayList<>();
        for (String s : segs) {
            String t = s.trim();
            if (!t.isEmpty()) clean.add(t);
        }
        if (clean.isEmpty()) return raw.trim();
        String last = clean.get(clean.size() - 1);
        if (clean.size() >= 2 && last.matches("[A-Za-z0-9\\-]+")) {
            return clean.get(clean.size() - 2) + " " + last;
        }
        return last;
    }

    /** 列表第二行：极简「来源（群名/教务系统） · 关键行动线索（考场/提交渠道/待核对） · 冲突」，严格单行、不加图标。 */
    private SpannableStringBuilder compactSubLine(Engine.Card c) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String origin = !TextUtils.isEmpty(c.groupName) ? c.groupName.trim() : sourceName(c);
        if ("演示样例".equals(origin)) origin = "";
        String hint = null;
        int hintColor = Ui.MUTE;
        if ("media".equals(c.kind)) {
            hint = c.sourceText != null && c.sourceText.contains("[图片]") ? "分享截图识别" : "去原群查看";
        } else if (c.changes.hasPending()) {
            hint = "有变更待核对";
            hintColor = Ui.AMBER_TX;
        } else if ("exam".equals(c.kind) && !TextUtils.isEmpty(c.place)) {
            hint = shortPlace(c.place);
        } else if (!TextUtils.isEmpty(c.channel) && !"jielong".equals(c.kind)) {
            hint = c.channel;
        } else if (c.deadlineHint() != null) {
            hint = c.deadlineHint();
            hintColor = Ui.AMBER_TX;
        }
        if (!TextUtils.isEmpty(origin) && !origin.equals(hint)) {
            sb.append(origin);
            sb.setSpan(new ForegroundColorSpan(Ui.MUTE), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!TextUtils.isEmpty(hint)) {
            if (sb.length() > 0) {
                int sep = sb.length();
                sb.append(" · ");
                sb.setSpan(new ForegroundColorSpan(Ui.MUTE), sep, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int s = sb.length();
            sb.append(hint);
            sb.setSpan(new ForegroundColorSpan(hintColor), s, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String conflict = cardConflictTag(c);
        if (conflict != null) {
            if (sb.length() > 0) {
                int sep = sb.length();
                sb.append(" · ");
                sb.setSpan(new ForegroundColorSpan(Ui.MUTE), sep, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int s = sb.length();
            sb.append(conflict);
            sb.setSpan(new ForegroundColorSpan(Ui.ORANGE), s, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static int localDay(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
    }

    private void refreshConflicts(Engine shown, long now) {
        conflicts.refresh(shown, timetable, demo != null, now);
    }

    private String cardConflictTag(Engine.Card c) {
        return conflicts.cardTag(c);
    }

    /** 供卡片详情底抽屉展示当前卡片关联的具体撞课或相近事项，不改变首页布局。 */
    List<String> cardConflictLines(Engine.Card c) {
        Engine shown = demo == null ? engine : demo.engine;
        refreshConflicts(shown, System.currentTimeMillis());
        return conflicts.cardLines(c);
    }

    private void loadTimetableAsync() {
        if (!resumed || timetableIo.isShutdown()) return;
        final int version = ++timetableLoadVersion;
        timetableIo.execute(() -> {
            final Timetable table;
            try { table = TimetableStore.load(getApplicationContext()); }
            catch (Exception ignored) { return; }
            uiHandler.post(() -> {
                if (!resumed || version != timetableLoadVersion || isFinishing() || isDestroyed()) return;
                // Engine 上下文由课表保存/清理同步，异步读出的旧快照不能反向覆盖它。
                timetable = table;
                long now = System.currentTimeMillis();
                Engine shown = demo == null ? engine : demo.engine;
                if (shown != null) {
                    refreshConflicts(shown, now);
                    updateHeadline(shown, now);
                    refreshDueBindings(now);
                }
            });
        });
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

    /** 来源小图标与品牌名解析：委托 HomeSourceStyle 实现。 */
    View sourceMark(Engine.Card c, float sizeDp) { return HomeSourceStyle.sourceMark(this, c, sizeDp); }

    String appNameFor(String pkg) { return HomeSourceStyle.appNameFor(pkg); }

    View sourceMark(String name, String pkg, float sizeDp) { return HomeSourceStyle.sourceMark(this, name, pkg, sizeDp); }

    static String joinDistinct(String... parts) { return HomeSourceStyle.joinDistinct(parts); }

    String sourceName(Engine.Card c) { return HomeSourceStyle.sourceName(c); }

    /** 卡片详情与来源溯源：委托 HomeCardSheets 构建。 */
    void showSource(Engine.Card c) { HomeCardSheets.showSource(this, c); }

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

    /** 已移除的卡片管理：委托 HomeOpsSheets 构建。 */
    void showRemovedCards() { HomeOpsSheets.showRemovedCards(this); }

    /** 到期提醒设置：委托 HomeOpsSheets 构建。 */
    void showDeadlineReminderSettings() { HomeOpsSheets.showDeadlineReminderSettings(this); }

    void enableDeadlineReminder() {
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

    /** 群列表与按群处理模式：委托 HomeOpsSheets 构建。 */
    void showGroupManager() { HomeOpsSheets.showGroupManager(this); }

    void fillGroups() { HomeOpsSheets.fillGroups(this); }

    void chooseGroupMode(String gid, String name, String pkg, String current) {
        HomeOpsSheets.chooseGroupMode(this, gid, name, pkg, current);
    }

    /** 首次安装保活与权限引导：委托 HomeOpsSheets 构建。 */
    void showSetupGuide() { HomeOpsSheets.showSetupGuide(this); }

    void fillSetup() { HomeOpsSheets.fillSetup(this); }

    /** 修正卡片 / 新建编辑手动卡片 / 写入系统日历：委托 HomeCardSheets 构建。 */
    void showCardEdit(Engine.Card card) { HomeCardSheets.showCardEdit(this, card); }

    private void showManualCardForm(Engine.Card card) { HomeCardSheets.showManualCardForm(this, card, null); }

    private void showManualCardForm(Engine.Card card, Bundle draft) {
        HomeCardSheets.showManualCardForm(this, card, draft);
    }

    void addToCalendar(Engine.Card c) { HomeCardSheets.addToCalendar(this, c); }
}
