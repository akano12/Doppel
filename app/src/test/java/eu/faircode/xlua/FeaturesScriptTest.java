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
import static org.junit.Assert.assertTrue;

// The cost of the list of features (assets/features.lua): the stack, which tells the framework from the app,
// is the same for all features of one call
public class FeaturesScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            "walks = 0",
            "function callers()",
            "  walks = walks + 1",
            "  local frame = { getClassName = function(self) return 'com.example.app.Main' end,",
            "    getMethodName = function(self) return 'onCreate' end }",
            "  return { frame, length = 1 }",
            "end",
            "function featuresof(count)",
            "  local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(",
            "    luajava.bindClass('android.content.pm.FeatureInfo'), count)",
            "  for index = 1, count do",
            "    local info = luajava.newInstance('android.content.pm.FeatureInfo')",
            "    info.name = 'android.hardware.feature' .. index",
            "    array[index] = info",
            "  end",
            "  return array",
            "end");

    @Test
    public void featureListWalksStackOnce() throws Exception {
        Globals globals = Scripts.load("features", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = { result = featuresof(150),\n" +
                        "  getResult = function(self) return self.result end,\n" +
                        "  setResult = function(self, value) self.result = value end,\n" +
                        "  getSetting = function(self, name) return 'android.hardware.feature1' end }\n" +
                        "local restricted = after(hookof('getSystemAvailableFeatures'), param)\n" +
                        "return restricted, param.result['length'], walks");
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals("stack walks", 1, result.arg(3).toint());
    }
}
