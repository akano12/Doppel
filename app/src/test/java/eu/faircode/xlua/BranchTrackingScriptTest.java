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
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The switch of Branch's TrackingController.disableTracking (branch_tracking): the app's false disables tracking, its
// true stays, and the context and the callback of version 5.11 and later stay as they are
public class BranchTrackingScriptTest {
    private static final String STUBS = String.join("\n",
            "function paramof(args)",
            "  return { args = args,",
            "    getArgument = function(self, i) return self.args[i] end,",
            "    setArgument = function(self, i, value) self.args[i] = value end }",
            "end");

    @Test
    public void appEnablingTrackingDisablesIt() throws Exception {
        Globals globals = Scripts.load("branch_tracking", STUBS);
        Varargs enable = Scripts.run(globals, "local param = paramof({ [0] = 'context', [1] = false, [2] = 'callback' })\n" +
                "return before(nil, param), param.args[0], param.args[1], param.args[2]");
        assertTrue(enable.arg1().toboolean());
        assertEquals("context", enable.arg(2).tojstring());
        assertTrue(enable.arg(3).toboolean());
        assertEquals("callback", enable.arg(4).tojstring());
        // Before 5.11, without the callback
        Varargs old = Scripts.run(globals, "local param = paramof({ [0] = 'context', [1] = false })\n" +
                "return before(nil, param), param.args[1]");
        assertTrue(old.arg1().toboolean());
        assertTrue(old.arg(2).toboolean());
        // The app disabling it itself is not restricted
        Varargs disable = Scripts.run(globals, "local param = paramof({ [0] = 'context', [1] = true, [2] = 'callback' })\n" +
                "return before(nil, param), param.args[1]");
        assertFalse(disable.arg1().toboolean());
        assertTrue(disable.arg(2).toboolean());
    }
}
