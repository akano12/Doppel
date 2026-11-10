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

// The script of the hidden sensors (assets/sensors.lua) with Lua stand-ins for the manager and the lists
public class SensorsScriptTest {
    private static final String STUBS = String.join("\n",
            "luajava = {",
            "  bindClass = function(name) assert(name == 'java.util.Collections', name)",
            "    return { unmodifiableList = function(self, list) return { unmodifiable = true, isEmpty = function() return true end,",
            "      size = function() return 0 end } end } end,",
            "  newInstance = function(name) assert(name == 'java.util.ArrayList', name) return {} end }",
            "function hookof(method) return { getMethodName = function(self) return method end } end",
            "values = {}",
            "function paramof(this, argument, result)",
            "  return { result = result,",
            "    getThis = function(self) return this end,",
            "    getArgument = function(self, index) return argument end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    putValue = function(self, name, value, scope) values[tostring(scope) .. name] = value end,",
            "    getValue = function(self, name, scope) return values[tostring(scope) .. name] end }",
            "end",
            "function list(count) return { isEmpty = function() return count == 0 end, size = function() return count end } end");

    private static String text(Varargs values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= values.narg(); i++)
            sb.append(i > 1 ? " " : "").append(values.arg(i).tojstring());
        return sb.toString();
    }

    // Empty unmodifiable lists, of getSensorList the same one for the same manager and type, like Android keeps them;
    // a list which is empty already stays
    @Test
    public void listsAreEmptyLikeAndroids() throws Exception {
        Globals globals = Scripts.load("sensors", STUBS);
        Varargs result = Scripts.run(globals,
                "local manager, other = {}, {}\n" +
                        "local function sensors(this, type, result) local param = paramof(this, type, result)\n" +
                        "  after(hookof('getSensorList'), param) return param.result end\n" +
                        "local one, again = sensors(manager, -1, list(3)), sensors(manager, -1, list(3))\n" +
                        "local empty = list(0)\n" +
                        "local dynamic = paramof(manager, -1, list(1))\n" +
                        "after(hookof('getDynamicSensorList'), dynamic)\n" +
                        "return one.unmodifiable, one == again, one == sensors(manager, 1, list(1)), one == sensors(other, -1, list(3)),\n" +
                        "  sensors(manager, 5, empty) == empty, dynamic.result.unmodifiable");
        assertEquals("true true false false true true", text(result));
    }

    // A dynamic sensor callback is not registered, a null one goes to Android, which throws; the screen cannot be
    // turned off by the proximity sensor, the other wake locks stay
    @Test
    public void noDynamicSensorsAndNoProximityWakeLock() throws Exception {
        Globals globals = Scripts.load("sensors", STUBS);
        Varargs result = Scripts.run(globals,
                "local registered = paramof({}, {}, 'none')\n" +
                        "local skipped = before(hookof('registerDynamicSensorCallback'), registered)\n" +
                        "local null = paramof({}, nil, 'none')\n" +
                        "local list = paramof({}, -1, 'none')\n" +
                        "local proximity = paramof({}, 32, true)\n" +
                        "after(hookof('isWakeLockLevelSupported'), proximity)\n" +
                        "local partial = paramof({}, 1, true)\n" +
                        "after(hookof('isWakeLockLevelSupported'), partial)\n" +
                        "return skipped, registered.result, before(hookof('registerDynamicSensorCallback'), null),\n" +
                        "  before(hookof('getSensorList'), list), list.result, proximity.result, partial.result");
        assertEquals("true nil false false none false true", text(result));
    }
}
