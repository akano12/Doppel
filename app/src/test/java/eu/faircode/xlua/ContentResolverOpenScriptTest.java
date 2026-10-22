/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of files of providers (assets/contentresolver_open.lua) with Lua stand-ins, like LocationScriptTest;
// the exception is a stand-in, because luajava cannot make java.base classes accessible on a desktop JVM
public class ContentResolverOpenScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(name, method)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return method end }",
            "end",
            "function paramof(authority, path, mode)",
            "  local uri = { getAuthority = function(self) return authority end,",
            "    toString = function(self) return 'content://' .. authority .. path end }",
            "  local arguments = { [0] = uri, [1] = mode }",
            "  return { getArgument = function(self, index) return arguments[index] end,",
            "    setResult = function(self, value) self.result = value end }",
            "end",
            "luajava = { newInstance = function(name, message) return { name = name, message = message } end }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = JsePlatform.standardGlobals();
        String script = new String(Files.readAllBytes(new File("src/main/assets/contentresolver_open.lua").toPath()),
                StandardCharsets.UTF_8);
        globals.load(script, "contentresolver_open").call();
        globals.load(STUBS, "stubs").call();
    }

    // Whether restricted, the class and the message of the exception
    private Varargs open(String hook, String method, String authority, String path, String mode) {
        return globals.load(
                "local param = paramof('" + authority + "', '" + path + "', " + mode + ")\n" +
                        "local restricted = before(hookof('" + hook + "', '" + method + "'), param)\n" +
                        "if param.result == nil then return restricted end\n" +
                        "return restricted, param.result.name, param.result.message", "test").invoke();
    }

    // Attachments of MMS messages are not found, like their rows, also with the user in the authority
    @Test
    public void attachmentIsNotFound() {
        Varargs result = open("ContentResolver.openAssetFileDescriptor/mmssms", "openAssetFileDescriptor",
                "0@mms", "/part/1", "'r'");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.io.FileNotFoundException", result.arg(2).tojstring());
        assertEquals("No entry for content://0@mms/part/1", result.arg(3).tojstring());

        // The second argument of the typed methods is a type
        Varargs photo = open("ContentProviderClient.openTypedAssetFile/contacts", "openTypedAssetFile",
                "com.android.contacts", "/contacts/1/display_photo", "'image/*'");
        assertTrue(photo.arg(1).toboolean());
    }

    // Writing reads nothing; other providers and other groups stay
    @Test
    public void otherFilesStay() {
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", "openAssetFileDescriptor",
                "com.android.contacts", "/display_photo/1", "'w'").arg1().toboolean());
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", "openAssetFileDescriptor",
                "com.example.provider", "/file", "'r'").arg1().toboolean());
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", "openAssetFileDescriptor",
                "mms", "/part/1", "'r'").arg1().toboolean());
    }
}
