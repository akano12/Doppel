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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;

// What hooked apps may ask the service without the permission of the app, see XProvider.APP_CALLS
public class XProviderTest {
    // A method added here can give any hooked app the data of other apps, like getAllSettings the fake values
    // set for each app: it must check the caller itself, like the ones listed do
    @Test
    public void hookedAppsAskForThemselvesOnly() {
        assertEquals(Collections.singleton("report"), XProvider.APP_CALLS);
        assertEquals(new HashSet<>(Arrays.asList("getAssignedHooks2", "getSettings", "getBootValues")),
                XProvider.APP_QUERIES);
    }
}
