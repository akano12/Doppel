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

import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.StringRes;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

// The bar of a tab while its header is scrolled away (bar_compact.xml): the title with a summary of the header,
// the filter of its chips as a menu and its search, which brings the header with the search field back.
// It fades in over the last part of the scroll, while the header fades out, and takes no taps while it is not shown.
final class CompactBar {
    // The part of the scroll of the header over which the bar fades in
    private static final float FADE = 0.4f;

    private final View bar;
    private final TextView subtitle;
    private final ImageButton filter;
    private final ChipGroup chips;

    // The header (llHeader) and the bar are in the app bar (appBar) of the tab; search is the field of the header,
    // which its button opens
    CompactBar(View root, @StringRes int title, ChipGroup chips, View search, View searchButton) {
        this.chips = chips;
        bar = root.findViewById(R.id.llCompact);
        subtitle = bar.findViewById(R.id.tvCompactSubtitle);
        filter = bar.findViewById(R.id.ibCompactFilter);
        ((TextView) bar.findViewById(R.id.tvCompactTitle)).setText(title);

        AppBarLayout appBar = root.findViewById(R.id.appBar);
        View header = root.findViewById(R.id.llHeader);
        appBar.addOnOffsetChangedListener((layout, offset) -> {
            int range = layout.getTotalScrollRange();
            float scrolled = (range == 0 ? 0 : -offset / (float) range);
            float alpha = Math.max(0f, (scrolled - (1 - FADE)) / FADE);
            bar.setAlpha(alpha);
            bar.setVisibility(alpha > 0 ? View.VISIBLE : View.INVISIBLE);
            header.setAlpha(1 - alpha);
        });

        filter.setOnClickListener(this::showFilter);
        bar.findViewById(R.id.ibCompactSearch).setOnClickListener(v -> {
            appBar.setExpanded(true, true);
            if (search.getVisibility() != View.VISIBLE)
                searchButton.performClick();
        });
    }

    // A small form of the overview, before the title, like the ring of the Apps tab
    void setLead(View view) {
        setSlot(R.id.flCompactLead, view);
    }

    // A small form of the overview, after the title, like the bars of the Activity tab
    void setTrail(View view) {
        setSlot(R.id.flCompactTrail, view);
    }

    private void setSlot(@IdRes int id, View view) {
        FrameLayout slot = bar.findViewById(id);
        slot.removeAllViews();
        slot.addView(view);
        slot.setVisibility(View.VISIBLE);
    }

    // The menu of the header, like on the Apps tab
    void setMore(View.OnClickListener listener) {
        ImageButton more = bar.findViewById(R.id.ibCompactMore);
        more.setOnClickListener(listener);
        more.setVisibility(View.VISIBLE);
    }

    // The summary, after each change of the header, like of the filter; screen readers hear the filter with its button
    void setSubtitle(CharSequence text) {
        subtitle.setText(text);
        Chip checked = chips.findViewById(chips.getCheckedChipId());
        if (checked != null)
            filter.setContentDescription(bar.getContext().getString(R.string.title_filter_by, checked.getText()));
    }

    // The chips, with their counts, checking one like tapping it
    private void showFilter(View anchor) {
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        Menu menu = popup.getMenu();
        for (int i = 0; i < chips.getChildCount(); i++) {
            Chip chip = (Chip) chips.getChildAt(i);
            menu.add(Menu.NONE, chip.getId(), i, chip.getText()).setChecked(chip.isChecked());
        }
        menu.setGroupCheckable(Menu.NONE, true, true);
        popup.setOnMenuItemClickListener(item -> {
            chips.check(item.getItemId());
            return true;
        });
        popup.show();
    }
}
