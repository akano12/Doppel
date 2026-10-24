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
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The generic scripts which many definitions share: skipping a method and emptying its result,
// with the value of the return type of the definition
public class GenericScriptsTest {
    // XParam.isInstance for Java objects, which Lua cannot do itself (Lua sees a Class as its static members)
    public static class Checks {
        public boolean isInstance(Object object, String className) throws ClassNotFoundException {
            return Class.forName(className).isInstance(object);
        }
    }

    // Lua tables stand for the collections of the results, with the class they are an instance of
    // and whether they are empty, which is all the script reads of them
    private static final String STUBS = String.join("\n",
            "function hookof(returnType)",
            "  return { getReturnType = function(self) return returnType end }",
            "end",
            // A param with a result, which tells whether a result was set
            "function paramof(result)",
            "  return { result = result, set = false,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    isInstance = function(self, object, name)",
            "      if type(object) == 'table' then",
            "        return object.kind == name or (name == 'java.util.Collection' and object.kind ~= nil)",
            "      end",
            "      return checks:isInstance(object, name)",
            "    end }",
            "end",
            "function collectionof(kind, empty)",
            "  return { kind = kind, isEmpty = function(self) return empty end }",
            "end");

    private static Globals load(String script) throws Exception {
        Globals globals = Scripts.load(script, STUBS);
        globals.set("checks", CoerceJavaToLua.coerce(new Checks()));
        return globals;
    }

    @Test
    public void blockedMethodsReturnTheValueOfTheirType() throws Exception {
        Globals globals = load("generic_block");
        for (String[] type : new String[][]{{"boolean", "false"}, {"int", "0"}, {"void", "nil"}}) {
            Varargs result = Scripts.run(globals, "local param = paramof('real')\n" +
                    "local restricted = before(hookof('" + type[0] + "'), param)\n" +
                    "return restricted, param.set, param.result");
            assertTrue(type[0], result.arg(1).toboolean());
            assertTrue(type[0], result.arg(2).toboolean());
            assertEquals(type[0], type[1], result.arg(3).tojstring());
        }
    }

    @Test
    public void listsSetsAndArraysAreEmptied() throws Exception {
        Globals globals = load("generic_empty");

        // A list and a set with items become empty Java collections of their kind
        for (String[] kind : new String[][]{{"java.util.List", "java.util.ArrayList"}, {"java.util.Set", "java.util.HashSet"}}) {
            Varargs result = Scripts.run(globals, "local param = paramof(collectionof('" + kind[0] + "', false))\n" +
                    "local restricted = after(hookof('" + kind[0] + "'), param)\n" +
                    "return restricted, param.result");
            assertTrue(kind[0], result.arg(1).toboolean());
            Object fake = result.arg(2).checkuserdata();
            assertEquals(kind[0], kind[1], fake.getClass().getName());
            assertTrue(kind[0], ((Collection<?>) fake).isEmpty());
        }

        // An array becomes an empty array of the component type of the return type
        globals.set("array", CoerceJavaToLua.coerce(new String[]{"a"}));
        Varargs result = Scripts.run(globals, "local param = paramof(array)\n" +
                "local restricted = after(hookof('[Ljava.lang.String;'), param)\n" +
                "return restricted, param.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, ((String[]) result.arg(2).checkuserdata()).length);

        // Empty results and no result stay
        globals.set("empty", CoerceJavaToLua.coerce(new String[0]));
        for (String value : new String[]{"collectionof('java.util.List', true)", "empty", "nil"}) {
            result = Scripts.run(globals, "local param = paramof(" + value + ")\n" +
                    "local restricted = after(hookof('java.util.List'), param)\n" +
                    "return restricted, param.set");
            assertFalse(value, result.arg(1).toboolean());
            assertFalse(value, result.arg(2).toboolean());
        }
    }
}
