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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
                {"gsm", "getMeid", null}, {"gsm", "getDeviceIdGemini", "fake-imei"}, {"gsm", "getDeviceIdDs", "fake-imei"},
                {"cdma", "getDeviceIdGemini", "fake-meid"},
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

    // The software version of the IMEI is a common 2-digit one, not null, which a phone with a modem does not give;
    // no version, the "0" of CDMA phones, the fake of the overload with a slot and the exception stay
    @Test
    public void deviceSoftwareVersionIsPlausible() throws Exception {
        Globals globals = load("value_device_svn");
        check(globals, "return after_(nil, paramof('23'))", true, true, "01");
        check(globals, "return after_(nil, paramof(nil))", false, false, null);
        check(globals, "return after_(nil, paramof('0'))", false, false, "0");
        check(globals, "return after_(nil, paramof('01'))", false, false, "01");
        check(globals, "return after_(nil, paramof('23', {}, nil, 'security'))", false, false, "23");
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
                "  getScheme = function(self) return self.scheme end, getSchemeSpecificPart = function(self) return self.part end,\n" +
                "  toString = function(self) return self.scheme .. ':' .. self.part end } end\n" +
                "function accountof(pkg) return { getAccountHandle = function(self)\n" +
                "  return { getComponentName = function(self) return { getPackageName = function(self) return pkg end } end } end } end");
        // Without a fake number an empty one (tel:), never null, which Android does not give for a SIM
        Varargs sim = Scripts.run(globals, "local param = paramof(uriof('tel', '+3161234'), {}, accountof('com.android.phone'))\n" +
                "return after(nil, param), param.result.scheme, param.result.part");
        assertTrue(sim.arg(1).toboolean());
        assertEquals("tel", sim.arg(2).tojstring());
        assertEquals("", sim.arg(3).tojstring());
        assertFalse(Scripts.run(globals, "return after(nil, paramof(uriof('tel', ''), {}, accountof('com.android.phone')))")
                .arg1().toboolean());
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

    // The phone accounts of another package, like the SIM accounts of com.android.phone, whose ids are the ICC IDs, are
    // none; those of the app itself, an empty list and no list (the exception or ParceledListSlice of a patched Android) stay
    @Test
    public void phoneAccountsOfOtherPackagesAreNone() throws Exception {
        Globals globals = load("phone_accounts_package");
        Scripts.run(globals, "function listof(empty) return { isEmpty = function(self) return empty end } end\n" +
                "function accountsof(result, pkg) local param = paramof(result, { [0] = pkg })\n" +
                "  param.isInstance = function(self, object, name)\n" +
                "    return name == 'java.util.List' and type(object) == 'table' and object.isEmpty ~= nil end\n" +
                "  return param end");
        for (String pkg : new String[]{"'com.android.phone'", "nil"}) {
            Varargs other = Scripts.run(globals, "local param = accountsof(listof(false), " + pkg + ")\n" +
                    "return after(nil, param), param.result");
            assertTrue(pkg, other.arg(1).toboolean());
            assertEquals(pkg, java.util.ArrayList.class, other.arg(2).checkuserdata().getClass());
            assertTrue(pkg, ((java.util.List<?>) other.arg(2).checkuserdata()).isEmpty());
        }
        for (String stays : new String[]{"listof(false), 'com.example'", "listof(true), 'com.android.phone'",
                "nil, 'com.android.phone'", "{ getList = function(self) end }, 'com.android.phone'"})
            assertTrue(stays, Scripts.run(globals, "local param = accountsof(" + stays + ")\n" +
                    "return after(nil, param) == false and param.set == false").arg1().toboolean());
    }

    // Parcels as lists of typed values, a position for each value, which a SubscriptionInfo and a PhoneAccount write and
    // read like Android; a tail value stands for the fields after the faked ones, which are copied as they are
    private static final String PARCELS = String.join("\n",
            "parcels = {}",
            "function parcelof() local p = { items = {}, pos = 0 } parcels[#parcels + 1] = p",
            "  function p:put(kind, value) self.pos = self.pos + 1 self.items[self.pos] = { kind, value } end",
            "  function p:get(kind) self.pos = self.pos + 1 local item = self.items[self.pos]",
            "    assert(item ~= nil and item[1] == kind, kind) return item[2] end",
            "  function p:writeInt(v) self:put('int', v) end function p:readInt() return self:get('int') end",
            "  function p:writeString(v) self:put('string', v) end function p:readString() return self:get('string') end",
            "  function p:dataPosition() return self.pos end function p:setDataPosition(pos) self.pos = pos end",
            "  function p:dataSize() return #self.items end function p:recycle() self.recycled = true end",
            "  function p:dataAvail() return #self.items - self.pos end function p:readParcelable(loader) return self:get('bitmap') end",
            "  function p:createByteArray() return self:get('bytes') end function p:readLong() return self:get('long') end",
            "  function p:appendFrom(other, offset, length)",
            "    for i = offset + 1, offset + length do self.pos = self.pos + 1 self.items[self.pos] = other.items[i] end end",
            "  return p end",
            "function creatorof(kind) return { createFromParcel = function(self, p) return p:get(kind) end } end",
            "function uriof(scheme, part) return { scheme = scheme, part = part,",
            "  getScheme = function(self) return self.scheme end, getSchemeSpecificPart = function(self) return self.part end,",
            "  toString = function(self) return self.scheme .. ':' .. self.part end,",
            "  equals = function(self, other) return other ~= nil and self:toString() == other:toString() end,",
            "  writeToParcel = function(self, p, flags) p:put('uri', self) end } end",
            "function handleof(pkg) return { getComponentName = function(self)",
            "  return { getPackageName = function(self) return pkg end } end } end",
            // Writes and reads the fields like Android, a ROM writes a field of its own before the number; Android 9 writes
            // the card id last, after the roaming, MCC, MNC, country, icon, embedded and access rules, Android 10 and
            // later the card string there, with the MCC and MNC as text and more fields after it
            "sdk = 27",
            "function infoof(iccid, number, rom, card)",
            "  local info = { iccid = iccid, number = number, rom = rom, card = card, icon = 'bitmap' }",
            "  function info:getIccId() return self.iccid end function info:getNumber() return self.number end",
            "  function info:writeToParcel(p, flags) p:writeInt(7) p:writeString(self.iccid) p:writeInt(0) p:put('text', 'SIM')",
            "    p:put('text', 'Carrier') p:writeInt(1) p:writeInt(2) if self.rom then p:writeString('rom') end",
            "    p:writeString(self.number)",
            "    if sdk >= 28 then p:writeInt(0)",
            "      if sdk == 28 then p:writeInt(204) p:writeInt(8) else p:writeString('204') p:writeString('08') end",
            "      p:writeString('nl') p:put(self.icon, 'icon') p:writeInt(0) p:writeInt(self.rules and #self.rules or -1)",
            "      for _, rule in ipairs(self.rules or {}) do p:writeInt(1) p:put('bytes', rule) p:writeString('pkg') p:put('long', 0) end",
            "      p:writeString(self.card) end",
            "    if sdk ~= 28 then p:writeString('tail') end end",
            "  return info end",
            "infos = { createFromParcel = function(self, p) assert(p:readInt() == 7) local iccid = p:readString()",
            "  p:readInt() p:get('text') p:get('text') p:readInt() p:readInt() local info = infoof(iccid, p:readString())",
            "  if sdk >= 28 then p:readInt() if sdk == 28 then p:readInt() p:readInt() else p:readString() p:readString() end",
            "    p:readString() p:setDataPosition(p:dataPosition() + 1) p:readInt() local rules = p:readInt()",
            "    if rules >= 0 then info.rules = {} end",
            "    for i = 1, rules do assert(p:readInt() == 1) info.rules[i] = p:get('bytes') p:readString() p:get('long') end",
            "    info.card = p:readString() end",
            "  if sdk ~= 28 then assert(p:readString() == 'tail') end return info end }",
            "function accountof(pkg, address, subscription)",
            "  local phone = { handle = handleof(pkg), address = address, subscription = subscription }",
            "  function phone:getAccountHandle() return self.handle end function phone:getAddress() return self.address end",
            "  function phone:getSubscriptionAddress() return self.subscription end",
            "  function phone:writeToParcel(p, flags) p:writeInt(1) p:put('handle', self.handle)",
            "    for _, uri in ipairs({ self.address or false, self.subscription or false }) do",
            "      if uri then p:writeInt(1) uri:writeToParcel(p, 0) else p:writeInt(0) end end",
            "    p:writeString('tail') end",
            "  return phone end",
            "accounts = { createFromParcel = function(self, p) local handle = (p:readInt() ~= 0 and p:get('handle') or nil)",
            "  local uris = {} for i = 1, 2 do if p:readInt() ~= 0 then uris[i] = p:get('uri') end end",
            "  assert(p:readString() == 'tail') local phone = accountof(nil, uris[1], uris[2]) phone.handle = handle",
            "  return phone end }",
            "luajava = { bindClass = function(name)",
            "  if name == 'android.os.Parcel' then return { obtain = function(self) return parcelof() end } end",
            "  if name == 'android.text.TextUtils' then return { CHAR_SEQUENCE_CREATOR = creatorof('text') } end",
            "  if name == 'android.telecom.PhoneAccountHandle' then return { CREATOR = creatorof('handle') } end",
            "  if name == 'android.telephony.SubscriptionInfo' then return { CREATOR = infos } end",
            "  if name == 'android.telecom.PhoneAccount' then return { CREATOR = accounts } end",
            "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "  if name == 'android.graphics.Bitmap' then return { CREATOR = creatorof('bitmap') } end",
            "  assert(name == 'android.net.Uri', name)",
            "  return { CREATOR = creatorof('uri'), fromParts = function(self, scheme, part, fragment) return uriof(scheme, part) end }",
            "end }",
            "function recycled() for _, p in ipairs(parcels) do if not p.recycled then return false end end return #parcels > 0 end");

    // A SubscriptionInfo is made again from its parcel with the ICCID and the number of the getters, the rest copied
    @Test
    public void subscriptionInfoFieldsAreFaked() throws Exception {
        Globals globals = load("telephony_createfromparcel");
        Scripts.run(globals, PARCELS);
        String hook = "hookof('CREATOR:createFromParcel', 'android.telephony.SubscriptionInfo')";
        Varargs made = Scripts.run(globals, "settings['value.phone_number'] = '+3100000'\n" +
                "local param = paramof(infoof('8931440000000000001', '+31612345678'))\n" +
                "return after(" + hook + ", param), param.result.iccid, param.result.number, recycled()");
        assertTrue(made.arg(1).toboolean());
        assertEquals("", made.arg(2).tojstring());
        assertEquals("+3100000", made.arg(3).tojstring());
        assertTrue(made.arg(4).toboolean());
        // Without a fake number none, like the getter; no ICCID and no number stay, like an unknown layout
        made = Scripts.run(globals, "settings = {}\n" +
                "local param = paramof(infoof('8931440000000000001', '+31612345678'))\n" +
                "return after(" + hook + ", param), param.result.number");
        assertEquals("", made.arg(2).tojstring());
        assertTrue(Scripts.run(globals, "local param = paramof(infoof('', nil))\n" +
                "return after(" + hook + ", param) == false and param.set == false").arg1().toboolean());
        assertTrue(Scripts.run(globals, "parcels = {} local param = paramof(infoof('8931440000000000001', '+31612345678', true))\n" +
                "return after(" + hook + ", param) == false and param.set == false and recycled()").arg1().toboolean());
    }

    // On Android 9 and 10 the card id or card string of a SubscriptionInfo, which is the ICCID of a SIM card, is none like
    // the ICCID, also with F of padding and with access rules; an EID, Android 8.1 (no card value) and 11 and a parcel
    // which does not read like Android keep it, and its ICCID and number are faked all the same
    @Test
    public void subscriptionInfoCardOfSimIsFaked() throws Exception {
        Globals globals = load("telephony_createfromparcel");
        Scripts.run(globals, PARCELS);
        String hook = "hookof('CREATOR:createFromParcel', 'android.telephony.SubscriptionInfo')";
        String iccid = "8931440000000000001";
        String eid = "89049032000000000000000000000001";
        for (String[] c : new String[][]{
                // The version, the card value, the access rules, the kind of the icon and the card value made
                {"28", iccid, "nil", "bitmap", ""}, {"28", iccid + "ff", "{ 'hash' }", "bitmap", ""},
                {"29", iccid, "nil", "bitmap", ""}, {"29", iccid, "{ 'hash', 'other' }", "bitmap", ""},
                {"28", eid, "nil", "bitmap", eid}, {"29", eid, "nil", "bitmap", eid},
                {"30", iccid, "nil", "bitmap", iccid}, {"28", iccid, "nil", "odd", iccid}}) {
            String lua = "sdk = " + c[0] + " parcels = {} settings['value.phone_number'] = '+3100000'\n" +
                    "local info = infoof('" + iccid + "', '+31612345678', nil, '" + c[1] + "')\n" +
                    "info.rules = " + c[2] + " info.icon = '" + c[3] + "'\n" +
                    "local param = paramof(info)\n" +
                    "return after(" + hook + ", param), param.result.iccid, param.result.number, param.result.card,\n" +
                    "  #(param.result.rules or {}) == #(info.rules or {}), recycled()";
            Varargs made = Scripts.run(globals, lua);
            assertTrue(lua, made.arg(1).toboolean());
            assertEquals(lua, "", made.arg(2).tojstring());
            assertEquals(lua, "+3100000", made.arg(3).tojstring());
            assertEquals(lua, c[4], made.arg(4).tojstring());
            assertTrue(lua, made.arg(5).toboolean());
            assertTrue(lua, made.arg(6).toboolean());
        }
        // Android 8.1 has no card value
        Varargs made = Scripts.run(globals, "sdk = 27\n" +
                "local param = paramof(infoof('" + iccid + "', '+31612345678', nil, '" + iccid + "'))\n" +
                "return after(" + hook + ", param), param.result.iccid, param.result.card");
        assertTrue(made.arg(1).toboolean());
        assertEquals("", made.arg(2).tojstring());
        assertTrue(made.arg(3).isnil());
        Scripts.run(globals, "sdk = 27");
    }

    // A phone account of a SIM is made again from its parcel with the addresses of the getters; the own ones stay
    @Test
    public void phoneAccountFieldsAreFaked() throws Exception {
        Globals globals = load("telephony_createfromparcel");
        Scripts.run(globals, PARCELS);
        String hook = "hookof('CREATOR:createFromParcel', 'android.telecom.PhoneAccount')";
        Varargs made = Scripts.run(globals, "settings['value.phone_number'] = '+3100000'\n" +
                "local param = paramof(accountof('com.android.phone', uriof('tel', '+3161234'), uriof('tel', '+3161234')))\n" +
                "local restricted = after(" + hook + ", param)\n" +
                "return restricted, param.result:getAddress():toString(), param.result:getSubscriptionAddress():toString(),\n" +
                "  param.result:getAccountHandle():getComponentName():getPackageName(), recycled()");
        assertTrue(made.arg(1).toboolean());
        assertEquals("tel:+3100000", made.arg(2).tojstring());
        assertEquals("tel:+3100000", made.arg(3).tojstring());
        assertEquals("com.android.phone", made.arg(4).tojstring());
        assertTrue(made.arg(5).toboolean());
        // No subscription address stays none
        made = Scripts.run(globals, "local param = paramof(accountof('com.android.phone', uriof('tel', '+3161234'), nil))\n" +
                "return after(" + hook + ", param), param.result:getAddress():toString(), param.result:getSubscriptionAddress()");
        assertTrue(made.arg(1).toboolean());
        assertEquals("tel:+3100000", made.arg(2).tojstring());
        assertTrue(made.arg(3).isnil());
        // The accounts of the app itself and other addresses stay
        for (String phone : new String[]{"'com.example', uriof('tel', '+3161234')", "'org.voip', uriof('sip', 'alice@example.com')"})
            assertTrue(phone, Scripts.run(globals, "local param = paramof(accountof(" + phone + ", nil))\n" +
                    "return after(" + hook + ", param) == false and param.set == false").arg1().toboolean());
        // One rule for the getters and the fields
        assertEquals(Scripts.function("phone_account_address", "fakeaddress"),
                Scripts.function("telephony_createfromparcel", "fakeaddress"));
    }

    // The ICCID of a port of a SIM card is redacted like for an app without carrier privileges, also of a port which
    // is not active, which has none
    @Test
    public void iccIdOfPortIsRedacted() throws Exception {
        Globals globals = load("uiccportinfo_iccid");
        String redacted = "FFFFFFFFFFFFFFFFFFFF";
        check(globals, "return after_(nil, paramof('8931440000000000001'))", true, true, redacted);
        check(globals, "return after_(nil, paramof(nil))", true, true, redacted);
        check(globals, "return after_(nil, paramof('" + redacted + "'))", false, false, redacted);
        // An app with READ_PRIVILEGED_PHONE_STATE, which also gets the ports of the slots, never gets it redacted: none,
        // like the card id of a slot; without it, or when the check fails, redacted
        Scripts.run(globals, "function portof(iccid, granted) local param = paramof(iccid)\n" +
                "  param.getApplicationContext = function(self) return { checkSelfPermission = function(self, name)\n" +
                "    assert(unhooked) if granted == nil then error('dead') end\n" +
                "    return (granted and name == 'android.permission.READ_PRIVILEGED_PHONE_STATE') and 0 or -1 end } end\n" +
                "  return param end");
        check(globals, "return after_(nil, portof('8931440000000000001', true))", true, true, null);
        check(globals, "return after_(nil, portof('" + redacted + "', true))", true, true, null);
        check(globals, "return after_(nil, portof(nil, true))", false, false, null);
        check(globals, "return after_(nil, portof('8931440000000000001', false))", true, true, redacted);
        check(globals, "return after_(nil, portof('8931440000000000001', nil))", true, true, redacted);
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

    // Its address, which Android 8.1 and 9 give in the broadcast THIS_DEVICE_CHANGED and as the owner of a group of
    // which the device is the owner, is anonymous, like Android 10 makes it; the owner of a group the device joined,
    // other broadcasts and an empty address stay
    @Test
    public void ownWifiDirectAddressIsAnonymous() throws Exception {
        Globals globals = load("wifip2p_own_address");
        // An intent with only a copy of its extras, like Intent.getExtras, whose device is a new object, like one unparcelled
        // from the copied parcel, so the change reaches the intent only with replaceExtras
        Scripts.run(globals, String.join("\n",
                "function intentof(action, device)",
                "  local intent = { extras = { wifiP2pDevice = device } }",
                "  function intent:getAction() return action end",
                "  function intent:replaceExtras(bundle) self.extras = bundle.values end",
                "  function intent:getExtras()",
                "    if self.extras == nil then return nil end",
                "    local bundle = { values = {} }",
                "    for key, value in pairs(self.extras) do bundle.values[key] = { deviceAddress = value.deviceAddress } end",
                "    function bundle:getParcelable(key) return self.values[key] end",
                "    return bundle",
                "  end",
                "  return intent",
                "end",
                "function groupof(owner, device)",
                "  return { isGroupOwner = function(self) return owner end, getOwner = function(self) return device end }",
                "end",
                "intent = hookof('createFromParcel', 'android.content.Intent')",
                "group = hookof('createFromParcel', 'android.net.wifi.p2p.WifiP2pGroup')"));
        Varargs own = Scripts.run(globals, "local device = { deviceAddress = '6a:11:22:33:44:55' }\n" +
                "local result = intentof('android.net.wifi.p2p.THIS_DEVICE_CHANGED', device)\n" +
                "local restricted, old, new = after(intent, paramof(result))\n" +
                "return restricted, old, new, result.extras.wifiP2pDevice.deviceAddress, device.deviceAddress");
        assertTrue(own.arg(1).toboolean());
        assertEquals("6a:11:22:33:44:55", own.arg(2).tojstring());
        assertEquals("02:00:00:00:00:00", own.arg(3).tojstring());
        assertEquals("02:00:00:00:00:00", own.arg(4).tojstring());
        // The extras of the intent itself are replaced, not unparcelled in place
        assertEquals("6a:11:22:33:44:55", own.arg(5).tojstring());
        Varargs owner = Scripts.run(globals, "local device = { deviceAddress = '6a:11:22:33:44:55' }\n" +
                "return after(group, paramof(groupof(true, device))), device.deviceAddress");
        assertTrue(owner.arg(1).toboolean());
        assertEquals("02:00:00:00:00:00", owner.arg(2).tojstring());
        Varargs joined = Scripts.run(globals, "local device = { deviceAddress = '6a:11:22:33:44:55' }\n" +
                "return after(group, paramof(groupof(false, device))), device.deviceAddress");
        assertFalse(joined.arg(1).toboolean());
        assertEquals("6a:11:22:33:44:55", joined.arg(2).tojstring());
        Varargs other = Scripts.run(globals, "local device = { deviceAddress = '6a:11:22:33:44:55' }\n" +
                "return after(intent, paramof(intentof('android.net.wifi.p2p.PEERS_CHANGED', device))), device.deviceAddress");
        assertFalse(other.arg(1).toboolean());
        assertEquals("6a:11:22:33:44:55", other.arg(2).tojstring());
        for (String address : new String[]{"''", "'02:00:00:00:00:00'", "nil"})
            assertFalse(address, Scripts.run(globals, "return after(intent, paramof(intentof(" +
                    "'android.net.wifi.p2p.THIS_DEVICE_CHANGED', { deviceAddress = " + address + " })))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return after(intent, paramof(intentof('android.net.wifi.p2p.THIS_DEVICE_CHANGED', nil)))")
                .arg1().toboolean());
        assertFalse(Scripts.run(globals, "local result = intentof('android.net.wifi.p2p.THIS_DEVICE_CHANGED', nil)\n" +
                "result.extras = nil\nreturn after(intent, paramof(result))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return after(group, paramof(groupof(true, nil)))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return after(intent, paramof(nil))").arg1().toboolean());
    }

    @Test
    public void phoneNumberIsReplaced() throws Exception {
        Globals globals = load("value_phone_number");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, null);
        check(globals, "return after_(nil, paramof(nil))", false, false, null);
        // A SIM without a number stays empty, like the other reads give it
        check(globals, "return after_(nil, paramof(''))", false, false, "");
        Scripts.run(globals, "settings['value.phone_number'] = '+3100000'");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, "+3100000");
        // The fake already, like getLine1Number() calling the hooked getLine1Number(subId): not reported twice
        check(globals, "return after_(nil, paramof('+3100000'))", false, false, "+3100000");

        globals = load("value_phone_number_nonnull");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, "");
        check(globals, "return after_(nil, paramof(''))", false, false, "");
        check(globals, "return after_(nil, paramof(nil))", false, false, null);
        Scripts.run(globals, "settings['value.phone_number'] = '+3100000'");
        check(globals, "return after_(nil, paramof('+3161234'))", true, true, "+3100000");
        // The fake already, of a SubscriptionInfo made again with it: not reported twice
        check(globals, "return after_(nil, paramof('+3100000'))", false, false, "+3100000");
    }

    // The Widevine ids come from the fake Android ID, of the same length, zeros without one; other properties stay
    @Test
    public void drmIdsFollowAndroidId() throws Exception {
        Globals globals = load("mediadrm_unique_id");
        for (String property : new String[]{"deviceUniqueId", "provisioningUniqueId"}) {
            globals.set("id", CoerceJavaToLua.coerce(new byte[32]));
            Varargs result = Scripts.run(globals, "return after_(nil, paramof(id, { [0] = '" + property + "' }))");
            assertTrue(property, result.arg1().toboolean());
            assertArrayEquals(property, new byte[32], (byte[]) result.arg(3).checkuserdata());

            for (String seed : new String[]{"44aa15730b248663", "0123456789abcdef"}) {
                Scripts.run(globals, "settings['value.android_id'] = '" + seed + "'");
                result = Scripts.run(globals, "return after_(nil, paramof(id, { [0] = '" + property + "' }))");
                assertTrue(property, result.arg1().toboolean());
                assertArrayEquals(property, MessageDigest.getInstance("SHA-256").digest((seed + property).getBytes(StandardCharsets.UTF_8)),
                        (byte[]) result.arg(3).checkuserdata());
            }
            Scripts.run(globals, "settings['value.android_id'] = nil");
        }
        // The value of the service goes before the Android ID, of which an app could compute the id
        Scripts.run(globals, "settings['value.android_id'] = 'public'; settings['value.drm_id'] = 'secret'");
        globals.set("id", CoerceJavaToLua.coerce(new byte[32]));
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest("secretdeviceUniqueId".getBytes(StandardCharsets.UTF_8)),
                (byte[]) Scripts.run(globals, "return after_(nil, paramof(id, { [0] = 'deviceUniqueId' }))")
                        .arg(3).checkuserdata());
        Scripts.run(globals, "settings['value.drm_id'] = nil");
        Scripts.run(globals, "settings['value.android_id'] = 'abc'");
        globals.set("id", CoerceJavaToLua.coerce(new byte[]{1, 2, 3}));
        assertEquals(3, ((byte[]) Scripts.run(globals, "return after_(nil, paramof(id, { [0] = 'deviceUniqueId' }))")
                .arg(3).checkuserdata()).length);
        globals.set("id", CoerceJavaToLua.coerce(new byte[]{1, 2, 3}));
        assertFalse(Scripts.run(globals, "return after_(nil, paramof(id, { [0] = 'vendor' }))").arg1().toboolean());
        globals.set("id", CoerceJavaToLua.coerce(new byte[0]));
        assertFalse(Scripts.run(globals, "return after_(nil, paramof(id, { [0] = 'deviceUniqueId' }))").arg1().toboolean());

        // Where Widevine gives the provisioning id as the inverted bits of the device id, the fakes are inverted too
        byte[] device = MessageDigest.getInstance("SHA-256").digest("real".getBytes(StandardCharsets.UTF_8));
        byte[] provisioning = new byte[device.length];
        for (int i = 0; i < device.length; i++)
            provisioning[i] = (byte) ~device[i];
        globals.set("realdevice", CoerceJavaToLua.coerce(device));
        globals.set("id", CoerceJavaToLua.coerce(provisioning));
        Scripts.run(globals, "settings['value.android_id'] = 'abc'");
        byte[] fakeDevice = MessageDigest.getInstance("SHA-256").digest("abcdeviceUniqueId".getBytes(StandardCharsets.UTF_8));
        byte[] fakeProvisioning = (byte[]) Scripts.run(globals,
                "local drm = { getPropertyByteArray = function(self, name) assert(name == 'deviceUniqueId') return realdevice end }\n" +
                        "return after_(nil, paramof(id, { [0] = 'provisioningUniqueId' }, drm))").arg(3).checkuserdata();
        for (int i = 0; i < fakeDevice.length; i++)
            assertEquals("byte " + i, (byte) ~fakeDevice[i], fakeProvisioning[i]);
    }

    // The cell location and cell info events are left out, the other events stay
    @Test
    public void listenWithoutCells() throws Exception {
        Globals globals = load("telephonymanager_listen");
        Scripts.run(globals, "function listenof(variant)\n" +
                "  return { getName = function(self) return 'TelephonyManager.listen' .. (variant and '/' .. variant or '') end }\n" +
                "end\n" +
                "function located(param, granted) param.getApplicationContext = function(self) return {\n" +
                "  checkSelfPermission = function(self, name) return granted and 0 or -1 end } end return param end");
        Varargs result = Scripts.run(globals, "local param = located(paramof(nil, { [0] = 'listener', [1] = 16 + 1024 + 1 }), true)\n" +
                "return before(listenof(), param), param.args[1]");
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        check(globals, "return before_(listenof(), located(paramof(nil, { [0] = 'listener', [1] = 1 }), true))", false, false, null);
        // Without a location permission the events stay, which Android refuses with a SecurityException
        result = Scripts.run(globals, "local param = located(paramof(nil, { [0] = 'listener', [1] = 16 + 1 }), false)\n" +
                "return before(listenof(), param), param.args[1]");
        assertFalse(result.arg1().toboolean());
        assertEquals(17, result.arg(2).toint());

        // With the highest bit (LISTEN_BARRING_INFO) the events are a negative int, which must stay an int
        for (int events : new int[]{0x80000010, 0x80000400}) {
            globals.set("events", LuaValue.valueOf(events));
            result = Scripts.run(globals, "local param = located(paramof(nil, { [0] = 'listener', [1] = events }), true)\n" +
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

    // The app asks for a time in the future, for which Android gives its own empty result
    @Test
    public void usageEventsAreEmpty() throws Exception {
        Globals globals = load("usageevents_query");
        Scripts.run(globals, "luajava = { newInstance = function(name, ...)\n" +
                "  local args = { ... }\n" +
                "  return { class = name, args = args, isEmpty = function() return true end,\n" +
                "    build = function(self) return { class = name, begin = args[1], finish = args[2] } end } end }\n" +
                "system = false\n" +
                "local plain = paramof\n" +
                "function paramof(...) local param = plain(...) param.isSystem = function() return system end return param end\n" +
                "function query(...)\n" +
                "  local param = paramof(nil, { [0] = select(1, ...), [1] = select(2, ...) })\n" +
                "  return before(hookof('queryEvents'), param), param.set, param.args[0], param.args[1]\n" +
                "end");
        Varargs times = Scripts.run(globals, "return query(1000, 2000)");
        assertTrue(times.arg(1).toboolean());
        assertTrue(times.arg(2).toboolean());
        assertEquals(1e18, times.arg(3).todouble(), 0);
        assertEquals(2e18, times.arg(4).todouble(), 0);
        Varargs query = Scripts.run(globals, "local restricted, set, query = query({ class = 'app' })\n" +
                "return restricted, set, query.class, query.begin, query.finish");
        assertTrue(query.arg(1).toboolean());
        assertTrue(query.arg(2).toboolean());
        assertEquals("android.app.usage.UsageEventsQuery$Builder", query.arg(3).tojstring());
        assertEquals(1e18, query.arg(4).todouble(), 0);
        assertEquals(2e18, query.arg(5).todouble(), 0);
        // A null query is left to the service, which throws for it
        Varargs none = Scripts.run(globals, "return query(nil)");
        assertFalse(none.arg(1).toboolean());
        assertFalse(none.arg(2).toboolean());
        // The result is not touched, nor are the statistics before the call
        assertFalse(Scripts.run(globals, "return after(hookof('queryEvents'), paramof({}))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return before(hookof('queryUsageStats'), paramof(nil, { [0] = 4 }))").arg1().toboolean());

        // The statistics per app are empty; processes of the system see all apps, like the other app lists
        Varargs stats = Scripts.run(globals,
                "local list = { isEmpty = function() return false end }\n" +
                        "local param = paramof(list)\n" +
                        "return after(hookof('queryUsageStats'), param), param.result.class");
        assertTrue(stats.arg(1).toboolean());
        assertEquals("java.util.ArrayList", stats.arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return after(hookof('queryUsageStats'), paramof({ isEmpty = function() return true end }))")
                .arg1().toboolean());
        Varargs system = Scripts.run(globals, "system = true\nreturn query(1000, 2000)");
        assertFalse(system.arg(1).toboolean());
        assertFalse(system.arg(2).toboolean());
        assertEquals(1000, system.arg(3).toint());
        assertFalse(Scripts.run(globals, "local list = { isEmpty = function() return false end }\n" +
                "return after(hookof('queryUsageStats'), paramof(list))").arg1().toboolean());
    }

    // The script of accounts with a stand-in of AccountManager, which has an account type of the app and one of Google,
    // and the names of the accounts of each type the app can see in live (none by default, 'error' when the lookup
    // fails), the hooks of Account and Intent, and a context of the app with its class loader
    private static Globals accounts() throws Exception {
        Globals globals = load("account_createfromparcel");
        Scripts.run(globals, "reads, lookups, live = 0, 0, {}\n" +
                "manager = { getAuthenticatorTypes = function(self) assert(unhooked, 'hooked lookup') reads = reads + 1\n" +
                "  return arrayof({ packageName = 'com.example', type = 'com.example.account' },\n" +
                "    { packageName = 'com.google.android.gms', type = 'com.google' }) end,\n" +
                "  getAccountsByType = function(self, type) assert(unhooked, 'hooked lookup') lookups = lookups + 1\n" +
                "    if live == 'error' then error('SecurityException') end\n" +
                "    local accounts = {}\n" +
                "    for index, name in ipairs(live[type] or {}) do accounts[index] = { name = name, type = type } end\n" +
                "    return arrayof(table.unpack(accounts)) end }\n" +
                "app = { getClassLoader = function(self) return 'loader' end }\n" +
                "luajava = { createProxy = function(interface, methods) return methods end,\n" +
                "  bindClass = function(name) assert(name == 'android.accounts.AccountManager', name)\n" +
                "  return { get = function(self, context) assert(context == app) return manager end } end }\n" +
                "local paramof_ = paramof\n" +
                "function paramof(...) local param = paramof_(...)\n" +
                "  param.getApplicationContext = function(self) return app end return param end\n" +
                "account_ = hookof('CREATOR:createFromParcel', 'android.accounts.Account')\n" +
                "intent_ = hookof('CREATOR:createFromParcel', 'android.content.Intent')");
        return globals;
    }

    // Accounts of other apps get the fake name, the accounts of the app itself keep theirs; the types are read once
    @Test
    public void accountNamesOfOtherApps() throws Exception {
        Globals globals = accounts();
        Varargs result = Scripts.run(globals, "local google = { type = 'com.google', name = 'me@gmail.com' }\n" +
                "local own = { type = 'com.example.account', name = 'me' }\n" +
                "local faked = after(account_, paramof(google))\n" +
                "local kept = after(account_, paramof(own))\n" +
                "settings['value.email'] = 'set@example.com'\n" +
                "local other = { type = 'com.google', name = 'other@gmail.com' }\n" +
                "after(account_, paramof(other))\n" +
                "return faked, google.name, kept, own.name, other.name, reads");
        assertTrue(result.arg(1).toboolean());
        assertEquals("private@example.com", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertEquals("me", result.arg(4).tojstring());
        assertEquals("set2@example.com", result.arg(5).tojstring());
        assertEquals(1, result.arg(6).toint());
    }

    // Android never gives two equal accounts, so each account of other apps gets its own name: the fake email for the
    // first one the app sees, then a number in front of the @, the same again for the same account; a fake stays, like
    // the name of an Account the app parcelled itself, also into a later process, and is not reported
    @Test
    public void accountsOfATypeGetTheirOwnNames() throws Exception {
        String names = "local names = {}\n" +
                "for _, name in ipairs({ ... }) do\n" +
                "  local account = { type = 'com.google', name = name }\n" +
                "  names[#names + 1] = tostring(after(account_, paramof(account))) .. ' ' .. account.name\n" +
                "end\n" +
                "return table.concat(names, ', ')";
        Globals globals = accounts();
        assertEquals("true private@example.com, true private2@example.com, true private@example.com, " +
                        "false private2@example.com, false private7@example.com, true private3@example.com, " +
                        "true private4@example.com",
                Scripts.run(globals, "return (function(...)\n" + names + "\nend)('a@gmail.com', 'b@gmail.com', " +
                        "'a@gmail.com', 'private2@example.com', 'private7@example.com', 'privatex@example.com', " +
                        "'private@example.com.org')").tojstring());
        // Without an @ the number goes at the end
        globals = accounts();
        Scripts.run(globals, "settings['value.email'] = 'nobody'");
        assertEquals("true nobody, true nobody2, false nobody2",
                Scripts.run(globals, "return (function(...)\n" + names + "\nend)('a@gmail.com', 'b@gmail.com', " +
                        "'nobody2')").tojstring());
    }

    // The numbers follow the order of the accounts the app can see, also in a process which first sees a later account
    // alone, like the result of the chooser; a name Android does not list gets a number after them; the accounts of
    // each type are numbered apart; a lookup which fails numbers in the order the process sees the names
    @Test
    public void accountsAreNumberedInTheOrderOfAndroid() throws Exception {
        String names = "local names = {}\n" +
                "for _, name in ipairs({ ... }) do\n" +
                "  local type, real = string.match(name, '^(.-) (.*)$')\n" +
                "  local account = { type = type, name = real }\n" +
                "  after(account_, paramof(account))\n" +
                "  names[#names + 1] = account.name\n" +
                "end\n" +
                "return table.concat(names, ', ') .. ' ' .. lookups";
        String run = "return (function(...)\n" + names + "\nend)";
        Globals globals = accounts();
        Scripts.run(globals, "live = { ['com.google'] = { 'a@gmail.com', 'b@gmail.com' }, " +
                "['org.other'] = { 'c@other.org' } }");
        assertEquals("private2@example.com, private@example.com, private@example.com, private3@example.com 3",
                Scripts.run(globals, run + "('com.google b@gmail.com', 'com.google a@gmail.com', " +
                        "'org.other c@other.org', 'com.google gone@gmail.com')").tojstring());
        globals = accounts();
        Scripts.run(globals, "live = 'error'");
        assertEquals("private@example.com, private2@example.com 2",
                Scripts.run(globals, run + "('com.google b@gmail.com', 'com.google a@gmail.com')").tojstring());
    }

    // The raw contacts and calendars of an account and the default account of contacts have the fake name of the Account
    // of the account, also of a second account, in a copy of the function of the fakes
    @Test
    public void accountNamesAreTheSameInEachScript() throws Exception {
        String fakeof = Scripts.function("account_createfromparcel", "fakeof");
        assertEquals(fakeof, Scripts.function("account_columns", "fakeof"));
        assertEquals(fakeof, Scripts.function("contacts_default_account", "fakeof"));
        Globals globals = accounts();
        Scripts.run(globals, "after(account_, paramof({ type = 'com.google', name = 'a@gmail.com' }))\n" +
                "after(account_, paramof({ type = 'com.google', name = 'b@gmail.com' }))");
        Globals columns = load("account_columns");
        Globals contacts = load("contacts_default_account");
        // The values of the process, in the stand-ins of the parameter, are shared by the scripts
        for (Globals other : new Globals[]{columns, contacts}) {
            other.set("paramof", globals.get("paramof"));
            other.set("luajava", globals.get("luajava"));
        }
        // A device without the local account and the SIM accounts of contacts, which are tested apart (AuditFixesScriptTest)
        Scripts.run(columns, "function localaccount() return nil, nil end\nfunction simaccounts() return {}, {} end");
        Varargs result = Scripts.run(columns, "local function rowof(name, type) return {\n" +
                "  getColumnIndex = function(self, column) return ({ account_name = 0, account_type = 1 })[column] or -1 end,\n" +
                "  getString = function(self, index) return index == 0 and name or type end,\n" +
                "  getBlob = function(self, index) return name end } end\n" +
                "local uri = { getAuthority = function(self) return 'com.android.contacts' end }\n" +
                "local b = paramof(rowof('b@gmail.com', 'com.google'), { [0] = uri })\n" +
                "local a = paramof(rowof('a@gmail.com', 'com.google'), { [0] = uri })\n" +
                "local own = paramof(rowof('me', 'com.example.account'), { [0] = uri })\n" +
                "after(nil, b) after(nil, a) after(nil, own)\n" +
                "return b.result.getString(0), b.result.getBlob(0), a.result.getString(0), own.result.getString(0)");
        assertEquals("private2@example.com", result.arg(1).tojstring());
        // The bytes end with the 0 of a text in a CursorWindow
        assertEquals("private2@example.com\0", result.arg(2).tojstring());
        assertEquals("private@example.com", result.arg(3).tojstring());
        assertEquals("me", result.arg(4).tojstring());
        assertEquals("private2@example.com", Scripts.run(contacts, "local values = { account_name = 'b@gmail.com', " +
                "account_type = 'com.google' }\n" +
                "local bundle = { getString = function(self, key) return values[key] end,\n" +
                "  putString = function(self, key, value) values[key] = value end }\n" +
                "after(nil, paramof(bundle, { [0] = 'com.android.contacts', [1] = 'queryDefaultAccountForNewContacts' }))\n" +
                "return values.account_name").tojstring());
    }

    // The owner of a calendar is not always an account: the id of a holiday calendar stays and takes no number, so the
    // next account still gets the second fake, like its Account; an owner, organizer or attendee which is the name of
    // an account gets the fake of that account, also in a row without its type
    @Test
    public void calendarOwnersWhichAreNoAccounts() throws Exception {
        Globals globals = accounts();
        Globals columns = load("account_columns");
        columns.set("paramof", globals.get("paramof"));
        columns.set("accounts_", globals.get("luajava"));
        // The hook of Account, in the script of accounts
        columns.set("account_", globals.get("account_"));
        columns.set("accountafter", globals.get("after"));
        String holiday = "en.usa#holiday@group.v.calendar.google.com";
        Varargs result = Scripts.run(columns, "luajava = { createProxy = accounts_.createProxy, bindClass = function(name)\n" +
                "  if name == 'android.net.Uri' then return { parse = function(self, text) return text end } end\n" +
                "  return accounts_.bindClass(name) end }\n" +
                "local function cursorof(columns, rows) local position = rows.at or 0 return {\n" +
                "  getColumnIndex = function(self, column) return columns[column] or -1 end,\n" +
                "  moveToNext = function(self) position = position + 1 return rows[position] ~= nil end,\n" +
                "  getString = function(self, index) return rows[position][index + 1] end,\n" +
                "  close = function(self) end } end\n" +
                // The calendars of the provider: one of a, a holiday calendar a subscribed to, and one of b
                "local calendars = { { 'a@gmail.com', 'com.google', 'a@gmail.com' },\n" +
                "  { 'a@gmail.com', 'com.google', '" + holiday + "' }, { 'b@gmail.com', 'com.google', 'b@gmail.com' } }\n" +
                "local provider = { query = function(self, uri) assert(uri == 'content://com.android.calendar/calendars', uri)\n" +
                "  return cursorof({ account_name = 0, account_type = 1, ownerAccount = 2 }, calendars) end }\n" +
                "local uri = { getAuthority = function(self) return 'com.android.calendar' end }\n" +
                "local function queried(columns, ...)\n" +
                "  local param = paramof(cursorof(columns, { at = 1, { ... } }), { [0] = uri }, provider)\n" +
                "  after(nil, param) return param.result end\n" +
                "local row = queried({ account_name = 0, account_type = 1, ownerAccount = 2 }, 'a@gmail.com', 'com.google',\n" +
                "  '" + holiday + "')\n" +
                "local name, owner = row.getString(0), row.getString(2)\n" +
                "local b = { type = 'com.google', name = 'b@gmail.com' }\n" +
                "accountafter(account_, paramof(b))\n" +
                "local event = queried({ ownerAccount = 0, organizer = 1 }, 'b@gmail.com', 'b@gmail.com')\n" +
                "local attendee = queried({ attendeeEmail = 0 }, 'a@gmail.com')\n" +
                "return name, owner, b.name, event.getString(0), event.getString(1), attendee.getString(0)");
        assertEquals("private@example.com", result.arg(1).tojstring());
        assertEquals(holiday, result.arg(2).tojstring());
        assertEquals("private2@example.com", result.arg(3).tojstring());
        assertEquals("private2@example.com", result.arg(4).tojstring());
        assertEquals("private2@example.com", result.arg(5).tojstring());
        assertEquals("private@example.com", result.arg(6).tojstring());
    }

    // The name of an account as text in the result of the chooser of accounts and in the broadcast of a removed account
    // gets the fake of its Account; the accounts of the app itself and other intents stay, and the extras of other
    // intents are not read; a copy of the extras is read with the class loader of the app, and stays when it cannot be
    // read
    @Test
    public void accountNamesInIntents() throws Exception {
        String intents = "function intentof(action, name, type, component, broken)\n" +
                "  local values = { authAccount = name, accountType = type }\n" +
                "  return { values = values, reads = 0,\n" +
                "    getAction = function(self) return action end,\n" +
                "    getComponent = function(self) return component end,\n" +
                "    getData = function(self) return nil end,\n" +
                "    getExtras = function(self)\n" +
                "      self.reads = self.reads + 1\n" +
                "      local copy = { authAccount = values.authAccount, accountType = values.accountType }\n" +
                "      return { copy = copy,\n" +
                "        setClassLoader = function(_, loader) copy.loader = loader end,\n" +
                "        getString = function(_, key) assert(copy.loader == 'loader', 'read without the class loader')\n" +
                "          if broken then error('BadParcelableException') end return copy[key] end,\n" +
                "        putString = function(_, key, value) copy[key] = value end }\n" +
                "    end,\n" +
                "    replaceExtras = function(self, extras) values.authAccount = extras.copy.authAccount end }\n" +
                "end\n" +
                "function check(intent)\n" +
                "  local restricted = after(intent_, paramof(intent))\n" +
                "  return tostring(restricted) .. ' ' .. tostring(intent.values.authAccount) .. ' ' .. intent.reads\n" +
                "end";
        Globals globals = accounts();
        Scripts.run(globals, intents);
        String removed = "'android.accounts.action.ACCOUNT_REMOVED'";
        Varargs result = Scripts.run(globals, "local first = { type = 'com.google', name = 'a@gmail.com' }\n" +
                "after(account_, paramof(first))\n" +
                "local chosen = check(intentof(nil, 'b@gmail.com', 'com.google'))\n" +
                "local account = { type = 'com.google', name = 'b@gmail.com' }\n" +
                "after(account_, paramof(account))\n" +
                "return chosen, account.name, check(intentof(" + removed + ", 'a@gmail.com', 'com.google')),\n" +
                "  check(intentof(nil, 'me', 'com.example.account')),\n" +
                "  check(intentof('android.intent.action.VIEW', 'c@gmail.com', 'com.google')),\n" +
                "  check(intentof(nil, 'c@gmail.com', 'com.google', 'component')),\n" +
                "  check(intentof(nil, 'c@gmail.com', 'com.google', nil, true)),\n" +
                "  check(intentof(nil, 'c@gmail.com', nil)), check(intentof(nil, nil, nil)),\n" +
                "  tostring(after(intent_, paramof(nil)))");
        assertEquals("true private2@example.com 1", result.arg(1).tojstring());
        assertEquals("private2@example.com", result.arg(2).tojstring());
        assertEquals("true private@example.com 1", result.arg(3).tojstring());
        assertEquals("false me 1", result.arg(4).tojstring());
        assertEquals("false c@gmail.com 0", result.arg(5).tojstring());
        assertEquals("false c@gmail.com 0", result.arg(6).tojstring());
        assertEquals("false c@gmail.com 1", result.arg(7).tojstring());
        assertEquals("false c@gmail.com 1", result.arg(8).tojstring());
        assertEquals("false nil 1", result.arg(9).tojstring());
        assertEquals("false", result.arg(10).tojstring());
        // The broadcast of the removal of the second of two accounts starts a process which saw no account yet:
        // Android lists only the first one, which keeps its fake, and the removed one gets the next
        globals = accounts();
        Scripts.run(globals, intents + "\nlive = { ['com.google'] = { 'a@gmail.com' } }");
        assertEquals("true private2@example.com 1",
                Scripts.run(globals, "return check(intentof(" + removed + ", 'b@gmail.com', 'com.google'))").tojstring());
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
                "    getDescription = function(self) return { getTimestamp = function(self) return 0 end } end,\n" +
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

    // A camera the app cannot see is one the device does not have: the error of Android for the asked id, with the
    // message of Android, which the same method, or getCameraCharacteristics, gives once per process for an id no
    // device has
    private static Globals camera(String characteristics) throws Exception {
        Globals globals = load("camera2_open");
        Scripts.run(globals, "luajava = { newInstance = function(class, message) return class .. ': ' .. message end }\n" +
                "probes, values = 0, {}\n" +
                "manager = { getCameraCharacteristics = function(self, id)\n" +
                "  probes = probes + 1\n" +
                characteristics + "\n" +
                "end,\n" +
                "  setTorchMode = function(self, id, on)\n" +
                "    torch = on\n" +
                "    error('vm error: java.lang.IllegalArgumentException: Camera ID \"' .. id .. '\" is a not valid camera ID', 0)\n" +
                "  end }\n" +
                "function cameraof(id)\n" +
                "  return { args = { [0] = id },\n" +
                "    getArgument = function(self, index) return self.args[index] end,\n" +
                "    setResult = function(self, value) self.result = value end,\n" +
                "    getApplicationContext = function(self) return values end,\n" +
                "    getValue = function(self, name, scope) return scope[name] end,\n" +
                "    putValue = function(self, name, value, scope) scope[name] = value end,\n" +
                "    getThis = function(self) return manager end,\n" +
                "    unhooked = function(self, f) return f() end }\n" +
                "end");
        return globals;
    }

    @Test
    public void cameraIsUnknown() throws Exception {
        Globals globals = camera("  error('vm error: java.lang.IllegalArgumentException: getCameraCharacteristics:851: " +
                "Unable to retrieve camera characteristics for unknown device ' .. id .. ': No such file or directory (-2)', 0)");
        Varargs result = Scripts.run(globals,
                "local first, second = cameraof('0'), cameraof('2')\n" +
                        "return before(hookof('openCamera'), first), first.result, before(hookof('getCameraCharacteristics'), second),\n" +
                        "  second.result, probes");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.lang.IllegalArgumentException: getCameraCharacteristics:851: Unable to retrieve camera " +
                "characteristics for unknown device 0: No such file or directory (-2)", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("java.lang.IllegalArgumentException: getCameraCharacteristics:851: Unable to retrieve camera " +
                "characteristics for unknown device 2: No such file or directory (-2)", result.arg(4).tojstring());
        // The message is kept for the process, so the probe runs once
        assertEquals(1, result.arg(5).toint());
    }

    // Without an error from the probe, the message is the one of Android's text for an unknown id
    @Test
    public void cameraIsUnknownWithoutProbeError() throws Exception {
        Varargs result = Scripts.run(camera("  return nil"),
                "local param = cameraof('7')\n" +
                        "return before(hookof('openCamera'), param), param.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.lang.IllegalArgumentException: Camera id 7 does not match any currently connected camera device",
                result.arg(2).tojstring());
    }

    // The torch of a camera the app cannot see gets the error of the torch for an unknown id, which the probe asks
    // with the torch off
    @Test
    public void torchIsUnknown() throws Exception {
        Globals globals = camera("  error('not this probe')");
        Varargs result = Scripts.run(globals,
                "local param = cameraof('0')\n" +
                        "return before(hookof('setTorchMode'), param), param.result, torch, probes");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.lang.IllegalArgumentException: Camera ID \"0\" is a not valid camera ID", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
    }

    // The message of a Java exception in LuaJ has the form the probe reads, like the one of getCameraCharacteristics
    @Test
    public void javaExceptionHasTheProbePattern() throws Exception {
        Varargs result = Scripts.run(Scripts.load("camera2_open", null),
                "local ok, err = pcall(function() return luajava.bindClass('java.lang.Character'):toChars(-1) end)\n" +
                        "return ok, string.match(tostring(err), 'java%.lang%.IllegalArgumentException: (.*)$')");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).isnil());
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
                {"advertisingidclient$info_getid", "nil", "false", null},
                // Deleted by the user already: no restriction
                {"advertisingidclient$info_getid", "'00000000-0000-0000-0000-000000000000'", "false",
                        "00000000-0000-0000-0000-000000000000"}};
        for (String[] c : cases) {
            boolean restricted = Boolean.parseBoolean(c[2]);
            check(load(c[0]), "return after_(nil, paramof(" + c[1] + "))", restricted, restricted, c[3]);
        }
        // The exception of the method stays, like the SecurityException of getUserCreationTime for another user
        Varargs thrown = Scripts.run(load("generic_zero_value"),
                "local param = paramof(nil, {}, nil, 'java.lang.SecurityException')\nreturn after(nil, param), param.set");
        assertFalse(thrown.arg1().toboolean());
        assertFalse(thrown.arg(2).toboolean());
    }

    // An empty collection like Android's: unmodifiable of the same kind when Android's is, like the bonded Bluetooth
    // devices (a set) and the devices of Wi-Fi Direct (a collection), else a new one which can be changed
    @Test
    public void emptyCollectionsLikeAndroids() throws Exception {
        Globals globals = load("generic_empty");
        Varargs result = Scripts.run(globals,
                "luajava = { newInstance = function(name) return name end,\n" +
                        "  bindClass = function(name) return { unmodifiableSet = function(self, c) return 'set of ' .. c end,\n" +
                        "    unmodifiableList = function(self, c) return 'list of ' .. c end,\n" +
                        "    unmodifiableCollection = function(self, c) return 'collection of ' .. c end } end }\n" +
                        // A collection of Android by the name of its class and the interfaces it has
                        "local function of(class, kinds) return { kinds = kinds, isEmpty = function() return false end,\n" +
                        "  getClass = function() return { getName = function() return class end } end } end\n" +
                        "local function empty(result) local param = paramof(result)\n" +
                        "  param.isInstance = function(self, object, name) return name == 'java.util.Collection' or object.kinds[name] end\n" +
                        "  after(nil, param) return param.result end\n" +
                        "local U = 'java.util.Collections$UnmodifiableCollection'\n" +
                        "return empty(of('java.util.Collections$UnmodifiableSet', { ['java.util.Set'] = true, [U] = true })),\n" +
                        "  empty(of('java.util.Collections$UnmodifiableCollection', { [U] = true })),\n" +
                        "  empty(of('java.util.Collections$UnmodifiableRandomAccessList', { ['java.util.List'] = true, [U] = true })),\n" +
                        "  empty(of('java.util.ArrayList', { ['java.util.List'] = true }))");
        assertEquals("set of java.util.HashSet", result.arg(1).tojstring());
        assertEquals("collection of java.util.ArrayList", result.arg(2).tojstring());
        assertEquals("list of java.util.ArrayList", result.arg(3).tojstring());
        assertEquals("java.util.ArrayList", result.arg(4).tojstring());
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

    // A geofence and a request of play-services-location, with the names R8 gives their fields; final in the library,
    // which ART lets reflection set once accessible, unlike newer JDKs
    public static final class Fence {
        private String zza;
        private double zzd;
        private double zze;
        private float zzf;

        Fence(String id, double latitude, double longitude, float radius) {
            zza = id;
            zzd = latitude;
            zze = longitude;
            zzf = radius;
        }
    }

    public static final class Request {
        private final java.util.List<Object> zza;
        private org.luaj.vm2.LuaTable parcel;
        private int flags;

        Request(Object... fences) {
            zza = java.util.Arrays.asList(fences);
        }

        public void writeToParcel(org.luaj.vm2.LuaTable parcel, int flags) {
            this.parcel = parcel;
            this.flags = flags;
        }
    }

    // Play services gets a copy of the request, read back from a parcel of it, with each fence at 0, 0 and 0.1 m, written
    // without the hooks into the parcel of the call instead of the request of the app, which keeps the region it set and
    // the library checked; a fence of other fields fails before anything is written, a null parcel throws like without the hook
    @Test
    public void geofencesLeaveAtZero() throws Exception {
        Globals globals = load("geofencingrequest_writetoparcel");
        String app = "local bind = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name ~= 'android.os.Parcel' then return bind(name) end\n" +
                "  return { obtain = function(self)\n" +
                "    scratch = { setDataPosition = function(self, position) self.position = position end,\n" +
                "      recycle = function(self) self.recycled = true end }\n" +
                "    return scratch end }\n" +
                "end\n" +
                "app = { writeToParcel = function(self, parcel, flags) parcel.request = self parcel.unhooked = unhooked end,\n" +
                "  CREATOR = { createFromParcel = function(self, parcel)\n" +
                "    return parcel.request == app and parcel.position == 0 and copy end } }\n" +
                "parcel = {}\n";
        Fence a = new Fence("a", 52.37, 4.89, 200f);
        Fence b = new Fence("b", -33.87, 151.21, 50f);
        Request copy = new Request(a, b);
        globals.set("copy", CoerceJavaToLua.coerce(copy));
        Varargs result = Scripts.run(globals, app + "local param = paramof(nil, { [0] = parcel, [1] = 3 }, app)\n" +
                "return before(nil, param), param.set, param.result, scratch.unhooked, scratch.recycled, parcel");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6) == copy.parcel);
        assertEquals(3, copy.flags);
        for (Fence fence : new Fence[]{a, b}) {
            assertEquals(0, fence.zzd, 0);
            assertEquals(0, fence.zze, 0);
            assertEquals(0.1f, fence.zzf, 0);
        }
        assertEquals("a", a.zza);

        Request odd = new Request(new Object());
        globals.set("copy", CoerceJavaToLua.coerce(odd));
        result = Scripts.run(globals, app + "local param = paramof(nil, { [0] = parcel, [1] = 0 }, app)\n" +
                "local ok, message = pcall(before, nil, param)\n" +
                "return ok, message, param.set");
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).tojstring(), result.arg(2).tojstring().contains("Geofence region unknown"));
        assertFalse(result.arg(3).toboolean());
        assertTrue(odd.parcel == null);

        // A request without one list of fences fails the same way
        globals.set("copy", CoerceJavaToLua.coerce(new Fence("c", 1, 2, 3f)));
        result = Scripts.run(globals, app + "local param = paramof(nil, { [0] = parcel, [1] = 0 }, app)\n" +
                "local ok, message = pcall(before, nil, param)\n" +
                "return ok, message, param.set");
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).tojstring(), result.arg(2).tojstring().contains("Geofence list unknown"));
        assertFalse(result.arg(3).toboolean());

        assertFalse(Scripts.run(globals, "local param = paramof(nil, {}, app)\n" +
                "return before(nil, param) or param.set").arg1().toboolean());
    }

    // The region the app gives a location fence of Awareness or the geofence builder of HMS is at 0, 0 with a radius of
    // 0.1 m
    @Test
    public void geofenceAtZero() throws Exception {
        Varargs result = Scripts.run(load("geofence$builder_setcircularregion"),
                "local param = paramof(nil, { [0] = 52.37, [1] = 4.89, [2] = 100 })\n" +
                        "return before(nil, param), param.args[0], param.args[1], param.args[2]");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).todouble(), 0);
        assertEquals(0, result.arg(3).todouble(), 0);
        assertEquals(0.1, result.arg(4).todouble(), 0);
        // Values out of range stay, so the checks of the library throw with the value of the app; a radius of 0, which
        // LocationFence takes, stays too, at 0, 0
        Varargs invalid = Scripts.run(load("geofence$builder_setcircularregion"),
                "local latitude = paramof(nil, { [0] = 91, [1] = 4.89, [2] = 100 })\n" +
                        "local longitude = paramof(nil, { [0] = 52.37, [1] = 184.89, [2] = -1 })\n" +
                        "local nan = paramof(nil, { [0] = 0 / 0, [1] = -180, [2] = 0 })\n" +
                        "before(nil, latitude) before(nil, longitude) before(nil, nan)\n" +
                        "return latitude.args[0], latitude.args[1], latitude.args[2], longitude.args[0], longitude.args[1],\n" +
                        "  longitude.args[2], nan.args[0] ~= nan.args[0], nan.args[1], nan.args[2]");
        assertEquals(91, invalid.arg(1).todouble(), 0);
        assertEquals(0, invalid.arg(2).todouble(), 0);
        assertEquals(0.1, invalid.arg(3).todouble(), 0);
        assertEquals(0, invalid.arg(4).todouble(), 0);
        assertEquals(184.89, invalid.arg(5).todouble(), 0);
        assertEquals(-1, invalid.arg(6).todouble(), 0);
        assertTrue(invalid.arg(7).toboolean());
        assertEquals(0, invalid.arg(8).todouble(), 0);
        assertEquals(0, invalid.arg(9).todouble(), 0);
    }

    // The activity is unknown, in a mutable list like the one of Play services, at the time of the real result, by the
    // constructor the creator uses, and with the int fields of it, the detector id and in 10.0.1 and older the version
    // code, whatever their order, each else the one of a new result, under extractResult and the creator alike; the
    // extras and static fields are left out. When the members are missing, which R8 can remove, extractResult gives no
    // result rather than the real one, and the creator, whose readers fail on none, leaves it; objects of other
    // classes, of a creator R8 merged, stay
    @Test
    public void activityIsUnknown() throws Exception {
        Globals globals = load("activityrecognitionresult_extractresult");
        String result = "com.google.android.gms.location.ActivityRecognitionResult";
        Scripts.run(globals, "luajava = { new = function(class, ...)\n" +
                "    return { class = class, args = { ... }, n = select('#', ...) } end,\n" +
                "  newInstance = function(name) local list = { class = name } list.add = function(self, v) self[#self + 1] = v end\n" +
                "    return list end }\n" +
                "local base = paramof\n" +
                "function paramof(...) local param = base(...)\n" +
                "  param.findClass = function(self, name) return name end\n" +
                "  param.isInstance = function(self, object, name) return object ~= nil and object.class == name end\n" +
                "  return param end");
        // The fields of 9.8.0 in the order of ART, by name, with the final version code after the detector id and a
        // static int, then those of 10.0.1, where a version code which cannot be set comes before the detector id; each
        // field sets a slot of its own in the fake, so the slots tell which ones were copied
        Scripts.run(globals, "function field(name, type, modifiers, value, broken) return { getType = function(self) return type end,\n" +
                "  getModifiers = function(self) return modifiers end, setAccessible = function(self, on) self.on = on end,\n" +
                "  getInt = function(self, object) assert(self.on and object.real) return value end,\n" +
                "  setInt = function(self, object, v) assert(self.on and object.n == 5 and not broken) object[name] = v end } end\n" +
                "fields = { length = 7, field('ajD', 'java.util.List', 0, 'list'), field('ajE', 'long', 0, 1000),\n" +
                "  field('ajF', 'long', 0, 500), field('ajG', 'int', 0, 7), field('extras', 'android.os.Bundle', 0, 'extras'),\n" +
                "  field('mVersionCode', 'int', 18, 2), field('SHARED', 'int', 25, 9) }\n" +
                "luajava.bindClass = function(name) assert(name == 'java.lang.Class')\n" +
                "  return { getDeclaredFields = function(class) assert(class == '" + result + "') return fields end,\n" +
                "    getName = function(type) return type end } end");
        Varargs found = Scripts.run(globals, "local real = { class = '" + result + "', real = true,\n" +
                "  getTime = function(self) return 1000 end, getElapsedRealtimeMillis = function(self) return 500 end }\n" +
                "local param = paramof(real)\n" +
                "after(hookof('extractResult'), param)\n" +
                "local fake = param.result\n" +
                "local creator = paramof(real)\n" +
                "after(hookof('CREATOR:createFromParcel'), creator)\n" +
                "local created = creator.result\n" +
                "fields = { length = 3, field('mVersionCode', 'int', 18, 2, true), field('zzbiN', 'java.util.List', 0, 'list'),\n" +
                "  field('zzbiQ', 'int', 0, 7) }\n" +
                "local older = paramof(real)\n" +
                "after(hookof('CREATOR:createFromParcel'), older)\n" +
                "local later = older.result\n" +
                "luajava.bindClass = function(name) error('hidden') end\n" +
                "local unread = paramof({ class = '" + result + "', getTime = function(self) return 1000 end,\n" +
                "  getElapsedRealtimeMillis = function(self) return 500 end })\n" +
                "after(hookof('extractResult'), unread)\n" +
                "return fake.n, fake.args[1][1].args[1], fake.args[2], fake.args[3], fake.args[4], fake.args[5],\n" +
                "  fake.ajG, fake.mVersionCode, fake.extras == nil and fake.SHARED == nil and fake.ajE == nil,\n" +
                "  unread.result.n, unread.result.args[4], unread.result.ajG, later.zzbiQ, later.mVersionCode,\n" +
                "  created.n, created.ajG, created.mVersionCode, created.extras == nil and created.SHARED == nil");
        assertEquals(5, found.arg(1).toint());
        assertEquals(4, found.arg(2).toint());
        assertEquals(1000, found.arg(3).toint());
        assertEquals(500, found.arg(4).toint());
        assertEquals(0, found.arg(5).toint());
        assertTrue(found.arg(6).isnil());
        assertEquals(7, found.arg(7).toint());
        assertEquals(2, found.arg(8).toint());
        assertTrue(found.arg(9).toboolean());
        assertEquals(5, found.arg(10).toint());
        assertEquals(0, found.arg(11).toint());
        assertTrue(found.arg(12).isnil());
        assertEquals(7, found.arg(13).toint());
        assertTrue(found.arg(14).isnil());
        assertEquals(5, found.arg(15).toint());
        assertEquals(7, found.arg(16).toint());
        assertEquals(2, found.arg(17).toint());
        assertTrue(found.arg(18).toboolean());
        Varargs results = Scripts.run(globals, "local real = { class = '" + result + "', getTime = function(self) return 1000 end,\n" +
                "  getElapsedRealtimeMillis = function(self) return 500 end }\n" +
                "local param = paramof(real)\n" +
                "local restricted = after(hookof('extractResult'), param)\n" +
                "local fake = param.result\n" +
                "local activities = fake.args[1]\n" +
                "local detected = activities[1]\n" +
                "local creator = paramof(real)\n" +
                "after(hookof('CREATOR:createFromParcel'), creator)\n" +
                "local stripped = { class = '" + result + "', getElapsedRealtimeMillis = function(self) return 500 end }\n" +
                "local extract = paramof(stripped)\n" +
                "local none = after(hookof('extractResult'), extract)\n" +
                "local kept = paramof(stripped)\n" +
                "local other = paramof({ class = 'com.google.android.gms.location.DetectedActivity' })\n" +
                "return restricted, fake.class, activities.class, #activities, detected.class, detected.args[1], detected.args[2],\n" +
                "  fake.args[2], fake.args[3], fake.args[4], fake.n, none, extract.result, creator.result.class,\n" +
                "  after(hookof('CREATOR:createFromParcel'), kept), kept.set, after(hookof('CREATOR:createFromParcel'), other), other.set");
        assertTrue(results.arg(1).toboolean());
        assertEquals(result, results.arg(2).tojstring());
        assertEquals("java.util.ArrayList", results.arg(3).tojstring());
        assertEquals(1, results.arg(4).toint());
        assertEquals("com.google.android.gms.location.DetectedActivity", results.arg(5).tojstring());
        assertEquals(4, results.arg(6).toint());
        assertEquals(100, results.arg(7).toint());
        assertEquals(1000, results.arg(8).toint());
        assertEquals(500, results.arg(9).toint());
        assertEquals(0, results.arg(10).toint());
        assertEquals(5, results.arg(11).toint());
        assertTrue(results.arg(12).toboolean());
        assertTrue(results.arg(13).isnil());
        assertEquals(result, results.arg(14).tojstring());
        assertFalse(results.arg(15).toboolean());
        assertFalse(results.arg(16).toboolean());
        assertFalse(results.arg(17).toboolean());
        assertFalse(results.arg(18).toboolean());
        check(globals, "return after_(hookof('extractResult'), paramof(nil))", false, false, null);
    }

    // The activity of HMS is OTHERS at 100, in a mutable list, at the time of the real response, and its conversions are
    // none; without the members there is no response rather than the real one, and an intent without one stays so; the
    // creators fake the same, which getDataFromIntent then fakes the same again, and without the members keep the real
    // response, since a creator never gives none
    @Test
    public void activityOfHmsIsOthers() throws Exception {
        Globals globals = load("activityidentificationresponse_getdatafromintent");
        String hms = "com.huawei.hms.location.";
        Scripts.run(globals, "luajava = { new = function(class, ...) local args = { ... }\n" +
                "    return { class = class, args = args, n = select('#', ...), getTime = function(self) return args[2] end,\n" +
                "      getElapsedTimeFromReboot = function(self) return args[3] end } end,\n" +
                "  newInstance = function(name) local list = { class = name } list.add = function(self, v) self[#self + 1] = v end\n" +
                "    return list end }\n" +
                "local base = paramof\n" +
                "function paramof(...) local param = base(...)\n" +
                "  param.isInstance = function(self, object, name) return object ~= nil and object.class == name end\n" +
                "  return param end");
        Varargs results = Scripts.run(globals, "local real = { class = '" + hms + "ActivityIdentificationResponse',\n" +
                "  getTime = function(self) return 1000 end, getElapsedTimeFromReboot = function(self) return 500 end }\n" +
                "local param = paramof(real)\n" +
                "local restricted = after(hookof('getDataFromIntent'), param)\n" +
                "local fake = param.result\n" +
                "local datas = fake.args[1]\n" +
                "local conversion = paramof({ class = '" + hms + "ActivityConversionResponse' })\n" +
                "local converted = after(hookof('getDataFromIntent'), conversion)\n" +
                "local bare = { class = '" + hms + "ActivityIdentificationResponse' }\n" +
                "local stripped = paramof(bare)\n" +
                "local none = after(hookof('getDataFromIntent'), stripped)\n" +
                "local parcel = paramof(real)\n" +
                "local created = after(hookof('CREATOR:createFromParcel'), parcel)\n" +
                "local read = paramof(parcel.result)\n" +
                "after(hookof('getDataFromIntent'), read)\n" +
                "local kept = paramof(bare)\n" +
                "return restricted, fake.class, datas.class, #datas, datas[1].class, datas[1].args[1], datas[1].args[2],\n" +
                "  fake.args[2], fake.args[3], fake.n, converted, conversion.result.class, conversion.result.n,\n" +
                "  none, stripped.set, stripped.result, created, parcel.result.args[1][1].args[1], read.result.class,\n" +
                "  read.result.args[1][1].args[1], read.result.args[1][1].args[2], read.result.args[2], read.result.args[3],\n" +
                "  after(hookof('CREATOR:createFromParcel'), kept), kept.set, kept.result == bare");
        assertTrue(results.arg(1).toboolean());
        assertEquals(hms + "ActivityIdentificationResponse", results.arg(2).tojstring());
        assertEquals("java.util.ArrayList", results.arg(3).tojstring());
        assertEquals(1, results.arg(4).toint());
        assertEquals(hms + "ActivityIdentificationData", results.arg(5).tojstring());
        assertEquals(104, results.arg(6).toint());
        assertEquals(100, results.arg(7).toint());
        assertEquals(1000, results.arg(8).toint());
        assertEquals(500, results.arg(9).toint());
        assertEquals(3, results.arg(10).toint());
        assertTrue(results.arg(11).toboolean());
        assertEquals(hms + "ActivityConversionResponse", results.arg(12).tojstring());
        assertEquals(0, results.arg(13).toint());
        assertTrue(results.arg(14).toboolean());
        assertTrue(results.arg(15).toboolean());
        assertTrue(results.arg(16).isnil());
        assertTrue(results.arg(17).toboolean());
        assertEquals(104, results.arg(18).toint());
        assertEquals(hms + "ActivityIdentificationResponse", results.arg(19).tojstring());
        assertEquals(104, results.arg(20).toint());
        assertEquals(100, results.arg(21).toint());
        assertEquals(1000, results.arg(22).toint());
        assertEquals(500, results.arg(23).toint());
        assertFalse(results.arg(24).toboolean());
        assertFalse(results.arg(25).toboolean());
        assertTrue(results.arg(26).toboolean());
        check(globals, "return after_(hookof('getDataFromIntent'), paramof(nil))", false, false, null);
    }

    // The ids of AdServices are made of the zero id, an AdId with ad tracking limited and an AppSetId of the app's scope,
    // so the getters, toString, hashCode and equals agree
    @Test
    public void adServicesIdsAreMadeOfTheZeroId() throws Exception {
        Globals globals = load("adservices_id");
        Varargs result = Scripts.run(globals,
                "local function hook(class) return { getClassName = function(self) return class end } end\n" +
                        "local adid = paramof(nil, { [0] = 'f0e1d2c3-0000-4000-8000-123456789abc', [1] = false })\n" +
                        "local appset = paramof(nil, { [0] = 'a1b2c3d4-0000-4000-8000-123456789abc', [1] = 2 })\n" +
                        "local zero = paramof(nil, { [0] = '00000000-0000-0000-0000-000000000000', [1] = 1 })\n" +
                        "return before(hook('android.adservices.adid.AdId'), adid), adid.args[0], adid.args[1],\n" +
                        "  before(hook('android.adservices.appsetid.AppSetId'), appset), appset.args[0], appset.args[1],\n" +
                        "  before(hook('android.adservices.appsetid.AppSetId'), zero)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("00000000-0000-0000-0000-000000000000", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals("00000000-0000-0000-0000-000000000000", result.arg(5).tojstring());
        assertEquals(1, result.arg(6).toint());
        assertFalse(result.arg(7).toboolean());
    }

    // The sleep events are none, in the list Play services gives for a delivered extra, by the real luajava:
    // an unmodifiable ArrayList and not Collections.emptyList, which it gives only without the extra
    @Test
    public void sleepEventsAreNone() throws Exception {
        Globals globals = load("sleepevent_extractevents");
        Varargs result = Scripts.run(globals,
                "local events = paramof({ isEmpty = function() return false end, size = function() return 2 end })\n" +
                        "local none = paramof({ isEmpty = function() return true end })\n" +
                        "return after(nil, events), events.result, after(nil, none)");
        assertTrue(result.arg(1).toboolean());
        java.util.List<?> fake = (java.util.List<?>) result.arg(2).touserdata();
        assertEquals(java.util.Collections.unmodifiableList(new java.util.ArrayList<>()).getClass(), fake.getClass());
        assertTrue(fake.isEmpty());
        assertFalse(result.arg(3).toboolean());
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

        // AppsFlyer before version 6 stops by stopTracking
        result = Scripts.run(globals, "local stops = {}\n" +
                "local sdk = { stopTracking = function(self, stop, context) stops[#stops + 1] = tostring(stop) .. ' ' .. context end }\n" +
                "return after(hookof('getInstance', 'com.appsflyer.AppsFlyerLib'), paramof(sdk)), #stops, stops[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals("true context", result.arg(3).tojstring());

        for (String[] c : new String[][]{{"com.google.firebase.analytics.FirebaseAnalytics", "setAnalyticsCollectionEnabled", "false"},
                {"com.google.firebase.crashlytics.FirebaseCrashlytics", "setCrashlyticsCollectionEnabled", "false"},
                {"com.google.android.gms.analytics.GoogleAnalytics", "setAppOptOut", "true"}}) {
            result = Scripts.run(globals, "local calls = {}\n" +
                    "local sdk = { " + c[1] + " = function(self, enabled) calls[#calls + 1] = enabled end }\n" +
                    "local hook = hookof('getInstance', '" + c[0] + "')\n" +
                    "local first = after(hook, paramof(sdk))\n" +
                    "local again = after(hook, paramof(sdk))\n" +
                    "return first, again, #calls, calls[1]");
            assertTrue(c[0], result.arg(1).toboolean());
            assertFalse(c[0], result.arg(2).toboolean());
            assertEquals(c[0], 1, result.arg(3).toint());
            assertEquals(c[0], Boolean.parseBoolean(c[2]), result.arg(4).toboolean());
        }
    }

    // Firebase Analytics gets all consent types denied, also when the app gives consent; Amazon no consent
    @Test
    public void analyticsConsentIsDenied() throws Exception {
        Globals globals = load("analytics_getinstance");
        Varargs result = Scripts.run(globals, "classes = {\n" +
                "  ['com.google.firebase.analytics.FirebaseAnalytics$ConsentType'] =\n" +
                "    { values = function(self) return arrayof('ANALYTICS_STORAGE', 'AD_STORAGE', 'FUTURE') end },\n" +
                "  ['com.google.firebase.analytics.FirebaseAnalytics$ConsentStatus'] = { DENIED = 'DENIED' },\n" +
                "  ['com.amazon.device.ads.AdRegistration$ConsentStatus'] = { EXPLICIT_NO = 'EXPLICIT_NO' } }\n" +
                "local function paramof2(result, args)\n" +
                "  local param = paramof(result, args)\n" +
                "  param.findClass = function(self, name) return classes[name] end\n" +
                "  return param\n" +
                "end\n" +
                "local consent, enabled\n" +
                "local sdk = { setAnalyticsCollectionEnabled = function(self, value) enabled = value end,\n" +
                "  setConsent = function(self, map) consent = map end }\n" +
                "local stopped = after(hookof('getInstance', 'com.google.firebase.analytics.FirebaseAnalytics'), paramof2(sdk))\n" +
                "local granted = luajava.newInstance('java.util.HashMap')\n" +
                "granted:put('ANALYTICS_STORAGE', 'GRANTED')\n" +
                "local param = paramof2(nil, { [0] = granted })\n" +
                "local replaced = before(hookof('setConsent'), param)\n" +
                "local status\n" +
                "local ads = { setConsentStatus = function(self, value) status = value end }\n" +
                "local amazon = after(hookof('getInstance', 'com.amazon.device.ads.AdRegistration'), paramof2(ads))\n" +
                "local keep = before(hookof('setConsentStatus'), paramof2(nil, { [0] = 'EXPLICIT_NO' }))\n" +
                "local yes = paramof2(nil, { [0] = 'EXPLICIT_YES' })\n" +
                "return stopped, enabled, consent:size(), consent:get('FUTURE'), replaced,\n" +
                "  param.args[0]:get('ANALYTICS_STORAGE'), amazon, status, keep, before(hookof('setConsentStatus'), yes), yes.args[0]");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals(3, result.arg(3).toint());
        assertEquals("DENIED", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("DENIED", result.arg(6).tojstring());
        assertTrue(result.arg(7).toboolean());
        assertEquals("EXPLICIT_NO", result.arg(8).tojstring());
        assertFalse(result.arg(9).toboolean());
        assertTrue(result.arg(10).toboolean());
        assertEquals("EXPLICIT_NO", result.arg(11).tojstring());
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

    // The type allocation code and the manufacturer code are the first 8 digits of the fake IMEI and MEID
    @Test
    public void deviceCodesOfFakeIds() throws Exception {
        Globals globals = Scripts.load("value_device_code", null);
        String param = "local function paramof(result, settings)\n" +
                "  return { result = result, getResult = function(self) return self.result end,\n" +
                "    getException = function(self) return nil end,\n" +
                "    setResult = function(self, value) self.result = value end,\n" +
                "    getSetting = function(self, name) return settings[name] end }\n" +
                "end\n" +
                "local function hookof(method) return { getMethodName = function(self) return method end } end\n" +
                "local settings = { ['value.imei'] = '490154203237518', ['value.meid'] = 'A0000049FBC2A1' }\n";
        Varargs result = Scripts.run(globals, param +
                "local tac, mc, none = paramof('35824005', settings), paramof('A1000001', settings), paramof(nil, settings)\n" +
                "return after(hookof('getTypeAllocationCode'), tac), tac.result, after(hookof('getManufacturerCode'), mc), mc.result,\n" +
                "  after(hookof('getTypeAllocationCode'), none), none.result == nil");
        assertTrue(result.arg(1).toboolean());
        assertEquals("49015420", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("A0000049", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
    }

    // The bytes of the hardware address of an interface are 02:00:00:00:00:00 like Android gives when it is not
    // accessible, not null, which Android does not give an app for an interface with an address
    @Test
    public void hardwareAddressBytes() throws Exception {
        Globals globals = Scripts.load("mac_address", null);
        Varargs result = Scripts.run(globals,
                "local array = luajava.bindClass('java.lang.reflect.Array')\n" +
                        "local real = array:newInstance(luajava.bindClass('java.lang.Byte').TYPE, 6)\n" +
                        "real[1] = 82 real[2] = 84 real[6] = 18\n" +
                        "local param = { result = real, getResult = function(self) return self.result end,\n" +
                        "  setResult = function(self, value) self.result = value end }\n" +
                        "local hook = { getMethodName = function(self) return 'getHardwareAddress' end }\n" +
                        "local restricted = after(hook, param)\n" +
                        "local fake = param.result\n" +
                        "local again = after(hook, param)\n" +
                        "return restricted, fake['length'], fake[1], fake[2], fake[6], again");
        assertTrue(result.arg(1).toboolean());
        assertEquals(6, result.arg(2).toint());
        assertEquals(2, result.arg(3).toint());
        assertEquals(0, result.arg(4).toint());
        assertEquals(0, result.arg(5).toint());
        assertFalse(result.arg(6).toboolean());
    }

    // The label of the voicemail number is the default label of Android, never null
    @Test
    public void voicemailLabelIsTheDefault() throws Exception {
        Globals globals = load("value_voicemail_tag");
        Scripts.run(globals, "luajava = { bindClass = function(name)\n" +
                "  assert(name == 'android.content.res.Resources', name)\n" +
                "  return { getSystem = function(self) return { getIdentifier = function(self, n, t, p)\n" +
                "    return (n == 'defaultVoiceMailAlphaTag' and t == 'string' and p == 'android') and 17 or 0 end,\n" +
                "    getString = function(self, id) return 'Voicemail' end } end }\n" +
                "end }");
        check(globals, "return after_(nil, paramof('Mailbox of Alice'))", true, true, "Voicemail");
        check(globals, "return after_(nil, paramof('Voicemail'))", false, false, "Voicemail");
        check(globals, "return after_(nil, paramof(nil))", false, false, null);
    }
}
