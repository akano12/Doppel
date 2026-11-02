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
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

// A restore of a backup file, which users can edit, against the service of the device: what cannot be read is left out
// and counted, the rest is restored. Needs the module running (skipped without it); what it restores is removed again.
@RunWith(AndroidJUnit4.class)
public class BackupRestoreTest {
    private static final String SETTING = "test.restore";
    private static final String REPLACED = "Privacy.Os.getsockname";

    @Test
    public void leavesOutWhatItCannotRead() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Module not running", Data.isAvailable(context));

        String definition = "{\"collection\": \"%s\", \"group\": \"Read.Network\", \"name\": \"%s\", \"author\": \"Test\"," +
                " \"className\": \"android.system.Os\", \"methodName\": \"getsockname\"," +
                " \"parameterTypes\": [\"java.io.FileDescriptor\"], \"minSdk\": 27, \"luaScript\": \"%s\"}";
        String backup = "{\"format\": 1, \"version\": " + BuildConfig.VERSION_CODE + "," +
                // A setting, one without a category and something else
                " \"settings\": [{\"category\": \"global\", \"name\": \"" + SETTING + "\", \"value\": \"1\"}," +
                " {\"name\": \"x\"}, 42]," +
                // A definition which replaces a built-in one and one with a script which does not compile
                " \"hooks\": [" + String.format(definition, "Privacy", "Os.getsockname",
                "function after(hook, param)\\n  return false\\nend\\n") + "," +
                String.format(definition, "Test", "Broken.restore", "function after(hook, param\\n") + "]," +
                // Restrictions without a package and of an app which is not installed
                " \"restrictions\": [{\"hooks\": []}, {\"package\": \"com.example.not.installed\", \"hooks\": []}]}";

        File file = new File(context.getCacheDir(), "restore-test.json");
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(backup.getBytes(StandardCharsets.UTF_8));
        }

        try {
            Backup.Result result = Backup.restore(context, Uri.fromFile(file));
            assertEquals(1, result.settings);
            assertEquals(1, result.hooks);
            assertEquals(1, result.hooksFailed);
            assertEquals(3, result.failed);
            assertEquals(Collections.singletonList(REPLACED), result.replaced);
            assertEquals(Collections.singletonList("com.example.not.installed"), result.missing);
            assertEquals("1", Data.getSetting(context, "global", SETTING));
        } finally {
            Data.putSetting(context, "global", SETTING, null);
            // Deleting the custom definition restores the built-in one
            Data.putHook(context, REPLACED, null);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        assertNull(Data.getSetting(context, "global", SETTING));
    }
}
