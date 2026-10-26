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

import android.animation.LayoutTransition;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.os.BundleCompat;
import androidx.core.widget.TextViewCompat;

// The bar of the tabs, floating as a pill: the selected tab has its icon next to its label and the width it needs,
// the other tabs are icons, named for screen readers, which share the rest of the width
public class NavBar extends LinearLayout {
    private static final String STATE_SUPER = "super";
    private static final String STATE_SELECTED = "selected";

    private int selected = View.NO_ID;
    private OnItemSelectedListener listener = null;

    public interface OnItemSelectedListener {
        void onItemSelected(int itemId);
    }

    public NavBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // The selected tab widens and the others make room smoothly
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        setLayoutTransition(transition);
    }

    // The items of a menu resource: ids, icons and titles
    void setMenu(int menuRes) {
        removeAllViews();
        PopupMenu popup = new PopupMenu(getContext(), this);
        popup.getMenuInflater().inflate(menuRes, popup.getMenu());
        Menu menu = popup.getMenu();
        for (int i = 0; i < menu.size(); i++)
            addView(createItem(menu.getItem(i)));
        if (menu.size() > 0)
            select(menu.getItem(0).getItemId(), false);
    }

    private View createItem(MenuItem menuItem) {
        Context context = getContext();
        int padding = getResources().getDimensionPixelSize(R.dimen.nav_item_padding);

        LinearLayout item = new LinearLayout(context);
        item.setId(menuItem.getItemId());
        item.setOrientation(HORIZONTAL);
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.nav_item_height));
        item.setPadding(padding, 0, padding, 0);
        item.setBackgroundResource(R.drawable.bg_nav_item);
        item.setContentDescription(menuItem.getTitle());
        item.setOnClickListener(view -> select(view.getId(), true));

        ImageView icon = new ImageView(context);
        int size = getResources().getDimensionPixelSize(R.dimen.nav_icon_size);
        icon.setLayoutParams(new LayoutParams(size, size));
        icon.setImageDrawable(menuItem.getIcon());
        icon.setImageTintList(context.getColorStateList(R.color.nav_item));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon);

        TextView label = new TextView(context);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.nav_label_spacing));
        label.setLayoutParams(lp);
        TextViewCompat.setTextAppearance(label, R.style.XLua_TextAppearance_LabelLarge);
        label.setTextColor(context.getColorStateList(R.color.nav_item));
        label.setText(menuItem.getTitle());
        label.setMaxLines(1);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(label);

        return item;
    }

    void setOnItemSelectedListener(OnItemSelectedListener listener) {
        this.listener = listener;
    }

    // Like tapping a tab; the listener is not called for the tab which is selected already
    void setSelectedItemId(int itemId) {
        select(itemId, true);
    }

    private void select(int itemId, boolean notify) {
        boolean changed = (itemId != selected);
        selected = itemId;
        for (int i = 0; i < getChildCount(); i++) {
            LinearLayout item = (LinearLayout) getChildAt(i);
            boolean on = (item.getId() == itemId);
            item.setSelected(on);
            item.getChildAt(1).setVisibility(on ? VISIBLE : GONE);
            item.setLayoutParams(on
                    ? new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0)
                    : new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        if (changed && notify && listener != null)
            listener.onItemSelected(itemId);
    }

    // The selected tab survives recreating the screen, like for other colors
    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putParcelable(STATE_SUPER, super.onSaveInstanceState());
        state.putInt(STATE_SELECTED, selected);
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle bundle = (Bundle) state;
            super.onRestoreInstanceState(BundleCompat.getParcelable(bundle, STATE_SUPER, Parcelable.class));
            select(bundle.getInt(STATE_SELECTED, selected), false);
        } else
            super.onRestoreInstanceState(state);
    }
}
