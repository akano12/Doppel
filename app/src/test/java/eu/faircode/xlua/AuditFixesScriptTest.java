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

    // The app which made a PendingIntent or IntentSender, like the alarm app of the next alarm clock, is unknown
    // when it is hidden, like Android 13 and later answer: no package, uid -1 and the user the getter makes of it
    @Test
    public void creatorOfHiddenPendingIntentIsUnknown() throws Exception {
        Globals globals = Scripts.load("pendingintent_creator", PARAM + "\n" +
                "version = 34\n" +
                "function sdkversion() return version end\n" +
                "luajava = { bindClass = function(name)\n" +
                "  assert(name == 'android.os.UserHandle', name)\n" +
                "  return { getUserHandleForUid = function(self, uid) return 'UserHandle of ' .. uid end }\n" +
                "end }\n" +
                "function senderof(result, creator, visible)\n" +
                "  local param = paramof(result, nil, visible)\n" +
                "  local sender = { getCreatorPackage = function(self) assert(param.inside); return creator end }\n" +
                "  param.getThis = function(self) return sender end\n" +
                "  param.unhooked = function(self, fn) param.inside = true; local value = fn(); param.inside = false; return value end\n" +
                "  return param\n" +
                "end\n" +
                "function creator(method, class, result, creator, visible)\n" +
                "  local param = senderof(result, creator, visible)\n" +
                "  local restricted, old, new = after(hookof(method, class), param)\n" +
                "  return restricted, param.result, old, new\n" +
                "end");
        String pi = "'android.app.PendingIntent'", is = "'android.content.IntentSender'";
        Varargs result = Scripts.run(globals, "return creator('getCreatorPackage', " + pi + ", 'com.alarm.hidden')");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals("com.alarm.hidden", result.arg(3).tojstring());
        assertTrue(Scripts.run(globals, "return creator('getTargetPackage', " + is + ", 'com.alarm.hidden')").arg(2).isnil());
        result = Scripts.run(globals, "return creator('getCreatorUid', " + pi + ", 10123, 'com.alarm.hidden')");
        assertTrue(result.arg1().toboolean());
        assertEquals(-1, result.arg(2).toint());
        assertEquals("com.alarm.hidden", result.arg(3).tojstring());

        // The user: the one Android makes of uid -1 for PendingIntent from Android 12, none before and for IntentSender
        assertEquals("UserHandle of -1", Scripts.run(globals, "return creator('getCreatorUserHandle', " + pi +
                ", 'UserHandle{10}', 'com.alarm.hidden')").arg(2).tojstring());
        assertTrue(Scripts.run(globals, "return creator('getCreatorUserHandle', " + is +
                ", 'UserHandle{10}', 'com.alarm.hidden')").arg(2).isnil());
        Scripts.run(globals, "version = 30");
        assertTrue(Scripts.run(globals, "return creator('getCreatorUserHandle', " + pi +
                ", 'UserHandle{10}', 'com.alarm.hidden')").arg(2).isnil());

        // Visible apps and the ones Android already hides (none, -1) stay
        assertFalse(Scripts.run(globals, "return creator('getCreatorPackage', " + pi + ", 'com.android.deskclock', nil, " +
                "'com.android.deskclock')").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return creator('getCreatorUid', " + pi + ", 10050, 'com.android.deskclock', " +
                "'com.android.deskclock')").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return creator('getCreatorPackage', " + is + ", nil)").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return creator('getCreatorUid', " + pi + ", -1, nil)").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return creator('getCreatorUserHandle', " + is + ", 'UserHandle{0}', nil)")
                .arg1().toboolean());
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
                "function fakeof(param) return param:getSetting('value.email') end\n" +
                "function cursorof(type, name) return {\n" +
                "  getColumnIndex = function(self, column)\n" +
                "    return ({ account_name = 0, account_type = 1 })[column] or -1 end,\n" +
                "  getString = function(self, index) return index == 0 and name or type end,\n" +
                "  getBlob = function(self, index) return 'blob ' .. name end,\n" +
                "  copyStringToBuffer = function(self, index, buffer)\n" +
                "    if buffer == nil then error('CharArrayBuffer should not be null') end\n" +
                "    buffer.copied = name end } end\n" +
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
        // The proxy coerces the text to the byte[] of getBlob, which ends with the 0 of a text in a CursorWindow
        assertEquals("fake@example.com\0", new String((byte[]) CoerceLuaToJava.coerce(result.arg(1), byte[].class)));
        assertEquals(16, result.arg(2).toint());
        // A buffer without an array gets one of 64 chars, like CursorWindow allocates
        char[] chars = (char[]) result.arg(3).touserdata();
        assertEquals(64, chars.length);
        assertEquals("fake@example.com", new String(chars, 0, 16));
        assertEquals("blob mine@example.com", result.arg(4).tojstring());
        assertEquals("mine@example.com", result.arg(5).tojstring());

        // An array which is long enough is filled in place, a short one is replaced; no buffer is the error of the cursor
        result = Scripts.run(globals,
                "local _, fake = queried('com.android.contacts', cursorof('com.google', 'real@example.com'))\n" +
                        "local Array = luajava.bindClass('java.lang.reflect.Array')\n" +
                        "local Character = luajava.bindClass('java.lang.Character')\n" +
                        "local long, short = Array:newInstance(Character.TYPE, 100), Array:newInstance(Character.TYPE, 4)\n" +
                        "local buffer, other = { data = long }, { data = short }\n" +
                        "fake.copyStringToBuffer(0, buffer)\n" +
                        "fake.copyStringToBuffer(0, other)\n" +
                        "local ok, message = pcall(fake.copyStringToBuffer, 0, nil)\n" +
                        "return buffer.data == long, buffer.data, other.data == short, other.data['length'], ok, message");
        assertTrue(result.arg(1).toboolean());
        assertEquals("fake@example.com", new String((char[]) result.arg(2).touserdata(), 0, 16));
        assertFalse(result.arg(3).toboolean());
        assertEquals(64, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).tojstring().contains("CharArrayBuffer should not be null"));
    }

    // Columns which do not tell the type of their account: the names of the accounts of the app, of LOCAL calendars, of
    // the local account and of the SIM accounts of contacts stay; directories of contacts have the name as accountName
    // with accountType, and calendars have the address of the account as the organizer and the attendee of events too
    @Test
    public void accountColumnsWithoutTheirType() throws Exception {
        Globals globals = Scripts.load("account_columns", PARAM + "\n" +
                "luajava = { createProxy = function(interface, methods) return methods end }\n" +
                "function owntypes() return { ['own.type'] = true } end\n" +
                "function fakeof(param) return param:getSetting('value.email') end\n" +
                "function ownnames(param, own) return { ['mine@example.com'] = false } end\n" +
                "function localaccount() return 'Device', 'local.type' end\n" +
                "function simaccounts() return { ['sim.type'] = true }, { SIM1 = true } end\n" +
                "function calendarnames(param, uri, keep)\n" +
                "  return { ['real@example.com'] = not keep('com.google') and 'com.google', probe = not keep('LOCAL'),\n" +
                "    ['mine@example.com'] = not keep('own.type') and 'own.type' } end\n" +
                "function cursorof(columns, ...) local values = { ... } return {\n" +
                "  getColumnIndex = function(self, column) return columns[column] or -1 end,\n" +
                "  getString = function(self, index) return values[index + 1] end } end\n" +
                "function uriof(authority) return { getAuthority = function(self) return authority end } end\n" +
                "function queried(authority, columns, ...)\n" +
                "  local param = paramof(cursorof(columns, ...), { uriof(authority) })\n" +
                "  param.getSetting = function(self, name) return 'fake@example.com' end\n" +
                "  return after(nil, param) and param.result\n" +
                "end");
        Varargs result = Scripts.run(globals,
                "local contacts, calendar = 'com.android.contacts', 'com.android.calendar'\n" +
                        "local raw = { account_name = 0 }\n" +
                        "local directory = { accountName = 0, accountType = 1 }\n" +
                        "local calendars = { account_name = 0, ownerAccount = 1 }\n" +
                        "return queried(contacts, raw, 'mine@example.com').getString(0),\n" +
                        "  queried(contacts, raw, 'real@example.com').getString(0),\n" +
                        "  queried(contacts, raw, 'Device').getString(0),\n" +
                        "  queried(contacts, directory, 'real@example.com', 'com.google').getString(0),\n" +
                        "  queried(contacts, directory, 'Device', 'local.type').getString(0),\n" +
                        "  queried(calendar, { account_name = 0, account_type = 1 }, 'probe', 'LOCAL').getString(0),\n" +
                        "  queried(calendar, calendars, 'probe', 'probe').getString(1),\n" +
                        "  queried(calendar, calendars, 'real@example.com', 'real@example.com').getString(1),\n" +
                        "  queried(contacts, { organizer = 0 }, 'real@example.com'),\n" +
                        "  queried(contacts, raw, 'SIM1').getString(0),\n" +
                        "  queried(contacts, { account_name = 0, account_type = 1 }, 'SIM1', 'sim.type').getString(0),\n" +
                        "  queried(calendar, { account_name = 0, account_type = 1 }, 'SIM1', 'sim.type').getString(0)");
        assertEquals("mine@example.com", result.arg(1).tojstring());
        assertEquals("fake@example.com", result.arg(2).tojstring());
        assertEquals("Device", result.arg(3).tojstring());
        assertEquals("fake@example.com", result.arg(4).tojstring());
        assertEquals("Device", result.arg(5).tojstring());
        assertEquals("probe", result.arg(6).tojstring());
        assertEquals("probe", result.arg(7).tojstring());
        assertEquals("fake@example.com", result.arg(8).tojstring());
        assertFalse(result.arg(9).toboolean());
        assertEquals("SIM1", result.arg(10).tojstring());
        assertEquals("SIM1", result.arg(11).tojstring());
        // SIM accounts are of contacts only
        assertEquals("fake@example.com", result.arg(12).tojstring());

        // The owner, the organizer and the attendee are the fake address when they are the address of an account, but a
        // row of a calendar of the app keeps them, also when an account has the same address; a delegated calendar has
        // another person as its owner, who stays, like the organizer and the attendee who are that person
        result = Scripts.run(globals,
                "local calendar = 'com.android.calendar'\n" +
                        "local events = { ownerAccount = 0, organizer = 1, account_type = 2 }\n" +
                        "local mine = queried(calendar, events, 'real@example.com', 'real@example.com', 'com.google')\n" +
                        "local other = queried(calendar, events, 'real@example.com', 'friend@example.com', 'com.google')\n" +
                        "local own = queried(calendar, events, 'real@example.com', 'real@example.com', 'own.type')\n" +
                        "local attendees = { attendeeEmail = 0 }\n" +
                        "return own.getString(0), own.getString(1),\n" +
                        "  queried(calendar, { account_name = 0, account_type = 1 }, 'real@example.com', 'own.type').getString(0),\n" +
                        "  mine.getString(0), mine.getString(1), other.getString(0), other.getString(1),\n" +
                        "  queried(calendar, attendees, 'real@example.com').getString(0),\n" +
                        "  queried(calendar, attendees, 'friend@example.com').getString(0),\n" +
                        "  queried(calendar, attendees, 'mine@example.com').getString(0),\n" +
                        "  queried(calendar, { calendar_displayName = 0 }, 'real@example.com').getString(0),\n" +
                        "  queried(calendar, events, 'friend@example.com', 'friend@example.com', 'com.google').getString(0),\n" +
                        "  queried(calendar, events, 'friend@example.com', 'friend@example.com', 'com.google').getString(1),\n" +
                        "  queried(calendar, { account_name = 0, account_type = 1, ownerAccount = 2 },\n" +
                        "    'real@example.com', 'com.google', 'friend@example.com').getString(0),\n" +
                        "  queried(calendar, { account_name = 0, account_type = 1, ownerAccount = 2 },\n" +
                        "    'real@example.com', 'com.google', 'friend@example.com').getString(2)");
        assertEquals("real@example.com", result.arg(1).tojstring());
        assertEquals("real@example.com", result.arg(2).tojstring());
        assertEquals("real@example.com", result.arg(3).tojstring());
        assertEquals("fake@example.com", result.arg(4).tojstring());
        assertEquals("fake@example.com", result.arg(5).tojstring());
        assertEquals("fake@example.com", result.arg(6).tojstring());
        assertEquals("friend@example.com", result.arg(7).tojstring());
        assertEquals("fake@example.com", result.arg(8).tojstring());
        assertEquals("friend@example.com", result.arg(9).tojstring());
        assertEquals("mine@example.com", result.arg(10).tojstring());
        assertEquals("fake@example.com", result.arg(11).tojstring());
        assertEquals("friend@example.com", result.arg(12).tojstring());
        assertEquals("friend@example.com", result.arg(13).tojstring());
        assertEquals("fake@example.com", result.arg(14).tojstring());
        assertEquals("friend@example.com", result.arg(15).tojstring());
    }

    // The names of the calendars come from their provider, those of the accounts of the app from AccountManager and the
    // SIM accounts of contacts from their provider, all without the hooks; a name of an account and of a kept calendar
    // both is faked, with the type of the account; the owner of a delegated calendar, another person, is no account
    @Test
    public void accountColumnsLookups() throws Exception {
        Globals globals = Scripts.load("account_columns", PARAM + "\n" +
                "sdk = 31\n" +
                "luajava = { bindClass = function(name) return {\n" +
                "  SDK_INT = sdk,\n" +
                "  getSimAccounts = function(self, resolver) return sims(resolver) end,\n" +
                "  parse = function(self, text) return text end,\n" +
                "  get = function(self, context) return { getAccountsByType = function(self, type)\n" +
                "    return { length = 1, { name = type .. '@example.com' } } end } end } end }\n" +
                "local rows = { { 'shared', 'LOCAL', 'real@example.com' }, { 'real@example.com', 'LOCAL', 'real@example.com' },\n" +
                "  { 'real@example.com', 'com.google', 'real@example.com' },\n" +
                "  { 'real@example.com', 'com.google', 'friend@example.com' },\n" +
                "  { 'probe', 'LOCAL', 'probe' }, { 'mine', 'own.type', 'mine' } }\n" +
                "function lookup(param)\n" +
                "  param.getApplicationContext = function(self)\n" +
                "    return { getContentResolver = function(self) return 'resolver' end } end\n" +
                "  param.unhooked = function(self, fn) self.inside = true; local value = fn(); self.inside = false; return value end\n" +
                "  return param\n" +
                "end\n" +
                "function resolverof(param) return { query = function(self, uri, projection, extras, signal)\n" +
                "  param.queried, param.queriedInside = uri, param.inside\n" +
                "  local position = 0\n" +
                "  return { getColumnIndex = function(self, column)\n" +
                "      return ({ account_name = 0, account_type = 1, ownerAccount = 2 })[column] end,\n" +
                "    moveToNext = function(self) position = position + 1; return rows[position] ~= nil end,\n" +
                "    getString = function(self, index) return rows[position][index + 1] end,\n" +
                "    close = function(self) param.closed = true end } end } end");
        Varargs result = Scripts.run(globals,
                "local param = lookup(paramof())\n" +
                        "param.getThis = function(self) return resolverof(param) end\n" +
                        "local uri = { getAuthority = function(self) return '0@com.android.calendar' end }\n" +
                        "local names = calendarnames(param, uri, function(type) return type == 'LOCAL' or type == 'own.type' end)\n" +
                        "local own = ownnames(lookup(paramof()), { ['own.type'] = true })\n" +
                        "local none = ownnames(lookup(paramof()), {})\n" +
                        "local failing = lookup(paramof())\n" +
                        "failing.getThis = function(self) return { query = function() error('DeadObjectException') end } end\n" +
                        "local unknown = calendarnames(failing, uri, function() return false end)\n" +
                        "return param.queried, param.queriedInside, param.closed, names['real@example.com'], names.shared,\n" +
                        "  names.probe, names.mine, own['own.type@example.com'], next(none), next(unknown),\n" +
                        "  names['friend@example.com']");
        assertEquals("content://0@com.android.calendar/calendars", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertEquals("com.google", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(5).isboolean());
        assertFalse(result.arg(6).toboolean());
        assertTrue(result.arg(6).isboolean());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).isboolean());
        assertFalse(result.arg(8).toboolean());
        assertTrue(result.arg(9).isnil());
        // A provider which fails gives no names, without an error
        assertTrue(result.arg(10).isnil());
        // The owner of a delegated calendar is not marked, so it stays like any other address
        assertTrue(result.arg(11).isnil());

        // A provider which fails or Android before 12, which does not list them, gives no SIM accounts, without an error
        result = Scripts.run(globals,
                "local param = lookup(paramof())\n" +
                        "function sims(resolver)\n" +
                        "  param.simResolver, param.simInside = resolver, param.inside\n" +
                        "  local accounts = { { 'SIM1', 'sim.type' }, { 'SIM2', 'sim.type' } }\n" +
                        "  return { size = function(self) return #accounts end, get = function(self, index)\n" +
                        "    local account = accounts[index + 1]\n" +
                        "    return { getAccountName = function(self) return account[1] end,\n" +
                        "      getAccountType = function(self) return account[2] end } end } end\n" +
                        "local types, names = simaccounts(param)\n" +
                        "sims = function() error('SecurityException') end\n" +
                        "local failedTypes, failedNames = simaccounts(lookup(paramof()))\n" +
                        "sdk = 30\n" +
                        "local oldTypes, oldNames = simaccounts(paramof())\n" +
                        "return param.simResolver, param.simInside, types['sim.type'], names.SIM1, names.SIM2,\n" +
                        "  next(failedTypes), next(failedNames), next(oldTypes), next(oldNames)");
        assertEquals("resolver", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        for (int index = 6; index <= 9; index++)
            assertTrue(result.arg(index).isnil());
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
