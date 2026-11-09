package com.nopalou;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Consistent 24-unit line icons for the campus shortcuts. */
final class CampusServiceIcon extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    CampusServiceIcon(String kind,int color) {
        this.kind=kind; paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void draw(Canvas c) {
        c.save(); c.translate(getBounds().left,getBounds().top); c.scale(getBounds().width()/24f,getBounds().height()/24f);
        if ("bus".equals(kind)) {
            c.drawRoundRect(4,3,20,19,4,4,paint); c.drawRoundRect(7,6,17,12,1,1,paint);
            c.drawLine(7,19,7,21,paint); c.drawLine(17,19,17,21,paint);
            c.drawCircle(7.5f,15.5f,.8f,paint); c.drawCircle(16.5f,15.5f,.8f,paint);
        } else if ("classroom".equals(kind)) {
            c.drawRoundRect(4,3,20,21,2,2,paint); c.drawLine(10,21,10,16,paint); c.drawLine(14,16,14,21,paint); c.drawLine(10,16,14,16,paint);
            for (int x:new int[]{8,16}) for (int y:new int[]{7,11}) c.drawLine(x-1,y,x+1,y,paint);
        } else {
            c.drawRoundRect(4,3,20,21,3,3,paint);
            Path p=new Path(); p.moveTo(7,9); p.lineTo(9,11); p.lineTo(12,7); c.drawPath(p,paint);
            c.drawLine(14,9,17,9,paint); c.drawLine(7,15,17,15,paint); c.drawLine(7,18,14,18,paint);
        }
        c.restore();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
