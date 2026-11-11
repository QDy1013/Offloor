package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Scrollable usage dialog; reading it never changes permissions or recovery state. */
final class HomeUsageNotice {
    private static final String PREFS = "home_usage_notice";
    private static final int VERSION = 1;

    private HomeUsageNotice() { }

    static boolean shouldAutoShow(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("acknowledged_version", 0) < VERSION;
    }

    static AlertDialog create(Activity activity, Runnable openSetup, Runnable openHealth) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(activity, 24), Ui.dp(activity, 24), Ui.dp(activity, 24), Ui.dp(activity, 8));
        addText(activity, header, "使用须知", 20, Ui.TEXT, true);
        TextView subtitle = addText(activity, header, "开始使用前，请留意以下几点。", 13, Ui.SUB, false);
        subtitle.setPadding(0, Ui.dp(activity, 6), 0, 0);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(activity, 24), Ui.dp(activity, 4), Ui.dp(activity, 24), Ui.dp(activity, 8));
        TextView summary = addText(activity, body, "允许自启动和后台运行，电池设为不限制。仅整理通知栏可见文字，重要事项请核对。", 13, Ui.SUB, false);
        summary.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 16));
        body.addView(Ui.hairline(activity));
        addDetail(activity, body, "通知使用权", "在系统设置中允许 Offloor 读取通知。权限已开启不代表服务已连接，请以首页的连接状态为准。");
        addDetail(activity, body, "自启动与后台运行", "在系统的应用管理或手机管家中，允许 Offloor 自启动和后台运行。各品牌的设置名称可能不同。");
        addDetail(activity, body, "电池与最近任务", "把 Offloor 的电池策略设为不限制或允许后台耗电。可在最近任务中锁定应用，避免手动强行停止；这些设置不能保证永不被系统清理。");
        addDetail(activity, body, "收不到消息时", "先确认微信 / QQ 等来源应用允许通知并显示消息正文。免打扰、隐藏正文、已清除的通知和历史聊天，可能无法自动读取；图片内文字需主动分享截图补充。");
        addDetail(activity, body, "重启或重连后", "重启手机后打开一次 Offloor，检查是否显示「服务已连接」。重连不是立即完成；持续未连接时先做通知自检，按提示重新开启通知使用权，无需清空数据。");
        addDetail(activity, body, "重要事项请核对", "自动识别可能遗漏或理解有误，请核对时间、地点和截止日期；重要通知仍以原文和校方信息为准。");
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.48f);
                int available = View.MeasureSpec.getSize(heightSpec);
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) cap = Math.min(cap, available);
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.dialogTheme())
                .setTitle("使用须知")
                .setCustomTitle(header)
                .setView(scroll)
                .setPositiveButton("知道了", (d, which) -> activity
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt("acknowledged_version", VERSION).apply())
                .setNegativeButton("后台设置", (d, which) -> {
                    d.dismiss();
                    openSetup.run();
                })
                .setNeutralButton("通知自检", (d, which) -> {
                    d.dismiss();
                    openHealth.run();
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(Ui.round(activity, Ui.CARD, 24));
                int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 32), Ui.dp(activity, 420));
                window.setLayout(width, -2);
                window.setDimAmount(0.42f);
            }
            styleAction(activity, dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
            styleAction(activity, dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
            styleAction(activity, dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false);
        });
        return dialog;
    }

    private static void styleAction(Activity activity, Button button, boolean primary) {
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTypeface(Ui.medium());
        button.setTextColor(primary ? Ui.ON_ACCENT : Ui.ACCENT);
        button.setBackground(Ui.ripple(activity, primary ? Ui.round(activity, Ui.ACCENT, 14) : null, Ui.press(), 14));
        button.setMinHeight(Ui.dp(activity, 48));
        button.setMinimumHeight(Ui.dp(activity, 48));
        button.setPadding(Ui.dp(activity, 14), 0, Ui.dp(activity, 14), 0);
    }

    private static void addDetail(Activity activity, LinearLayout body, String title, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(activity, 14), 0, Ui.dp(activity, 14));
        TextView heading = addText(activity, row, title, 15, Ui.TEXT, true);
        heading.setPadding(0, 0, 0, Ui.dp(activity, 5));
        addText(activity, row, value, 13, Ui.SUB, false);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        body.addView(Ui.hairline(activity));
    }

    private static TextView addText(Activity activity, LinearLayout body, String value, int size, int color, boolean bold) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setLineSpacing(Ui.dp(activity, 3), 1f);
        body.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }
}
