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

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Who can deliver the service of the framework: any other app would then answer for LSPosed, whether an app is in the
// scope and which apps the user adds
public class ScopeProviderTest {
    @Test
    public void onlyTheFrameworkDeliversItsService() {
        assertTrue(ScopeProvider.accepts(0));
        assertTrue(ScopeProvider.accepts(1000));

        assertFalse(ScopeProvider.accepts(2000)); // The shell
        assertFalse(ScopeProvider.accepts(10123)); // An app
        assertFalse(ScopeProvider.accepts(Util.getUserUid(10, 10123))); // An app of another user
        assertFalse(ScopeProvider.accepts(Util.getUserUid(10, 1000)));
    }

    // The activity manager starts the process of the provider for ContentResolver.getType whatever the manifest says (see
    // ScopeProvider), so what the provider answers must be what an authority which does not exist answers: nothing
    @Test
    public void theProviderAnswersNothing() {
        ScopeProvider provider = new ScopeProvider();
        android.net.Uri uri = android.net.Uri.parse("content://sk.akano12.doppel.XposedService/x");
        assertNull(provider.getType(uri));
        assertNull(provider.query(uri, null, null, null, null));
        assertNull(provider.insert(uri, null));
        assertEquals(0, provider.delete(uri, null, null));
        assertEquals(0, provider.update(uri, null, null, null));
    }

    // The provider of the library takes the service from any app, so only the one of the app may have the authority
    @Test
    public void theProviderOfTheLibraryIsReplaced() throws Exception {
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("<provider\\s+android:name=\"io\\.github\\.libxposed\\.service\\.XposedProvider\"" +
                "[^>]*tools:node=\"remove\"").matcher(manifest).find());
        assertTrue(Pattern.compile("<provider\\s+android:name=\"\\.ScopeProvider\"" +
                "[^>]*android:authorities=\"\\$\\{applicationId}\\.XposedService\"").matcher(manifest).find());
    }

    // An app which can reach the provider makes the activity manager start the process of Doppel, which not exported keeps
    // from every uid but the app's own, root and system, which the framework delivers as, see the manifest
    @Test
    public void noAppReachesTheProvider() throws Exception {
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        java.util.regex.Matcher provider = Pattern.compile("<provider\\s+android:name=\"\\.ScopeProvider\"[^>]*>").matcher(manifest);
        assertTrue(provider.find());
        assertTrue(provider.group(), provider.group().contains("android:exported=\"false\""));
        assertFalse(provider.group(), provider.group().contains("android:exported=\"true\""));
        // And it grants no access to its uris
        assertFalse(provider.group(), provider.group().contains("android:grantUriPermissions=\"true\""));
    }
}
