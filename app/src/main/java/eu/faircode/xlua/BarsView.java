/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

// Bars of counts, like the restricted calls of today per hour or of the last week per day:
// a hairline for none, the highest in the accent color
public class BarsView extends View {
    private int[] values = new int[0];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int none;
    private final int some;
    private final int most;

    public BarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        none = Ui.color(context, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        some = Ui.color(context, com.google.android.material.R.attr.colorPrimaryContainer);
        most = Ui.color(context, androidx.appcompat.R.attr.colorPrimary);
    }

    void setValues(int[] values) {
        this.values = values;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int count = values.length;
        if (count == 0)
            return;
        int max = 0;
        for (int value : values)
            max = Math.max(max, value);
        // Narrow views have narrower gaps, like the bars in the compact bar of the Activity tab
        float gap = Math.min(Ui.dp(getContext(), 3), getWidth() / (count * 4f));
        float width = (getWidth() - gap * (count - 1)) / count;
        float height = getHeight();
        float line = Ui.dp(getContext(), 2);
        float radius = Ui.dp(getContext(), 1);
        for (int i = 0; i < count; i++) {
            float x = i * (width + gap);
            float bar = (values[i] == 0 ? line : Math.max(line, height * values[i] / max));
            paint.setColor(values[i] == 0 ? none : values[i] == max ? most : some);
            canvas.drawRoundRect(x, height - bar, x + width, height, radius, radius, paint);
        }
    }
}
