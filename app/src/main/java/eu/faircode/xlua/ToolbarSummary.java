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

import android.view.View;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;

// The toolbar of a screen with a summary of its top while that is scrolled away, like the compact bar of the tabs
// (CompactBar): after the subtitle of the toolbar, like the app of the log of one app, or as the subtitle
final class ToolbarSummary {
    private final MaterialToolbar toolbar;
    private final CharSequence subtitle;
    private CharSequence summary = null;
    private boolean scrolled = false;

    // The top is scrolled away once the scroll passes the end of the header
    ToolbarSummary(MaterialToolbar toolbar, NestedScrollView scroll, View header) {
        this(toolbar);
        scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (view, x, y, oldX, oldY) ->
                setScrolled(y > header.getBottom()));
    }

    private ToolbarSummary(MaterialToolbar toolbar) {
        this.toolbar = toolbar;
        this.subtitle = toolbar.getSubtitle();
    }

    // After each change of the top, like of a filter
    void setSummary(CharSequence summary) {
        this.summary = summary;
        apply();
    }

    private void setScrolled(boolean scrolled) {
        if (this.scrolled != scrolled) {
            this.scrolled = scrolled;
            apply();
        }
    }

    private void apply() {
        if (!scrolled || summary == null)
            toolbar.setSubtitle(subtitle);
        else if (subtitle == null)
            toolbar.setSubtitle(summary);
        else
            toolbar.setSubtitle(toolbar.getContext().getString(R.string.title_pair, subtitle, summary));
    }
}
