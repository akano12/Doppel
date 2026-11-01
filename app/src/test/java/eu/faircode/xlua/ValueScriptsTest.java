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
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The small scripts of ids, values, arguments and the SDKs of apps, which before had no test, with Lua stand-ins
// for the hook, the parameter and the Android objects, like LocationScriptTest; luajava is a stand-in where
// the script makes Android objects, which are stubs in unit tests
public class ValueScriptsTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method, class)",
            "  return { getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class end }",
            "end",
            "settings = {}",
            "values = setmetatable({}, { __mode = 'k' })",
            "function paramof(result, args, this, exception)",
            "  return { result = result, args = args or {}, set = false,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    setArgument = function(self, index, value) self.args[index] = value self.set = true end,",
            "    getThis = function(self) return this end,",
            "    getException = function(self) return exception end,",
            "    getSetting = function(self, name) return settings[name] end,",
            "    getPackageName = function(self) return 'com.example' end,",
            "    getApplicationContext = function(self) return 'context' end,",
            "    findClass = function(self, name) return name end,",
            "    putValue = function(self, name, value, scope)",
            "      values[scope] = values[scope] or {} values[scope][name] = value end,",
            "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end,",
            "    unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end }",
            "end",
            // Runs after() or before() and returns whether restricted, whether set and the result
            "function after_(hook, param) return after(hook, param), param.set, param.result end",
            "function before_(hook, param) return before(hook, param), param.set, param.result end");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, Scripts.ARRAYOF + "\n" + STUBS);
    }

    private static void check(Globals globals, String lua, boolean restricted, boolean set, String result) {
        Varargs varargs = Scripts.run(globals, lua);
        assertEquals(lua, restricted, varargs.arg(1).toboolean());
        assertEquals(lua, set, varargs.arg(2).toboolean());
        assertEquals(lua, result, varargs.arg(3).isnil() ? null : varargs.arg(3).tojstring());
    }

    // getDeviceId gives the id of the phone type, getImei and getMeid only their own, nothing without a setting
    @Test
    public void deviceIdOfThePhoneType() throws Exception {
        Globals globals = load("value_device_id");
        Scripts.run(globals, "gsm = { getPhoneType = function(self) return 1 end }\n" +
                "cdma = { getPhoneType = function(self) return 2 end }\n" +
                "settings['value.imei'] = 'fake-imei' settings['value.meid'] = 'fake-meid'");
        for (String[] c : new String[][]{
                {"gsm", "getDeviceId", "fake-imei"}, {"gsm", "getImei", "fake-imei"}, {"gsm", "getPrimaryImei", "fake-imei"},
                {"gsm", "getMeid", null},
                {"cdma", "getDeviceId", "fake-meid"}, {"cdma", "getMeid", "fake-meid"},
                {"cdma", "getImei", null}, {"cdma", "getPrimaryImei", null}})
            check(globals, "return after_(hookof('" + c[1] + "'), paramof('real', {}, " + c[0] + "))", true, true, c[2]);

        // The fake already, like getImei() calling the hooked getImei(slot): not reported twice
        check(globals, "return after_(hookof('getImei'), paramof('fake-imei', {}, gsm))", false, false, "fake-imei");

        // Without a setting the real id is gone; no id and no setting stays
        Scripts.run(globals, "settings = {}");
        check(globals, "return after_(hookof('getImei'), paramof('real', {}, gsm))", true, true, null);
        check(globals, "return after_(hookof('getImei'), paramof(nil, {}, gsm))", false, false, null);
        // Apps without permission keep the exception, static calls stay
        check(globals, "return after_(hookof('getImei'), paramof('real', {}, gsm, 'security'))", false, false, "real");
        check(globals, "return after_(hookof('getImei'), paramof('real', {}, nil))", false, false, "real");
    }

    // The address of the phone account of a SIM is its number (tel:): the fake number, else none; the accounts of the app
    // itself and other addresses, like sip: of calling apps, stay
    @Test
    public void phoneAccountAddressIsReplaced() throws Exception {
        Globals globals = load("phone_account_address");
        Scripts.run(globals, "luajava = { bindClass = function(name)\n" +
                "  assert(name == 'android.net.Uri', name)\n" +
                "  return { fromParts = function(self, scheme, part, fragment) return uriof(scheme, part) end }\n" +
                "end }\n" +
                "function uriof(scheme, part) return { scheme = scheme, part = part,\n" +
                "  getScheme = function(self) return self.scheme end, toString = function(self) return self.scheme .. ':' .. self.part end } end\n" +
                "function accountof(pkg) return { getAccountHandle = function(self)\n" +
                "  return { getComponentName = function(self) return { getPackageName = function(self) return pkg end } end } end } end");
        Varargs sim = Scripts.run(globals, "local param = paramof(uriof('tel', '+3161234'), {}, accountof('com.android.phone'))\n" +
                "return after(nil, param), param.result");
        assertTrue(sim.arg(1).toboolean());
        assertTrue(sim.arg(2).isnil());
        Varargs fake = Scripts.run(globals, "settings['value.phone_number'] = '+3100000'\n" +
                "local param = paramof(uriof('tel', '+3161234'), {}, accountof('com.android.phone'))\n" +
                "return after(nil, param), param.result.part\n");
        assertTrue(fake.arg(1).toboolean());
        assertEquals("+3100000", fake.arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return after(nil, paramof(uriof('tel', '+3161234'), {}, accountof('com.example')))")
                .arg1().toboolean());
        assertFalse(Scripts.run(globals, "return after(nil, paramof(uriof('sip', 'alice@example.com'), {}, accountof('org.voip')))")
                .arg1().toboolean());
    }

    // The own device of Wi-Fi Direct, of which apps get the anonymous address, has the name users give the device,
    // often with their own name, like device_name (see settingssecure_getstring); devices around keep theirs
    @Test
    public void ownWifiDirectNameIsEmpty() throws Exception {
        Globals globals = load("wifip2pdevice_name");
        Varargs own = Scripts.run(globals, "local device = { deviceAddress = '02:00:00:00:00:00', deviceName = 'Pixel of Alice' }\n" +
                "return after(nil, paramof(device, {})), device.deviceName");
        assertTrue(own.arg(1).toboolean());
        assertEquals("", own.arg(2).tojstring());
        Varargs other = Scripts.run(globals, "local device = { deviceAddress = '6a:11:22:33:44:55', deviceName = 'TV' }\n" +
                "return after(nil, paramof(device, {})), device.deviceName");
        assertFalse(other.arg(1).toboolean());
        assertEquals("TV", other.arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return after(nil, paramof({ deviceAddress = '02:00:00:00:00:00', deviceName = '' }, {}))")
                .arg1().toboolean());
    }

    @Test
    public void phoneNumberIsReplaced() throws Exception {
        Globals globals = load("value_phone_number");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, null);
        check(globals, "return after_(nil, paramof(nil))", false, false, null);
        Scripts.run(globals, "settings['value.phone_number'] = '+3100000'");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, "+3100000");

        globals = load("value_phone_number_nonnull");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, "");
        check(globals, "return after_(nil, paramof(''))", false, false, "");
        check(globals, "return after_(nil, paramof(nil))", false, false, null);
        Scripts.run(globals, "settings['value.phone_number'] = '+3100000'");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, "+3100000");
    }

    // The Widevine ids become zeros of the same length, other properties stay
    @Test
    public void drmIdsAreZeroed() throws Exception {
        Globals globals = load("mediadrm_unique_id");
        for (String property : new String[]{"deviceUniqueId", "provisioningUniqueId"}) {
            globals.set("id", CoerceJavaToLua.coerce(new byte[]{1, 2, 3}));
            Varargs result = Scripts.run(globals, "return after_(nil, paramof(id, { [0] = '" + property + "' }))");
            assertTrue(property, result.arg1().toboolean());
            assertArrayEquals(property, new byte[3], (byte[]) result.arg(3).checkuserdata());
        }
        globals.set("id", CoerceJavaToLua.coerce(new byte[]{1, 2, 3}));
        assertFalse(Scripts.run(globals, "return after_(nil, paramof(id, { [0] = 'vendor' }))").arg1().toboolean());
        globals.set("id", CoerceJavaToLua.coerce(new byte[0]));
        assertFalse(Scripts.run(globals, "return after_(nil, paramof(id, { [0] = 'deviceUniqueId' }))").arg1().toboolean());
    }

    // The cell location and cell info events are left out, the other events stay
    @Test
    public void listenWithoutCells() throws Exception {
        Globals globals = load("telephonymanager_listen");
        Scripts.run(globals, "function listenof(variant)\n" +
                "  return { getName = function(self) return 'TelephonyManager.listen' .. (variant and '/' .. variant or '') end }\n" +
                "end");
        Varargs result = Scripts.run(globals, "local param = paramof(nil, { [0] = 'listener', [1] = 16 + 1024 + 1 })\n" +
                "return before(listenof(), param), param.args[1]");
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        check(globals, "return before_(listenof(), paramof(nil, { [0] = 'listener', [1] = 1 }))", false, false, null);

        // With the highest bit (LISTEN_BARRING_INFO) the events are a negative int, which must stay an int
        for (int events : new int[]{0x80000010, 0x80000400}) {
            globals.set("events", LuaValue.valueOf(events));
            result = Scripts.run(globals, "local param = paramof(nil, { [0] = 'listener', [1] = events })\n" +
                    "return before(listenof(), param), param.args[1]");
            assertTrue(result.arg1().toboolean());
            Object value = XParam.coerceValue(int.class, CoerceLuaToJava.coerce(result.arg(2), Object.class));
            assertEquals(Integer.toHexString(events), Integer.MIN_VALUE, value);
        }
    }

    // The call state (LISTEN_CALL_STATE, 32) comes with the number of an incoming call, like the call log: the listener
    // gets it empty, like apps without the permission of the call log; its events stay
    @Test
    public void callStateWithoutNumber() throws Exception {
        Globals globals = load("telephonymanager_listen");
        Varargs result = Scripts.run(globals, "local hooked = {}\n" +
                "function hook(object, method, fn) hooked = { object = object, method = method, fn = fn } end\n" +
                "local hook_ = { getName = function(self) return 'TelephonyManager.listen/call_log' end }\n" +
                "local param = paramof(nil, { [0] = 'listener', [1] = 32 + 16 })\n" +
                "local restricted = before(hook_, param)\n" +
                "local call = paramof(nil, { [0] = 1, [1] = '+3161234' })\n" +
                "hooked.fn('before', call)\n" +
                "return restricted, param.args[1], hooked.object, hooked.method, call.args[1], call.args[0]");
        assertTrue(result.arg(1).toboolean());
        assertEquals(48, result.arg(2).toint());
        assertEquals("listener", result.arg(3).tojstring());
        assertEquals("onCallStateChanged", result.arg(4).tojstring());
        assertEquals("", result.arg(5).tojstring());
        assertEquals(1, result.arg(6).toint());
        // Without the call state nothing is hooked
        assertFalse(Scripts.run(globals, "hook = function() error('hooked') end\n" +
                "return before({ getName = function(self) return 'TelephonyManager.listen/call_log' end },\n" +
                "  paramof(nil, { [0] = 'listener', [1] = 16 }))").arg1().toboolean());
    }

    // All events are read, so the app gets none
    @Test
    public void usageEventsAreRead() throws Exception {
        Globals globals = load("usageevents_query");
        Scripts.run(globals, "luajava = { newInstance = function(name) assert(name == 'android.app.usage.UsageEvents$Event', name) return {} end }\n" +
                "function eventsof(count)\n" +
                "  return { count = count, hasNextEvent = function(self) return self.count > 0 end,\n" +
                "    getNextEvent = function(self, event) assert(event) self.count = self.count - 1 return true end }\n" +
                "end");
        Varargs result = Scripts.run(globals, "local events = eventsof(3)\nreturn after(nil, paramof(events)), events.count");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).toint());
        assertFalse(Scripts.run(globals, "return after(nil, paramof(eventsof(0)))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return after(nil, paramof(nil))").arg1().toboolean());
    }

    // Accounts of other apps get the fake name, the accounts of the app itself keep theirs; the types are read once
    @Test
    public void accountNamesOfOtherApps() throws Exception {
        Globals globals = load("account_createfromparcel");
        Scripts.run(globals, "reads = 0\n" +
                "manager = { getAuthenticatorTypes = function(self) assert(unhooked, 'hooked lookup') reads = reads + 1\n" +
                "  return arrayof({ packageName = 'com.example', type = 'com.example.account' },\n" +
                "    { packageName = 'com.google.android.gms', type = 'com.google' }) end }\n" +
                "luajava = { bindClass = function(name) assert(name == 'android.accounts.AccountManager', name)\n" +
                "  return { get = function(self, context) assert(context == 'context') return manager end } end }");
        Varargs result = Scripts.run(globals, "local google = { type = 'com.google', name = 'me@gmail.com' }\n" +
                "local own = { type = 'com.example.account', name = 'me' }\n" +
                "local faked = after(nil, paramof(google))\n" +
                "local kept = after(nil, paramof(own))\n" +
                "settings['value.email'] = 'set@example.com'\n" +
                "local other = { type = 'com.google', name = 'other@gmail.com' }\n" +
                "after(nil, paramof(other))\n" +
                "return faked, google.name, kept, own.name, other.name, reads");
        assertTrue(result.arg(1).toboolean());
        assertEquals("private@example.com", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertEquals("me", result.arg(4).tojstring());
        assertEquals("set@example.com", result.arg(5).tojstring());
        assertEquals(1, result.arg(6).toint());
    }

    // The account types of hidden apps are left out, those of visible apps stay
    @Test
    public void accountTypesOfHiddenApps() throws Exception {
        Globals globals = load("account_authenticators");
        Scripts.run(globals, "luajava = { bindClass = function(name) assert(name == 'java.lang.reflect.Array', name)\n" +
                "  return { newInstance = function(self, class, size)\n" +
                "    assert(class == 'android.accounts.AuthenticatorDescription', class) return { length = size } end } end }\n" +
                "function typesparamof(result)\n" +
                "  local param = paramof(result)\n" +
                "  param.isPackageVisible = function(self, name) return name ~= 'com.bank' end\n" +
                "  return param\n" +
                "end");
        Varargs result = Scripts.run(globals, "local param = typesparamof(arrayof({ packageName = 'com.google.android.gms' },\n" +
                "  { packageName = 'com.bank' }, { packageName = 'com.example' }))\n" +
                "return after(nil, param), param.result.length, param.result[1].packageName, param.result[2].packageName");
        assertTrue(result.arg(1).toboolean());
        assertEquals(2, result.arg(2).toint());
        assertEquals("com.google.android.gms", result.arg(3).tojstring());
        assertEquals("com.example", result.arg(4).tojstring());
        assertFalse(Scripts.run(globals, "return after_(nil, typesparamof(arrayof({ packageName = 'com.example' })))").arg(2).toboolean());
        assertFalse(Scripts.run(globals, "return after_(nil, typesparamof(nil))").arg1().toboolean());
    }

    // The clip and its description are a plain text, without the label and the types of the real clip
    @Test
    public void clipIsPlainText() throws Exception {
        Globals globals = load("clipboard_getprimaryclip");
        Scripts.run(globals, "function clipof(count)\n" +
                "  return { getItemCount = function(self) return count end,\n" +
                "    newPlainText = function(self, label, text) return label .. ':' .. text end }\n" +
                "end");
        check(globals, "return after_(hookof('getPrimaryClip'), paramof(clipof(1)))", true, true, "text:Private");
        assertFalse(Scripts.run(globals, "return after_(hookof('getPrimaryClip'), paramof(clipof(0)))").arg(2).toboolean());
        check(globals, "return after_(hookof('getPrimaryClip'), paramof(nil))", false, false, null);

        // The description is of the same fake clip
        Scripts.run(globals, "luajava = { bindClass = function(name) assert(name == 'android.content.ClipData', name)\n" +
                "  return { newPlainText = function(self, label, text)\n" +
                "    return { getDescription = function(self) return 'description ' .. label .. ':' .. text end } end } end }");
        check(globals, "return after_(hookof('getPrimaryClipDescription'), paramof('real'))", true, true, "description text:Private");
        check(globals, "return after_(hookof('getPrimaryClipDescription'), paramof(nil))", false, false, null);
    }

    // Opening the camera throws the error of a camera disabled by policy
    @Test
    public void cameraIsDisabled() throws Exception {
        Globals globals = load("camera2_open");
        Scripts.run(globals, "luajava = { bindClass = function(name) return name end,\n" +
                "  new = function(class, reason, message) return class .. ' ' .. reason .. ' ' .. message end }");
        check(globals, "return before_(nil, paramof(nil, { [0] = '0' }))", true, true,
                "android.hardware.camera2.CameraAccessException 1 Camera \"0\" disabled by policy");
    }

    @Test
    public void genericValues() throws Exception {
        String[][] cases = {
                // script, result, restricted, fake
                {"generic_null_value", "'x'", "true", null}, {"generic_null_value", "nil", "false", null},
                {"generic_unknown_value", "'x'", "true", "unknown"}, {"generic_unknown_value", "nil", "false", null},
                {"generic_true_value", "false", "true", "true"}, {"generic_true_value", "true", "false", "true"},
                {"generic_zero_value", "5", "true", "0"}, {"generic_zero_value", "0", "false", "0"},
                {"generic_empty_text", "'x'", "true", ""}, {"generic_empty_text", "''", "false", ""},
                {"generic_empty_text", "nil", "false", null},
                {"advertisingidclient$info_getid", "'38400000-8cf0-11bd-b23e-10b96e40000d'", "true",
                        "00000000-0000-0000-0000-000000000000"},
                {"advertisingidclient$info_getid", "nil", "false", null}};
        for (String[] c : cases) {
            boolean restricted = Boolean.parseBoolean(c[2]);
            check(load(c[0]), "return after_(nil, paramof(" + c[1] + "))", restricted, restricted, c[3]);
        }
    }

    // Opt-outs by the last argument: false, also for null of a Boolean, and true; like setAnalyticsCollectionEnabled(enabled)
    // and submitPolicyGrantResult(context, granted), of which the context stays
    @Test
    public void genericArguments() throws Exception {
        String[][] cases = {
                // script, argument, restricted, argument after
                {"generic_false_argument", "true", "true", "false"}, {"generic_false_argument", "nil", "true", "false"},
                {"generic_false_argument", "false", "false", "false"},
                {"generic_true_argument", "false", "true", "true"}, {"generic_true_argument", "true", "false", "true"}};
        for (String[] c : cases) {
            Varargs single = Scripts.run(load(c[0]), "local param = paramof(nil, { [0] = " + c[1] + " })\n" +
                    "local hook = { getParameterTypes = function(self) return { length = 1 } end }\n" +
                    "return before(hook, param), param.args[0]");
            assertEquals(c[0] + " " + c[1], Boolean.parseBoolean(c[2]), single.arg1().toboolean());
            assertEquals(c[0] + " " + c[1], c[3], single.arg(2).tojstring());
            Varargs last = Scripts.run(load(c[0]), "local param = paramof(nil, { [0] = 'context', [1] = " + c[1] + " })\n" +
                    "local hook = { getParameterTypes = function(self) return { length = 2 } end }\n" +
                    "return before(hook, param), param.args[1], param.args[0]");
            assertEquals(c[0] + " " + c[1], Boolean.parseBoolean(c[2]), last.arg1().toboolean());
            assertEquals(c[0] + " " + c[1], c[3], last.arg(2).tojstring());
            assertEquals("context", last.arg(3).tojstring());
        }
    }

    @Test
    public void geofenceAtZero() throws Exception {
        Varargs result = Scripts.run(load("geofence$builder_setcircularregion"),
                "local param = paramof(nil, { [0] = 52.37, [1] = 4.89, [2] = 100 })\n" +
                        "return before(nil, param), param.args[0], param.args[1], param.args[2]");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).todouble(), 0);
        assertEquals(0, result.arg(3).todouble(), 0);
        assertEquals(0.1, result.arg(4).todouble(), 0);
    }

    // The activity is unknown, at the time of the real result
    @Test
    public void activityIsUnknown() throws Exception {
        Globals globals = load("activityrecognitionresult_extractresult");
        Scripts.run(globals, "luajava = { new = function(class, ...) return { class = class, args = { ... } } end }");
        Varargs result = Scripts.run(globals, "local real = { getTime = function(self) return 1000 end,\n" +
                "  getElapsedRealtimeMillis = function(self) return 500 end }\n" +
                "local param = paramof(real)\n" +
                "local restricted = after(nil, param)\n" +
                "local fake = param.result\n" +
                "local detected = fake.args[1]\n" +
                "return restricted, fake.class, detected.class, detected.args[1], detected.args[2], fake.args[2], fake.args[3]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.google.android.gms.location.ActivityRecognitionResult", result.arg(2).tojstring());
        assertEquals("com.google.android.gms.location.DetectedActivity", result.arg(3).tojstring());
        assertEquals(4, result.arg(4).toint());
        assertEquals(100, result.arg(5).toint());
        assertEquals(1000, result.arg(6).toint());
        assertEquals(500, result.arg(7).toint());
        check(globals, "return after_(nil, paramof(nil))", false, false, null);
    }

    // The SDKs of apps are stopped once for each instance
    @Test
    public void analyticsSdksAreStoppedOnce() throws Exception {
        Globals globals = load("analytics_getinstance");
        Varargs result = Scripts.run(globals, "local stops = {}\n" +
                "local sdk = { stop = function(self, stop, context) stops[#stops + 1] = tostring(stop) .. ' ' .. context end }\n" +
                "local hook = hookof('getInstance', 'com.appsflyer.AppsFlyerLib')\n" +
                "local first = after(hook, paramof(sdk))\n" +
                "local again = after(hook, paramof(sdk))\n" +
                "return first, again, #stops, stops[1], after(hook, paramof({}))");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals("true context", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());

        for (String[] c : new String[][]{{"com.google.firebase.analytics.FirebaseAnalytics", "setAnalyticsCollectionEnabled"},
                {"com.google.firebase.crashlytics.FirebaseCrashlytics", "setCrashlyticsCollectionEnabled"}}) {
            result = Scripts.run(globals, "local calls = {}\n" +
                    "local sdk = { " + c[1] + " = function(self, enabled) calls[#calls + 1] = enabled end }\n" +
                    "local hook = hookof('getInstance', '" + c[0] + "')\n" +
                    "local first = after(hook, paramof(sdk))\n" +
                    "local again = after(hook, paramof(sdk))\n" +
                    "return first, again, #calls, calls[1]");
            assertTrue(c[0], result.arg(1).toboolean());
            assertFalse(c[0], result.arg(2).toboolean());
            assertEquals(c[0], 1, result.arg(3).toint());
            assertFalse(c[0], result.arg(4).toboolean());
        }
    }

    // Segment opts out by the method of older versions or by the AtomicBoolean of newer ones
    @Test
    public void segmentOptsOut() throws Exception {
        Globals globals = load("segment_getinstance");
        Varargs result = Scripts.run(globals, "local out\n" +
                "local analytics = { optOut = function(self, value) out = value end }\n" +
                "return before(hookof('setSingletonInstance'), paramof(nil, { [0] = analytics })), out,\n" +
                "  after(hookof('setSingletonInstance'), paramof(analytics)), before(hookof('build'), paramof(nil, {}))");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());

        AtomicBoolean optOut = new AtomicBoolean(false);
        globals.set("flag", CoerceJavaToLua.coerce(optOut));
        assertTrue(Scripts.run(globals, "return after(hookof('build'), paramof({ optOut = flag }))").arg1().toboolean());
        assertTrue(optOut.get());
        assertFalse(Scripts.run(globals, "return after(hookof('build'), paramof({}))").arg1().toboolean());
    }
}
