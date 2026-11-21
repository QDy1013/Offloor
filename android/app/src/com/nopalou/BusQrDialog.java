package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 原生高清乘车核验二维码弹窗：按校方官网规格（objid + L 级纠错）纯白框绘制，自动提亮屏幕，关闭即复原。 */
final class BusQrDialog {
    private BusQrDialog() { }

    static void show(Activity host, CampusBusTicket.Ticket ticket, Runnable onVerifyStatus, Runnable onOpenPortal) {
        if (host == null || host.isFinishing() || host.isDestroyed() || ticket == null) return;
        Ui.apply(host);

        if (!ticket.hasQrCode()) {
            new AlertDialog.Builder(host, Ui.dialogTheme())
                    .setTitle("正在获取核验码")
                    .setMessage("暂未同步到校方核验码编号，请点击「实时刷新」后再出示，或直接前往校方官网查看。\n"
                            + (ticket.order.isEmpty() ? "" : "预约单号：" + ticket.order))
                    .setNeutralButton("前往校方官网", (d, w) -> { if (onOpenPortal != null) onOpenPortal.run(); })
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("实时刷新", (d, w) -> { if (onVerifyStatus != null) onVerifyStatus.run(); })
                    .show();
            return;
        }

        boolean[][] matrix;
        try {
            matrix = QrCode.encode(ticket.qrCode, QrCode.Level.L);
        } catch (Exception e) {
            new AlertDialog.Builder(host, Ui.dialogTheme())
                    .setTitle("暂无法本地制码")
                    .setMessage("当前核验码格式暂不支持本地绘制，请前往校方官网查看。\n核验码：" + ticket.qrCode)
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("前往校方官网", (d, w) -> { if (onOpenPortal != null) onOpenPortal.run(); })
                    .show();
            return;
        }

        Bitmap bmp = renderBitmap(matrix, 600);
        LinearLayout body = CourseUi.column(host);
        body.setPadding(Ui.dp(host, 22), Ui.dp(host, 18), Ui.dp(host, 22), Ui.dp(host, 8));
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        String[] threeDays = CampusBusTicket.threeDaysFrom(System.currentTimeMillis());
        LinearLayout head = new LinearLayout(host);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBaselineAligned(false);
        head.addView(CourseUi.text(host, CampusBusTicket.friendlyDate(ticket.date, threeDays) + " " + ticket.time, 18, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        boolean verified = "已核验".equals(ticket.status);
        head.addView(Ui.tag(host, ticket.status.isEmpty() ? "待核验" : ticket.status,
                verified ? Ui.SUB : Ui.GREEN, verified ? Ui.FILL : Ui.GREEN_BG));
        body.addView(head, new LinearLayout.LayoutParams(-1, -2));

        StringBuilder sub = new StringBuilder();
        String routeText = ticket.displayRoute();
        if (!routeText.isEmpty()) sub.append(routeText);
        if (!ticket.fee.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append("¥").append(ticket.fee);
        }
        if (sub.length() > 0) {
            CourseUi.gap(body, 4);
            body.addView(CourseUi.text(host, sub.toString(), 13, Ui.SUB, false), new LinearLayout.LayoutParams(-1, -2));
        }
        if (!ticket.place.isEmpty()) {
            CourseUi.gap(body, 3);
            body.addView(CourseUi.text(host, "上车点：" + ticket.place, 12, Ui.SUB, false), new LinearLayout.LayoutParams(-1, -2));
        }

        CourseUi.gap(body, 16);
        LinearLayout frame = new LinearLayout(host);
        frame.setGravity(Gravity.CENTER);
        int pad = Ui.dp(host, 14);
        frame.setPadding(pad, pad, pad, pad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(Ui.dp(host, 14));
        cardBg.setStroke(Math.max(1, Ui.dp(host, 1)), Color.parseColor("#E6E2D8"));
        frame.setBackground(cardBg);

        ImageView qrImage = new ImageView(host);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrImage.setImageBitmap(bmp);
        qrImage.setContentDescription("校车乘车核验二维码");
        int qrPx = Ui.dp(host, 212);
        frame.addView(qrImage, new LinearLayout.LayoutParams(qrPx, qrPx));
        body.addView(frame, new LinearLayout.LayoutParams(-2, -2));

        CourseUi.gap(body, 12);
        TextView codeText = CourseUi.text(host, "核验码 " + ticket.qrCode, 12, Ui.SUB, false);
        codeText.setTypeface(Typeface.MONOSPACE);
        codeText.setGravity(Gravity.CENTER);
        codeText.setTextIsSelectable(true);
        body.addView(codeText, new LinearLayout.LayoutParams(-1, -2));

        CourseUi.gap(body, 5);
        TextView tip = CourseUi.text(host, "屏幕已自动提亮 · 请对准校车扫码设备核验", 11, Ui.MUTE, false);
        tip.setGravity(Gravity.CENTER);
        body.addView(tip, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(host, Ui.dialogTheme())
                .setView(body)
                .setNegativeButton("实时复核", (d, w) -> { if (onVerifyStatus != null) onVerifyStatus.run(); })
                .setPositiveButton("关闭", null)
                .create();

        if (onOpenPortal != null) {
            CourseUi.gap(body, 10);
            TextView portal = CourseUi.text(host, "前往校方官网查看或退票 ›", 12, Ui.ACCENT, true);
            portal.setGravity(Gravity.CENTER);
            portal.setPadding(Ui.dp(host, 12), Ui.dp(host, 8), Ui.dp(host, 12), Ui.dp(host, 8));
            portal.setBackground(Ui.ripple(host, null, Ui.press(), 10));
            portal.setOnClickListener(v -> {
                dialog.dismiss();
                onOpenPortal.run();
            });
            body.addView(portal, new LinearLayout.LayoutParams(-2, -2));
        }

        final float[] prevBrightness = {-1f};
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                WindowManager.LayoutParams lp = w.getAttributes();
                prevBrightness[0] = lp.screenBrightness;
                lp.screenBrightness = 0.95f;
                w.setAttributes(lp);
            }
        });
        dialog.setOnDismissListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.screenBrightness = prevBrightness[0];
                w.setAttributes(lp);
            }
            qrImage.setImageDrawable(null);
            if (!bmp.isRecycled()) bmp.recycle();
        });
        dialog.show();
    }

    private static Bitmap renderBitmap(boolean[][] matrix, int targetPx) {
        int quiet = 4;
        int n = matrix.length;
        int dim = n + quiet * 2;
        int scale = Math.max(4, targetPx / dim);
        int px = dim * scale;
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        paint.setFilterBitmap(false);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
        for (int r = 0; r < n; r++) {
            float top = (r + quiet) * scale;
            float bottom = top + scale;
            for (int c = 0; c < n; c++) {
                if (matrix[r][c]) {
                    float left = (c + quiet) * scale;
                    canvas.drawRect(left, top, left + scale, bottom, paint);
                }
            }
        }
        return bmp;
    }
}
