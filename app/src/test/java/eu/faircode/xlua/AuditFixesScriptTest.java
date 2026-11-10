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
            "function hookof(method, class) return { getMethodName = function(self) return method end,",
            "  getClassName = function(self) return class end,",
            "  getParameterTypes = function(self) return { length = 0 } end } end");

    // A name or a component of a hidden app is nothing, like Android gives for an app the caller may not see
    @Test
    public void callsWhichAnswerWithAnApp() throws Exception {
        Globals globals = Scripts.load("package_result", PARAM);
        Varargs result = Scripts.run(globals,
                "local name = paramof('com.hidden')\n" +
                        "local component = paramof({ getPackageName = function(self) return 'com.hidden' end })\n" +
                        "local visible = paramof('com.visible', nil, 'com.visible')\n" +
                        "local hook = hookof('getInstallerPackageName')\n" +
                        "return after(hook, name), name.result, after(hook, component), component.result,\n" +
                        "  after(hook, visible), visible.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
    }

    // The certificate of the store which started the install goes with its name: none when that store is hidden,
    // like Android gives no signing info without the name (ComputerEngine.getInstallSourceInfo)
    @Test
    public void signingInfoOfHiddenStoreIsNone() throws Exception {
        Globals globals = Scripts.load("package_result", PARAM + "\n" +
                "function sourceof(initiating, visible)\n" +
                "  local param = paramof({ signing = true }, nil, visible)\n" +
                "  local info = { getInitiatingPackageName = function(self) return initiating end }\n" +
                "  param.getThis = function(self) return info end\n" +
                "  param.unhooked = function(self, fn) param.inside = true; return fn() end\n" +
                "  return param\n" +
                "end");
        Varargs result = Scripts.run(globals,
                "local hook = hookof('getInitiatingPackageSigningInfo')\n" +
                        "local hidden = sourceof('com.store.hidden')\n" +
                        "local visible = sourceof('com.store', 'com.store')\n" +
                        "local none = sourceof(nil)\n" +
                        "local r1, old = after(hook, hidden)\n" +
                        "return r1, old, hidden.result, hidden.inside, after(hook, visible), visible.set,\n" +
                        "  after(hook, none), none.set");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.store.hidden", result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
        // The name is read without the hooks, which would else hide it
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
    }

    // The standby buckets of all apps keep the visible ones only, a map like Android gives (real HashMap of luajava)
    @Test
    public void standbyBucketsOfHiddenAppsAreLeftOut() throws Exception {
        Globals globals = Scripts.load("generic_filter_by_uid", PARAM);
        Varargs result = Scripts.run(globals,
                "local hook = { getName = function() return 'UsageStatsManager.getAppStandbyBuckets' end }\n" +
                        "local map = luajava.newInstance('java.util.HashMap')\n" +
                        "map:put('com.visible', 10)\n" +
                        "map:put('com.hidden', 40)\n" +
                        "local param = paramof(map, nil, 'com.visible')\n" +
                        "param.isSystem = function() return false end\n" +
                        "local all = luajava.newInstance('java.util.HashMap')\n" +
                        "all:put('com.visible', 10)\n" +
                        "local same = paramof(all, nil, 'com.visible')\n" +
                        "same.isSystem = function() return false end\n" +
                        "local restricted, old, new = after(hook, param)\n" +
                        "return restricted, old, new, param.result:size(), param.result:get('com.visible'), after(hook, same)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("2", result.arg(2).tojstring());
        assertEquals("1", result.arg(3).tojstring());
        assertEquals(1, result.arg(4).toint());
        assertEquals(10, result.arg(5).toint());
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
                        "local hook = { getMethodName = function() return 'getDevices' end }\n" +
                        "return after(hook, param), param.result.length, after(hook, builtin)");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
    }

    // A started record gives silence at the pace of its sample rate: zeros in whole frames, a blocking read waits for
    // its frames, a non-blocking one gives the frames since the start; calls Android refuses go to Android
    @Test
    public void startedRecordGivesSilence() throws Exception {
        Globals globals = Scripts.load("audio_record", PARAM);
        Varargs result = Scripts.run(globals,
                "now = 1000\n" +
                        "function clock() return now end\n" +
                        "local values = {}\n" +
                        "local record = { state = 1, format = 2, getState = function(self) return self.state end,\n" +
                        "  getAudioFormat = function(self) return self.format end,\n" +
                        "  getChannelCount = function(self) return 1 end, getSampleRate = function(self) return 48000 end }\n" +
                        "local function call(name, ...)\n" +
                        "  local args = { ... }\n" +
                        "  local param = paramof(nil, args)\n" +
                        "  param.getThis = function() return record end\n" +
                        "  param.putValue = function(self, key, value, scope) values[key] = value end\n" +
                        "  param.getValue = function(self, key, scope) return values[key] end\n" +
                        "  local hook = { getMethodName = function() return string.match(name, '^[^/]+%.(%a+)') end,\n" +
                        "    getName = function() return name end,\n" +
                        "    getParameterTypes = function() return { length = #args } end }\n" +
                        "  local restricted = before(hook, param)\n" +
                        "  return restricted, param.result end\n" +
                        "local array = luajava.bindClass('java.lang.reflect.Array')\n" +
                        "local shorts = array:newInstance(luajava.bindClass('java.lang.Short').TYPE, 100)\n" +
                        "for i = 1, 100 do shorts[i] = 7 end\n" +
                        "local started = call('AudioRecord.startRecording')\n" +
                        "local _, state = call('AudioRecord.getRecordingState')\n" +
                        "local _, blocking = call('AudioRecord.read/short', shorts, 2, 96)\n" +
                        "local s1, s3, s98, s99 = shorts[1], shorts[3], shorts[98], shorts[99]\n" +
                        "local _, none = call('AudioRecord.read/short/mode', shorts, 0, 96, 1)\n" +
                        "now = now + 10\n" +
                        "local _, some = call('AudioRecord.read/short/mode', shorts, 0, 96, 1)\n" +
                        "local bytes = array:newInstance(luajava.bindClass('java.lang.Byte').TYPE, 5)\n" +
                        "local _, odd = call('AudioRecord.read/byte', bytes, 0, 5)\n" +
                        "local floats = array:newInstance(luajava.bindClass('java.lang.Float').TYPE, 4)\n" +
                        "local float = call('AudioRecord.read/float/mode', floats, 0, 4, 0)\n" +
                        "local outside = call('AudioRecord.read/short', shorts, 90, 20)\n" +
                        // A stand-in, since the JVM does not let LuaJ call the members of its direct buffers
                        "local buffer = { first = 9, isDirect = function() return true end,\n" +
                        "  capacity = function() return 8 end, clear = function(self) self.cleared = true end,\n" +
                        "  put = function(self, zeros, at, count) if self.cleared and at == 0 then self.first = zeros[1] end end,\n" +
                        "  get = function(self, index) return self.first end }\n" +
                        "buffer.duplicate = function() return buffer end\n" +
                        "local _, direct = call('AudioRecord.read/buffer', buffer, 16)\n" +
                        "local stopped = call('AudioRecord.stop')\n" +
                        "local after = call('AudioRecord.getRecordingState')\n" +
                        "record.state = 0\n" +
                        "local uninitialized = call('AudioRecord.startRecording')\n" +
                        "return started, state, blocking, s1, s3, s98, s99, none, some,\n" +
                        "  odd, float, outside, direct, buffer:get(0), stopped, after, uninitialized");
        assertTrue(result.arg(1).toboolean());
        assertEquals(3, result.arg(2).toint());
        assertEquals(96, result.arg(3).toint());
        assertEquals(7, result.arg(4).toint());
        assertEquals(0, result.arg(5).toint());
        assertEquals(0, result.arg(6).toint());
        assertEquals(7, result.arg(7).toint());
        assertEquals(0, result.arg(8).toint()); // Nothing new without time
        assertEquals(96, result.arg(9).toint()); // 10 ms later there are frames
        assertEquals(4, result.arg(10).toint()); // Two frames of two bytes
        assertFalse(result.arg(11).toboolean()); // Floats of 16 bit audio are refused by Android
        assertFalse(result.arg(12).toboolean()); // So is a size past the array
        assertEquals(8, result.arg(13).toint()); // At most the capacity of the buffer, from its start
        assertEquals(0, result.arg(14).toint());
        assertTrue(result.arg(15).toboolean());
        assertFalse(result.arg(16).toboolean());
        assertFalse(result.arg(17).toboolean());
    }

    // The devices for calls keep the speaker and the earpiece; a hidden device for calls or sound becomes the
    // earpiece or the speaker, like Android chooses without it
    @Test
    public void builtInDevicesForCallsStay() throws Exception {
        Globals globals = Scripts.load("audio_devices", PARAM + "\n" + Scripts.ARRAYOF + "\n" +
                "function listof(...)\n" +
                "  local items = { ... }\n" +
                "  return { items = items, size = function(self) return #self.items end,\n" +
                "    get = function(self, index) return self.items[index + 1] end,\n" +
                "    add = function(self, item) self.items[#self.items + 1] = item end,\n" +
                "    isEmpty = function(self) return #self.items == 0 end } end\n" +
                "luajava = { newInstance = function(name) return listof() end }");
        Varargs result = Scripts.run(globals,
                "local function device(sink, type) return { isSink = function() return sink end,\n" +
                        "  getType = function() return type end } end\n" +
                        "local earpiece, speaker, headset = device(true, 1), device(true, 2), device(true, 7)\n" +
                        "local function hookof(method) return { getMethodName = function() return method end } end\n" +
                        "local function call(method, result, usage)\n" +
                        "  local param = paramof(result)\n" +
                        "  param.getThis = function() return { getDevices = function(self, flags)\n" +
                        "    return arrayof(earpiece, speaker, headset) end } end\n" +
                        "  param.unhooked = function(self, fn) return fn() end\n" +
                        "  param.getArgument = function() return { getUsage = function() return usage end } end\n" +
                        "  return after(hookof(method), param), param.result end\n" +
                        "local _, available = call('getAvailableCommunicationDevices', listof(earpiece, speaker, headset))\n" +
                        "local _, current = call('getCommunicationDevice', headset)\n" +
                        "local same = call('getCommunicationDevice', speaker)\n" +
                        "local _, media = call('getAudioDevicesForAttributes', listof(headset), 1)\n" +
                        "local _, voice = call('getAudioDevicesForAttributes', listof(headset), 2)\n" +
                        "return available:size(), available:get(1) == speaker, current == earpiece, same,\n" +
                        "  media:size(), media:get(0) == speaker, voice:get(0) == earpiece");
        assertEquals(2, result.arg(1).toint());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertEquals(1, result.arg(5).toint());
        assertTrue(result.arg(6).toboolean());
        assertTrue(result.arg(7).toboolean());
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

    // The methods of LauncherApps for a user, like getActivityList, are asked about a user which does not exist for
    // another user, so the apps of a hidden profile are not shown; the own user stays
    @Test
    public void launcherAppsOfOtherUsers() throws Exception {
        Globals globals = Scripts.load("user_profiles", PARAM + "\n" +
                "own = { equals = function(self, other) return self == other end }\n" +
                "luajava = { bindClass = function(name) return {\n" +
                "  myUserHandle = function(self) return own end,\n" +
                "  getUserHandleForUid = function(self, uid) return uid end } end }");
        Varargs result = Scripts.run(globals,
                "local function launcher(method, count, at) local hook = hookof(method, 'android.content.pm.LauncherApps')\n" +
                        "  hook.getParameterTypes = function(self) local types = { length = count }\n" +
                        "    for i = 1, count do types[i] = (i == at) and 'android.os.UserHandle' or 'java.lang.String' end\n" +
                        "    return types end return hook end\n" +
                        "local other = { equals = function() return false end }\n" +
                        "local list = paramof(nil, { 'com.android.settings', other })\n" +
                        "local mine = paramof(nil, { 'com.android.settings', own })\n" +
                        "return before(launcher('getActivityList', 2, 2), list), list.arguments[2],\n" +
                        "  before(launcher('isPackageEnabled', 2, 2), mine), after(launcher('getActivityList', 2, 2), paramof({}))");
        assertTrue(result.arg(1).toboolean());
        assertEquals(999900000, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
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
