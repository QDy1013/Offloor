package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Credentials -> official authentication -> current semester -> atomic save, with no preview step. */
public class NwpuImportActivity extends Activity {
    static final String COURSE_URL = "https://jwxt.nwpu.edu.cn/student/for-std/course-table";
    static final String LOGIN_URL = "https://jwxt.nwpu.edu.cn/student/sso-login";
    private WebView web;
    private FrameLayout content;
    private View form, waiting;
    private EditText account, password;
    private TextView status, hint, submit, schoolChoice, accountCaption, passwordCaption, feedback;
    private String importScript, loginScript, trialScript, academicScript, campusScript;
    // One read = timetable + (exams, grades, GPA). The extra part never replaces a good timetable and,
    // Allow all historical semesters to finish; slow or failed terms must not discard successful ones.
    private static final long ACADEMIC_GRACE = 180_000;
    private Timetable pendingTable;
    private String pendingTableError, pendingAcademic, pendingCampus;
    private boolean tableDone, academicDone, campusDone;
    private long academicDeadline, campusDeadline;
    private TimetableSchool school;
    private String lastDiagnostic = "", lastFailure = "";
    private long trialLoadingAt;
    private String pendingAccount = "", pendingPassword = "", readToken;
    private boolean busy, submitted, reading, saving, reuseSession, courseOpened;
    private boolean automaticFallback;
    private long deadline, courseDeadline, readDeadline;
    private int generation;
    private long loginReadyDeadline;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable driver = this::drive;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.apply(this);
        // Login fields and school verification pages must not enter screenshots or recent-task previews.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        school = TimetableSchool.byId(getIntent().getStringExtra("school_id") == null ? getPreferences(MODE_PRIVATE).getString("school", "nwpu") : getIntent().getStringExtra("school_id"));
        automaticFallback=getIntent().getBooleanExtra("automatic_refresh",false);
        try { importScript = asset("nwpu-import.js"); loginScript = asset("nwpu-login.js"); trialScript = asset("school-import.js"); }
        catch (Exception e) { Toast.makeText(this, "导入组件无法加载", Toast.LENGTH_LONG).show(); finish(); return; }
        // Optional: without it the timetable import still works, only exams/grades are skipped.
        try { academicScript = asset("nwpu-academic.js"); } catch (Exception e) { academicScript = null; }
        try { campusScript = asset("nwpu-campus.js"); } catch (Exception e) { campusScript = null; }
        buildUi();
        if (getIntent().getBooleanExtra("reuse_session", false)) startWithSession();
        else if (Intent.ACTION_MAIN.equals(getIntent().getAction())) io.execute(() -> {
            try {
                Timetable existing = TimetableStore.load(getApplicationContext());
                if (existing != null) runOnUiThread(() -> { if (!busy && !isFinishing() && !isDestroyed()) { startActivity(new Intent(this, TimetableActivity.class)); finish(); } });
            } catch (Exception ignored) { }
        });
    }
    private String asset(String name) throws Exception {
        try (java.io.InputStream in = getAssets().open(name); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }
    private int dp(float n) { return Ui.dp(this, n); }
    private TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this, s, size, color, bold); }
    private void buildUi() {
        LinearLayout root = CourseUi.column(this); root.setBackgroundColor(Ui.BG);
        LinearLayout bar = new LinearLayout(this); bar.setBaselineAligned(false); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(8), dp(6), dp(16), dp(6));
        root.addView(bar);
        TextView back = text("‹", 29, Ui.TEXT, false); back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回"); back.setOnClickListener(v -> { if (!saving) finish(); });
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(text("校园账号登录", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        boolean testBuild = getPackageName().equals("com.nopalou.preview") || getPackageName().equals("com.nopalou.timetabletest");
        TextView secure = Ui.tag(this, testBuild ? "测试版" : "校方认证", Ui.ACCENT, Ui.ACCENT_BG); bar.addView(secure);
        TextView update=text("更新",12,Ui.SUB,true); update.setGravity(Gravity.CENTER); update.setContentDescription("下载最新版");
        update.setOnClickListener(v -> startActivity(new Intent(this,UpdateActivity.class))); bar.addView(update,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout intro = CourseUi.column(this); intro.setPadding(dp(24), dp(14), dp(24), dp(20)); root.addView(intro);
        status = text("登录一次，课表就位", 25, Ui.TEXT, true); intro.addView(status);
        hint = text("自动获取本学期课程，保存后随时查看", 12, Ui.SUB, false); hint.setPadding(0, dp(10), 0, 0); intro.addView(hint);
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this); web.setVisibility(View.INVISIBLE); content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        configureWeb();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout fields = CourseUi.column(this); fields.setPadding(dp(24), dp(4), dp(24), dp(24)); scroll.addView(fields);
        LinearLayout schoolRow = new LinearLayout(this); schoolRow.setGravity(Gravity.CENTER_VERTICAL);
        schoolRow.setPadding(dp(18), dp(18), dp(18), dp(18)); schoolRow.setBackground(Ui.ripple(this, Ui.round(this, Ui.CARD, 16), Ui.press(), 16)); fields.addView(schoolRow);
        schoolChoice = text("", 16, Ui.TEXT, true); schoolRow.addView(schoolChoice, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView choose = new android.widget.ImageView(this); choose.setImageDrawable(CourseUi.glyph(this, "down")); schoolRow.addView(choose, new LinearLayout.LayoutParams(dp(20), dp(20)));
        schoolRow.setContentDescription("选择学校"); schoolRow.setOnClickListener(v -> chooseSchool()); schoolRow.setFocusable(true);
        CourseUi.gap(fields, 25); accountCaption = text("账号", 12, Ui.SUB, true); fields.addView(accountCaption); CourseUi.gap(fields, 9);
        account = input("请输入账号", false); fields.addView(account, CourseUi.row(dp(54)));
        CourseUi.gap(fields, 20); passwordCaption = text("密码", 12, Ui.SUB, true); fields.addView(passwordCaption); CourseUi.gap(fields, 9);
        LinearLayout passwordRow = new LinearLayout(this); passwordRow.setGravity(Gravity.CENTER_VERTICAL);
        passwordRow.setBackground(Ui.round(this, Ui.CARD, 14)); fields.addView(passwordRow, CourseUi.row(dp(54)));
        password = input("请输入密码", true); password.setBackgroundColor(Color.TRANSPARENT);
        passwordRow.addView(password, new LinearLayout.LayoutParams(0, -1, 1));
        TextView eye = text("显示", 12, Ui.SUB, true); eye.setGravity(Gravity.CENTER); eye.setContentDescription("显示或隐藏密码");
        passwordRow.addView(eye, new LinearLayout.LayoutParams(dp(52), -1));
        eye.setOnClickListener(v -> {
            boolean hidden = password.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod;
            password.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
            password.setSelection(password.length()); eye.setText(hidden ? "隐藏" : "显示");
        });
        CourseUi.gap(fields, 28);
        submit = CourseUi.action(this, "一键导入", true, this::startWithCredentials); fields.addView(submit, CourseUi.row(dp(52)));
        CourseUi.gap(fields, 16);
        TextView note = text("账号密码仅用于学校认证，不在本机保存。\n如学校要求验证码，完成后会自动继续导入。", 11, Ui.SUB, false);
        note.setLineSpacing(dp(4), 1f); fields.addView(note);
        CourseUi.gap(fields, 15);
        feedback = CourseUi.action(this, "反馈问题", false, this::shareFeedback); feedback.setVisibility(View.GONE); intro.addView(feedback, CourseUi.row(dp(46)));
        updateSchool();
        password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        password.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { startWithCredentials(); return true; } return false; });
        form = scroll; content.addView(form, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout progress = CourseUi.column(this); progress.setGravity(Gravity.CENTER); progress.setPadding(dp(24),0,dp(24),0); progress.setBackgroundColor(Ui.BG);
        ProgressBar spinner = new ProgressBar(this); progress.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
        CourseUi.gap(progress, 22); TextView progressNote=text("登录 · 自动读取 · 保存到本机",13,Ui.SUB,false); progressNote.setGravity(Gravity.CENTER); progress.addView(progressNote,new LinearLayout.LayoutParams(-1,-2));
        waiting = progress; content.addView(waiting, new FrameLayout.LayoutParams(-1, -1)); waiting.setVisibility(View.GONE);
        setContentView(root); Ui.systemBars(this, root);
    }
    private void chooseSchool() {
        if (busy || saving) return;
        String[] names = new String[TimetableSchool.ALL.length];
        for (int i = 0; i < names.length; i++) names[i] = TimetableSchool.ALL[i].name + (TimetableSchool.ALL[i].trial ? "（测试中）" : "");
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("选择你的学校").setItems(names, (d, i) -> {
            school = TimetableSchool.ALL[i]; account.setText(""); password.setText(""); lastDiagnostic = ""; lastFailure = "";
            getPreferences(MODE_PRIVATE).edit().putString("school", school.id).apply(); updateSchool(); feedback.setVisibility(View.GONE);
        }).setNegativeButton("取消", null).show();
    }
    private void updateSchool() {
        schoolChoice.setText(school.name); accountCaption.setText(school.accountLabel); passwordCaption.setText(school.passwordLabel);
        status.setText(school.trial?"登录一次，课表就位":"登录一次，校园信息自动读取");
        hint.setText(school.trial ? "这所学校正在测试，遇到问题可直接反馈" : "课表、成绩及各项校园信息自动读取，无需手动同步");
        submit.setText(school.trial?"一键导入":"登录并自动读取");
    }
    private boolean schoolNavigationAllowed(Uri u) {
        return "https".equals(u.getScheme()) && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null && TimetableSchool.hostAllowed(u.getHost(), school.domains);
    }
    private EditText input(String placeholder, boolean secret) {
        EditText e = new EditText(this); e.setHint(placeholder); e.setTextSize(15); e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.MUTE);
        e.setSingleLine(true); e.setSaveEnabled(false); e.setPadding(dp(16), 0, dp(12), 0); e.setBackground(Ui.round(this, Ui.CARD, 14));
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        e.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)); e.getPaint().setTextSkewX(0); e.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        return e;
    }
    private void configureWeb() {
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setSaveFormData(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !schoolNavigationAllowed(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return !schoolNavigationAllowed(Uri.parse(u)); }
            @Override public void onPageFinished(WebView v, String u) {
                if (busy && !reading && !saving) schedule(150);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && busy) fail("学校网站暂时打不开，请连接校园网后重试；仍不行可点“反馈问题”");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) { h.cancel(); fail("暂时无法安全连接学校网站，请点“反馈问题”"); }
        });
    }
    static boolean navigationAllowed(Uri u) {
        String h = u.getHost(); return "https".equals(u.getScheme()) && (u.getPort() == -1 || u.getPort() == 443) &&
                h != null && (h.equals("nwpu.edu.cn") || h.endsWith(".nwpu.edu.cn")) && u.getUserInfo() == null;
    }
    static boolean isCourse(String url) {
        if (url == null) return false; Uri u = Uri.parse(url); String p = u.getPath();
        return navigationAllowed(u) && "jwxt.nwpu.edu.cn".equals(u.getHost()) && p != null &&
                (p.equals("/student/for-std/course-table") || p.startsWith("/student/for-std/course-table/"));
    }
    private boolean isLogin(String url) {
        if (url == null) return false; Uri u = Uri.parse(url);
        return navigationAllowed(u) && "uis.nwpu.edu.cn".equals(u.getHost()) && "/cas/login".equals(u.getPath());
    }
    private void startWithCredentials() {
        if (busy || saving) return;
        if (account.getText().toString().trim().isEmpty()) { account.setError("请输入账号"); return; }
        if (password.length() == 0) { password.setError("请输入密码"); return; }
        automaticFallback=false;
        pendingAccount = account.getText().toString().trim(); pendingPassword = password.getText().toString();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(password.getWindowToken(), 0);
        reset(false); int active = generation;
        // A freshly entered account must never accidentally import a different existing session.
        CookieManager.getInstance().removeAllCookies(value -> {
            if (!busy || active != generation || isDestroyed()) return;
            CookieManager.getInstance().flush(); android.webkit.WebStorage.getInstance().deleteAllData();
            web.clearCache(true); web.loadUrl(school.entry); schedule(500);
        });
    }
    private void startWithSession() { reset(true); web.loadUrl(school.entry); schedule(500); }
    private void reset(boolean reuse) {
        generation++; busy = true; submitted = false; reading = false; courseOpened = false; reuseSession = reuse;
        deadline = android.os.SystemClock.elapsedRealtime() + 60_000; courseDeadline = 0; loginReadyDeadline = 0;
        trialLoadingAt = 0; lastDiagnostic = ""; lastFailure = ""; feedback.setVisibility(View.GONE);
        showProgress("正在连接学校认证", "认证完成后自动读取并保存课表"); submit.setEnabled(false);
    }
    private void showProgress(String title, String detail) {
        status.setText(title); hint.setText(detail); status.setGravity(Gravity.CENTER); hint.setGravity(Gravity.CENTER); form.setVisibility(View.GONE); web.setVisibility(View.INVISIBLE); waiting.setVisibility(View.VISIBLE);
    }
    private void showChallenge() {
        deadline = android.os.SystemClock.elapsedRealtime() + 300_000;
        status.setText("请完成学校安全验证"); hint.setText("验证通过后自动导入，无需再点其他按钮");
        form.setVisibility(View.GONE); waiting.setVisibility(View.GONE); web.setVisibility(View.VISIBLE);
    }
    private void schedule(long delay) { handler.removeCallbacks(driver); if (busy && !reading && !saving) handler.postDelayed(driver, delay); }
    private JSONObject result(String value) throws Exception {
        Object raw = new JSONTokener(value).nextValue(); return raw instanceof String ? new JSONObject((String) raw) : new JSONObject();
    }
    private void drive() {
        if (!busy || reading || saving || isFinishing() || isDestroyed()) return;
        if (android.os.SystemClock.elapsedRealtime() > deadline) { fail("登录等待超时，请重新导入"); return; }
        String url = web.getUrl(); int active = generation;
        if (school.trial) { driveTrial(active); return; }
        if (isCourse(url)) {
            if (courseDeadline == 0) courseDeadline = android.os.SystemClock.elapsedRealtime() + 20_000;
            showProgress("正在读取本学期课表", "自动识别学期与开学日期");
            web.evaluateJavascript(importScript + "\nJSON.stringify((function(){try{NwpuImport.context(window,document);return {ready:true};}catch(e){return {ready:false};}})())", value -> {
                if (!current(active)) return;
                try { if (result(value).optBoolean("ready")) { beginRead(); return; } } catch (Exception ignored) { }
                if (android.os.SystemClock.elapsedRealtime() > courseDeadline) { fail("未能取得当前学期课表，请稍后重试"); return; }
                schedule(500);
            }); return;
        }
        if (isLogin(url)) {
            if (loginReadyDeadline == 0) loginReadyDeadline = android.os.SystemClock.elapsedRealtime() + 20_000;
            web.evaluateJavascript(loginScript + "\nJSON.stringify(NwpuLogin.inspect())", value -> {
                if (!current(active)) return;
                try {
                    JSONObject r = result(value); String phase = r.optString("phase");
                    if ("loading".equals(phase) && android.os.SystemClock.elapsedRealtime() > loginReadyDeadline) {
                        fail("学校认证页未加载完成或已更新，请稍后重试"); return;
                    }
                    if ("error".equals(phase)) { fail(r.optString("message", "学校未通过登录验证")); return; }
                    if ("challenge".equals(phase)) { showChallenge(); schedule(600); return; }
                    if ("ready".equals(phase)) {
                        if (reuseSession && pendingPassword.isEmpty()) { automaticFallback=false; fail("校方登录状态已过期，登录后会自动继续读取"); return; }
                        if (!submitted && !pendingPassword.isEmpty()) { submitCredentials(active); return; }
                        if (submitted) { fail("学校未完成登录验证，请核对账号密码后重试"); return; }
                    } else if ("submitted".equals(phase)) {
                        showProgress("学校正在验证登录", "认证成功后会自动读取课表");
                    }
                } catch (Exception ignored) { }
                schedule(600);
            }); return;
        }
        Uri page = url == null ? Uri.EMPTY : Uri.parse(url);
        if ("jwxt.nwpu.edu.cn".equals(page.getHost()) && !"/student/sso-login".equals(page.getPath())) {
            if ("/student/login".equals(page.getPath())) { fail("学校统一认证未完成，请重新导入"); return; }
            if (!courseOpened) { courseOpened = true; showProgress("认证成功，正在获取课表", "自动读取当前学期"); web.loadUrl(COURSE_URL); }
        } else if (page.getHost() != null && page.getHost().endsWith("nwpu.edu.cn") && submitted) showChallenge();
        schedule(600);
    }
    private void driveTrial(int active) {
        if (web.getUrl() == null || !schoolNavigationAllowed(Uri.parse(web.getUrl()))) { schedule(500); return; }
        web.evaluateJavascript(trialScript + "\nSchoolImport.setup(" + school.config() + ");JSON.stringify(SchoolImport.inspect())", value -> {
            if (!current(active)) return;
            try {
                JSONObject r = result(value); String phase = r.optString("phase");
                if ("error".equals(phase)) { fail(r.optString("message", "还需要适配这个学校，请点“反馈问题”")); return; }
                if ("courses".equals(phase)) { save(new Timetable(r.getJSONObject("data").toString()), active); return; }
                if ("challenge".equals(phase)) { showChallenge(); schedule(600); return; }
                if ("navigate".equals(phase)) {
                    showProgress("正在进入学校课表", "稍等一下，读到后会自动保存");
                    web.evaluateJavascript("SchoolImport.navigate()", ignored -> schedule(600)); return;
                }
                if ("ready".equals(phase)) {
                    if (reuseSession && pendingPassword.isEmpty()) { fail("登录已过期，请输入账号密码重试"); return; }
                    if (!submitted && !pendingPassword.isEmpty()) {
                        web.evaluateJavascript("JSON.stringify(SchoolImport.submit(" + JSONObject.quote(pendingAccount) + "," + JSONObject.quote(pendingPassword) + "))", reply -> {
                            if (!current(active)) return;
                            try {
                                JSONObject sent = result(reply);
                                if ("error".equals(sent.optString("phase"))) { fail(sent.optString("message")); return; }
                                if ("submitted".equals(sent.optString("phase"))) { submitted = true; pendingAccount = ""; pendingPassword = ""; password.setText(""); showProgress("正在登录学校系统", "课表读到后会自动保存"); }
                            } catch (Exception ignored) { }
                            schedule(600);
                        }); return;
                    }
                    if (submitted) { fail("学校还没有完成登录，请点“反馈问题”帮我们继续适配"); return; }
                }
                if ("loading".equals(phase)) {
                    if (trialLoadingAt == 0) trialLoadingAt = android.os.SystemClock.elapsedRealtime();
                    if (android.os.SystemClock.elapsedRealtime() - trialLoadingAt > 20_000) { fail("学校页面和预期不一样，请点“反馈问题”"); return; }
                } else trialLoadingAt = 0;
            } catch (Exception e) { fail("暂时没有读到课表，请点“反馈问题”"); return; }
            schedule(600);
        });
    }
    private String safePage() {
        try {
            Uri u = Uri.parse(web.getUrl() == null ? school.entry : web.getUrl());
            String path = u.getPath() == null ? "" : u.getPath();
            java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList("cas", "login", "student", "sso-login", "home", "authserver", "auth", "login.action", "loginXsJs.html", "loginCAS", "jwgl", "login.do", "main", "index", "index.html", "portal", "mobile", "login.html", "newservice", "for-std", "course-table"));
            StringBuilder safe = new StringBuilder();
            for (String part : path.split("/")) if (!part.isEmpty()) safe.append('/').append(known.contains(part) ? part : "[page]");
            return u.getHost() + safe;
        } catch (Exception e) { return school.id; }
    }
    private void collectDiagnostics() {
        final String id = school.id, page = safePage();
        lastDiagnostic = "页面：" + page;
        if (!school.trial || web.getUrl() == null || !schoolNavigationAllowed(Uri.parse(web.getUrl()))) return;
        web.evaluateJavascript(trialScript + "\nSchoolImport.setup(" + school.config() + ");JSON.stringify(SchoolImport.diagnostics())", value -> {
            if (!school.id.equals(id) || isDestroyed()) return;
            try { String structure = result(value).toString(); if (structure.length() < 6000) lastDiagnostic = "页面：" + page + "\n页面结构：" + structure; }
            catch (Exception ignored) { }
        });
    }
    private void shareFeedback() {
        String version = "未知";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        String report = "Offloor 课表测试反馈\n学校：" + school.name + "\n结果：" + lastFailure + "\n版本：" + version + "\n手机：" + android.os.Build.MODEL + " / Android " + android.os.Build.VERSION.RELEASE
                + "\n\n以下信息由应用自动生成，不用修改：\n" + lastDiagnostic + "\n\n不含账号、密码、Cookie或课表原文。";
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(send, "发给邀请你测试的人"));
    }
    private boolean current(int active) { return active == generation && busy && !isFinishing() && !isDestroyed(); }
    private void submitCredentials(int active) {
        String expression = "JSON.stringify(NwpuLogin.submit(" + JSONObject.quote(pendingAccount) + "," + JSONObject.quote(pendingPassword) + "))";
        web.evaluateJavascript(expression, value -> {
            if (!current(active)) return;
            try {
                JSONObject r = result(value);
                if ("error".equals(r.optString("phase"))) { fail(r.optString("message")); return; }
                if ("submitted".equals(r.optString("phase"))) {
                    submitted = true; pendingAccount = ""; pendingPassword = ""; password.setText("");
                    showProgress("学校正在验证登录", "如需安全验证，会自动显示校方页面");
                }
            } catch (Exception ignored) { }
            schedule(500);
        });
    }
    private void beginRead() {
        if (reading || saving || !busy) return;
        reading = true; readToken = java.util.UUID.randomUUID().toString();
        long now = android.os.SystemClock.elapsedRealtime(); readDeadline = now + 22_000; academicDeadline = now + ACADEMIC_GRACE;
        pendingTable = null; pendingTableError = null; pendingAcademic = null; pendingCampus = null;
        tableDone = false; academicDone = "nwpu".equals(school.id) || academicScript == null; campusDone = campusScript == null; campusDeadline = now + 30_000;
        String token = readToken, quoted = JSONObject.quote(token); int active = generation;
        // The identity is the one NwpuImport already confirmed on this page; nothing is guessed.
        String script = academicDone ? "NwpuImport.start(" + quoted + ");" : academicScript + "\nNwpuImport.start(" + quoted + ");(function(){try{NwpuAcademic.start(" + quoted
                + ",NwpuImport.context(window,document).student);}catch(e){window.__louAcademicResult={token:" + quoted + ",status:'error'};}})();";
        if (campusScript != null) script += "\n" + campusScript + "\n(function(){try{NwpuCampus.start(" + quoted
                + ",NwpuImport.context(window,document).student);}catch(e){window.__louCampusResult={token:" + quoted + ",status:'error'};}})();";
        web.evaluateJavascript(script, ignored -> pollRead(active, token));
    }
    private void pollRead(int active, String token) {
        if (!current(active) || !reading || !token.equals(readToken)) return;
        if (!isCourse(web.getUrl())) { fail("课表页面已变化，本次未保存，请重试"); return; }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!tableDone && now > readDeadline) { tableDone = true; pendingTableError = "读取课表超时，请稍后重试"; }
        if (!academicDone && now > academicDeadline) academicDone = true;
        if (!campusDone && now > campusDeadline) campusDone = true;
        if (tableDone && academicDone && campusDone) { finishRead(active); return; }
        web.evaluateJavascript("JSON.stringify({t:window.__louTimetableResult||null,a:window.__louAcademicResult||null,c:window.__louCampusResult||null})", value -> {
            if (!current(active) || !reading || !token.equals(readToken)) return;
            JSONObject both;
            try { both = result(value); } catch (Exception e) { both = new JSONObject(); }
            JSONObject t = both.optJSONObject("t"), a = both.optJSONObject("a");
            if (!tableDone && t != null && token.equals(t.optString("token"))) {
                try {
                    if ("error".equals(t.optString("status"))) { tableDone = true; pendingTableError = t.optString("message", "课表读取失败"); }
                    else if ("ok".equals(t.optString("status"))) {
                        tableDone = true;
                        Timetable parsed = new Timetable(t.getJSONObject("data").toString());
                        if (parsed.firstMonday.isEmpty()) pendingTableError = "未取得学期起始日期，本次未保存"; else pendingTable = parsed;
                    }
                } catch (Exception e) { tableDone = true; pendingTableError = "课表格式暂不受支持，原课表已保留"; }
                if (tableDone && !academicDone) {
                    showProgress("正在读取考试与成绩", "考试安排、各学期成绩和绩点");
                }
            }
            if (!academicDone && a != null && token.equals(a.optString("token"))) {
                String st = a.optString("status");
                // "partial" snapshots mark unfinished parts as failed, so they are safe to keep if time runs out.
                if (("ok".equals(st) || "partial".equals(st)) && a.optJSONObject("data") != null) pendingAcademic = a.optJSONObject("data").toString();
                JSONObject progress=a.optJSONObject("data")==null?null:a.optJSONObject("data").optJSONObject("gradeProgress");
                if (tableDone && progress!=null) showProgress("正在读取所有学期成绩", "已读取 "+progress.optInt("finished")+" / "+progress.optInt("total")+" 个学期，完成后自动继续校园服务");
                if ("ok".equals(st) || "error".equals(st)) academicDone = true;
            }
            JSONObject c = both.optJSONObject("c");
            if (!campusDone && c != null && token.equals(c.optString("token"))) {
                String st = c.optString("status");
                if (("ok".equals(st) || "partial".equals(st) || "loading".equals(st)) && c.optJSONObject("data") != null) pendingCampus = c.optJSONObject("data").toString();
                if ("ok".equals(st) || "error".equals(st) || (pendingCampus!=null && tableDone)) campusDone = true;
            }
            if (tableDone && academicDone && campusDone) { finishRead(active); return; }
            handler.postDelayed(() -> pollRead(active, token), 350);
        });
    }
    private void finishRead(int active) {
        if (pendingTable != null) { save(pendingTable, active, pendingAcademic); return; }
        String message = pendingTableError == null ? "课表读取失败" : pendingTableError, academic = pendingAcademic;
        String campus = pendingCampus;
        if (academic == null && campus == null) { fail(message); return; }
        // No usable timetable (e.g. vacation), but exams and grades still deserve saving: show them instead of a failure.
        saving = true; reading = false; handler.removeCallbacksAndMessages(null);
        io.execute(() -> {
            boolean ok;
            try { saveExtras(academic, campus); ok = true; } catch (Exception e) { ok = false; }
            boolean saved = ok;
            runOnUiThread(() -> {
                saving = false;
                if (!saved) { fail(message); return; }
                if (!current(active)) return;
                Toast.makeText(this, "课表暂未更新，继续自动读取校园信息", Toast.LENGTH_SHORT).show();
                completeLogin(false);
            });
        });
    }
    private void save(Timetable table, int active) { save(table, active, null); }
    private void save(Timetable table, int active, String academic) {
        saving = true; reading = false; handler.removeCallbacksAndMessages(null);
        showProgress("正在保存课表与成绩", "随后自动读取其他校园信息，无需操作");
        io.execute(() -> {
            try {
                Academic extra = saveExtras(academic, pendingCampus);
                TimetableStore.save(getApplicationContext(), table);
                String summary = extra == null ? "" : "，" + extra.exams.size() + " 场考试、" + extra.grades.size() + " 门成绩";
                runOnUiThread(() -> { saving = false; if (!current(active)) return;
                    Toast.makeText(this, "已导入 " + table.lessons.size() + " 条上课安排" + summary, Toast.LENGTH_SHORT).show();
                    completeLogin(true); });
            } catch (Exception e) { runOnUiThread(() -> { saving = false; fail("保存未完成，请重新同步"); }); }
        });
    }
    private void completeLogin(boolean hasTable) {
        boolean incomplete=!hasTable;
        if ("nwpu".equals(school.id)) {
            try { JSONObject a=pendingAcademic==null?null:new JSONObject(pendingAcademic); incomplete|=a!=null && a.optJSONArray("failed")!=null && a.getJSONArray("failed").length()>0; } catch (Exception e) { incomplete=true; }
        }
        String courseUrl=web.getUrl(); if (isCourse(courseUrl) && CampusServices.readUrl(courseUrl)) getIntent().putExtra("course_url",courseUrl);
        setResult(RESULT_OK,new Intent().putExtra("academic_incomplete",incomplete).putExtra("course_url",getIntent().getStringExtra("course_url")));
        getIntent().putExtra("academic_incomplete",incomplete);
        if (CampusAutoPolicy.fullReadAfterLogin(school.id,getIntent().getBooleanExtra("auth_only",false))) {
            showProgress("登录成功，正在读取校园信息", "自动进入各服务并保存本人可见的信息");
            CampusAutoRefresh.afterLogin(this);
        } else {
            if (!getIntent().getBooleanExtra("auth_only",false) && Intent.ACTION_MAIN.equals(getIntent().getAction())) startActivity(new Intent(this,hasTable?TimetableActivity.class:CampusActivity.class));
            finish();
        }
    }
    private Academic saveExtras(String academic, String campus) throws Exception {
        if (campus != null) CampusStore.bind(getApplicationContext(),campus);
        if (academic != null) try { return AcademicStore.update(getApplicationContext(), academic); } catch (Exception ignored) { }
        return null;
    }
    private void fail(String message) {
        if (saving || isDestroyed()) return;
        if (automaticFallback) {
            automaticFallback=false; generation++; busy=false; handler.removeCallbacksAndMessages(null);
            setResult(RESULT_OK,new Intent().putExtra("academic_incomplete",true)); finish(); return;
        }
        generation++; busy = false; reading = false; submitted = false; pendingAccount = ""; pendingPassword = "";
        handler.removeCallbacksAndMessages(null); web.stopLoading(); password.setText("");
        web.setVisibility(View.INVISIBLE); waiting.setVisibility(View.GONE); form.setVisibility(View.VISIBLE); submit.setEnabled(true);
        status.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); hint.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        status.setText("重新试一次"); hint.setText(message == null || message.isEmpty() ? "学校认证未完成，请重试" : message);
        lastFailure = hint.getText().toString(); feedback.setVisibility(View.VISIBLE); collectDiagnostics();
    }
    @Override public void onBackPressed() { if (!saving) super.onBackPressed(); }
    @Override public void onDestroy() {
        generation++; busy = false; pendingAccount = ""; pendingPassword = ""; handler.removeCallbacksAndMessages(null);
        if (password != null) password.setText("");
        if (web != null) { web.stopLoading(); content.removeView(web); web.destroy(); web = null; }
        io.shutdown(); super.onDestroy();
    }
}
