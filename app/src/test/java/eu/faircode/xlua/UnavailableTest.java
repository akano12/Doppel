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
*/

package eu.faircode.xlua;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UnavailableTest {
    @Test
    public void helpTellsApartWhatTheAppCanKnow() {
        int current = BuildConfig.VERSION_CODE;
        assertEquals(R.string.msg_module_version, ActivityMain.helpFor(current - 1, false, false));
        assertEquals(R.string.msg_no_service_scope, ActivityMain.helpFor(-1, true, false));
        assertEquals(R.string.msg_no_service_restart, ActivityMain.helpFor(-1, true, true));
        // No framework, a legacy one, a disabled module: not told apart
        assertEquals(R.string.msg_no_service_help, ActivityMain.helpFor(-1, false, false));
        // A module newer than the app is not cured by a restart
        assertEquals(R.string.msg_no_service_help, ActivityMain.helpFor(current + 1, true, true));
    }
}
