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

import java.util.HashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// The scripts of Get.Applications for single packages and user ids (package_probe, package_resolve)
// and for lists (generic_filter_by_uid, packagemanager_getchangedpackages), with Lua stand-ins like
// LocationScriptTest; lists and arrays are stand-ins too, which the tests read back as Lua tables,
// and the exceptions are of the test, because those of Android are stubs in unit tests
public class PackageScriptsTest {
    private static final String STUBS = String.join("\n",
            "function hookof(name, method, class)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class end }",
            "end",
            // A call with arguments, the packages and user ids which stay visible and the values of the process
            "function paramof(args, visible, values)",
            "  local param = { args = args, result = nil, exception = nil }",
            "  function param:getArgument(index) return self.args[index] end",
            "  function param:setArgument(index, value) self.args[index] = value end",
            "  function param:getResult() return self.result end",
            "  function param:setResult(value) self.result = value end",
            "  function param:getException() return self.exception end",
            "  function param:getUid() return 10001 end",
            "  function param:isSystem() return self.system == true end",
            "  function param:isPackageVisible(name) return visible[name] == true end",
            "  function param:isUidVisible(uid) return visible[uid] == true end",
            "  function param:isVisible(ai) return ai.uid == 10001 or ai.system == true end",
            "  function param:isInstance(object, name)",
            "    return name == '[Ljava.lang.String;' and type(object) == 'table' and object.length ~= nil end",
            "  function param:getApplicationContext() return values or {} end",
            "  function param:getValue(name, scope) return scope[name] end",
            "  function param:putValue(name, value, scope) scope[name] = value end",
            "  function param:getSetting(name) return self.settings and self.settings[name] end",
            "  return param",
            "end",
            // A java.util.List and a Java array as Lua sees them
            "function listof(items)",
            "  return { items = items,",
            "    add = function(self, value) self.items[#self.items + 1] = value end,",
            "    get = function(self, index) return self.items[index + 1] end,",
            "    size = function(self) return #self.items end,",
            "    isEmpty = function(self) return #self.items == 0 end,",
            "    remove = function(self, index) table.remove(self.items, index + 1) end,",
            "    toArray = function(self) return arrayof(table.unpack(self.items)) end }",
            "end",
            Scripts.ARRAYOF,
            // PackageManager.ResolveInfoFlags
            "function flagsof(value)",
            "  return { getValue = function(self) return value end }",
            "end",
            "callingUid = 10001", // Binder.getCallingUid
            "local real = luajava",
            "luajava = { new = real.new,",
            "  newInstance = function(name, ...)",
            "    if name == 'java.util.ArrayList' then return listof({}) end",
            "    if name == 'android.content.pm.PackageManager$NameNotFoundException' then",
            "      return { exception = name, message = ... }",
            "    end",
            "    assert(name == 'android.content.pm.ChangedPackages', name)",
            "    local sequence, names = ...",
            "    return { sequence = sequence, names = names }",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'java.lang.reflect.Array' then",
            "      return { newInstance = function(self, type, length) return { length = length } end }",
            "    end",
            "    if name == 'android.os.Binder' then",
            "      return { getCallingUid = function(self) return callingUid end }",
            "    end",
            "    if name == 'android.content.pm.PackageManager$ResolveInfoFlags' then",
            "      return { of = function(self, value) return flagsof(value) end }",
            "    end",
            "    assert(name == 'java.lang.String', name)",
            "    return {}",
            "  end }");

    // An exception of Android, like PackageManager.NameNotFoundException
    public static class ProbeException extends Exception {
        public ProbeException(String message) {
            super(message);
        }
    }

    private static Globals load(String name) throws Exception {
        return Scripts.load(name, STUBS);
    }

