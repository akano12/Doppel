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
import android.content.Intent;
import android.os.Bundle;

// The log of one app, opened from the screen of the app, like the Activity tab
public class ActivityLog extends ActivityBase {
    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_LABEL = "label";

    // A uid of -1 is the app of any user
    static Intent getIntent(Context context, String packageName, int uid, String label) {
        Intent intent = new Intent(context, ActivityLog.class);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_LABEL, label);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        setupToolbar(getString(R.string.title_log)).setSubtitle(getIntent().getStringExtra(EXTRA_LABEL));

        // Else the fragment is restored by the framework
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.content, FragmentLog.forApp(
                            getIntent().getStringExtra(EXTRA_PACKAGE),
                            getIntent().getIntExtra(EXTRA_UID, -1)))
                    .commit();
    }
}
