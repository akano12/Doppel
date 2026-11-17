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
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The events of the legacy Amplitude SDK (amplitude_logevent) are skipped with the -1 of an opted-out SDK
public class AmplitudeLogEventScriptTest {
    @Test
    public void eventIsSkippedWithNoId() throws Exception {
        Varargs result = Scripts.run(Scripts.load("amplitude_logevent", ""),
                "local param = { setResult = function(self, value) self.result = value end }\n" +
                "return before(nil, param), param.result");
        assertTrue(result.arg1().toboolean());
        assertEquals(-1, result.arg(2).toint());
    }
}
