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

import static org.junit.Assert.assertFalse;
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

    // The provider of the library takes the service from any app, so only the one of the app may have the authority
    @Test
    public void theProviderOfTheLibraryIsReplaced() throws Exception {
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("<provider\\s+android:name=\"io\\.github\\.libxposed\\.service\\.XposedProvider\"" +
                "[^>]*tools:node=\"remove\"").matcher(manifest).find());
        assertTrue(Pattern.compile("<provider\\s+android:name=\"\\.ScopeProvider\"" +
                "[^>]*android:authorities=\"\\$\\{applicationId}\\.XposedService\"").matcher(manifest).find());
    }
}
