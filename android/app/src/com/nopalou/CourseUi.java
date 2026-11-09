package com.nopalou;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;

/** Small, flat controls shared by the timetable and its import screen. */
final class CourseUi {
    static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Ui.medium());
        return t;
    }
    static TextView action(Context c, String value, boolean primary, Runnable click) {
        TextView t = text(c, value, 14, primary ? Ui.ON_ACCENT : Ui.TEXT, true);
        Ui.pressScale(t, 0.97f);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 48));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        t.setBackground(Ui.ripple(c, Ui.round(c, primary ? Ui.ACCENT : Ui.CARD, 99), Ui.press(), 99));
        if (primary) Ui.lift(t, 3f);
        t.setOnClickListener(v -> click.run()); t.setFocusable(true);
        return t;
    }
    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    static ImageView icon(Context c, String type, String description, Runnable click) {
        ImageView v = new ImageView(c); v.setImageDrawable(glyph(c, type));
        int p = Ui.dp(c, 12); v.setPadding(p, p, p, p);
        v.setBackground(Ui.rippleCircle(c, Ui.press())); v.setContentDescription(description);
        v.setOnClickListener(x -> click.run()); v.setFocusable(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44))); return v;
    }
    static Drawable glyph(Context c, String type) {
        return new Drawable() {
            final Paint paint = new Paint(3);
            @Override public void draw(Canvas canvas) {
                canvas.save(); canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                paint.setColor(Ui.TEXT); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStyle(Paint.Style.STROKE); Path path = new Path();
                if ("more".equals(type)) {
                    paint.setStyle(Paint.Style.FILL); for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, 12, 1.6f, paint);
                } else if ("sync".equals(type)) {
                    canvas.drawArc(4, 4, 20, 20, -55, 295, false, paint);
                    path.moveTo(17, 3); path.lineTo(18, 9); path.lineTo(12, 8); canvas.drawPath(path, paint);
                } else if ("down".equals(type)) {
                    path.moveTo(6, 9); path.lineTo(12, 15); path.lineTo(18, 9); canvas.drawPath(path, paint);
                } else {
                    boolean next = "next".equals(type); path.moveTo(next ? 9 : 15, 5); path.lineTo(next ? 16 : 8, 12); path.lineTo(next ? 9 : 15, 19); canvas.drawPath(path, paint);
                }
                canvas.restore();
            }
            @Override public void setAlpha(int a) { paint.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return Ui.dp(c, 24); }
            @Override public int getIntrinsicHeight() { return Ui.dp(c, 24); }
        };
    }
    static LinearLayout.LayoutParams row(int height) { return new LinearLayout.LayoutParams(-1, height); }
    static void gap(LinearLayout p, int dp) {
        android.view.View v = new android.view.View(p.getContext());
        p.addView(v, new LinearLayout.LayoutParams(1, Ui.dp(p.getContext(), dp)));
    }
}