    // A hidden package is looked up under a name which is not installed, so Android itself answers
    @Test
    public void hiddenPackageIsLookedUpAsMissing() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
                "local param = paramof({ [0] = 'com.example.bank', [1] = 0 }, {})\n" +
                        "local restricted, old = before(hookof('PackageManager.getPackageInfo', 'getPackageInfo'), param)\n" +
                        "return restricted, old, param.args[0], param.args[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.example.bank", result.arg(2).tojstring());
        String substitute = result.arg(3).tojstring();
        assertTrue(substitute, substitute.matches("com\\.example\\.bank\\.x[0-9]{9}"));
        assertEquals(0, result.arg(4).toint());
    }

    @Test
    public void visiblePackageStays() throws Exception {
        Globals globals = load("package_probe");
        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException("com.android.vending")));
        Varargs result = Scripts.run(globals,
                "local hook = hookof('PackageManager.getPackageInfo', 'getPackageInfo')\n" +
                        "local param = paramof({ [0] = 'com.android.vending', [1] = 0 }, { ['com.android.vending'] = true })\n" +
                        "local restricted = before(hook, param)\n" +
                        "param.exception = genuine\n" +
                        "after(hook, param)\n" +
                        "return restricted, param.args[0], param.result");
        assertFalse(result.arg(1).toboolean());
        assertEquals("com.android.vending", result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
    }

    // The exception of Android for the name which is not installed, with the name the app asked for,
    // like for createPackageContext, whose message is not just the name; the same class, stack trace and cause
    @Test
    public void exceptionHasNameOfApp() throws Exception {
        Globals globals = load("package_probe");
        String substitute = Scripts.run(globals,
                "hook = hookof('Context.createPackageContext', 'createPackageContext')\n" +
                        "param = paramof({ [0] = 'com.example.bank', [1] = 0 }, {})\n" +
                        "before(hook, param)\n" +
                        "return param.args[0]").arg1().tojstring();

        ProbeException genuine = new ProbeException("Application package " + substitute + " not found");
        genuine.initCause(new RuntimeException("Remote stack trace"));
        globals.set("genuine", CoerceJavaToLua.coerce(genuine));
        Varargs result = Scripts.run(globals,
                "param.exception = genuine\n" +
                        "return after(hook, param), param.result");

        assertFalse(result.arg(1).toboolean()); // Reported by before
        Throwable fake = (Throwable) result.arg(2).touserdata();
        assertEquals(ProbeException.class, fake.getClass());
        assertEquals("Application package com.example.bank not found", fake.getMessage());
        assertArrayEquals(genuine.getStackTrace(), fake.getStackTrace());
        assertSame(genuine.getCause(), fake.getCause());
    }

    // An exception without a constructor with a message, like ServiceSpecificException, which takes a code too and which
    // LuaJ would make with that constructor and no message: the app gets the exception itself, with the name it asked for,
    // which Java sets (XParam.setMessage), without an error of the script
    public static class CodedException extends Exception {
        public CodedException(int code, String message) {
            super(message);
        }
    }

    @Test
    public void exceptionWithoutMessageConstructor() throws Exception {
        Globals globals = load("package_probe");
        String substitute = Scripts.run(globals,
                "hook = hookof('PackageManager.getPackageInfo', 'getPackageInfo')\n" +
                        "param = paramof({ [0] = 'com.example.bank', [1] = 0 }, {})\n" +
                        "before(hook, param)\n" +
                        "return param.args[0]").arg1().tojstring();
        assertTrue(substitute.startsWith("com.example.bank.x"));
        CodedException genuine = new CodedException(1, "Package " + substitute + " not found");
        globals.set("genuine", CoerceJavaToLua.coerce(genuine));
        globals.set("xparam", CoerceJavaToLua.coerce(new XParam(null, PackageScriptsTest.class.getDeclaredField("STUBS"),
                new HashMap<>())));
        Varargs result = Scripts.run(globals, "param.exception = genuine\n" +
                "function param:setMessage(ex, message) xparam:setMessage(ex, message) end\n" +
                "return after(hook, param), param.result");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals("Package com.example.bank not found", genuine.getMessage());
    }

    // AppOpsManager answers differently for a package of the uid: the package is the third argument of the
    // checks of operations, the second of checkPackage
    @Test
    public void appOpsPackages() throws Exception {
        Globals globals = load("package_probe");
        Varargs result = Scripts.run(globals,
                "local op = paramof({ [0] = 'android:coarse_location', [1] = 10123, [2] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.checkOpNoThrow', 'checkOpNoThrow'), op)\n" +
                        "local unsafe = paramof({ [0] = 'android:coarse_location', [1] = 10123, [2] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.unsafeCheckOpNoThrow', 'unsafeCheckOpNoThrow'), unsafe)\n" +
                        "local check = paramof({ [0] = 10123, [1] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.checkPackage', 'checkPackage'), check)\n" +
                        "return op.args[0], op.args[2], unsafe.args[2], check.args[0], check.args[1]");
        assertEquals("android:coarse_location", result.arg(1).tojstring());
        for (int i : new int[]{2, 3, 5})
            assertTrue(String.valueOf(i), result.arg(i).tojstring().startsWith("com.example.bank.x"));
        assertEquals(10123, result.arg(4).toint());
    }

    // The enabled settings of a package and an attribution check of a uid are looked up under a name which is not
    // installed, like the other checks of packages (package_probe)
    @Test
    public void enabledSettingsOfHiddenPackages() throws Exception {
        Globals globals = load("package_probe");
        Varargs result = Scripts.run(globals,
                "local op = paramof({ [0] = 'android:coarse_location', [1] = 10123, [2] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.checkOpRawNoThrow/attribution', 'checkOpRawNoThrow'), op)\n" +
                        "local enabled = paramof({ [0] = 'com.example.bank', [1] = 2, [2] = 0 }, {})\n" +
                        "before(hookof('PackageManager.setApplicationEnabledSetting', 'setApplicationEnabledSetting'), enabled)\n" +
                        "return op.args[2], enabled.args[0], enabled.args[1]");
        assertTrue(result.arg(1).tojstring().startsWith("com.example.bank.x"));
        assertTrue(result.arg(2).tojstring().startsWith("com.example.bank.x"));
        assertEquals(2, result.arg(3).toint());
    }

    // A provider of the app checks the app calling it (ContentProvider.getCallingPackage), which is not a probe,
    // with an entry for after like any other call, inside a call which was a probe
    @Test
    public void checkPackageOfCaller() throws Exception {
        Globals globals = load("package_probe");
        String substitute = Scripts.run(globals,
                "callingUid = 10123\n" +
                        "hook = hookof('AppOpsManager.checkPackage', 'checkPackage')\n" +
                        "probe = paramof({ [0] = 10124, [1] = 'com.example.bank' }, {})\n" +
                        "before(hook, probe)\n" +
                        "return probe.args[1]").arg1().tojstring();
        assertTrue(substitute, substitute.startsWith("com.example.bank.x"));

        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException(substitute)));
        Varargs result = Scripts.run(globals,
                "local caller = paramof({ [0] = 10123, [1] = 'com.example.bank' }, {})\n" +
                        "local restricted = before(hook, caller)\n" +
                        "after(hook, caller)\n" +
                        "probe.exception = genuine\n" +
                        "after(hook, probe)\n" +
                        "return restricted, caller.args[1], probe.result");
        assertFalse(result.arg(1).toboolean());
        assertEquals("com.example.bank", result.arg(2).tojstring());
        assertEquals("com.example.bank", ((Throwable) result.arg(3).touserdata()).getMessage());
    }

    // The package is the second argument of checkPermission, checkSignatures checks both
    @Test
    public void packageArguments() throws Exception {
        Globals globals = load("package_probe");
        Varargs permission = Scripts.run(globals,
                "local param = paramof({ [0] = 'android.permission.INTERNET', [1] = 'com.example.bank' }, {})\n" +
                        "before(hookof('PackageManager.checkPermission', 'checkPermission'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("android.permission.INTERNET", permission.arg(1).tojstring());
        assertTrue(permission.arg(2).tojstring().startsWith("com.example.bank.x"));

        Varargs signatures = Scripts.run(globals,
                "local param = paramof({ [0] = 'com.example.app', [1] = 'com.example.bank' }, { ['com.example.app'] = true })\n" +
                        "before(hookof('PackageManager.checkSignatures', 'checkSignatures'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("com.example.app", signatures.arg(1).tojstring());
        assertTrue(signatures.arg(2).tojstring().startsWith("com.example.bank.x"));
        // The storage of another app, like its sizes, by its package, the second argument (volume, package, user)
        Varargs storage = Scripts.run(globals,
                "local param = paramof({ [0] = 'uuid', [1] = 'com.example.bank', [2] = 'user' }, {})\n" +
                        "before(hookof('StorageStatsManager.queryStatsForPackage', 'queryStatsForPackage'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("uuid", storage.arg(1).tojstring());
        assertTrue(storage.arg(2).tojstring().startsWith("com.example.bank.x"));
    }

    // An intent with a selector is sent by the selector, so the package of the intent itself does not count for it
    @Test
    public void selectorOfIntentIsSubstituted() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
                "local base = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, ...)\n" +
                        "  if name == 'android.content.Intent' then\n" +
                        "    return { from = ..., setSelector = function(self, selector) self.selector = selector end,\n" +
                        "      setPackage = function(self, name) self.package = name return self end }\n" +
                        "  end\n" +
                        "  return base(name, ...)\n" +
                        "end\n" +
                        "function intentof(packageName, selector)\n" +
                        "  return { getComponent = function(self) return nil end,\n" +
                        "    getPackage = function(self) return packageName end,\n" +
                        "    getSelector = function(self) return selector end }\n" +
                        "end\n" +
                        "local hook = hookof('LauncherApps.resolveActivity', 'resolveActivity')\n" +
                        "local selector = intentof('com.example.bank', nil)\n" +
                        "local outer = intentof('com.example.app', selector)\n" +
                        "local hidden = paramof({ [0] = outer }, { ['com.example.app'] = true })\n" +
                        "hidden.isInstance = function(self, object, name) return name == 'android.content.Intent' end\n" +
                        "local restricted = before(hook, hidden)\n" +
                        "local copy = hidden.args[0]\n" +
                        "local visibleSelector = intentof('com.example.app', nil)\n" +
                        "local other = intentof('com.example.bank', visibleSelector)\n" +
                        "local visible = paramof({ [0] = other }, { ['com.example.app'] = true })\n" +
                        "visible.isInstance = hidden.isInstance\n" +
                        "local stays = before(hook, visible)\n" +
                        "return restricted, copy ~= outer, copy.from == outer, copy.selector.package,\n" +
                        "  stays, visible.args[0] == other");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).tojstring().startsWith("com.example.bank.x"));
        // A visible selector gives nothing, whatever the package of the intent is
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
    }

    // Android moves the stream of a share to the clip data of the intent of an activity, with the permission to read it,
    // here on the copy with the substitute; the intent of the app gets both, like without Doppel, also when the call failed
    // after the move, and keeps its own clip data; other kinds of PendingIntent, which Android does not move, change nothing
    @Test
    public void streamOfShareMovesToIntentOfApp() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
                "local base = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, ...)\n" +
                        "  if name == 'android.content.Intent' then\n" +
                        "    local from = ...\n" +
                        "    return intentof(from.package, from.clip, from.flags)\n" +
                        "  end\n" +
                        "  return base(name, ...)\n" +
                        "end\n" +
                        "function intentof(packageName, clip, flags)\n" +
                        "  return { package = packageName, clip = clip, flags = flags,\n" +
                        "    getComponent = function(self) return nil end,\n" +
                        "    getSelector = function(self) return nil end,\n" +
                        "    getPackage = function(self) return self.package end,\n" +
                        "    setPackage = function(self, name) self.package = name return self end,\n" +
                        "    getClipData = function(self) return self.clip end,\n" +
                        "    setClipData = function(self, clip) self.clip = clip end,\n" +
                        "    getFlags = function(self) return self.flags end,\n" +
                        "    setFlags = function(self, flags) self.flags = flags return self end }\n" +
                        "end\n" +
                        // The intent of the app for WhatsApp, which is hidden, after the call with what Android did on the copy
                        "function share(method, clip, failure)\n" +
                        "  local intent = intentof('com.whatsapp', clip, 268435456)\n" + // FLAG_ACTIVITY_NEW_TASK
                        "  local hook = hookof('PendingIntent.' .. method, method)\n" +
                        "  local param = paramof({ [0] = 'context', [1] = 0, [2] = intent, [3] = 67108864 }, {})\n" +
                        "  param.isInstance = function(self, object, name)\n" +
                        "    return name == 'android.content.Intent' and type(object) == 'table' end\n" +
                        "  before(hook, param)\n" +
                        "  local copy = param.args[2]\n" +
                        "  if method == 'getActivity' and copy.clip == nil then\n" +
                        "    copy.clip = 'stream'\n" +
                        "    copy.flags = copy.flags + 1\n" + // FLAG_GRANT_READ_URI_PERMISSION
                        "  end\n" +
                        "  param.exception = failure\n" +
                        "  after(hook, param)\n" +
                        "  return copy ~= intent and copy.package ~= 'com.whatsapp', intent.package, intent.clip, intent.flags\n" +
                        "end\n" +
                        "local failure = { getMessage = function(self) return nil end }\n" +
                        "local substituted, packageName, clip, flags = share('getActivity', nil, nil)\n" +
                        "local _, _, failedClip, failedFlags = share('getActivity', nil, failure)\n" +
                        "local _, _, ownClip, ownFlags = share('getActivity', 'own', nil)\n" +
                        "local _, _, broadcastClip, broadcastFlags = share('getBroadcast', nil, nil)\n" +
                        "return substituted, packageName, clip, flags, failedClip, failedFlags, ownClip, ownFlags,\n" +
                        "  broadcastClip, broadcastFlags");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.whatsapp", result.arg(2).tojstring());
        assertEquals("stream", result.arg(3).tojstring());
        assertEquals(268435457, result.arg(4).toint());
        assertEquals("stream", result.arg(5).tojstring());
        assertEquals(268435457, result.arg(6).toint());
        assertEquals("own", result.arg(7).tojstring());
        assertEquals(268435456, result.arg(8).toint());
        assertTrue(result.arg(9).isnil());
        assertEquals(268435456, result.arg(10).toint());
    }

    // The callback of a sent PendingIntent or IntentSender gets the intent Android sent with the names the app asked for,
    // of its package, component and selector; other names stay
    @Test
    public void callbackIntentHasNamesOfApp() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
                "local base = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, ...)\n" +
                        "  if name == 'android.content.ComponentName' then return componentof(...) end\n" +
                        "  return base(name, ...)\n" +
                        "end\n" +
                        "function componentof(packageName, className)\n" +
                        "  return { getPackageName = function(self) return packageName end,\n" +
                        "    getClassName = function(self) return className end }\n" +
                        "end\n" +
                        "function intentof(packageName, component, selector)\n" +
                        "  return { package = packageName, component = component, selector = selector,\n" +
                        "    getPackage = function(self) return self.package end,\n" +
                        "    setPackage = function(self, name) self.package = name return self end,\n" +
                        "    getComponent = function(self) return self.component end,\n" +
                        "    setComponent = function(self, component) self.component = component return self end,\n" +
                        "    getSelector = function(self) return self.selector end }\n" +
                        "end\n" +
                        "function send(hook, intent)\n" +
                        "  local param = paramof({ [0] = intent, [1] = 0, [2] = nil, [3] = nil }, {})\n" +
                        "  param.settings = { ['package_probe.suffix'] = '.x123456789' }\n" +
                        "  local restricted = before(hook, param)\n" +
                        "  after(hook, param)\n" +
                        "  return restricted\n" +
                        "end\n" +
                        "local pending = hookof('PendingIntent.FinishedDispatcher.performReceive', 'performReceive')\n" +
                        "local sender = hookof('IntentSender.FinishedDispatcher.performReceive', 'performReceive')\n" +
                        "local activity = intentof(nil, componentof('com.example.bank.x123456789', 'com.example.bank.Main'), nil)\n" +
                        "local broadcast = intentof('com.example.bank.x123456789', nil, nil)\n" +
                        "local selector = intentof('com.example.bank.x123456789', nil, nil)\n" +
                        "local chooser = intentof(nil, nil, selector)\n" +
                        "local other = intentof('com.example.app', componentof('com.example.app', 'com.example.app.Main'), nil)\n" +
                        "return send(pending, activity), activity.component:getPackageName(), activity.component:getClassName(),\n" +
                        "  send(pending, broadcast), broadcast.package, send(sender, chooser), selector.package,\n" +
                        "  send(sender, other), other.package, other.component:getPackageName()");
        for (int i : new int[]{1, 4, 6, 8})
            assertFalse(String.valueOf(i), result.arg(i).toboolean());
        assertEquals("com.example.bank", result.arg(2).tojstring());
        assertEquals("com.example.bank.Main", result.arg(3).tojstring());
        assertEquals("com.example.bank", result.arg(5).tojstring());
        assertEquals("com.example.bank", result.arg(7).tojstring());
        assertEquals("com.example.app", result.arg(9).tojstring());
        assertEquals("com.example.app", result.arg(10).tojstring());
    }

    // A permission of a user id which is not in use is denied, like the ones of hidden apps; visible ones stay
    @Test
    public void permissionOfHiddenUidIsDenied() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('Context.checkPermission', 'checkPermission')\n" +
                        "local granted = paramof({ [0] = 'android.permission.INTERNET', [1] = 1, [2] = 10060 }, {})\n" +
                        "granted.result = 0\n" +
                        "local visible = paramof({ [0] = 'android.permission.INTERNET', [1] = 1, [2] = 10050 }, { [10050] = true })\n" +
                        "visible.result = 0\n" +
                        "local denied = paramof({ [0] = 'android.permission.INTERNET', [1] = 1, [2] = 10060 }, {})\n" +
                        "denied.result = -1\n" +
                        "return after(hook, granted), granted.result, after(hook, visible), visible.result,\n" +
                        "  after(hook, denied), denied.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals(-1, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertEquals(-1, result.arg(6).toint());
    }

    // The hidden checks of a uid which ask Android or the package manager themselves: the overload with a token
    // (uid third) and ActivityManager.checkComponentPermission (uid second), which grants root, the system, no
    // permission and the uid owning the component without asking, also for a user id which is not in use
    @Test
    public void componentPermissionOfHiddenUidIsDenied() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local function check(method, args)\n" +
                        "  local param = paramof(args, { [10050] = true })\n" +
                        "  param.result = 0\n" +
                        "  local restricted = after(hookof('Context.checkPermission', method), param)\n" +
                        "  return restricted, param.result\n" +
                        "end\n" +
                        "local p = 'android.permission.INTERNET'\n" +
                        "local token = { check('checkPermission', { [0] = p, [1] = 1, [2] = 10060, [3] = 'token' }) }\n" +
                        "local hidden = { check('checkComponentPermission', { [0] = p, [1] = 10060, [2] = -1, [3] = true }) }\n" +
                        "local visible = { check('checkComponentPermission', { [0] = p, [1] = 10050, [2] = -1, [3] = true }) }\n" +
                        "local owner = { check('checkComponentPermission', { [0] = p, [1] = 1010060, [2] = 10060, [3] = true }) }\n" +
                        "local none = { check('checkComponentPermission', { [0] = nil, [1] = 10060, [2] = -1, [3] = true }) }\n" +
                        "local system = { check('checkComponentPermission', { [0] = p, [1] = 1001000, [2] = -1, [3] = true }) }\n" +
                        "return token[1], token[2], hidden[1], hidden[2], visible[1], visible[2], owner[1], owner[2],\n" +
                        "  none[1], none[2], system[1], system[2]");
        assertTrue(result.arg(1).toboolean());
        assertEquals(-1, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
        assertEquals(-1, result.arg(4).toint());
        for (int i = 5; i <= 11; i += 2) {
            assertFalse(String.valueOf(i), result.arg(i).toboolean());
            assertEquals(String.valueOf(i), 0, result.arg(i + 1).toint());
        }
    }

    // An array of targets is copied, the array of the app stays as it is
    @Test
    public void arrayOfAppStays() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
                "local targets = arrayof('com.android.vending', 'com.example.bank')\n" +
                        "local param = paramof({ [0] = 'com.example.app', [1] = targets },\n" +
                        "  { ['com.example.app'] = true, ['com.android.vending'] = true })\n" +
                        "before(hookof('PackageManager.canPackageQuery/targets', 'canPackageQuery'), param)\n" +
                        "return targets[2], param.args[1] ~= targets, param.args[1][1], param.args[1][2]");
        assertEquals("com.example.bank", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
        assertEquals("com.android.vending", result.arg(3).tojstring());
        assertTrue(result.arg(4).tojstring().startsWith("com.example.bank.x"));
    }

    // Hooks of methods which the hooked method calls, like getPackageInfoAsUser, leave the substitute
    // and the exception to the hook which made it; the calls on a thread end in reverse order
    @Test
    public void nestedHooksLeaveSubstitute() throws Exception {
        Globals globals = load("package_probe");
        String substitute = Scripts.run(globals,
                "values = {}\n" +
                        "hook = hookof('PackageManager.getPackageInfo', 'getPackageInfo')\n" +
                        "outer = paramof({ [0] = 'com.example.bank', [1] = 0 }, {}, values)\n" +
                        "before(hook, outer)\n" +
                        "return outer.args[0]").arg1().tojstring();

        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException(substitute)));
        Varargs result = Scripts.run(globals,
                "local inner = paramof({ [0] = outer.args[0], [1] = 0 }, {}, values)\n" +
                        "local nested = before(hook, inner)\n" +
                        "inner.exception = genuine\n" +
                        "after(hook, inner)\n" +
                        "outer.exception = genuine\n" +
                        "after(hook, outer)\n" +
                        "return nested, inner.args[0] == outer.args[0], inner.result, outer.result");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
        assertEquals("com.example.bank", ((Throwable) result.arg(4).touserdata()).getMessage());
    }

    // The suffix is random, so apps cannot ask for a substitute: the one of the app for the boot (see FakeIds), the same
    // in every process of the app, else the same for the process
    @Test
    public void suffixIsRandom() throws Exception {
        Globals globals = load("package_probe");
        String suffix = "local param = paramof({}, {}, values)\nreturn suffixof(param)";
        String one = Scripts.run(globals, "local values = {}\n" + suffix).arg1().tojstring();
        String two = Scripts.run(globals, "local values = {}\n" + suffix).arg1().tojstring();
        String same = Scripts.run(globals, "local values = { ['package_probe.suffix'] = '" + one + "' }\n" + suffix)
                .arg1().tojstring();
        assertTrue(one, one.matches("\\.x[0-9]{9}"));
        assertNotEquals(one, two);
        assertEquals(one, same);
        String boot = Scripts.run(globals, "local param = paramof({}, {}, { ['package_probe.suffix'] = '" + one + "' })\n" +
                "param.settings = { ['package_probe.suffix'] = '.x123456789' }\n" +
                "return suffixof(param)").arg1().tojstring();
        assertEquals(".x123456789", boot);
    }

    // A shared user id of a visible app, like of Google Play services, stays visible
    @Test
    public void sharedUserOfVisibleApp() throws Exception {
        Globals globals = load("package_resolve");
        Varargs visible = Scripts.run(globals,
                "local param = paramof({ [0] = 10050 }, { [10050] = true })\n" +
                        "param.result = 'com.google.uid.shared:10050'\n" +
                        "local restricted = after(hookof('PackageManager.getNameForUid', 'getNameForUid'), param)\n" +
                        "return restricted, param.result");
        assertFalse(visible.arg(1).toboolean());
        assertEquals("com.google.uid.shared:10050", visible.arg(2).tojstring());

        Varargs hidden = Scripts.run(globals,
                "local param = paramof({ [0] = 10060 }, {})\n" +
                        "param.result = 'com.example.shared:10060'\n" +
                        "local restricted = after(hookof('PackageManager.getNameForUid', 'getNameForUid'), param)\n" +
                        "return restricted, param.result");
        assertTrue(hidden.arg(1).toboolean());
        assertTrue(hidden.arg(2).isnil());
    }

    // An intent any app may handle, like a link, resolves like on a device without the hidden apps, so it agrees with
    // queryIntentActivities: nothing when only a hidden app handles it, the only visible app, or the chooser of
    // Android when several visible apps can and the hidden one was the default; an intent for a hidden app gives nothing
    @Test
    public void linksResolveLikeWithoutHiddenApps() throws Exception {
        Globals globals = load("package_resolve");
        // The Android classes are stand-ins, like the rest of the stubs
        String setup = "local base, bind = luajava.newInstance, luajava.bindClass\n" +
                "luajava.newInstance = function(name, from)\n" +
                "  if name == 'android.content.Intent' then\n" +
                "    return { setClassName = function(self, p, c) self.target = p .. '/' .. c return self end } end\n" +
                "  if name == 'android.os.Bundle' then return { values = {},\n" +
                "    putBoolean = function(self, key, value) self.values[key] = value end } end\n" +
                "  if name == 'android.content.pm.ResolveInfo' then\n" +
                "    local copy = { copied = true } for key, value in pairs(from) do copy[key] = value end return copy end\n" +
                "  return base(name, from) end\n" +
                // The release of Android, the system apps and the default browser of the device (config_defaultBrowser)
                "local sdk, system, priorities, config = 34, {}, {}\n" +
                // A Parcel gives back a Bundle of its own with what was written to it
                "local parcel\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end\n" +
                "  if name == 'android.os.Parcel' then return { obtain = function() parcel = {\n" +
                "    writeBundle = function(self, bundle) self.bundle = bundle end,\n" +
                "    setDataPosition = function(self, position) self.position = position end,\n" +
                "    readBundle = function(self) assert(self.position == 0) return { read = true, values = self.bundle.values } end,\n" +
                "    recycle = function(self) self.recycled = true end } return parcel end } end\n" +
                "  return bind(name) end\n" +
                "local context = { getResources = function() return {\n" +
                "  getIdentifier = function(self, name, type, package)\n" +
                "    assert(name == 'config_defaultBrowser' and type == 'string' and package == 'android')\n" +
                "    return config and 17 or 0 end,\n" +
                "  getString = function(self, id) assert(id == 17) return config end } end }\n" +
                "local function infoof(name, web) return { priority = priorities[name] or 0, preferredOrder = 0,\n" +
                "  isDefault = true, handleAllWebDataURI = web, activityInfo = { packageName = name, name = name .. '.Main',\n" +
                "  exported = true, applicationInfo = { flags = (system[name] and 1 or 0) } } } end\n" +
                "local candidates, default\n" +
                "local queries = 0\n" +
                // Of a link Android gives only the default app, which the hidden one can be, unless asked for all
                "local pm = { queryIntentActivities = function(self, intent, flags)\n" +
                "    local value = (type(flags) == 'number' and flags or flags:getValue())\n" +
                "    assert(value == 65536 or value == 196608, value)\n" +
                "    queries = queries + 1\n" +
                "    if default ~= nil and value == 65536 then return listof({ infoof(default, true) }) end\n" +
                "    return listof(candidates) end,\n" +
                "  resolveActivity = function(self, intent, flags)\n" +
                "    assert(intent.target == 'android/com.android.internal.app.ResolverActivity')\n" +
                "    return { activityInfo = { packageName = 'android', name = 'com.android.internal.app.ResolverActivity'," +
                " processName = 'system:ui' } } end }\n" +
                "local function intentof(package, scheme) return { getComponent = function(self) return nil end,\n" +
                "  getPackage = function(self) return package end, getScheme = function(self) return scheme end } end\n" +
                "local function resolve(intent, result, names, flags)\n" +
                "  candidates = {}\n" +
                "  for _, name in ipairs(names) do candidates[#candidates + 1] = infoof(name, name ~= 'com.whatsapp') end\n" +
                "  default = (result ~= 'android' and result or nil)\n" +
                "  local param = paramof({ [0] = intent, [1] = flags or 65536 }, { ['org.mozilla.firefox'] = true," +
                " ['com.android.chrome'] = true }, context)\n" +
                "  param.unhooked = function(self, fn) return fn() end\n" +
                "  param.getThis = function(self) return pm end\n" +
                "  param.result = infoof(result)\n" +
                "  if result == 'android' then param.result.activityInfo.name = 'com.android.internal.app.ResolverActivity' end\n" +
                "  local restricted = after(hookof('PackageManager.resolveActivity', 'resolveActivity'), param)\n" +
                "  return restricted, param.result\n" +
                "end\n";
        Varargs only = Scripts.run(globals, setup +
                "return resolve(intentof(nil), 'com.whatsapp', { 'com.whatsapp' })");
        assertTrue(only.arg(1).toboolean());
        assertTrue(only.arg(2).isnil());
        Varargs several = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'com.hidden.browser'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox', 'com.android.chrome' })\n" +
                "return restricted, result.activityInfo.name, result.activityInfo.processName, result.handleAllWebDataURI,\n" +
                "  result.activityInfo.metaData.values['android.dock_home'], result.activityInfo.metaData.read, parcel.recycled");
        assertTrue(several.arg(1).toboolean());
        assertEquals("com.android.internal.app.ResolverActivity", several.arg(2).tojstring());
        assertEquals("system:ui", several.arg(3).tojstring());
        assertTrue(several.arg(4).toboolean());
        // The meta-data which shows it in car mode, parcelled like the one of the chooser of Android
        assertTrue(several.arg(5).toboolean());
        assertTrue(several.arg(6).toboolean());
        assertTrue(several.arg(7).toboolean());
        // Android 12 and later give the role of a hidden default browser to another one, which a link resolves to: the
        // only system browser, else the default of the device when a system app or named with its certificate, unless
        // another browser has a higher priority; none of them, or Android 11 and earlier, leave the chooser
        String link = "local function browser(...)\n" +
                "  local restricted, result = resolve(intentof(nil, 'https'), 'com.hidden.browser'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox', 'com.android.chrome' })\n" +
                "  return result.activityInfo.packageName end\n";
        String[][] browsers = {
                {"", "android"},
                {"system['com.android.chrome'] = true", "com.android.chrome"},
                {"system['com.android.chrome'] = true system['org.mozilla.firefox'] = true", "android"},
                {"system['com.android.chrome'] = true system['org.mozilla.firefox'] = true config = 'org.mozilla.firefox'",
                        "org.mozilla.firefox"},
                {"config = 'org.mozilla.firefox'", "android"},
                {"config = 'org.mozilla.firefox:3082'", "org.mozilla.firefox"},
                {"config = 'com.example.missing:3082'", "android"},
                {"system['com.android.chrome'] = true priorities['org.mozilla.firefox'] = 1", "org.mozilla.firefox"},
                {"system['com.android.chrome'] = true sdk = 30", "android"}};
        for (String[] browser : browsers) {
            Varargs result = Scripts.run(globals, setup + link + browser[0] + "\nreturn browser()");
            assertEquals(browser[0], browser[1], result.arg1().tojstring());
        }
        Varargs one = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'com.hidden.browser'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox' })\n" +
                "return restricted, result.activityInfo.packageName");
        assertTrue(one.arg(1).toboolean());
        assertEquals("org.mozilla.firefox", one.arg(2).tojstring());
        // The default browser is hidden: the flags as ResolveInfoFlags of Android 13 and later too
        Varargs flags = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'com.hidden.browser'," +
                " { 'com.android.chrome', 'com.hidden.browser' }, flagsof(65536))\n" +
                "return restricted, result.activityInfo.packageName, queries");
        assertTrue(flags.arg(1).toboolean());
        assertEquals("com.android.chrome", flags.arg(2).tojstring());
        assertEquals(2, flags.arg(3).toint());
        // The chooser for a hidden and a visible app is that app, like without the hidden one, with one query
        Varargs chooser = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'android'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox' })\n" +
                "return restricted, result.activityInfo.packageName, queries");
        assertTrue(chooser.arg(1).toboolean());
        assertEquals("org.mozilla.firefox", chooser.arg(2).tojstring());
        assertEquals(1, chooser.arg(3).toint());
        // The chooser of Android for a hidden app which is no browser and two browsers is marked as a browser, like
        // without that app, on a copy of it
        String kept = "local restricted, result = resolve(intentof(nil), 'android', %s)\n" +
                "return restricted, result.activityInfo.name, result.handleAllWebDataURI, result.copied";
        Varargs marked = Scripts.run(globals, setup +
                String.format(kept, "{ 'com.whatsapp', 'org.mozilla.firefox', 'com.android.chrome' }"));
        assertTrue(marked.arg(1).toboolean());
        assertEquals("com.android.internal.app.ResolverActivity", marked.arg(2).tojstring());
        assertTrue(marked.arg(3).toboolean());
        assertTrue(marked.arg(4).toboolean());
        Varargs app = Scripts.run(globals, setup +
                "return resolve(intentof('com.whatsapp'), 'com.whatsapp', { 'com.whatsapp' })");
        assertTrue(app.arg(1).toboolean());
        assertTrue(app.arg(2).isnil());
        // The chooser between the screens of a hidden app gives nothing, of a visible app it stays
        Varargs screens = Scripts.run(globals, setup +
                "return resolve(intentof('com.whatsapp'), 'android', { 'com.whatsapp', 'com.whatsapp' })");
        assertTrue(screens.arg(1).toboolean());
        assertTrue(screens.arg(2).isnil());
        Varargs own = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof('org.mozilla.firefox'), 'android', {})\n" +
                "return restricted, result.activityInfo.packageName");
        assertFalse(own.arg(1).toboolean());
        assertEquals("android", own.arg(2).tojstring());
    }

    // A link which only a hidden app is approved for, like its verified links, of which Android gives only that app,
    // also when asked for all: like without that app, the visible browsers, which a link of a host no app claims finds:
    // the default one, else the only one, else the chooser; nothing without a visible browser
    @Test
    public void verifiedLinksOfHiddenAppsResolveToBrowsers() throws Exception {
        Globals globals = load("package_resolve");
        String setup = "local base = luajava.newInstance\n" +
                "luajava.newInstance = function(name, from)\n" +
                "  if name == 'android.content.Intent' then return { from = from,\n" +
                "    setClassName = function(self, p, c) self.target = p .. '/' .. c return self end,\n" +
                "    setDataAndType = function(self, data, type) self.data = data self.type = type return self end } end\n" +
                "  if name == 'android.os.Bundle' then return { putBoolean = function() end } end\n" +
                "  return base(name, from) end\n" +
                "local bind = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Parcel' then return { obtain = function() return { writeBundle = function() end,\n" +
                "    setDataPosition = function() end, readBundle = function() return {} end, recycle = function() end } end } end\n" +
                "  return bind(name) end\n" +
                "local function infoof(name, web) return { priority = 0, preferredOrder = 0, isDefault = true,\n" +
                "  handleAllWebDataURI = web, activityInfo = { packageName = name, name = name .. '.Main' } } end\n" +
                "local link = { getComponent = function() return nil end, getPackage = function() return nil end,\n" +
                "  getAction = function() return 'android.intent.action.VIEW' end, getScheme = function() return 'https' end,\n" +
                "  getType = function() return nil end, getData = function() return { buildUpon = function()\n" +
                "    return { authority = function(self, host) self.host = host return self end,\n" +
                "      build = function(self) return { host = self.host } end } end } end }\n" +
                "local browsers, default, asked\n" +
                // The link of the app gives only the approved app, whatever the flags
                "local pm = { queryIntentActivities = function(self, intent, flags)\n" +
                "    if intent == link then return listof({ infoof('com.twitter.android', false) }) end\n" +
                "    assert(intent.from == link and intent.data.host == 'example.invalid' and intent.type == nil)\n" +
                "    asked = flags\n" +
                "    if default ~= nil and flags == 65536 then return listof({ infoof(default, true) }) end\n" +
                "    local all = {}\n" +
                "    for _, name in ipairs(browsers) do all[#all + 1] = infoof(name, name ~= 'com.example.app') end\n" +
                "    return listof(all) end,\n" +
                "  resolveActivity = function(self, intent, flags)\n" +
                "    return { activityInfo = { packageName = 'android', name = 'com.android.internal.app.ResolverActivity' } } end }\n" +
                "local function resolve(names, defaultBrowser)\n" +
                "  browsers, default, asked = names, defaultBrowser, nil\n" +
                "  local param = paramof({ [0] = link, [1] = 65536 }, { ['org.mozilla.firefox'] = true,\n" +
                "    ['com.android.chrome'] = true, ['com.example.app'] = true })\n" +
                "  param.unhooked = function(self, fn) return fn() end\n" +
                "  param.getThis = function(self) return pm end\n" +
                "  param.result = infoof('com.twitter.android', false)\n" +
                "  local restricted = after(hookof('PackageManager.resolveActivity', 'resolveActivity'), param)\n" +
                "  local info = param.result\n" +
                "  return restricted, info and info.activityInfo.packageName, info and info.activityInfo.name, asked,\n" +
                "    info and info.handleAllWebDataURI\n" +
                "end\n";
        Varargs chrome = Scripts.run(globals, setup +
                "return resolve({ 'com.android.chrome', 'org.mozilla.firefox' }, 'com.android.chrome')");
        assertTrue(chrome.arg(1).toboolean());
        assertEquals("com.android.chrome", chrome.arg(2).tojstring());
        assertEquals(65536, chrome.arg(4).toint());
        Varargs chooser = Scripts.run(globals, setup + "return resolve({ 'com.android.chrome', 'org.mozilla.firefox' })");
        assertTrue(chooser.arg(1).toboolean());
        assertEquals("com.android.internal.app.ResolverActivity", chooser.arg(3).tojstring());
        assertTrue(chooser.arg(5).toboolean());
        Varargs only = Scripts.run(globals, setup + "return resolve({ 'org.mozilla.firefox' })");
        assertEquals("org.mozilla.firefox", only.arg(2).tojstring());
        // A hidden default browser leaves the others, an app of the reserved host is not a browser
        Varargs hidden = Scripts.run(globals, setup +
                "return resolve({ 'com.hidden.browser', 'org.mozilla.firefox' }, 'com.hidden.browser')");
        assertEquals("org.mozilla.firefox", hidden.arg(2).tojstring());
        assertEquals(196608, hidden.arg(4).toint());
        Varargs claimed = Scripts.run(globals, setup + "return resolve({ 'com.example.app', 'org.mozilla.firefox' })");
        assertEquals("org.mozilla.firefox", claimed.arg(2).tojstring());
        Varargs none = Scripts.run(globals, setup + "return resolve({ 'com.hidden.browser' })");
        assertTrue(none.arg(1).toboolean());
        assertTrue(none.arg(2).isnil());
    }

    // MATCH_ALL once, also with the bits of a long, which bit32 would lose
    @Test
    public void matchAllKeepsFlags() throws Exception {
        assertEquals(Scripts.function("package_resolve", "matchall"), Scripts.function("generic_filter_by_uid", "matchall"));
        for (String script : new String[]{"package_resolve", "generic_filter_by_uid"}) {
            Globals globals = load(script);
            Varargs result = Scripts.run(globals, "return matchall(65536), matchall(196608), " +
                    "matchall(flagsof(4294967296 + 65536)):getValue(), matchall(flagsof(131072)):getValue()");
            assertEquals(script, 196608, result.arg(1).toint());
            assertEquals(script, 196608, result.arg(2).toint());
            assertEquals(script, 4295163904L, result.arg(3).tolong());
            assertEquals(script, 131072, result.arg(4).toint());
        }
    }

    // Of a link Android gives only the default browser: when it is hidden the visible apps of all, like on a device
    // without it, for each way to ask, and only then
    @Test
    public void linksOfAHiddenDefaultBrowser() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String run = "local function activity(uid) return { activityInfo = { packageName = 'app' .. uid," +
                " applicationInfo = { uid = uid, system = (uid == 10010) } } } end\n" +
                "local asked\n" +
                "local param = paramof({ [0] = 'link', [1] = %s }, { app10010 = true })\n" +
                "param.result = listof({ activity(10060) })\n" +
                "function param:unhooked(fn) return fn() end\n" +
                "function param:getThis() return { queryIntentActivities = function(self, intent, flags)\n" +
                "  assert(intent == 'link')\n" +
                "  asked = (type(flags) == 'number' and flags or flags:getValue())\n" +
                "  if asked == 65536 then return listof({ activity(10060) }) end\n" +
                "  return listof({ activity(10010), activity(10060) }) end } end\n" +
                "local restricted = after(hookof('%s'), param)\n" +
                "return restricted, param.result:size(), param.result:get(0).activityInfo.applicationInfo.uid, asked";
        String[][] calls = {{"65536", "PackageManager.queryIntentActivities"},
                {"flagsof(65536)", "PackageManager.queryIntentActivities.33"},
                {"65536", "PackageManager.queryIntentActivitiesAsUser"},
                {"flagsof(65536)", "PackageManager.queryIntentActivitiesAsUser.33"}};
        for (String[] call : calls) {
            Varargs result = Scripts.run(globals, String.format(run, call[0], call[1]));
            assertTrue(call[1], result.arg(1).toboolean());
            assertEquals(call[1], 1, result.arg(2).toint());
            assertEquals(call[1], 10010, result.arg(3).toint());
            assertEquals(call[1], 196608, result.arg(4).toint());
        }

        // Nothing to find for other lists
        Varargs services = Scripts.run(globals,
                "local param = paramof({ [0] = 'link', [1] = 0 }, {})\n" +
                        "param.result = listof({ { serviceInfo = { applicationInfo = { uid = 10060 } } } })\n" +
                        "after(hookof('PackageManager.queryIntentServices'), param)\n" +
                        "return param.result:size()");
        assertEquals(0, services.arg(1).toint());
    }

    // A link of hidden apps lists what resolveActivity resolves it to, like a device without them: the browsers of a
    // link which only a hidden app is approved for, whatever the flags, and on Android 12 and later the one browser
    // which gets the role of a hidden default browser; all visible browsers when resolveActivity gives the chooser
    @Test
    public void linksOfHiddenAppsListWhatTheyResolveTo() throws Exception {
        for (String copy : new String[]{"visibleof", "fallback", "weblink", "unclaimed"})
            assertEquals(copy, Scripts.function("package_resolve", copy), Scripts.function("generic_filter_by_uid", copy));
        String setup = "local base, bind = luajava.newInstance, luajava.bindClass\n" +
                "luajava.newInstance = function(name, from)\n" +
                "  if name == 'android.content.Intent' then return { from = from,\n" +
                "    setClassName = function(self) return self end,\n" +
                "    setDataAndType = function(self, data, type) self.data = data return self end } end\n" +
                "  if name == 'android.os.Bundle' then return { putBoolean = function() end } end\n" +
                "  return base(name, from) end\n" +
                "local sdk, approved, default = 34\n" +
                "local browsers = { 'com.hidden.browser', 'com.android.chrome', 'org.mozilla.firefox' }\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end\n" +
                "  if name == 'android.os.Parcel' then return { obtain = function() return { writeBundle = function() end,\n" +
                "    setDataPosition = function() end, readBundle = function() return {} end, recycle = function() end } end } end\n" +
                "  return bind(name) end\n" +
                // Chrome is the only system browser, which gets the browser role of a hidden one
                "local function infoof(name) return { priority = 0, preferredOrder = 0, isDefault = true,\n" +
                "  handleAllWebDataURI = (name ~= approved), activityInfo = { packageName = name, name = name .. '.Main',\n" +
                "  exported = true, applicationInfo = { uid = 10060, flags = (name == 'com.android.chrome' and 1 or 0) } } } end\n" +
                "local link = { getComponent = function() return nil end, getPackage = function() return nil end,\n" +
                "  getAction = function() return 'android.intent.action.VIEW' end, getScheme = function() return 'https' end,\n" +
                "  getType = function() return nil end, getData = function() return { buildUpon = function()\n" +
                "    return { authority = function(self, host) self.host = host return self end,\n" +
                "      build = function(self) return { host = self.host } end } end } end }\n" +
                // The link gives only the approved app whatever the flags, else the default browser unless asked for all
                "local pm = { queryIntentActivities = function(self, intent, flags)\n" +
                "    local value = (type(flags) == 'number' and flags or flags:getValue())\n" +
                "    if intent == link and approved ~= nil then return listof({ infoof(approved) }) end\n" +
                "    assert(intent == link or intent.data.host == 'example.invalid')\n" +
                "    if default ~= nil and value == 65536 then return listof({ infoof(default) }) end\n" +
                "    local all = {}\n" +
                "    for _, name in ipairs(browsers) do all[#all + 1] = infoof(name) end\n" +
                "    return listof(all) end,\n" +
                "  resolveActivity = function(self, intent, flags)\n" +
                "    return { activityInfo = { packageName = 'android', name = 'com.android.internal.app.ResolverActivity' } } end }\n" +
                "local function call(hook, result)\n" +
                "  local param = paramof({ [0] = link, [1] = 65536 }, { ['com.android.chrome'] = true,\n" +
                "    ['org.mozilla.firefox'] = true }, { getResources = function() return {\n" +
                "    getIdentifier = function() return 0 end } end })\n" +
                "  param.unhooked = function(self, fn) return fn() end\n" +
                "  param.getThis = function(self) return pm end\n" +
                "  param.result = result\n" +
                "  return after(hook, param), param.result\n" +
                "end\n" +
                "local function listed()\n" +
                "  local restricted, list = call(hookof('PackageManager.queryIntentActivities'), pm:queryIntentActivities(link, 65536))\n" +
                "  local names = {}\n" +
                "  for index = 0, list:size() - 1 do names[#names + 1] = list:get(index).activityInfo.packageName end\n" +
                "  return table.concat(names, ','), restricted\n" +
                "end\n" +
                "local function resolved()\n" +
                "  local restricted, info = call(hookof('PackageManager.resolveActivity', 'resolveActivity'),\n" +
                "    pm:queryIntentActivities(link, 65536):get(0))\n" +
                "  return info and info.activityInfo.packageName or '', restricted\n" +
                "end\n";
        // The apps approved for the host and the default browser; the list, and what resolveActivity gives
        String[][] links = {
                {"approved = 'com.twitter.android' default = 'com.android.chrome'", "com.android.chrome", "com.android.chrome"},
                {"approved = 'com.twitter.android'", "com.android.chrome,org.mozilla.firefox", "android"},
                {"approved = 'com.twitter.android' default = 'com.hidden.browser'", "com.android.chrome", "com.android.chrome"},
                {"default = 'com.hidden.browser'", "com.android.chrome", "com.android.chrome"},
                {"default = 'com.hidden.browser' sdk = 30", "com.android.chrome,org.mozilla.firefox", "android"},
                {"approved = 'com.twitter.android' browsers = { 'com.hidden.browser' }", "", ""}};
        Globals list = load("generic_filter_by_uid");
        Globals resolve = load("package_resolve");
        for (String[] link : links) {
            Varargs listed = Scripts.run(list, setup + link[0] + "\nreturn listed()");
            assertEquals(link[0], link[1], listed.arg(1).tojstring());
            assertTrue(link[0], listed.arg(2).toboolean());
            Varargs resolved = Scripts.run(resolve, setup + link[0] + "\nreturn resolved()");
            assertEquals(link[0], link[2], resolved.arg(1).tojstring());
            assertTrue(link[0], resolved.arg(2).toboolean());
        }
    }

    // The activities of hidden apps are left out of both lists, which are filled by Android and of the app
    @Test
    public void preferredActivitiesOfHiddenApps() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String run = "local function activity(name) return { getPackageName = function(self) return name end } end\n" +
                "local filters = listof({ 'chrome', 'bank', 'mail' })\n" +
                "local activities = listof({ activity('com.android.chrome'), activity('com.example.bank'), activity('com.example.mail') })\n" +
                "local param = paramof({ [0] = filters, [1] = activities }, %s)\n" +
                "param.result = %d\n" +
                "local restricted = after(hookof('PackageManager.getPreferredActivities', 'getPreferredActivities'), param)\n" +
                "return restricted, param.result, filters:size(), activities:size(), filters:get(0), activities:get(0):getPackageName()";
        Varargs result = Scripts.run(globals, String.format(run, "{ ['com.android.chrome'] = true }", 3));
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals(1, result.arg(3).toint());
        assertEquals(1, result.arg(4).toint());
        assertEquals("chrome", result.arg(5).tojstring());
        assertEquals("com.android.chrome", result.arg(6).tojstring());

        // Android itself returns 0, whatever it found
        assertEquals(0, Scripts.run(globals, String.format(run, "{ ['com.android.chrome'] = true }", 0)).arg(2).toint());

        Varargs all = Scripts.run(globals, String.format(run, "{ ['com.android.chrome'] = true, " +
                "['com.example.bank'] = true, ['com.example.mail'] = true }", 3));
        assertFalse(all.arg(1).toboolean());
        assertEquals(3, all.arg(2).toint());
        assertEquals(3, all.arg(3).toint());
    }

    // The apps which sync, like com.whatsapp, of which the hidden ones are left out
    @Test
    public void syncAdaptersOfHiddenAppsAreLeftOut() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local function syncof(name) return { getPackageName = function(self) return name end } end\n" +
                        "local param = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "param.findClass = function(self, name) return name end\n" +
                        "param.result = { length = 3, syncof('com.google.android.gms'), syncof('com.whatsapp'), syncof(nil) }\n" +
                        "local restricted = after(hookof('ContentResolver.getSyncAdapterTypes', 'getSyncAdapterTypes'), param)\n" +
                        "return restricted, param.result['length']");
        assertTrue(result.arg(1).toboolean());
        assertEquals(2, result.arg(2).toint());

        // For a user, the hidden overloads: the types, and the packages which sync an authority
        Varargs user = Scripts.run(load("package_resolve"),
                "local function syncof(name) return { getPackageName = function(self) return name end } end\n" +
                        "local types = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "types.findClass = function(self, name) return name end\n" +
                        "types.result = { length = 2, syncof('com.google.android.gms'), syncof('com.whatsapp') }\n" +
                        "local packages = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "packages.result = arrayof('com.whatsapp', 'com.google.android.gms')\n" +
                        "return after(hookof('ContentResolver.getSyncAdapterTypesAsUser', 'getSyncAdapterTypesAsUser'), types),\n" +
                        "  types.result['length'],\n" +
                        "  after(hookof('ContentResolver.getSyncAdapterPackagesForAuthorityAsUser',\n" +
                        "    'getSyncAdapterPackagesForAuthorityAsUser'), packages), packages.result.length, packages.result[1]");
        assertTrue(user.arg(1).toboolean());
        assertEquals(1, user.arg(2).toint());
        assertTrue(user.arg(3).toboolean());
        assertEquals(1, user.arg(4).toint());
        assertEquals("com.google.android.gms", user.arg(5).tojstring());
    }

    // An account syncs no authority of a hidden app's sync adapter (0, like an authority without one); the others stay
    @Test
    public void syncableOfHiddenSyncAdapter() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local function syncof(name, authority, account)\n" +
                        "  return { authority = authority, accountType = account, getPackageName = function(self) return name end } end\n" +
                        "local types = { length = 2, syncof('com.whatsapp', 'com.whatsapp.provider.contact', 'com.whatsapp'),\n" +
                        "  syncof('com.google.android.gms', 'com.android.contacts', 'com.google') }\n" +
                        "luajava = { bindClass = function(name) return { getSyncAdapterTypes = function(self) return types end } end }\n" +
                        "local function syncable(type, authority, result)\n" +
                        "  local param = paramof({ [0] = { type = type }, [1] = authority }, { ['com.google.android.gms'] = true })\n" +
                        "  param.unhooked = function(self, f) return f() end\n" +
                        "  param.result = result\n" +
                        "  local restricted = after(hookof('ContentResolver.getIsSyncable', 'getIsSyncable'), param)\n" +
                        "  return restricted, param.result end\n" +
                        "local hidden, hiddenResult = syncable('com.whatsapp', 'com.whatsapp.provider.contact', -1)\n" +
                        "local shown, shownResult = syncable('com.google', 'com.android.contacts', 1)\n" +
                        "local none, noneResult = syncable('com.whatsapp', 'com.whatsapp.provider.contact', 0)\n" +
                        "return hidden, hiddenResult, shown, shownResult, none, noneResult");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertEquals(1, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertEquals(0, result.arg(6).toint());
    }

    // Like for a user id which is not in use
    @Test
    public void signaturesOfHiddenUid() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local param = paramof({ [0] = 10001, [1] = 10060 }, { [10001] = true })\n" +
                        "param.result = -3\n" +
                        "local restricted = after(hookof('PackageManager.checkSignatures/uid', 'checkSignatures'), param)\n" +
                        "return restricted, param.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals(-4, result.arg(2).toint());
    }

    // A hidden user id has no certificate, also like for a user id which is not in use
    @Test
    public void signingCertificateOfHiddenUid() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.hasSigningCertificate/uid', 'hasSigningCertificate')\n" +
                        "local hidden = paramof({ [0] = 10060 }, { [10001] = true })\n" +
                        "hidden.result = true\n" +
                        "local visible = paramof({ [0] = 10001 }, { [10001] = true })\n" +
                        "visible.result = true\n" +
                        "local none = paramof({ [0] = 10060 }, {})\n" +
                        "none.result = false\n" +
                        "return after(hook, hidden), hidden.result, after(hook, visible), visible.result, after(hook, none)");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
    }

    // The packages of a hidden user id are null, like of a user id which is not in use; the first one is reported
    @Test
    public void packagesOfHiddenUid() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.getPackagesForUid', 'getPackagesForUid')\n" +
                        "local hidden = paramof({ [0] = 10060 }, { [10001] = true })\n" +
                        "hidden.result = arrayof('com.whatsapp', 'com.whatsapp.w4b')\n" +
                        "local visible = paramof({ [0] = 10001 }, { [10001] = true })\n" +
                        "visible.result = arrayof('com.example')\n" +
                        "local none = paramof({ [0] = 10060 }, {})\n" +
                        "none.result = arrayof()\n" +
                        "local restricted, name = after(hook, hidden)\n" +
                        "return restricted, name, hidden.result, after(hook, visible), visible.result[1], after(hook, none)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.whatsapp", result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
        assertFalse(result.arg(4).toboolean());
        assertEquals("com.example", result.arg(5).tojstring());
        assertFalse(result.arg(6).toboolean());

        // A shared user id with a visible app: its other packages stay hidden, like for their lookups
        Varargs shared = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.getPackagesForUid', 'getPackagesForUid')\n" +
                        "local param = paramof({ [0] = 10070 }, { [10070] = true, ['com.example.shared'] = true })\n" +
                        "param.result = arrayof('com.example.shared', 'com.third.hidden')\n" +
                        "local all = paramof({ [0] = 10070 }, { [10070] = true, ['com.a'] = true, ['com.b'] = true })\n" +
                        "all.result = arrayof('com.a', 'com.b')\n" +
                        "local restricted, old, new = after(hook, param)\n" +
                        "return restricted, old, new, param.result.length, param.result[1], after(hook, all)");
        assertTrue(shared.arg(1).toboolean());
        assertEquals("2", shared.arg(2).tojstring());
        assertEquals("1", shared.arg(3).tojstring());
        assertEquals(1, shared.arg(4).toint());
        assertEquals("com.example.shared", shared.arg(5).tojstring());
        assertFalse(shared.arg(6).toboolean());
    }

    // The binder proxies, which apps call by reflection, answer like the hooks above them; through those hooks both run,
    // and the second leaves what the first answered. The own user id and those of the system keep their real answer.
    // ApplicationPackageManager.getPackagesForUid leaves its call of the proxy to its own hook, see packagemanager.
    @Test
    public void binderProxiesOfHiddenUids() throws Exception {
        String pm = "'android.content.pm.IPackageManager$Stub$Proxy'";
        Varargs result = Scripts.run(load("package_resolve"),
                "local frames = { length = 0 }\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name == 'java.lang.reflect.Array' then\n" +
                        "    return { newInstance = function(self, type, length) return { length = length } end } end\n" +
                        "  assert(name == 'java.lang.Thread', name)\n" +
                        "  return { currentThread = function(self) return { getStackTrace = function(self) return frames end } end }\n" +
                        "end\n" +
                        "local function frameof(class, method)\n" +
                        "  return { getClassName = function(self) return class end, getMethodName = function(self) return method end }\n" +
                        "end\n" +
                        "local visible = { [10001] = true, [1000] = true }\n" +
                        "local function twice(proxy, wrapper, args, value)\n" +
                        "  local param = paramof(args, visible)\n" +
                        "  param.result = value\n" +
                        "  local first = after(proxy, param)\n" +
                        "  local second = after(wrapper, param)\n" +
                        "  return first, second, param.result\n" +
                        "end\n" +
                        "local packages = hookof('IPackageManager.getPackagesForUid', 'getPackagesForUid', " + pm + ")\n" +
                        "local name = hookof('IPackageManager.getNameForUid', 'getNameForUid', " + pm + ")\n" +
                        "local signatures = hookof('IPackageManager.checkUidSignatures', 'checkUidSignatures', " + pm + ")\n" +
                        "local packagesOuter = hookof('PackageManager.getPackagesForUid', 'getPackagesForUid')\n" +
                        "local nameOuter = hookof('PackageManager.getNameForUid', 'getNameForUid')\n" +
                        "local signaturesOuter = hookof('PackageManager.checkSignatures/uid', 'checkSignatures')\n" +
                        "local r = {}\n" +
                        "local function addall(a, b, c) r[#r + 1] = a; r[#r + 1] = b; r[#r + 1] = (c == nil and 'nil' or c) end\n" +
                        // 1-3: a hidden uid, the proxy answers null and the hook of PackageManager leaves it
                        "local a, b, c = twice(packages, packagesOuter, { [0] = 10060 }, arrayof('com.whatsapp'))\n" +
                        "addall(a, b, c)\n" +
                        // 4-6: the name of a hidden uid
                        "a, b, c = twice(name, nameOuter, { [0] = 10060 }, 'com.whatsapp')\n" +
                        "addall(a, b, c)\n" +
                        // 7-9: the own and a hidden uid, like for a uid which is not in use
                        "a, b, c = twice(signatures, signaturesOuter, { [0] = 10001, [1] = 10060 }, -3)\n" +
                        "addall(a, b, c)\n" +
                        // 10-12: the own uid and the system keep their names
                        "a, b, c = twice(name, nameOuter, { [0] = 10001 }, 'com.example')\n" +
                        "addall(a, b, c)\n" +
                        "a, b, c = twice(name, nameOuter, { [0] = 1000 }, 'android.uid.system:1000')\n" +
                        "addall(a, b, c)\n" +
                        "a, b, c = twice(signatures, signaturesOuter, { [0] = 10001, [1] = 1000 }, -3)\n" +
                        "addall(a, b, c)\n" +
                        // 19-21: called through ApplicationPackageManager, below the frames of the cache, the proxy
                        // leaves the call to the hook of PackageManager, which restricts it
                        "frames = { length = 3, frameof('LSPHooker_', 'getPackagesForUid'),\n" +
                        "  frameof('android.app.ApplicationPackageManager$1', 'recompute'),\n" +
                        "  frameof('android.app.ApplicationPackageManager', 'getPackagesForUid') }\n" +
                        "a, b, c = twice(packages, packagesOuter, { [0] = 10060 }, arrayof('com.whatsapp'))\n" +
                        "addall(a, b, c)\n" +
                        "return table.unpack(r)");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("nil", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertEquals("nil", result.arg(6).tojstring());
        assertTrue(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
        assertEquals(-4, result.arg(9).toint());
        for (int i = 10; i <= 16; i += 3) {
            assertFalse(result.arg(i).toboolean());
            assertFalse(result.arg(i + 1).toboolean());
        }
        assertEquals("com.example", result.arg(12).tojstring());
        assertEquals("android.uid.system:1000", result.arg(15).tojstring());
        assertEquals(-3, result.arg(18).toint());
        assertFalse(result.arg(19).toboolean());
        assertTrue(result.arg(20).toboolean());
        assertEquals("nil", result.arg(21).tojstring());

        // The own user id and the system through the proxy itself, also the packages of the system
        Varargs own = Scripts.run(load("package_resolve"),
                "local thread = { getStackTrace = function(self) return { length = 0 } end }\n" +
                        "luajava.bindClass = function(name) return { currentThread = function(self) return thread end } end\n" +
                        "local hook = hookof('IPackageManager.getPackagesForUid', 'getPackagesForUid', " + pm + ")\n" +
                        "local own = paramof({ [0] = 10001 }, { [10001] = true, ['com.example'] = true })\n" +
                        "own.result = arrayof('com.example')\n" +
                        "local system = paramof({ [0] = 1000 }, { [1000] = true, ['android'] = true, ['com.android.settings'] = true })\n" +
                        "system.result = arrayof('android', 'com.android.settings')\n" +
                        "return after(hook, own), own.result[1], after(hook, system), system.result.length");
        assertFalse(own.arg(1).toboolean());
        assertEquals("com.example", own.arg(2).tojstring());
        assertFalse(own.arg(3).toboolean());
        assertEquals(2, own.arg(4).toint());

        // The sync adapters of hidden apps through the proxy of the content service, which the hook of ContentResolver
        // above it leaves then
        Varargs sync = Scripts.run(load("package_resolve"),
                "local function syncof(name) return { getPackageName = function(self) return name end } end\n" +
                        "local param = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "param.findClass = function(self, name) return name end\n" +
                        "param.result = { length = 2, syncof('com.google.android.gms'), syncof('com.whatsapp') }\n" +
                        "local proxy = after(hookof('IContentService.getSyncAdapterTypes', 'getSyncAdapterTypes',\n" +
                        "  'android.content.IContentService$Stub$Proxy'), param)\n" +
                        "local outer = after(hookof('ContentResolver.getSyncAdapterTypes', 'getSyncAdapterTypes'), param)\n" +
                        "return proxy, outer, param.result['length'], param.result[1]:getPackageName()");
        assertTrue(sync.arg(1).toboolean());
        assertFalse(sync.arg(2).toboolean());
        assertEquals(1, sync.arg(3).toint());
        assertEquals("com.google.android.gms", sync.arg(4).tojstring());
    }

    // A permission which a hidden app defines, like the own permission of a messenger, is denied to the app like a
    // permission no app defines; asked once per process, by Context.checkPermission and PackageManager.checkPermission
    @Test
    public void permissionsOfHiddenAppsAreDenied() throws Exception {
        String context = "local owners = { ['com.whatsapp.permission.X'] = 'com.whatsapp',\n" +
                "  ['android.permission.CAMERA'] = 'android' }\n" +
                "asked = 0\n" +
                "local values = { getPackageManager = function(self) return { getPermissionInfo = function(self, name, flags)\n" +
                "  asked = asked + 1\n" +
                "  if owners[name] == nil then error('NameNotFoundException') end\n" +
                "  return { packageName = owners[name] } end } end }\n" +
                "local function check(args, visible)\n" +
                "  local param = paramof(args, visible, values)\n" +
                "  param.result = 0\n" +
                "  param.unhooked = function(self, fn) return fn() end\n" +
                "  return param\n" +
                "end\n";
        Varargs resolve = Scripts.run(load("package_resolve"), context +
                "local hook = hookof('Context.checkPermission', 'checkPermission')\n" +
                "local hidden = check({ [0] = 'com.whatsapp.permission.X', [2] = 10001 }, { [10001] = true, android = true })\n" +
                "local again = check({ [0] = 'com.whatsapp.permission.X', [2] = 10001 }, { [10001] = true, android = true })\n" +
                "local system = check({ [0] = 'android.permission.CAMERA', [2] = 10001 }, { [10001] = true, android = true })\n" +
                "local none = check({ [0] = 'com.example.UNDEFINED', [2] = 10001 }, { [10001] = true })\n" +
                "local r1, owner = after(hook, hidden)\n" +
                "after(hook, again)\n" +
                "return r1, owner, hidden.result, again.result, after(hook, system), system.result, after(hook, none), asked");
        assertTrue(resolve.arg(1).toboolean());
        assertEquals("com.whatsapp", resolve.arg(2).tojstring());
        assertEquals(-1, resolve.arg(3).toint());
        assertEquals(-1, resolve.arg(4).toint());
        assertFalse(resolve.arg(5).toboolean());
        assertEquals(0, resolve.arg(6).toint());
        assertFalse(resolve.arg(7).toboolean());
        // Once per permission and process: whatsapp, camera, the undefined one
        assertEquals(3, resolve.arg(8).toint());

        Varargs probe = Scripts.run(load("package_probe"), context +
                "local hook = hookof('PackageManager.checkPermission', 'checkPermission')\n" +
                "local param = check({ [0] = 'com.whatsapp.permission.X', [1] = 'test.app' }, { ['test.app'] = true })\n" +
                "param.getPackageName = function() return 'test.app' end\n" +
                "before(hook, param)\n" +
                "local restricted, owner = after(hook, param)\n" +
                "return restricted, owner, param.result");
        assertTrue(probe.arg(1).toboolean());
        assertEquals("com.whatsapp", probe.arg(2).tojstring());
        assertEquals(-1, probe.arg(3).toint());
        // The two copies of the lookup stay the same
        assertEquals(Scripts.function("package_probe", "permissionowner"), Scripts.function("package_resolve", "permissionowner"));
    }

    // The names of several user ids: null for the user ids of hidden apps, like for user ids which are not in use
    @Test
    public void namesOfHiddenUids() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.getNamesForUids', 'getNamesForUids')\n" +
                        "local param = paramof({ [0] = arrayof(10001, 10060, 1000) }, { [10001] = true, [1000] = true })\n" +
                        "param.result = arrayof('test.app', 'com.hidden', 'android')\n" +
                        "local visible = paramof({ [0] = arrayof(10001) }, { [10001] = true })\n" +
                        "visible.result = arrayof('test.app')\n" +
                        "return after(hook, param), param.result.length, param.result[1], param.result[2], param.result[3],\n" +
                        "  after(hook, visible)");
        assertTrue(result.arg(1).toboolean());
        assertEquals(3, result.arg(2).toint());
        assertEquals("test.app", result.arg(3).tojstring());
        assertTrue(result.arg(4).isnil());
        assertEquals("android", result.arg(5).tojstring());
        assertFalse(result.arg(6).toboolean());
    }

    // A service resolved for a user, like resolveService: an implicit intent gets the first visible service,
    // an intent naming a hidden app gets none
    @Test
    public void servicesResolvedForUser() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local function service(name) return { serviceInfo = { packageName = name } } end\n" +
                        "local hidden, visible = service('com.hidden'), service('com.visible')\n" +
                        "local implicit = { getComponent = function() return nil end, getPackage = function() return nil end }\n" +
                        "local explicit = { getComponent = function() return nil end, getPackage = function() return 'com.hidden' end }\n" +
                        "local function call(intent)\n" +
                        "  local param = paramof({ [0] = intent, [1] = 0, [2] = 0 }, { ['com.visible'] = true })\n" +
                        "  param.result = hidden\n" +
                        "  param.getThis = function() return { queryIntentServices = function(self, i, f)\n" +
                        "    return listof({ hidden, visible }) end } end\n" +
                        "  param.unhooked = function(self, fn) return fn() end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local hook = hookof('PackageManager.resolveServiceAsUser', 'resolveServiceAsUser')\n" +
                        "local any, named = call(implicit), call(explicit)\n" +
                        "local r1, old, new = after(hook, any)\n" +
                        "return r1, old, new, any.result == visible, after(hook, named), named.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.hidden", result.arg(2).tojstring());
        assertEquals("com.visible", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());
    }

    // Lists of components keep system apps, which the Android framework uses, lists of packages do not
    @Test
    public void componentsOfSystemAppsStay() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String items = "local own = { uid = 10001 }\n" +
                "local system = { uid = 10010, system = true }\n" +
                "local other = { uid = 10060 }\n";
        Varargs activities = Scripts.run(globals, items +
                "local list = listof({ { activityInfo = { applicationInfo = own } },\n" +
                "  { activityInfo = { applicationInfo = system } }, { activityInfo = { applicationInfo = other } } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "local restricted = after(hookof('PackageManager.queryIntentActivities.33'), param)\n" +
                "return restricted, list:size(), list:get(1).activityInfo.applicationInfo == system");
        assertTrue(activities.arg(1).toboolean());
        assertEquals(2, activities.arg(2).toint());
        assertTrue(activities.arg(3).toboolean());

        Varargs providers = Scripts.run(globals, items +
                "local list = listof({ { applicationInfo = system }, { applicationInfo = other } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.queryContentProviders'), param)\n" +
                "return list:size()");
        assertEquals(1, providers.arg(1).toint());

        Varargs packages = Scripts.run(globals, items +
                "local list = listof({ { applicationInfo = own }, { applicationInfo = system } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.getInstalledPackages'), param)\n" +
                "return list:size()");
        assertEquals(1, packages.arg(1).toint());

        // Properties have a package, like the lookups of single packages
        Varargs properties = Scripts.run(globals,
                "local function property(name) return { getPackageName = function(self) return name end } end\n" +
                        "local list = listof({ property('com.android.vending'), property('com.example.bank') })\n" +
                        "local param = paramof({}, { ['com.android.vending'] = true })\n" +
                        "param.result = list\n" +
                        "after(hookof('PackageManager.queryApplicationProperty'), param)\n" +
                        "return list:size(), list:get(0):getPackageName()");
        assertEquals(1, properties.arg(1).toint());
        assertEquals("com.android.vending", properties.arg(2).tojstring());

        // Device admins are components, by their package
        Varargs admins = Scripts.run(globals,
                "local function admin(name) return { getPackageName = function(self) return name end } end\n" +
                        "local list = listof({ admin('com.google.android.gms'), admin('com.example.mdm') })\n" +
                        "local param = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "param.result = list\n" +
                        "after(hookof('DevicePolicyManager.getActiveAdmins'), param)\n" +
                        "return list:size(), list:get(0):getPackageName()");
        assertEquals(1, admins.arg(1).toint());
        assertEquals("com.google.android.gms", admins.arg(2).tojstring());

        // The hidden overloads of the widgets and the admins, like their public twins
        Varargs overloads = Scripts.run(globals,
                "local function widget(name) return { provider = { getPackageName = function(self) return name end } } end\n" +
                        "local function admin(name) return { getPackageName = function(self) return name end } end\n" +
                        "local widgets = listof({ widget('com.hidden'), widget('com.visible') })\n" +
                        "local w = paramof({}, { ['com.visible'] = true })\n" +
                        "w.result = widgets\n" +
                        "local a = paramof({}, {})\n" +
                        "a.result = listof({ admin('com.example.mdm') })\n" +
                        "return after(hookof('AppWidgetManager.getInstalledProvidersForProfile.category'), w), widgets:size(),\n" +
                        "  after(hookof('DevicePolicyManager.getActiveAdminsAsUser'), a), a.result == nil");
        assertTrue(overloads.arg(1).toboolean());
        assertEquals(1, overloads.arg(2).toint());
        assertTrue(overloads.arg(3).toboolean());
        assertTrue(overloads.arg(4).toboolean());

        // The search providers of the global search, by the package of their screen
        Varargs searches = Scripts.run(globals,
                "local function searchable(name) return { getSearchActivity = function(self)\n" +
                        "  return { getPackageName = function(self) return name end } end } end\n" +
                        "local list = listof({ searchable('com.hidden'), searchable('com.google.android.googlequicksearchbox') })\n" +
                        "local param = paramof({}, { ['com.google.android.googlequicksearchbox'] = true })\n" +
                        "param.result = list\n" +
                        "local activities = listof({ { activityInfo = { applicationInfo = { uid = 10060 } } },\n" +
                        "  { activityInfo = { applicationInfo = { uid = 10010, system = true } } } })\n" +
                        "local global = paramof({}, {})\n" +
                        "global.result = activities\n" +
                        "return after(hookof('SearchManager.getSearchablesInGlobalSearch'), param), list:size(),\n" +
                        "  after(hookof('SearchManager.getGlobalSearchActivities'), global), activities:size()");
        assertTrue(searches.arg(1).toboolean());
        assertEquals(1, searches.arg(2).toint());
        assertTrue(searches.arg(3).toboolean());
        assertEquals(1, searches.arg(4).toint());

        // The activities of a hidden package are what Android gives for a package which is not installed
        Varargs launcher = Scripts.run(globals,
                "local list = listof({ { getApplicationInfo = function(self) return { uid = 10060 } end } })\n" +
                        "local missing = listof({})\n" +
                        "local param = paramof({ [1] = 'user' }, {})\n" +
                        "param.result = list\n" +
                        "param.getThis = function() return { getActivityList = function(self, name, user)\n" +
                        "  asked = name .. ' ' .. user return missing end } end\n" +
                        "param.unhooked = function(self, fn) return fn() end\n" +
                        "return after(hookof('LauncherApps.getActivityList'), param), param.result == missing, asked");
        assertTrue(launcher.arg(1).toboolean());
        assertTrue(launcher.arg(2).toboolean());
        assertEquals("android.nonexistent.package user", launcher.arg(3).tojstring());

        // The tasks of the device keep those of visible apps, by their first or top activity
        Varargs tasks = Scripts.run(globals,
                "local function comp(name) return { getPackageName = function(self) return name end } end\n" +
                        "local list = listof({ { baseActivity = comp('com.hidden.launcher') }, { topActivity = comp('com.visible') }, {} })\n" +
                        "local param = paramof({}, { ['com.visible'] = true, ['test.app'] = true })\n" +
                        "param.getPackageName = function() return 'test.app' end\n" +
                        "param.result = list\n" +
                        "return after(hookof('ActivityManager.getRunningTasks.27'), param), list:size()");
        assertTrue(tasks.arg(1).toboolean());
        assertEquals(2, tasks.arg(2).toint());

        // The launchers: the hidden ones leave the list of the app, a hidden default launcher is none
        Varargs homes = Scripts.run(globals,
                "local function home(uid, system) return { activityInfo = { applicationInfo = { uid = uid, system = system } } } end\n" +
                        "local activities = listof({ home(10060), home(10010, true) })\n" +
                        "local param = paramof({ [0] = activities }, {})\n" +
                        "param.result = { getPackageName = function(self) return 'com.hidden.launcher' end }\n" +
                        "local restricted, old = after(hookof('PackageManager.getHomeActivities'), param)\n" +
                        "local none = paramof({ [0] = listof({ home(10010, true) }) }, {})\n" +
                        "return restricted, old, param.result, activities:size(), after(hookof('PackageManager.getHomeActivities'), none)");
        assertTrue(homes.arg(1).toboolean());
        assertEquals("com.hidden.launcher", homes.arg(2).tojstring());
        assertTrue(homes.arg(3).isnil());
        assertEquals(1, homes.arg(4).toint());
        assertFalse(homes.arg(5).toboolean());

        // The phone accounts of VoIP apps, by the package of their connection service
        Varargs accounts = Scripts.run(globals,
                "local function handle(name) return { getComponentName = function(self)\n" +
                        "  return { getPackageName = function(self) return name end } end } end\n" +
                        "local list = listof({ handle('com.hidden.voip'), handle('com.visible.voip') })\n" +
                        "local param = paramof({}, { ['com.visible.voip'] = true })\n" +
                        "param.result = list\n" +
                        "return after(hookof('TelecomManager.getSelfManagedPhoneAccounts'), param), list:size(),\n" +
                        "  list:get(0):getComponentName():getPackageName()");
        assertTrue(accounts.arg(1).toboolean());
        assertEquals(1, accounts.arg(2).toint());
        assertEquals("com.visible.voip", accounts.arg(3).tojstring());

        // The processes in error of other apps go, and none left is null, like Android answers without errors
        Varargs errors = Scripts.run(globals,
                "local list = listof({ { uid = 10060 }, { uid = 10001 } })\n" +
                        "local param = paramof({}, {})\n" +
                        "param.result = list\n" +
                        "local none = paramof({}, {})\n" +
                        "none.result = listof({ { uid = 10060 } })\n" +
                        "return after(hookof('ActivityManager.getProcessesInErrorState'), param), list:size(),\n" +
                        "  after(hookof('ActivityManager.getProcessesInErrorState'), none), none.result == nil");
        assertTrue(errors.arg(1).toboolean());
        assertEquals(1, errors.arg(2).toint());
        assertTrue(errors.arg(3).toboolean());
        assertTrue(errors.arg(4).toboolean());

        // No admins left is null, like Android answers without admins (DevicePolicyManagerService.getActiveAdmins)
        Varargs noadmins = Scripts.run(globals,
                "local list = listof({ { getPackageName = function(self) return 'com.example.mdm' end } })\n" +
                        "local param = paramof({}, {})\n" +
                        "param.result = list\n" +
                        "return after(hookof('DevicePolicyManager.getActiveAdmins'), param), param.result == nil");
        assertTrue(noadmins.arg(1).toboolean());
        assertTrue(noadmins.arg(2).toboolean());

        // Instrumentations stay when both the test runner and the package it tests are visible
        Varargs instrumentations = Scripts.run(globals,
                "local function instrumentation(name, target) return { packageName = name, targetPackage = target } end\n" +
                        "local list = listof({ instrumentation('com.example.test', 'com.example'),\n" +
                        "  instrumentation('com.example.test', 'com.example.bank'),\n" +
                        "  instrumentation('com.example.bank.test', 'com.example') })\n" +
                        "local param = paramof({}, { ['com.example'] = true, ['com.example.test'] = true })\n" +
                        "param.result = list\n" +
                        "after(hookof('PackageManager.queryInstrumentation'), param)\n" +
                        "return list:size(), list:get(0).targetPackage");
        assertEquals(1, instrumentations.arg(1).toint());
        assertEquals("com.example", instrumentations.arg(2).tojstring());

        // Processes of the system manage all apps
        Varargs managed = Scripts.run(globals, items +
                "local list = listof({ { applicationInfo = own }, { applicationInfo = other } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "param.system = true\n" +
                "return after(hookof('PackageManager.getInstalledPackages'), param), list:size()");
        assertFalse(managed.arg(1).toboolean());
        assertEquals(2, managed.arg(2).toint());
    }

    // Only visible packages are changed packages; no changes is null
    @Test
    public void changedPackages() throws Exception {
        Globals globals = load("packagemanager_getchangedpackages");
        String changed = "local result = { names = listof({ 'com.android.vending', 'com.example.bank' }),\n" +
                "  getPackageNames = function(self) return self.names end,\n" +
                "  getSequenceNumber = function(self) return 5 end }\n" +
                "local hook = hookof('PackageManager.getChangedPackages')\n";
        Varargs some = Scripts.run(globals, changed +
                "local param = paramof({ [0] = 0 }, { ['com.android.vending'] = true })\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result.sequence, param.result.names:size(), param.result.names:get(0)");
        assertTrue(some.arg(1).toboolean());
        assertEquals(5, some.arg(2).toint());
        assertEquals(1, some.arg(3).toint());
        assertEquals("com.android.vending", some.arg(4).tojstring());

        Varargs none = Scripts.run(globals, changed +
                "local param = paramof({ [0] = 0 }, {})\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result");
        assertTrue(none.arg(1).toboolean());
        assertTrue(none.arg(2).isnil());

        Varargs all = Scripts.run(globals, changed +
                "local param = paramof({ [0] = 0 }, { ['com.android.vending'] = true, ['com.example.bank'] = true })\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result == result");
        assertFalse(all.arg(1).toboolean());
        assertTrue(all.arg(2).toboolean());
    }

    // A permission which a hidden app defines, like com.example.bank.permission.C2D_MESSAGE, is not found,
    // like of an app which is not installed; those of Android and of visible apps stay
    @Test
    public void permissionsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = load("package_resolve");
        Varargs single = Scripts.run(globals,
                "local param = paramof({ [0] = 'com.example.bank.permission.C2D_MESSAGE', [1] = 0 }, {})\n" +
                        "param.result = { name = 'com.example.bank.permission.C2D_MESSAGE', packageName = 'com.example.bank' }\n" +
                        "local restricted, old = after(hookof('PackageManager.getPermissionInfo', 'getPermissionInfo'), param)\n" +
                        "return restricted, old, param.result.exception, param.result.message");
        assertTrue(single.arg(1).toboolean());
        assertEquals("com.example.bank", single.arg(2).tojstring());
        assertEquals("android.content.pm.PackageManager$NameNotFoundException", single.arg(3).tojstring());
        assertEquals("com.example.bank.permission.C2D_MESSAGE", single.arg(4).tojstring());

        Varargs own = Scripts.run(globals,
                "local param = paramof({ [0] = 'android.permission.CAMERA', [1] = 0 }, { android = true })\n" +
                        "param.result = { name = 'android.permission.CAMERA', packageName = 'android' }\n" +
                        "return after(hookof('PackageManager.getPermissionInfo', 'getPermissionInfo'), param), param.result.name");
        assertFalse(own.arg(1).toboolean());
        assertEquals("android.permission.CAMERA", own.arg(2).tojstring());

        Varargs list = Scripts.run(globals,
                "local list = listof({ { packageName = 'android' }, { packageName = 'com.example.bank' } })\n" +
                        "local param = paramof({ [0] = nil, [1] = 0 }, { android = true })\n" +
                        "param.result = list\n" +
                        "local restricted = after(hookof('PackageManager.queryPermissionsByGroup', 'queryPermissionsByGroup'), param)\n" +
                        "return restricted, list:size(), list:get(0).packageName");
        assertTrue(list.arg(1).toboolean());
        assertEquals(1, list.arg(2).toint());
        assertEquals("android", list.arg(3).tojstring());

        // The permissions of a group of a hidden app: the group is looked up under a name no app defines, so the
        // exception is of Android, like for getPermissionGroupInfo; those of a group of Android stay
        String byGroup = "values = {}\n" +
                "hook = hookof('PackageManager.queryPermissionsByGroup', 'queryPermissionsByGroup')\n" +
                "param = paramof({ [0] = '%s', [1] = 0 }, { android = true }, values)\n" +
                "param.result = listof({ { packageName = 'android' } })\n" +
                "function param:unhooked(fn) return fn() end\n" +
                "function param:getThis() return { getPermissionGroupInfo = function(self, name, flags)\n" +
                "  assert(flags == 0)\n" +
                "  if name ~= 'android.permission-group.CAMERA' and name ~= 'com.example.bank.permission-group.API' then\n" +
                "    error('NameNotFoundException') end\n" +
                "  return { packageName = (name == 'android.permission-group.CAMERA' and 'android' or 'com.example.bank') } end } end\n" +
                "local restricted, old = before(hook, param)\n" +
                "return restricted, old, param.args[0]";
        Varargs hidden = Scripts.run(globals, String.format(byGroup, "com.example.bank.permission-group.API"));
        assertTrue(hidden.arg(1).toboolean());
        assertEquals("com.example.bank", hidden.arg(2).tojstring());
        String substitute = hidden.arg(3).tojstring();
        assertTrue(substitute, substitute.matches("com\\.example\\.bank\\.permission-group\\.API\\.x[0-9]{9}"));
        ProbeException genuine = new ProbeException(substitute);
        globals.set("genuine", CoerceJavaToLua.coerce(genuine));
        Varargs thrown = Scripts.run(globals, "param.exception = genuine\nreturn after(hook, param), param.result");
        assertFalse(thrown.arg(1).toboolean()); // Reported by before
        Throwable fake = (Throwable) thrown.arg(2).touserdata();
        assertEquals(ProbeException.class, fake.getClass());
        assertEquals("com.example.bank.permission-group.API", fake.getMessage());
        assertArrayEquals(genuine.getStackTrace(), fake.getStackTrace());

        Varargs camera = Scripts.run(globals, String.format(byGroup, "android.permission-group.CAMERA") +
                ", after(hook, param)");
        assertFalse(camera.arg(1).toboolean());
        assertEquals("android.permission-group.CAMERA", camera.arg(3).tojstring());
        assertFalse(camera.arg(4).toboolean());
        // A group no app defines, and all permissions without a group, are asked as they are
        Varargs none = Scripts.run(globals, String.format(byGroup, "com.example.UNDEFINED"));
        assertFalse(none.arg(1).toboolean());
        assertEquals("com.example.UNDEFINED", none.arg(3).tojstring());
        Varargs ungrouped = Scripts.run(globals, "local param = paramof({ [1] = 0 }, {})\n" +
                "return before(hookof('PackageManager.queryPermissionsByGroup', 'queryPermissionsByGroup'), param)");
        assertFalse(ungrouped.arg(1).toboolean());
        // The copies of the substitutes stay the same, so both scripts use the same suffix
        assertEquals(Scripts.function("package_probe", "suffixof"), Scripts.function("package_resolve", "suffixof"));
        assertEquals(Scripts.function("package_probe", "replace"), Scripts.function("package_resolve", "replace"));
    }

    // A permission or a group which a hidden app defines is looked up under a name no app defines, so Android itself
    // throws, with its own stack trace instead of one of the script; other exceptions and other hooks stay
    @Test
    public void permissionLookupsThrowLikeAndroid() throws Exception {
        Globals globals = load("package_resolve");
        String lookup = "values = {}\n" +
                "hook = hookof('PackageManager.%1$s', '%1$s')\n" +
                "param = paramof({ [0] = '%2$s', [1] = 128 }, { android = true }, values)\n" +
                "function param:unhooked(fn) return fn() end\n" +
                "local function find(self, name, flags)\n" +
                "  local owners = { ['com.example.bank.permission.C2D_MESSAGE'] = 'com.example.bank',\n" +
                "    ['com.example.bank.permission-group.API'] = 'com.example.bank', ['android.permission.CAMERA'] = 'android' }\n" +
                "  if owners[name] == nil then error('NameNotFoundException') end\n" +
                "  return { packageName = owners[name] } end\n" +
                "function param:getThis() return { getPermissionInfo = find, getPermissionGroupInfo = find } end\n" +
                "local restricted, old = before(hook, param)\n" +
                "return restricted, old, param.args[0], param.args[1]";
        String[][] calls = {{"getPermissionInfo", "com.example.bank.permission.C2D_MESSAGE"},
                {"getPermissionGroupInfo", "com.example.bank.permission-group.API"}};
        for (String[] call : calls) {
            Varargs before = Scripts.run(globals, String.format(lookup, call[0], call[1]));
            assertTrue(call[0], before.arg(1).toboolean());
            assertEquals(call[0], "com.example.bank", before.arg(2).tojstring());
            String substitute = before.arg(3).tojstring();
            assertTrue(substitute, substitute.startsWith(call[1] + ".x"));
            assertEquals(call[0], 128, before.arg(4).toint());

            ProbeException genuine = new ProbeException(substitute);
            genuine.initCause(new RuntimeException("Remote stack trace"));
            globals.set("genuine", CoerceJavaToLua.coerce(genuine));
            Varargs after = Scripts.run(globals, "param.exception = genuine\nreturn after(hook, param), param.result");
            assertFalse(call[0], after.arg(1).toboolean());
            Throwable fake = (Throwable) after.arg(2).touserdata();
            assertEquals(call[0], ProbeException.class, fake.getClass());
            assertEquals(call[0], call[1], fake.getMessage());
            assertArrayEquals(call[0], genuine.getStackTrace(), fake.getStackTrace());
            assertSame(call[0], genuine.getCause(), fake.getCause());
        }

        // A permission of Android is asked as it is, and an exception for a name the app asked for stays
        Varargs camera = Scripts.run(globals, String.format(lookup, "getPermissionInfo", "android.permission.CAMERA"));
        assertFalse(camera.arg(1).toboolean());
        assertEquals("android.permission.CAMERA", camera.arg(3).tojstring());
        Varargs missing = Scripts.run(globals, String.format(lookup, "getPermissionInfo", "com.example.UNDEFINED"));
        assertFalse(missing.arg(1).toboolean());
        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException("com.example.UNDEFINED")));
        Varargs stays = Scripts.run(globals, "param.exception = genuine\nreturn after(hook, param), param.result");
        assertFalse(stays.arg(1).toboolean());
        assertTrue(stays.arg(2).isnil());

        // The other hooks of the script have nothing to look up
        Varargs other = Scripts.run(globals, "local param = paramof({}, {})\n" +
                "return before(hookof('ContentResolver.getSyncAdapterTypes', 'getSyncAdapterTypes'), param)");
        assertFalse(other.arg(1).toboolean());
    }

    // Permission groups name the app which defines them too, like getAllPermissionGroups an app's own group
    @Test
    public void permissionGroupsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = load("package_resolve");
        Varargs single = Scripts.run(globals,
                "local param = paramof({ [0] = 'com.example.bank.permission-group.API', [1] = 0 }, {})\n" +
                        "param.result = { name = 'com.example.bank.permission-group.API', packageName = 'com.example.bank' }\n" +
                        "local restricted, old = after(hookof('PackageManager.getPermissionGroupInfo', 'getPermissionGroupInfo'), param)\n" +
                        "return restricted, old, param.result.exception, param.result.message");
        assertTrue(single.arg(1).toboolean());
        assertEquals("com.example.bank", single.arg(2).tojstring());
        assertEquals("android.content.pm.PackageManager$NameNotFoundException", single.arg(3).tojstring());
        assertEquals("com.example.bank.permission-group.API", single.arg(4).tojstring());

        Varargs list = Scripts.run(globals,
                "local list = listof({ { packageName = 'android' }, { packageName = 'com.example.bank' } })\n" +
                        "local param = paramof({ [0] = 0 }, { android = true })\n" +
                        "param.result = list\n" +
                        "local restricted = after(hookof('PackageManager.getAllPermissionGroups', 'getAllPermissionGroups'), param)\n" +
                        "return restricted, list:size(), list:get(0).packageName");
        assertTrue(list.arg(1).toboolean());
        assertEquals(1, list.arg(2).toint());
        assertEquals("android", list.arg(3).tojstring());
    }

    // The hidden methods for a user have the items of their public twins, also when filtered twice
    @Test
    public void listsForUser() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String items = "local own = { uid = 10001 }\n" +
                "local system = { uid = 10010, system = true }\n" +
                "local other = { uid = 10060 }\n";
        Varargs services = Scripts.run(globals, items +
                "local list = listof({ { serviceInfo = { applicationInfo = own } },\n" +
                "  { serviceInfo = { applicationInfo = system } }, { serviceInfo = { applicationInfo = other } } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "local restricted = after(hookof('PackageManager.queryIntentServicesAsUser.33'), param)\n" +
                "local again = after(hookof('PackageManager.queryIntentServices.33'), param)\n" +
                "return restricted, again, list:size()");
        assertTrue(services.arg(1).toboolean());
        assertFalse(services.arg(2).toboolean());
        assertEquals(2, services.arg(3).toint());

        Varargs packages = Scripts.run(globals, items +
                "local list = listof({ { applicationInfo = own }, { applicationInfo = other } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.getInstalledPackagesAsUser'), param)\n" +
                "return list:size(), list:get(0).applicationInfo == own");
        assertEquals(1, packages.arg(1).toint());
        assertTrue(packages.arg(2).toboolean());
    }

    // A document picked from a provider with several authorities: the grant of one of them counts, nothing without one
    @Test
    public void pickedDocumentsOfAProviderWithAuthorities() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        Varargs result = Scripts.run(globals,
                "local grants = { { getUri = function(self) return { getAuthority = function(self) return 'com.example.docs.legacy' end } end } }\n" +
                        "local param = { getApplicationContext = function(self) return { getContentResolver = function(self)\n" +
                        "  return { getPersistedUriPermissions = function(self) return { size = function(self) return #grants end,\n" +
                        "    get = function(self, index) return grants[index + 1] end } end } end } end }\n" +
                        "return picked(param, 'com.example.docs;com.example.docs.legacy'), picked(param, 'com.example.docs'),\n" +
                        "  picked(param, nil)");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
    }

    // A provider of a hidden app is not found, also for a user
    @Test
    public void providerForUser() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local param = paramof({ [0] = 'com.example.bank.provider' }, {})\n" +
                        "param.result = { packageName = 'com.example.bank' }\n" +
                        "local restricted, pkg = after(hookof('PackageManager.resolveContentProviderAsUser', 'resolveContentProviderAsUser'), param)\n" +
                        "return restricted, param.result, pkg");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals("com.example.bank", result.arg(3).tojstring());
    }
}
