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
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.jse.JsePlatform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// A dynamic hook runs a function of the runtime of another thread, which can be in a script waiting on it
// (see XCore.execute): the call stacks of debug builds are per Java thread, so neither needs a lock of the other
public class DebugLibThreadsTest {
    @Test
    public void callStackPerThread() throws Throwable {
        Globals globals = JsePlatform.standardGlobals();
        globals.load(new DebugLib());
        final String[] other = new String[1];
        globals.set("onOtherThread", new OneArgFunction() {
            @Override
            public LuaValue call(final LuaValue fun) {
                Thread thread = new Thread(() -> other[0] = fun.call().tojstring());
                thread.start();
                try {
                    thread.join();
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                return LuaValue.NIL;
            }
        });
        String own = globals.load(
                "function dynamic() local t = debug.traceback() return t end\n" +
                        "function outer() onOtherThread(dynamic) local t = debug.traceback() return t end\n" +
                        "local t = outer() return t", "test").call().tojstring();
        // Lines 1, 2 and 3 are the frames of dynamic, outer and the chunk
        assertTrue(own, own.contains("test:2") && own.contains("test:3"));
        assertTrue(other[0], other[0].contains("test:1"));
        assertFalse(other[0], other[0].contains("test:2") || other[0].contains("test:3"));
    }
}
