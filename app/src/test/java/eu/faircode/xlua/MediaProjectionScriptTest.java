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

// The result of the consent to screen capture is that of a user declining (mediaprojection_result): RESULT_CANCELED
// without data, while other results stay as they are
public class MediaProjectionScriptTest {
    private static final String STUBS = String.join("\n",
            "TOKEN = 'android.media.projection.extra.EXTRA_MEDIA_PROJECTION'",
            // A result intent with the extras given; nil extras fail to unparcel
            "function intentof(extras)",
            "  return { hasExtra = function(self, key) if extras == nil then error('BadParcelableException') end",
            "    return extras[key] ~= nil end }",
            "end",
            "function paramof(code, data)",
            "  return { args = { [0] = nil, [1] = 7, [2] = code, [3] = data }, set = false,",
            "    getArgument = function(self, i) return self.args[i] end,",
            "    setArgument = function(self, i, v) self.args[i] = v self.set = true end }",
            "end",
            "function run(param) return before(nil, param), param.args[2], param.args[3] == nil, param.set end");

    private static Globals load() throws Exception {
        return Scripts.load("mediaprojection_result", STUBS);
    }

    @Test
    public void consentIsDeclined() throws Exception {
        Globals globals = load();
        String[][] cases = {
                // code, data, restricted, code after, no data after, set
                {"-1", "intentof({ [TOKEN] = 'binder' })", "true", "0", "true", "true"},
                {"-1", "intentof({ other = 1 })", "false", "-1", "false", "false"},
                {"0", "intentof({ [TOKEN] = 'binder' })", "false", "0", "false", "false"},
                {"1", "intentof({ [TOKEN] = 'binder' })", "false", "1", "false", "false"},
                {"-1", "nil", "false", "-1", "true", "false"},
                {"-1", "intentof(nil)", "false", "-1", "false", "false"}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "return run(paramof(" + c[0] + ", " + c[1] + "))");
            String name = c[0] + " " + c[1];
            assertEquals(name, Boolean.parseBoolean(c[2]), result.arg(1).toboolean());
            assertEquals(name, Integer.parseInt(c[3]), result.arg(2).toint());
            assertEquals(name, Boolean.parseBoolean(c[4]), result.arg(3).toboolean());
            assertEquals(name, Boolean.parseBoolean(c[5]), result.arg(4).toboolean());
        }
    }
}
