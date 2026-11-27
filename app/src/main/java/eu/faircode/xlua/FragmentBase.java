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

import android.app.Activity;
import android.app.Dialog;

import androidx.fragment.app.Fragment;

// A tab of the main screen, which reloads its data each time it becomes visible
public abstract class FragmentBase extends Fragment {
    private volatile boolean destroyed = false;

    // The dialog of the tab which waits for an answer, see track
    private Dialog dialog = null;

    protected abstract void load();

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden())
            load();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isResumed())
            load();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    // A dialog which asks or tells something is closed with the view, else a recreation, like for dark mode or the
    // language, leaks its window; the tab shows it again from what it keeps, which closed tells to forget
    protected void track(Dialog dialog, Runnable closed) {
        this.dialog = dialog;
        dialog.setOnDismissListener(d -> {
            this.dialog = null;
            closed.run();
        });
    }

    protected boolean hasDialog() {
        return dialog != null;
    }

    @Override
    public void onDestroyView() {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
            dialog = null;
        }
        super.onDestroyView();
    }

    // Run on the main thread, when the view still exists
    protected void post(Runnable runnable) {
        Activity activity = getActivity();
        if (activity != null)
            activity.runOnUiThread(() -> {
                if (getView() != null)
                    runnable.run();
            });
    }

    // Also from a background thread, after the tab is gone
    protected void error(String tag, Throwable ex) {
        Ui.error(getActivity(), tag, ex);
    }

    // A failed load, with a retry, else the tab stays blank or shows what it showed before
    protected void loadError(String tag, Throwable ex) {
        Ui.error(getActivity(), tag, ex, () -> {
            if (isAdded())
                load();
        });
    }

    // Run on the background thread, errors are shown; not anymore when the tab is gone, like for a late Undo
    protected void run(String tag, Ui.Task task) {
        if (!destroyed)
            Ui.submit(task, ex -> error(tag, ex));
    }

    // Run on the background thread a task which handles its errors itself, like a load; not anymore when the tab is gone
    protected void submit(Runnable task) {
        if (!destroyed)
            Ui.submit(task);
    }
}
