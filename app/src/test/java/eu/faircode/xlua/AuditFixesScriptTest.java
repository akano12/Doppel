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
import org.luaj.vm2.lib.jse.CoerceLuaToJava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The scripts of calls which answered with hidden apps or real values, as the hook audit found them on a device
public class AuditFixesScriptTest {
    private static final String PARAM = String.join("\n",
            "function paramof(result, arguments, visible)",
            "  return { result = result, arguments = arguments or {},",
            "    getResult = function(self) return self.result end,",
            "    getException = function(self) return nil end,",
            "    getArgument = function(self, index) return self.arguments[index + 1] end,",
            "    setArgument = function(self, index, value) self.arguments[index + 1] = value end,",
            "    setResult = function(self, value) self.result = value; self.set = true end,",
            "    isPackageVisible = function(self, name) return name == visible end }",
            "end",
            "function hookof(method) return { getMethodName = function(self) return method end } end");

    // A name or a component of a hidden app is nothing, like Android gives for an app the caller may not see
    @Test
    public void callsWhichAnswerWithAnApp() throws Exception {
        Globals globals = Scripts.load("package_result", PARAM);
        Varargs result = Scripts.run(globals,
                "local name = paramof('com.hidden')\n" +
                        "local component = paramof({ getPackageName = function(self) return 'com.hidden' end })\n" +
                        "local visible = paramof('com.visible', nil, 'com.visible')\n" +
                        "return after(nil, name), name.result, after(nil, component), component.result,\n" +
                        "  after(nil, visible), visible.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
    }

    // Only the speaker and the earpiece stay: a device without an output does not exist, a headset names its owner
    @Test
    public void builtInOutputsStay() throws Exception {
        Globals globals = Scripts.load("audio_devices", PARAM + "\n" + Scripts.ARRAYOF + "\n" +
                "luajava = { bindClass = function(name) return {\n" +
                "  newInstance = function(self, class, length) return { length = length } end } end }");
        Varargs result = Scripts.run(globals,
                "local function device(sink, type) return { isSink = function() return sink end,\n" +
                        "  getType = function() return type end } end\n" +
                        "local param = paramof(arrayof(device(true, 2), device(false, 15), device(true, 8)))\n" +
                        "local builtin = paramof(arrayof(device(true, 2)))\n" +
                        "return after(nil, param), param.result.length, after(nil, builtin)");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
    }

    // The id of the file system goes, the sizes stay
    @Test
    public void fileSystemId() throws Exception {
        Globals globals = Scripts.load("statvfs_fsid", PARAM + "\n" +
                "luajava = { newInstance = function(name, ...) return { args = { ... } } end }");
        Varargs result = Scripts.run(globals,
                "local param = paramof({ f_fsid = 1234, f_blocks = 7 })\n" +
                        "local none = paramof({ f_fsid = 0 })\n" +
                        "return after(nil, param), param.result.args[9], param.result.args[3], after(nil, none)");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertEquals(7, result.arg(3).toint());
        assertFalse(result.arg(4).toboolean());
    }

    // Asked about another user the service answers for a user which does not exist, for the own user as it is
    @Test
    public void otherUsersAreLikeUsersWhichDoNotExist() throws Exception {
        Globals globals = Scripts.load("user_profiles", PARAM + "\n" +
                "own = { equals = function(self, other) return self == other end }\n" +
                "luajava = { bindClass = function(name) return {\n" +
                "  myUserHandle = function(self) return own end,\n" +
                "  getUserHandleForUid = function(self, uid) return uid end } end }");
        Varargs result = Scripts.run(globals,
                "local other = { equals = function() return false end }\n" +
                        "local param = paramof(nil, { other })\n" +
                        "local mine = paramof(nil, { own })\n" +
                        // A call without arguments, which Android throws for reading
                        "local noargument = paramof()\n" +
                        "noargument.getArgument = function() error('no argument') end\n" +
                        "return before(hookof('isUserRunning'), param), param.arguments[1],\n" +
                        "  before(hookof('isUserRunning'), mine), mine.arguments[1] == own,\n" +
                        "  before(hookof('getUserProfiles'), noargument), after(hookof('isQuietModeEnabled'), paramof(true))");
        assertTrue(result.arg(1).toboolean());
        assertEquals(999900000, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
    }

    // The other handles of users are answered like users which do not exist, the own one stays
    @Test
    public void otherUsersInUserRestrictions() throws Exception {
        Globals globals = Scripts.load("user_profiles", PARAM + "\n" +
                "own = { equals = function(self, other) return self == other end }\n" +
                "luajava = { bindClass = function(name) return {\n" +
                "  myUserHandle = function(self) return own end,\n" +
                "  getUserHandleForUid = function(self, uid) return uid end } end }");
        Varargs result = Scripts.run(globals,
                "local other = { equals = function() return false end }\n" +
                        "local foreign = paramof(nil, { other })\n" +
                        "local mine = paramof(nil, { own })\n" +
                        "return before(hookof('isUserRunningOrStopping'), foreign), foreign.arguments[1],\n" +
                        "  before(hookof('getUserRestrictions'), mine), mine.arguments[1] == own");
        assertTrue(result.arg(1).toboolean());
        assertEquals(999900000, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
    }

    // The name of an account in a column of contacts or calendars is the fake one, of the types of the app it stays
    @Test
    public void accountColumns() throws Exception {
        Globals globals = Scripts.load("account_columns", PARAM + "\n" +
                "local real = luajava\n" +
                "luajava = { createProxy = function(interface, methods) return methods end, newInstance = real.newInstance,\n" +
                "  bindClass = real.bindClass }\n" +
                "function owntypes() return { ['own.type'] = true } end\n" +
                "function cursorof(type, name) return {\n" +
                "  getColumnIndex = function(self, column)\n" +
                "    return ({ account_name = 0, account_type = 1 })[column] or -1 end,\n" +
                "  getString = function(self, index) return index == 0 and name or type end,\n" +
                "  getBlob = function(self, index) return 'blob ' .. name end,\n" +
                "  copyStringToBuffer = function(self, index, buffer) buffer.copied = name end } end\n" +
                "function uriof(authority) return { getAuthority = function(self) return authority end } end\n" +
                "function queried(authority, cursor)\n" +
                "  local param = paramof(cursor, { uriof(authority) })\n" +
                "  param.getSetting = function(self, name) return 'fake@example.com' end\n" +
                "  return after(nil, param), param.result\n" +
                "end");
        Varargs result = Scripts.run(globals,
                "local _, fake = queried('com.android.contacts', cursorof('com.google', 'real@example.com'))\n" +
                        "local _, own = queried('0@com.android.calendar', cursorof('own.type', 'mine@example.com'))\n" +
                        "local other = queried('com.example', cursorof('com.google', 'real@example.com'))\n" +
                        "return fake.getString(0), fake.getString(1), own.getString(0), other");
        assertEquals("fake@example.com", result.arg(1).tojstring());
        assertEquals("com.google", result.arg(2).tojstring());
        assertEquals("mine@example.com", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());

        // The bytes and the chars of the name are the fake one too, of the account of the app the real ones
        result = Scripts.run(globals,
                "local _, fake = queried('com.android.contacts', cursorof('com.google', 'real@example.com'))\n" +
                        "local _, own = queried('0@com.android.calendar', cursorof('own.type', 'mine@example.com'))\n" +
                        "local buffer, ownBuffer = {}, {}\n" +
                        "fake.copyStringToBuffer(0, buffer)\n" +
                        "own.copyStringToBuffer(0, ownBuffer)\n" +
                        "return fake.getBlob(0), buffer.sizeCopied, buffer.data, own.getBlob(0), ownBuffer.copied");
        // The proxy coerces the text to the byte[] of getBlob
        assertEquals("fake@example.com", new String((byte[]) CoerceLuaToJava.coerce(result.arg(1), byte[].class)));
        assertEquals(16, result.arg(2).toint());
        assertEquals("fake@example.com", new String((char[]) result.arg(3).touserdata()));
        assertEquals("blob mine@example.com", result.arg(4).tojstring());
        assertEquals("mine@example.com", result.arg(5).tojstring());
    }

    // The search domains of a VPN are text, nothing when they go; its routes are a list
    @Test
    public void searchDomainsOfVpn() throws Exception {
        Globals globals = Scripts.load("vpn_addresses", PARAM + "\n" +
                "luajava = { newInstance = function(name) return name end, bindClass = function(name) return {\n" +
                "  currentThread = function(self) return { getStackTrace = function(self) return { length = 0 } end } end } end }\n" +
                "function isvpninterface(name) return true end\n" +
                "function linkproperties() return false end");
        Varargs result = Scripts.run(globals,
                "local function properties(value)\n" +
                        "  local param = paramof(value)\n" +
                        "  param.getThis = function() return { getInterfaceName = function() return 'tun0' end } end\n" +
                        "  param.unhooked = function(self, f) return f() end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local domains = properties('corp.example')\n" +
                        "local routes = properties({ isEmpty = function() return false end })\n" +
                        "return after(hookof('getDomains'), domains), domains.result, domains.set,\n" +
                        "  after(hookof('getRoutes'), routes), routes.result");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals("java.util.ArrayList", result.arg(5).tojstring());
    }
}
