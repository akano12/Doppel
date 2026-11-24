package eu.faircode.xlua;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

// The line of a log row with the old value, the arrow, the new value, the hook and the expand button:
// the two values share the room which is left after the arrow and the button, so the button stays on the row
// and a value which does not fit is ellipsized, whatever the width and the font scale
public class ValueLine extends LinearLayout {
    public ValueLine(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        TextView old = findViewById(R.id.tvOld);
        TextView neu = findViewById(R.id.tvNew);
        View arrow = findViewById(R.id.tvArrow);
        View button = findViewById(R.id.ibExpand);
        if (old != null && neu != null && old.getVisibility() == VISIBLE &&
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int[] caps = share(MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight()
                            - outer(arrow, true) - outer(button, false) - margins(neu),
                    natural(old), natural(neu));
            if (old.getMaxWidth() != caps[0])
                old.setMaxWidth(caps[0]);
            if (neu.getMaxWidth() != caps[1])
                neu.setMaxWidth(caps[1]);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    // Each value gets what it needs, or half of the room when both need more
    static int[] share(int room, int old, int neu) {
        room = Math.max(room, 0);
        if (old + neu <= room)
            return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
        int half = room / 2;
        if (old <= half)
            return new int[]{old, room - old};
        if (neu <= half)
            return new int[]{room - neu, neu};
        return new int[]{half, room - half};
    }

    private static int natural(TextView tv) {
        return (int) Math.ceil(tv.getPaint().measureText(tv.getText().toString())) +
                tv.getCompoundPaddingLeft() + tv.getCompoundPaddingRight();
    }

    private static int margins(View v) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        return lp.leftMargin + lp.rightMargin;
    }

    // The button is reserved even while it is hidden, so that it can show when a value is cut
    private static int outer(View v, boolean text) {
        if (v == null)
            return 0;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        int width = (text ? (int) Math.ceil(((TextView) v).getPaint().measureText(((TextView) v).getText().toString())) : lp.width);
        return width + lp.leftMargin + lp.rightMargin;
    }
}
