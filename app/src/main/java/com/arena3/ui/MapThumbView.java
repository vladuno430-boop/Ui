package com.arena3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.arena3.core.Vec3;
import com.arena3.game.Brush;
import com.arena3.game.Contents;
import com.arena3.game.MapDef;
import com.arena3.game.Maps;

/**
 * Top-down thumbnail of an arena, drawn straight from the brush data: solid
 * footprints in grey, hazards in red, jump pads and teleporters as accents.
 */
public final class MapThumbView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private MapDef map;

    public MapThumbView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMapIndex(int index) {
        map = Maps.build(index);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (map == null) return;

        int w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0C0E13);
        canvas.drawRect(0, 0, w, h, paint);

        // Fit the map's footprint into the view with a small margin.
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Brush b : map.brushes) {
            if ((b.contents & Contents.SOLID) == 0) continue;
            minX = Math.min(minX, b.mins.x);
            maxX = Math.max(maxX, b.maxs.x);
            minY = Math.min(minY, b.mins.y);
            maxY = Math.max(maxY, b.maxs.y);
        }
        if (minX > maxX) return;

        float pad = 6f;
        float scale = Math.min((w - pad * 2) / (maxX - minX), (h - pad * 2) / (maxY - minY));
        float offX = (w - (maxX - minX) * scale) * 0.5f;
        float offY = (h - (maxY - minY) * scale) * 0.5f;

        float lowZ = Float.MAX_VALUE, highZ = -Float.MAX_VALUE;
        for (Brush b : map.brushes) {
            lowZ = Math.min(lowZ, b.mins.z);
            highZ = Math.max(highZ, b.maxs.z);
        }
        for (Brush b : map.brushes) {
            if ((b.contents & Contents.SOLID) == 0) continue;
            // Higher brushes are drawn lighter, so the levels read apart.
            float height = (b.maxs.z - lowZ) / Math.max(1f, highZ - lowZ);
            int shade = (int) (46 + Math.min(1f, Math.max(0f, height)) * 120);
            paint.setColor(Color.rgb(shade, shade + 3, shade + 9));
            canvas.drawRect(offX + (b.mins.x - minX) * scale, offY + (b.mins.y - minY) * scale,
                    offX + (b.maxs.x - minX) * scale, offY + (b.maxs.y - minY) * scale, paint);
        }

        paint.setColor(0xCCD8452E);
        for (MapDef.HurtVolume hv : map.hurtVolumes) {
            if (hv.instantKill) continue;
            canvas.drawRect(offX + (hv.mins.x - minX) * scale, offY + (hv.mins.y - minY) * scale,
                    offX + (hv.maxs.x - minX) * scale, offY + (hv.maxs.y - minY) * scale, paint);
        }

        paint.setColor(0xFF39C9A0);
        for (MapDef.JumpPad pad2 : map.jumpPads) {
            float cx = offX + ((pad2.mins.x + pad2.maxs.x) * 0.5f - minX) * scale;
            float cy = offY + ((pad2.mins.y + pad2.maxs.y) * 0.5f - minY) * scale;
            canvas.drawCircle(cx, cy, 2.2f, paint);
        }
        paint.setColor(0xFFB06BE8);
        for (MapDef.Teleporter t : map.teleporters) {
            float cx = offX + ((t.mins.x + t.maxs.x) * 0.5f - minX) * scale;
            float cy = offY + ((t.mins.y + t.maxs.y) * 0.5f - minY) * scale;
            canvas.drawCircle(cx, cy, 2.6f, paint);
        }
        paint.setColor(0xFFE8A33D);
        for (MapDef.ItemSpawn item : map.items) {
            Vec3 p = item.pos;
            canvas.drawCircle(offX + (p.x - minX) * scale, offY + (p.y - minY) * scale, 1.1f, paint);
        }
    }
}
