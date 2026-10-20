/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.faircode.xlua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// A bar of one segment per group, the restricted groups filled, like the hook switches of an app
public class SegmentBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float gap;
    private int total = 0;
    private int filled = 0;
    private final int colorFilled;
    private final int colorEmpty;

    public SegmentBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gap = Ui.dp(context, 2);
        colorFilled = Ui.color(context, androidx.appcompat.R.attr.colorPrimary);
        colorEmpty = Ui.color(context, com.google.android.material.R.attr.colorSurfaceContainerHighest);
    }

    void set(int total, int filled) {
        this.total = total;
        this.filled = filled;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (total <= 0)
            return;
        float width = (getWidth() - gap * (total - 1)) / total;
        float radius = getHeight() / 2f;
        for (int i = 0; i < total; i++) {
            float left = i * (width + gap);
            rect.set(left, 0, left + width, getHeight());
            paint.setColor(i < filled ? colorFilled : colorEmpty);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
