package com.nopalou;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * 首页运维与设置类弹窗/面板（从 MainActivity 拆出，保持主活动结构清晰）：
 * 自动读取自检、诊断日志、已移除卡片恢复、到期提醒设置、按群管理、后台保活引导。
 */
final class HomeOpsSheets {
    private HomeOpsSheets() { }

    static void showHealthCheck(MainActivity a) {
        HealthCheck.Result r = HealthCheck.evaluate(WatchdogJob.accessEnabled(a),
                EngineHolder.listenerConnected, WatchdogJob.batteryIgnored(a),
                EngineHolder.lastReceivedAt, System.currentTimeMillis());

        StringBuilder body = new StringBuilder();
        if (r.issues.isEmpty()) {
            body.append("通知读取权限已开，服务已连接，电池不受限。");
        } else {
            for (int i = 0; i < r.issues.size(); i++) {
                if (i > 0) body.append("\n");
                body.append("· ").append(r.issues.get(i).text);
            }
        }
        if (EngineHolder.lastReceivedAt > 0) {
            body.append("\n最近一次自动收到：").append(a.relativeTime(EngineHolder.lastReceivedAt));
        } else {
            body.append("\n尚未收到可读取的消息，可以发一条测试通知检查。");
        }
        body.append("\n\n仅能整理系统实际展示的通知文字。免打扰、折叠或被隐藏的消息，以及历史聊天和图片内部文字，不保证自动获取；可主动分享文字或截图补充。");
        body.append("\n\n保持自动读取不被系统杀掉：\n")
                .append("1. 在系统设置里允许「Offloor」自启动和后台运行（vivo：设置 > 应用与权限 > 权限管理 > 自启动）。\n")
                .append("2. 电池设置里把「Offloor」设为不限制或允许后台高耗电。\n")
                .append("3. 在最近任务里不要上滑清掉它；可以长按卡片锁定。\n")
                .append("4. 重启手机后打开一次 App，确认状态是「已连接」。");

        if (!EngineHolder.listenerConnected && WatchdogJob.accessEnabled(a)) {
            if (NotifRecovery.failed()) {
                body.append("\n\n自动恢复尚未成功。请在系统「通知使用权」里关闭再开启 Offloor，然后返回应用；无需清空数据或重新安装。");
            } else if (NotifRecovery.running()) {
                body.append("\n\n正在检查系统连接结果，请稍等；发出请求不代表已经连接。");
            }
        }
        boolean needAccess = hasFix(r, "NOTIF_ACCESS");
        boolean needRebind = hasFix(r, "REBIND");
        boolean needBattery = hasFix(r, "BATTERY");

        AlertDialog.Builder builder = a.alert()
                .setTitle(r.summary())
                .setMessage(body.toString())
                .setNegativeButton("知道了", null);

        if (needAccess) {
            builder.setPositiveButton("去开启", (d, w) -> {
                try {
                    a.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Exception ignored) {
                    Toast.makeText(a, "这台手机没有通知使用权页面，请到系统设置里手动找", Toast.LENGTH_LONG).show();
                }
            });
        } else if (needRebind) {
            if (NotifRecovery.failed()) {
                builder.setPositiveButton("重开通知权限", (d, w) -> {
                    a.retryNotifOnResume = true;
                    try { a.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
                    catch (Exception e) {
                        Toast.makeText(a, "请到系统设置的通知使用权中，关闭再开启 Offloor", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                builder.setPositiveButton("重新连接", (d, w) -> {
                    NotifRecovery.request(a, true);
                    Toast.makeText(a, "正在恢复通知读取，将检查连接结果", Toast.LENGTH_SHORT).show();
                });
            }
        } else if (needBattery) {
            builder.setPositiveButton("去电池设置", (d, w) -> SetupGuide.requestBattery(a));
        }

        if (needBattery && (needAccess || needRebind)) {
            builder.setNeutralButton("电池设置", (d, w) -> SetupGuide.requestBattery(a));
        }
        builder.show();
    }

    private static boolean hasFix(HealthCheck.Result r, String fix) {
        for (int i = 0; i < r.issues.size(); i++) {
            if (fix.equals(r.issues.get(i).fix)) return true;
        }
        return false;
    }

    static void showDiagLog(MainActivity a) {
        List<DiagLog.Entry> list = DiagLog.recent(30);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        StringBuilder body = new StringBuilder();
        body.append("累计 收到 ").append(DiagLog.count("收到"))
                .append(" / 跳过 ").append(DiagLog.count("跳过"))
                .append(" / 出错 ").append(DiagLog.count("出错"))
                .append(" / 连接 ").append(DiagLog.count("连接"))
                .append(" / 断开 ").append(DiagLog.count("断开"))
                .append(" / 重连 ").append(DiagLog.count("重连"));
        if (list.isEmpty()) {
            body.append("\n\n还没有记录");
        } else {
            for (int i = 0; i < list.size(); i++) {
                DiagLog.Entry e = list.get(i);
                String detail = e.detail == null ? "" : e.detail.replace('\n', ' ').replace('\r', ' ');
                if (detail.length() > 40) detail = detail.substring(0, 40) + "…";
                body.append('\n').append(fmt.format(new java.util.Date(e.time)))
                        .append(' ').append(e.kind).append(' ').append(detail);
            }
        }

        TextView text = new TextView(a);
        text.setText(body.toString());
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        text.setPadding(MainActivity.dp(a, 20), MainActivity.dp(a, 20), MainActivity.dp(a, 20), MainActivity.dp(a, 20));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(text);

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

        AlertDialog.Builder b = a.alert()
                .setTitle("诊断日志")
                .setView(scroll)
                .setPositiveButton("关闭", null);
        if (samples.length() > 0) {
            String copied = samples.toString();
            b.setNeutralButton("复制平台原文", (d, w) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) a.getSystemService(MainActivity.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("学习平台通知原文", copied));
                    Toast.makeText(a, "已复制学习平台通知原文", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }

    static void showRemovedCards(MainActivity a) {
        Engine shown = a.demo == null ? a.engine : a.demo.engine;
        List<Engine.Card> removed = shown.removedCards();
        if (removed.isEmpty()) {
            a.alert().setTitle("已移除的卡片")
                    .setMessage("这里是空的。误移除的卡片可以从这里恢复。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] titles = new String[removed.size()];
        for (int i = 0; i < removed.size(); i++) {
            Engine.Card card = removed.get(i);
            titles[i] = card.displayTitle() + "\n" + a.sourceName(card) + " · " + card.groupName;
            if (card.cancelled) titles[i] = "已被来源取消 · " + titles[i];
        }
        a.alert()
                .setTitle("已移除的卡片（" + removed.size() + "）")
                .setItems(titles, (dialog, which) -> {
                    Engine.Card card = removed.get(which);
                    a.alert()
                            .setTitle(card.displayTitle())
                            .setMessage("来源：" + a.sourceName(card) + " · " + card.groupName + "\n截止时间：" + HomeCardSheets.dateTimeLabel(card.deadline)
                                    + "\n移除时间：" + HomeCardSheets.dateTimeLabel(card.removedAt)
                                    + "\n\n原消息：\n" + card.sourceText
                                    + "\n\n恢复后请核对时间，移除期间的群消息变更可能未应用到这张卡。")
                            .setPositiveButton("恢复", (d, w) -> {
                                if (shown.restoreCard(card)) {
                                    if (a.demo == null) EngineHolder.saveAsync(a);
                                    a.showAllCards();
                                    Toast.makeText(a, "已恢复，请核对事项时间", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(a, "当前已有同一张卡或更新的接龙，保留当前版本", Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("返回列表", (d, w) -> showRemovedCards(a))
                            .show();
                })
                .setNeutralButton("清空已移除", (dialog, which) -> a.alert()
                        .setTitle("清空已移除的卡片？")
                        .setMessage("这些卡片将无法恢复。")
                        .setPositiveButton("清空", (d, w) -> {
                            shown.clearRemovedCards();
                            if (a.demo == null) EngineHolder.saveAsync(a);
                        })
                        .setNegativeButton("取消", (d, w) -> showRemovedCards(a))
                        .show())
                .setNegativeButton("关闭", null)
                .show();
    }

    static void showDeadlineReminderSettings(MainActivity a) {
        boolean enabled = ReminderReceiver.isEnabled(a);
        String state = enabled
                ? (ReminderReceiver.canNotify(a) ? "已开启" : "已开启，系统通知权限待确认")
                : "已关闭";
        a.alert()
                .setTitle("到期提醒 · " + state)
                .setMessage("给时间已确认的未完成事项安排提醒。提前两小时进入提醒窗口，实际送达时间受系统调度影响。通知栏只提示有待办即将到期。")
                .setPositiveButton(enabled ? "关闭提醒" : "开启提醒", (dialog, which) -> {
                    if (enabled) {
                        ReminderReceiver.setEnabled(a, a.engine, false);
                    } else if (Build.VERSION.SDK_INT >= 33
                            && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, MainActivity.REQUEST_REMINDER_PERMISSION);
                    } else {
                        a.enableDeadlineReminder();
                    }
                })
                .setNeutralButton("系统设置", (dialog, which) -> openAppNotifications(a))
                .setNegativeButton("返回", null)
                .show();
    }

    private static void openAppNotifications(MainActivity a) {
        Intent settings;
        if (Build.VERSION.SDK_INT >= 26) {
            settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
        } else {
            settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()));
        }
        a.startActivity(settings);
    }

    static void showGroupManager(MainActivity a) {
        if (a.groupDialog != null && a.groupDialog.isShowing()) {
            fillGroups(a);
            return;
        }
        a.groupBody = new LinearLayout(a);
        a.groupBody.setOrientation(LinearLayout.VERTICAL);
        fillGroups(a);
        a.groupDialog = a.showSheet(a.groupBody, null);
        a.groupDialog.setOnDismissListener(d -> a.groupDialog = null);
    }

    static void fillGroups(MainActivity a) {
        if (a.groupBody == null) return;
        a.groupBody.removeAllViews();
        TextView title = a.uiText("按群管理", 21, Ui.TEXT, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LinearLayout.LayoutParams tLp = a.lp(-1, -2);
        tLp.topMargin = MainActivity.dp(a, 16);
        a.groupBody.addView(title, tLp);
        TextView intro = a.uiText("重点关注：放宽识别，同学发的作业通知、讨论里定下的时间也会出卡。\n"
                + "不用管：这个群的消息不再出卡，已有的卡移到「已移除的卡片」。", 13, Ui.SUB, false);
        intro.setLineSpacing(MainActivity.dp(a, 4), 1f);
        LinearLayout.LayoutParams iLp = a.lp(-1, -2);
        iLp.topMargin = MainActivity.dp(a, 8);
        iLp.bottomMargin = MainActivity.dp(a, 8);
        a.groupBody.addView(intro, iLp);

        List<Engine.GroupInfo> list = a.engine.groupList();
        if (list.isEmpty()) {
            TextView none = a.uiText("还没有收到过群消息。开启自动读取后，收到消息的群会出现在这里。", 14, Ui.MUTE, false);
            none.setLineSpacing(MainActivity.dp(a, 4), 1f);
            none.setPadding(0, MainActivity.dp(a, 24), 0, MainActivity.dp(a, 24));
            a.groupBody.addView(none);
            return;
        }
        long now = System.currentTimeMillis();
        String lastSection = null;
        for (Engine.GroupInfo g : list) {
            String section = MainActivity.modeLabel(g.mode);
            if (!section.equals(lastSection)) {
                TextView s = a.uiText(section, 12, Ui.MUTE, true);
                s.setPadding(MainActivity.dp(a, 2), MainActivity.dp(a, 18), 0, MainActivity.dp(a, 4));
                a.groupBody.addView(s);
                lastSection = section;
            }
            a.groupBody.addView(groupRow(a, g, now), a.lp(-1, -2));
        }
    }

    private static View groupRow(MainActivity a, Engine.GroupInfo g, long now) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(MainActivity.dp(a, 60));
        row.setPadding(MainActivity.dp(a, 2), MainActivity.dp(a, 10), MainActivity.dp(a, 2), MainActivity.dp(a, 10));
        row.setBackground(Ui.ripple(a, null, Ui.press(), 12));
        String app = a.appNameFor(g.pkg);
        row.addView(a.sourceMark(app, g.pkg, 22));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = a.uiText(g.name, 15, Engine.MODE_IGNORE.equals(g.mode) ? Ui.MUTE : Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        int cards = a.engine.cardCount(g.id);
        StringBuilder sub = new StringBuilder(g.isPlatform() ? "学习平台通知" : app);
        if (cards > 0) sub.append(" · ").append(cards).append(" 张卡");
        if (g.lastTs > 0) sub.append(" · ").append(a.relativeTime(Math.min(g.lastTs, now)));
        TextView s = a.uiText(sub.toString(), 12, Ui.MUTE, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setPadding(0, MainActivity.dp(a, 5), 0, 0);
        col.addView(s);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1);
        cLp.leftMargin = MainActivity.dp(a, 12);
        cLp.rightMargin = MainActivity.dp(a, 10);
        row.addView(col, cLp);
        int fg = Engine.MODE_FOCUS.equals(g.mode) ? Ui.ACCENT : Engine.MODE_IGNORE.equals(g.mode) ? Ui.MUTE : Ui.SUB;
        int bg = Engine.MODE_FOCUS.equals(g.mode) ? Ui.ACCENT_BG : Ui.FILL;
        row.addView(Ui.tag(a, MainActivity.modeLabel(g.mode) + " ▾", fg, bg));
        row.setOnClickListener(v -> chooseGroupMode(a, g.id, g.name, g.pkg, g.mode));
        return row;
    }

    static void chooseGroupMode(MainActivity a, String gid, String name, String pkg, String current) {
        String[] modes = {Engine.MODE_FOCUS, Engine.MODE_NORMAL, Engine.MODE_IGNORE};
        String[] labels = {"重点关注 · 放宽识别，宁可多出卡",
                "普通 · 按默认规则识别",
                "不用管 · 这个群不再出卡"};
        int checked = Engine.MODE_FOCUS.equals(current) ? 0 : Engine.MODE_IGNORE.equals(current) ? 2 : 1;
        a.alert().setTitle(name).setSingleChoiceItems(labels, checked, (d, which) -> {
            d.dismiss();
            String mode = modes[which];
            if (mode.equals(current)) return;
            int existing = a.engine.cardCount(gid);
            if (Engine.MODE_IGNORE.equals(mode) && existing > 0) {
                a.alert().setTitle("「" + name + "」不用管？")
                        .setMessage("这个群现有的 " + existing + " 张卡会移到「已移除的卡片」，之后的消息不再出卡。随时可以改回来。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("不用管", (dd, w) -> applyGroupMode(a, gid, name, pkg, mode))
                        .show();
            } else {
                applyGroupMode(a, gid, name, pkg, mode);
            }
        }).setNegativeButton("取消", null).show();
    }

    private static void applyGroupMode(MainActivity a, String gid, String name, String pkg, String mode) {
        int removed = a.engine.setGroupMode(gid, name, pkg, mode, System.currentTimeMillis());
        EngineHolder.saveAsync(a);
        a.render();
        if (a.groupDialog != null && a.groupDialog.isShowing()) fillGroups(a);
        String msg = Engine.MODE_IGNORE.equals(mode)
                ? (removed > 0 ? "已设为不用管，移走 " + removed + " 张卡" : "已设为不用管")
                : Engine.MODE_FOCUS.equals(mode) ? "已设为重点关注" : "已恢复普通";
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }

    static void showSetupGuide(MainActivity a) {
        if (a.setupDialog != null && a.setupDialog.isShowing()) {
            fillSetup(a);
            return;
        }
        a.setupBody = new LinearLayout(a);
        a.setupBody.setOrientation(LinearLayout.VERTICAL);
        LinearLayout footer = new LinearLayout(a);
        footer.setPadding(0, MainActivity.dp(a, 12), 0, 0);
        a.setupDone = a.stylePrimary(a.button("稍后再说", v -> {
            if (!SetupGuide.allRecommendedDone(a)) SetupGuide.snooze(a);
            if (a.setupDialog != null) a.setupDialog.dismiss();
        }));
        footer.addView(a.setupDone, new LinearLayout.LayoutParams(-1, MainActivity.dp(a, 52)));
        fillSetup(a);
        a.setupDialog = a.showSheet(a.setupBody, footer);
        a.setupDialog.setOnDismissListener(d -> {
            a.setupDialog = null;
            a.render();
        });
    }

    static void fillSetup(MainActivity a) {
        if (a.setupBody == null) return;
        a.setupBody.removeAllViews();
        boolean access = WatchdogJob.accessEnabled(a);
        boolean battery = WatchdogJob.batteryIgnored(a);
        boolean autostart = SetupGuide.autostartVisited(a);
        boolean remind = ReminderReceiver.isEnabled(a) && ReminderReceiver.canNotify(a);
        int done = (access ? 1 : 0) + (battery ? 1 : 0) + (autostart ? 1 : 0);

        TextView title = a.uiText("让自动整理一直在线", 20, Ui.TEXT, false);
        title.setTypeface(Ui.medium());
        LinearLayout.LayoutParams tLp = a.lp(-1, -2);
        tLp.topMargin = MainActivity.dp(a, 16);
        a.setupBody.addView(title, tLp);
        TextView intro = a.uiText("手机会在后台悄悄关掉不常用的应用。做完下面三步，群里的作业、考试和接龙才能一直自动整理。",
                13, Ui.SUB, false);
        intro.setLineSpacing(MainActivity.dp(a, 4), 1f);
        LinearLayout.LayoutParams iLp = a.lp(-1, -2);
        iLp.topMargin = MainActivity.dp(a, 8);
        a.setupBody.addView(intro, iLp);

        LinearLayout progress = new LinearLayout(a);
        for (int i = 0; i < 3; i++) {
            View seg = new View(a);
            seg.setBackground(Ui.round(a, i < done ? Ui.GREEN : Ui.FILL, 3));
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, MainActivity.dp(a, 6), 1);
            if (i > 0) sLp.leftMargin = MainActivity.dp(a, 6);
            progress.addView(seg, sLp);
        }
        LinearLayout.LayoutParams pLp = a.lp(-1, -2);
        pLp.topMargin = MainActivity.dp(a, 18);
        a.setupBody.addView(progress, pLp);
        TextView count = a.uiText(done == 3 ? "已全部完成" : "已完成 " + done + " / 3", 12, done == 3 ? Ui.GREEN : Ui.MUTE, true);
        count.setPadding(0, MainActivity.dp(a, 8), 0, MainActivity.dp(a, 6));
        a.setupBody.addView(count);

        setupStep(a, 1, "打开通知使用权", "必需 · 只在本机读取群消息通知，不联网", access, "已开启", "去开启",
                () -> {
                    try {
                        a.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    } catch (Exception e) {
                        Toast.makeText(a, "请到系统设置里搜索「通知使用权」", Toast.LENGTH_LONG).show();
                    }
                });
        setupStep(a, 2, "允许后台运行", "推荐 · 不受电池优化限制，锁屏后也能收到", battery, "已允许", "允许",
                () -> SetupGuide.requestBattery(a));
        setupStep(a, 3, "允许自启动", "推荐 · " + SetupGuide.autostartHint(), autostart, "已去设置", "去设置",
                () -> SetupGuide.openAutostart(a));
        setupStep(a, 4, "填写我的档案", "可选 · 姓名、班级、课程，用来判断哪条消息和你有关", ProfileStore.exists(a),
                "已填写", "去填写", () -> a.startActivity(new Intent(a, ProfileActivity.class)));
        setupStep(a, 5, "到期提醒", "可选 · 截止前两小时发一条提醒", remind, "已开启", "开启", () -> {
            if (ReminderReceiver.isEnabled(a) && ReminderReceiver.canNotify(a)) {
                showDeadlineReminderSettings(a);
            } else if (Build.VERSION.SDK_INT >= 33
                    && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, MainActivity.REQUEST_REMINDER_PERMISSION);
            } else {
                a.enableDeadlineReminder();
                fillSetup(a);
            }
        });
        setupStep(a, 6, "AI 复核", "可选 · 帮规则找回漏掉的待办、补全时间地点，可以完全在本机运行", AiConfig.ready(a),
                "已开启", "了解", () -> a.startActivity(new Intent(a, AiActivity.class)));
        if (a.setupDone != null) a.setupDone.setText(done == 3 ? "全部就绪" : access ? "稍后再说" : "稍后再说（暂时收不到群消息）");
    }

    private static void setupStep(MainActivity a, int n, String title, String desc, boolean ok,
                                  String okLabel, String action, Runnable run) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(MainActivity.dp(a, 2), MainActivity.dp(a, 14), MainActivity.dp(a, 2), MainActivity.dp(a, 14));
        row.setBackground(Ui.ripple(a, null, Ui.press(), 12));
        View mark;
        if (ok) {
            mark = new View(a);
            mark.setBackground(new Ui.CheckCircle(a, Ui.GREEN, Ui.GREEN, 28, true));
        } else {
            TextView num = a.uiText(String.valueOf(n), 14, Ui.TEXT, true);
            num.setGravity(Gravity.CENTER);
            GradientDrawable ring = Ui.disc(a, Ui.CARD);
            ring.setStroke(MainActivity.dp(a, 1.5f), Ui.LINE);
            num.setBackground(ring);
            mark = num;
        }
        row.addView(mark, a.lp(MainActivity.dp(a, 28), MainActivity.dp(a, 28)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(a.uiText(title, 16, Ui.TEXT, true));
        TextView d = a.uiText(desc, 12, Ui.SUB, false);
        d.setLineSpacing(MainActivity.dp(a, 2), 1f);
        d.setPadding(0, MainActivity.dp(a, 5), 0, 0);
        col.addView(d);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1);
        cLp.leftMargin = MainActivity.dp(a, 14);
        cLp.rightMargin = MainActivity.dp(a, 10);
        row.addView(col, cLp);
        TextView btn = a.uiText(ok ? okLabel : action, 13, ok ? Ui.GREEN : Ui.ACCENT, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(MainActivity.dp(a, 14), 0, MainActivity.dp(a, 14), 0);
        if (!ok) btn.setBackground(Ui.round(a, Ui.ACCENT_BG, 16));
        row.addView(btn, a.lp(-2, MainActivity.dp(a, 34)));
        row.setOnClickListener(v -> run.run());
        if (n > 1) a.setupBody.addView(Ui.hairline(a));
        a.setupBody.addView(row, a.lp(-1, -2));
    }

    static void showRecognitionMenu(MainActivity a) {
        android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(a.sheetTitle("AI 与截图识别"));
        a.menuSection(body, null);
        a.menuRow(body, holder, "AI 识别", AiConfig.load(a).enabled ? AiConfig.summary(a)
                        : "未开启 · 帮规则找回漏掉的待办、补全时间地点", Ui.TEXT,
                () -> a.startActivity(new Intent(a, AiActivity.class)));
        a.menuRow(body, holder, "截图识别", "选一张群聊天截图，逐条转写成待办", Ui.TEXT,
                () -> a.startActivity(new Intent(a, ShotActivity.class).putExtra(ShotActivity.EXTRA_PICK, true)));
        holder[0] = a.showSheet(body, null);
    }

    static void showManageMenu(MainActivity a) {
        android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(a.sheetTitle("提醒与按群管理"));
        a.menuSection(body, "提醒");
        a.menuRow(body, holder, "到期提醒", ReminderReceiver.isEnabled(a) ? "已开启 · 到期前两小时提醒" : "已关闭",
                Ui.TEXT, a::showDeadlineReminderSettings);
        a.menuRow(body, holder, "成绩与课表提醒", "出成绩、考试改期、课表变化、余额阈值提醒", Ui.TEXT,
                a::showCampusAlerts);
        a.menuSection(body, "整理");
        a.menuRow(body, holder, "按群管理", a.groupMenuHint(), Ui.TEXT, a::showGroupManager);
        a.menuRow(body, holder, "已移除的卡片", "误删的卡片可以在这里恢复", Ui.TEXT, a::showRemovedCards);
        holder[0] = a.showSheet(body, null);
    }

    static void showToolsMenu(MainActivity a) {
        android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(a.sheetTitle("诊断、备份与演示"));
        a.menuSection(body, "排查与维护");
        a.menuRow(body, holder, "扫描通知栏历史", "重新识别通知栏里挂着的未读会话消息", Ui.TEXT, a::scanHistory);
        a.menuRow(body, holder, "自动读取自检", "检查通知权限、连接状态和后台限制", Ui.TEXT, a::showHealthCheck);
        a.menuRow(body, holder, "系统通知使用权", "跳转到系统通知读取授权页", Ui.TEXT,
                () -> a.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        a.menuRow(body, holder, "诊断日志", "最近 30 条读取与处理记录", Ui.TEXT, a::showDiagLog);
        a.menuRow(body, holder, "存档与恢复", EngineHolder.storageIssue == null ? "本机存档与有效备份"
                : EngineHolder.storageIssue, EngineHolder.storageBlocked() ? Ui.RED : Ui.TEXT, a::showStorageState);
        a.menuRow(body, holder, "桌面小组件", "把今天的课和最近的待办放到桌面", Ui.TEXT, () -> {
            if (!TodayWidget.requestPin(a)) {
                a.alert().setTitle("手动添加小组件")
                        .setMessage("这台手机的桌面不支持应用直接添加。请长按桌面空白处 → 小组件（或“安卓组件”），找到“楼外 / Offloor · 今日课程与待办”，拖到桌面。")
                        .setPositiveButton("知道了", null).show();
            }
        });
        a.menuSection(body, "演示工具");
        a.menuRow(body, holder, "情境演示", "分步演示改期、歧义核对和取消场景", Ui.TEXT,
                () -> a.startActivity(new Intent(a, ShowcaseActivity.class)));
        a.menuRow(body, holder, a.demo == null ? "离线演示" : "退出演示", "用样例群消息体验作业、考试和接龙", Ui.TEXT, a::toggleDemo);
        a.menuRow(body, holder, "通知回放", "打开消息回放器，模拟真实通知", Ui.TEXT, a::startReplay);
        a.menuSection(body, null);
        a.menuRow(body, holder, "清空本机数据", "清空全部待办、已移除卡片和近期消息", Ui.RED, () ->
                a.alert()
                        .setTitle("清空待办和近期消息？")
                        .setMessage("会清空本机的全部待办、已移除卡片，以及这次运行中用于识别的近期消息。")
                        .setPositiveButton("清空", (dialog, w) -> {
                            a.engine.clearLocalData();
                            try { AiQueue.clear(a); }
                            catch (RuntimeException error) { EngineHolder.diag(a, "队列", "旧请求已失效，队列文件清理未成功"); }
                            boolean saved = EngineHolder.storageBlocked() ? EngineHolder.rebuildStorage(a) : EngineHolder.save(a);
                            if (!saved) Toast.makeText(a, "当前列表已清空，但存档未保存，请检查存档与恢复", Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());
        holder[0] = a.showSheet(body, null);
    }

    static void showStorageState(MainActivity a) {
        AlertDialog.Builder dialog = a.alert().setTitle("存档与恢复")
                .setMessage(EngineHolder.storageIssue == null
                        ? "每次成功保存会保留上一份有效存档。读取时会核对完整性，损坏时尝试有效备份；没有可用备份时会暂停覆盖并保留原文件。"
                        : EngineHolder.storageIssue);
        if (EngineHolder.storageBlocked()) {
            dialog.setPositiveButton("保留副本并重建", (d, w) -> a.alert()
                    .setTitle("以当前列表建立新存档？")
                    .setMessage("会先保留原文件副本，再保存当前列表。当前未恢复的旧事项不会自动出现在新列表中。")
                    .setPositiveButton("保留副本并保存", (confirm, which) -> {
                        boolean ok = EngineHolder.rebuildStorage(a);
                        Toast.makeText(a, ok ? "已保存当前列表，原文件副本保留" : "保存未成功，原文件仍保留", Toast.LENGTH_LONG).show();
                    }).setNegativeButton("返回", null).show());
        }
        dialog.setNegativeButton("关闭", null).show();
    }

    static void showCampusAlerts(MainActivity a) {
        SharedPreferences p = CampusChanges.prefs(a);
        CampusChanges.migrate(p);
        String[] keys = {"grades", "exams", "exam_cards", "timetable", "electric", "network"};
        String[] labels = {"出新成绩或成绩变动", "考试安排新增或改期", "教务系统的考试自动放进待办", "课表有调整（同一学期内）",
                "电费低于阈值时提醒", "网费低于阈值时提醒"};
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = p.getBoolean(keys[i], true);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(MainActivity.dp(a, 22), 0, MainActivity.dp(a, 22), 0);
        TextView hint = a.uiText("电费按电价折算成金额后再判断；读不到余额时不提醒，也不会显示 0 元。", 12, Ui.SUB, false);
        hint.setPadding(0, 0, 0, MainActivity.dp(a, 12));
        hint.setLineSpacing(MainActivity.dp(a, 3), 1);
        box.addView(hint, a.lp(-1, -2));
        EditText price = numberField(a, "电价（元/度）", CampusChanges.price(p));
        EditText electric = numberField(a, "电费提醒阈值（元）", CampusChanges.electricThreshold(p));
        EditText network = numberField(a, "网费提醒阈值（元）", CampusChanges.networkThreshold(p));
        box.addView(price, a.lp(-1, -2));
        box.addView(electric, a.lp(-1, -2));
        box.addView(network, a.lp(-1, -2));
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        a.alert()
                .setTitle("成绩、课表与余额提醒")
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    SharedPreferences.Editor e = p.edit();
                    for (int i = 0; i < keys.length; i++) e.putBoolean(keys[i], checked[i]);
                    Double pv = ChangeDiff.firstNumber(price.getText().toString());
                    if (pv != null && CampusBalance.validPrice(pv)) e.putFloat(CampusChanges.KEY_PRICE, pv.floatValue());
                    Double ev = ChangeDiff.firstNumber(electric.getText().toString());
                    if (ev != null && CampusBalance.validThreshold(ev)) {
                        e.putFloat(CampusChanges.KEY_ELECTRIC_YUAN, ev.floatValue()).putBoolean("electric_low", false);
                    }
                    Double nv = ChangeDiff.firstNumber(network.getText().toString());
                    if (nv != null && CampusBalance.validThreshold(nv)) {
                        e.putFloat(CampusChanges.KEY_NETWORK_YUAN, nv.floatValue()).putBoolean("network_low", false);
                    }
                    e.apply();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static EditText numberField(MainActivity a, String hint, double value) {
        EditText field = new EditText(a);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(CampusChanges.trim(value));
        return field;
    }

    static void scanHistory(MainActivity a) {
        if (a.demo != null) return;
        if (!NotifListener.canScan()) {
            a.alert().setTitle("还不能扫描")
                    .setMessage("通知读取还没连上，扫描需要先在「通知使用权」里打开「Offloor」。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            a.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                        } catch (Exception e) {
                            Toast.makeText(a, "请到系统设置里搜索「通知使用权」", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null).show();
            return;
        }
        NotifListener.ScanResult r = NotifListener.scanNow();
        if (r == null) {
            Toast.makeText(a, "扫描失败，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        MainActivity.scanNoticeShownAt = NotifListener.lastScanAt;
        showScanResult(a, r, true);
    }

    static void showScanResult(MainActivity a, NotifListener.ScanResult r, boolean manual) {
        int added = r.newCardIds.size();
        if (!manual) {
            if (added > 0) {
                Toast.makeText(a, "已从通知栏补上 " + added + " 个事项", Toast.LENGTH_LONG).show();
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
                Engine.Card c = findCard(a, id);
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
        a.alert().setTitle(added > 0 ? "扫描完成" : "扫描完成，没有新事项")
                .setMessage(msg.toString().trim())
                .setPositiveButton(added > 0 ? "查看" : "好", (d, w) -> {
                    if (added > 0) {
                        a.newCardsOnly = false;
                        a.scope = Engine.CardScope.ALL;
                        a.window = Engine.CardWindow.ANY;
                        a.resetCardPage();
                    }
                })
                .show();
        a.render();
    }

    private static Engine.Card findCard(MainActivity a, String id) {
        if (id == null || a.engine == null) return null;
        for (Engine.Card c : a.engine.all()) if (id.equals(c.id)) return c;
        return null;
    }
}
