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

import android.app.Activity;

import androidx.fragment.app.Fragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// A tab of the main screen, which reloads its data each time it becomes visible
public abstract class FragmentBase extends Fragment {
    protected final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        executor.shutdown();
        super.onDestroy();
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

    // Run on the background thread, errors are shown; not anymore when the tab is gone, like for a late Undo
    protected void run(String tag, Ui.Task task) {
        if (executor.isShutdown())
            return;
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                error(tag, ex);
            }
        });
    }
}
