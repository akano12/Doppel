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

import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.util.Consumer;
import androidx.core.util.Supplier;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;

// The search of a screen: the field replaces the title while it is shown, the button opens and closes it,
// Back closes it first; a search restored with the view is shown again, without the keyboard
final class TitleSearch {
    private final ComponentActivity activity;
    private final Supplier<LifecycleOwner> owner; // Of the views, known once they exist
    private final Supplier<Boolean> hidden; // Whether the screen is behind another tab
    private final View title;
    private final View field;
    private final EditText input;
    private final ImageButton button;
    private OnBackPressedCallback back;

    // Of a tab, which can be hidden behind another one, see FragmentBase
    TitleSearch(Fragment fragment, View title, View field, EditText input, ImageButton button, Consumer<String> onQuery) {
        this(fragment.requireActivity(), fragment::getViewLifecycleOwner, fragment::isHidden,
                title, field, input, button, onQuery);
    }

    // Of a screen of its own, see ActivityDownloads
    TitleSearch(ComponentActivity activity, View title, View field, EditText input, ImageButton button, Consumer<String> onQuery) {
        this(activity, () -> activity, () -> false, title, field, input, button, onQuery);
    }

    private TitleSearch(ComponentActivity activity, Supplier<LifecycleOwner> owner, Supplier<Boolean> hidden,
                        View title, View field, EditText input, ImageButton button, Consumer<String> onQuery) {
        this.activity = activity;
        this.owner = owner;
        this.hidden = hidden;
        this.title = title;
        this.field = field;
        this.input = input;
        this.button = button;
        button.setOnClickListener(v -> show(field.getVisibility() != View.VISIBLE));
        Ui.onTextChanged(input, onQuery);
    }

    // Once the views exist, see Fragment.onViewCreated; for a screen right after its views are set
    void attach() {
        back = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                show(false);
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(owner.get(), back);
    }

    // The text restored with the views filters the list, so the field is shown,
    // see Fragment.onViewStateRestored and Activity.onRestoreInstanceState
    void restore() {
        if (!TextUtils.isEmpty(input.getText()))
            setShown(true);
    }

    // Back closes the search of the tab shown only, else it leaves the app
    void onHiddenChanged() {
        updateBack();
    }

    private void setShown(boolean show) {
        title.setVisibility(show ? View.GONE : View.VISIBLE);
        field.setVisibility(show ? View.VISIBLE : View.GONE);
        button.setImageResource(show ? R.drawable.ic_close : R.drawable.ic_search);
        button.setContentDescription(activity.getString(show ? R.string.title_close_search : R.string.menu_search));
        updateBack();
    }

    private void updateBack() {
        if (back != null)
            back.setEnabled(field.getVisibility() == View.VISIBLE && !hidden.get());
    }

    // Closing the search clears it
    private void show(boolean show) {
        setShown(show);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(activity.getWindow(), input);
        if (show) {
            input.requestFocus();
            controller.show(WindowInsetsCompat.Type.ime());
        } else {
            input.setText(null);
            controller.hide(WindowInsetsCompat.Type.ime());
        }
    }
}
