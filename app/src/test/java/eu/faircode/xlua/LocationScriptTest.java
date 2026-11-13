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

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The location script (assets/location.lua) with Lua stand-ins for the parameter and the location,
// because the Android classes are stubs in unit tests; the script reads the result and the settings only
public class LocationScriptTest {
    private static final String STUBS = String.join("\n",
            // A complete fix of the GNSS, like Android delivers
            "function location(latitude, longitude)",
            "  return { latitude = latitude, longitude = longitude, accuracy = 4, time = 1700000000000, elapsed = 5,",
            "    altitude = 12, speed = 1.5, bearing = 90, provider = 'gps',",
            "    extras = { values = { satellites = 9, noGPSLocation = 'real' },",
            "      keySet = function(self) local keys = {} for k in pairs(self.values) do keys[#keys + 1] = k end",
            "        keys.length = #keys return { toArray = function() return keys end } end,",
            "      remove = function(self, key) self.values[key] = nil end,",
            "      isEmpty = function(self) return next(self.values) == nil end },",
            "    getProvider = function(self) return self.provider end,",
            "    getLatitude = function(self) return self.latitude end,",
            "    getLongitude = function(self) return self.longitude end,",
            "    setLatitude = function(self, value) self.latitude = value end,",
            "    setLongitude = function(self, value) self.longitude = value end,",
            "    hasAccuracy = function(self) return self.accuracy ~= nil end,",
            "    getAccuracy = function(self) return self.accuracy end,",
            "    setAccuracy = function(self, value) self.accuracy = value end,",
            "    getTime = function(self) return self.time end,",
            "    getElapsedRealtimeNanos = function(self) return self.elapsed end,",
            "    hasAltitude = function(self) return self.altitude ~= nil end,",
            "    setAltitude = function(self, value) self.altitude = value end,",
            "    removeAltitude = function(self) self.altitude = nil end,",
            "    hasSpeed = function(self) return self.speed ~= nil end,",
            "    setSpeed = function(self, value) self.speed = value end,",
            "    removeSpeed = function(self) self.speed = nil end,",
            "    removeBearing = function(self) self.bearing = nil end,",
            "    getExtras = function(self) return self.extras end,",
            "    setExtras = function(self, extras) self.extras = extras end,",
            "    toString = function(self) return self.latitude .. ',' .. self.longitude end }",
            "end",
            // An HWLocation of HMS, of its JSON without a provider and the time since boot, with an address without a
            // phone; getters and setters of the fields of the same names, with no has and remove methods, and extra info,
            // a map
            "function hwlocation(latitude, longitude)",
            "  local hw = { Latitude = latitude, Longitude = longitude, Accuracy = 4, Altitude = 12, Speed = 1.5, Bearing = 90,",
            "    BearingAccuracyDegrees = 5, VerticalAccuracyMeters = 3, SpeedAccuracyMetersPerSecond = 1, City = 'Amsterdam',",
            "    Street = 'Dam 1', PostalCode = '1012', extra = { values = { satellites = 9, ['hw-address-city'] = 'Amsterdam' },",
            "      keySet = function(self) local keys = {} for k in pairs(self.values) do keys[#keys + 1] = k end",
            "        keys.length = #keys return { toArray = function() return keys end } end,",
            "      remove = function(self, key) self.values[key] = nil end,",
            "      isEmpty = function(self) return next(self.values) == nil end,",
            "      clear = function(self) self.values = {} end },",
            "    getTime = function(self) return 1700000000000 end,",
            "    getProvider = function(self) return nil end,",
            "    getElapsedRealtimeNanos = function(self) return 0 end,",
            "    getExtraInfo = function(self) return self.extra end,",
            "    toString = function(self) return 'HWLocation' end }",
            "  for _, name in ipairs({ 'Latitude', 'Longitude', 'Accuracy', 'Altitude', 'Speed', 'Bearing', 'BearingAccuracyDegrees',",
            "      'VerticalAccuracyMeters', 'SpeedAccuracyMetersPerSecond', 'CountryCode', 'CountryName', 'State', 'City',",
            "      'County', 'Street', 'FeatureName', 'PostalCode', 'Phone', 'Url' }) do",
            "    hw['get' .. name] = function(self) return self[name] end",
            "    hw['set' .. name] = function(self, value) self[name] = value end",
            "  end",
            "  return hw",
            "end",
            // A LocationResult of HMS of a parcel, of HWLocations
            "function resultof(...)",
            "  local list = { ... }",
            "  return { class = 'com.huawei.hms.location.LocationResult', getHWLocationList = function(self)",
            "    return { size = function(self) return #list end, get = function(self, i) return list[i + 1] end } end }",
            "end",
            // The hooks of the script
            "function hookof(class, method)",
            "  return { getClassName = function(self) return class end, getMethodName = function(self) return method end }",
            "end",
            "LOCATION = hookof('android.location.Location', 'CREATOR:createFromParcel')",
            "PARSE = hookof('com.huawei.hms.support.api.location.common.LocationJsonUtil', 'parseLocationFromJsonObject')",
            "PARCEL = hookof('com.huawei.hms.location.LocationResult', 'CREATOR:createFromParcel')",
            "CREATE = hookof('com.huawei.hms.location.LocationResult', 'create')",
            // XParam.uuidOf, a hash of the seed
            "local UUID = luajava.bindClass('java.util.UUID')",
            "local UTF8 = luajava.bindClass('java.nio.charset.StandardCharsets').UTF_8",
            "function paramof(result, settings)",
            "  return {",
            "    getResult = function(self) return result end,",
            "    getSetting = function(self, name) return settings[name] end,",
            "    isInstance = function(self, object, name) return object ~= nil and object.class == name end,",
            "    uuidOf = function(self, seed)",
            "      return UUID:nameUUIDFromBytes(UTF8:encode(seed):array()):toString() end }",
            "end");

    private static final String COARSE = "{ ['location.type'] = 'coarse', ['location.accuracy'] = '10000', ['location.key'] = 'a' }";
    private static final double STEP = 10000 / 111000.0;

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("location", STUBS);
    }

    // A location the app made itself, like a place picked on a map passed between its screens, stays: of another
    // provider, or not complete, which Android never delivers; one of a provider of the system, also one the device
    // names only, becomes the fake one
    @Test
    public void ownLocationsStay() throws Exception {
        Varargs result = Scripts.run(globals,
                "local own = location(52.1, 4.3)\n" +
                        "own.provider = 'picker'\n" +
                        "local context = { getSystemService = function(self, name) return {\n" +
                        "  getAllProviders = function(self) return { contains = function(self, p) return p == 'vendor' end } end } end }\n" +
                        "local function paramwith(result) local param = paramof(result, {})\n" +
                        "  param.getApplicationContext = function(self) return context end return param end\n" +
                        "local mine = after(LOCATION, paramwith(own))\n" +
                        "local vendor = location(52.1, 4.3)\n" +
                        "vendor.provider = 'vendor'\n" +
                        "local fused = location(52.1, 4.3)\n" +
                        "fused.provider = 'fused'\n" +
                        "local picked = location(52.1, 4.3)\n" +
                        "picked.elapsed = 0\n" +
                        "return mine, own.latitude, after(LOCATION, paramwith(vendor)), vendor.latitude,\n" +
                        "  after(LOCATION, paramwith(fused)), fused.latitude, after(LOCATION, paramwith(picked)), picked.latitude");
        assertFalse(result.arg(1).toboolean());
        assertEquals(52.1, result.arg(2).todouble(), 0);
        assertTrue(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).todouble(), 1e-4);
        assertTrue(result.arg(5).toboolean());
        assertEquals(0, result.arg(6).todouble(), 1e-4);
        assertFalse(result.arg(7).toboolean());
        assertEquals(52.1, result.arg(8).todouble(), 0);
    }

    // An HWLocation of HMS, of its JSON and in the results of its parcels and of LocationResult.create, whatever its
    // provider and time since boot, is faked in place like a Location of the same fix, so the Location copies HMS makes
    // of it agree; its address is empty, a field it has not stays none, and its extra info, which HMS copies to the
    // extras, keeps what tells nothing of the place; one read again, like of a parcel the app wrote, stays the same
    @Test
    public void hmsLocationsAreFakedInPlace() {
        String settings = "{ ['location.type'] = 'set', ['location.latitude'] = '10.5', ['location.longitude'] = '20', " +
                "['location.key'] = 'a' }";
        Varargs result = Scripts.run(globals,
                "local settings = " + settings + "\n" +
                        "local hw = hwlocation(52.37, 4.89)\n" +
                        "local restricted = after(PARSE, paramof(hw, settings))\n" +
                        "local fix = location(52.37, 4.89)\n" +
                        "after(LOCATION, paramof(fix, settings))\n" +
                        "local latitude, longitude = hw.Latitude, hw.Longitude\n" +
                        "local first, second = hwlocation(52.37, 4.89), hwlocation(52.37, 4.89)\n" +
                        "local updates = after(PARCEL, paramof(resultof(first, second), settings))\n" +
                        "after(CREATE, paramof(resultof(hw), settings))\n" +
                        "local created = hwlocation(52.37, 4.89)\n" +
                        "local made = after(CREATE, paramof(resultof(created), settings))\n" +
                        "return restricted, latitude - fix.latitude, longitude - fix.longitude, hw.Accuracy, hw.Altitude,\n" +
                        "  hw.Speed, hw.Bearing, hw.BearingAccuracyDegrees, hw.City, hw.Street, hw.PostalCode, hw.Phone,\n" +
                        "  hw.extra.values.satellites, hw.extra.values['hw-address-city'], hw.extra ~= nil, updates,\n" +
                        "  first.Latitude - latitude, second.Longitude - longitude, hw.Latitude - latitude, hw.Longitude - longitude,\n" +
                        "  made, created.Latitude - latitude, created.Longitude - longitude, created.City");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).todouble(), 0);
        assertEquals(0, result.arg(3).todouble(), 0);
        assertEquals(4, result.arg(4).todouble(), 0);
        for (int i = 5; i <= 8; i++)
            assertEquals(0, result.arg(i).todouble(), 0);
        for (int i = 9; i <= 11; i++)
            assertEquals("", result.arg(i).tojstring());
        assertTrue(result.arg(12).isnil());
        assertEquals(9, result.arg(13).toint());
        assertTrue(result.arg(14).isnil());
        assertTrue(result.arg(15).toboolean());
        assertTrue(result.arg(16).toboolean());
        for (int i = 17; i <= 20; i++)
            assertEquals(0, result.arg(i).todouble(), 0);
        assertTrue(result.arg(21).toboolean());
        assertEquals(0, result.arg(22).todouble(), 0);
        assertEquals(0, result.arg(23).todouble(), 0);
        assertEquals("", result.arg(24).tojstring());
    }

    // A coarse HWLocation is the center of the cell of a Location of the same fix, with the accuracy of the grid, without
    // altitude, movement, extra info and address, and stays in its cell when read again
    @Test
    public void hmsLocationsAreCoarse() {
        Varargs result = Scripts.run(globals,
                "local hw = hwlocation(52.37, 4.89)\n" +
                        "local restricted = after(PARSE, paramof(hw, " + COARSE + "))\n" +
                        "local fix = location(52.37, 4.89)\n" +
                        "after(LOCATION, paramof(fix, " + COARSE + "))\n" +
                        "local latitude = hw.Latitude\n" +
                        "return restricted, hw.Latitude - fix.latitude, hw.Longitude - fix.longitude, hw.Accuracy, hw.Altitude,\n" +
                        "  hw.VerticalAccuracyMeters, hw.Speed, hw.SpeedAccuracyMetersPerSecond, hw.Bearing,\n" +
                        "  hw.BearingAccuracyDegrees, hw.City, hw.extra:isEmpty(), after(PARSE, paramof(hw, " + COARSE + ")),\n" +
                        "  hw.Latitude - latitude");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).todouble(), 0);
        assertEquals(0, result.arg(3).todouble(), 0);
        assertEquals(10000, result.arg(4).todouble(), 0);
        for (int i = 5; i <= 10; i++)
            assertEquals(0, result.arg(i).todouble(), 0);
        assertEquals("", result.arg(11).tojstring());
        assertTrue(result.arg(12).toboolean());
        assertFalse(result.arg(13).toboolean());
        assertEquals(0, result.arg(14).todouble(), 0);
    }

    // The places the geocoder of HMS answers, which it makes of the same JSON, stay
    @Test
    public void hmsGeocoderPlacesStay() {
        Varargs result = Scripts.run(globals,
                "local util = 'com.huawei.hms.support.api.location.common.LocationJsonUtil'\n" +
                        "function callers() return { length = 2,\n" +
                        "  { getClassName = function() return util end,\n" +
                        "    getMethodName = function() return 'parseLocationFromJsonObject' end },\n" +
                        "  { getClassName = function() return util end,\n" +
                        "    getMethodName = function() return 'parseHwLocationsFromJsonObject' end } }\n" +
                        "end\n" +
                        "local place = hwlocation(48.8584, 2.2945)\n" +
                        "return after(PARSE, paramof(place, {})), place.Latitude, place.City");
        assertFalse(result.arg(1).toboolean());
        assertEquals(48.8584, result.arg(2).todouble(), 0);
        assertEquals("Amsterdam", result.arg(3).tojstring());
    }

    // R8 can merge the creator of LocationResult with those of the other classes of HMS, whose objects stay as they are,
    // without an error: an HWLocation, like a place of the geocoder the app parcels, and a LocationAvailability
    @Test
    public void hmsOtherParcelsStay() {
        Varargs result = Scripts.run(globals,
                "local place = hwlocation(48.8584, 2.2945)\n" +
                        "local availability = { isLocationAvailable = function(self) return true end }\n" +
                        "return after(PARCEL, paramof(place, {})), place.Latitude, place.City,\n" +
                        "  after(PARCEL, paramof(availability, {}))");
        assertFalse(result.arg(1).toboolean());
        assertEquals(48.8584, result.arg(2).todouble(), 0);
        assertEquals("Amsterdam", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
    }

    // Callbacks with cells are registered as they are, for their other events too, like the call state, and their
    // methods of the cells are hooked (telephonymanager_registertelephonycallback); callbacks without cells stay, and the
    // callback of a network scan is known first, without the classes of TelephonyCallback, which Android 8.1 to 11 lack;
    // the reset of the first location, which runs code of the app (the hashCode of the callback), comes after the hooks
    @Test
    public void telephonyCallbacksKeepTheirOtherEvents() throws Exception {
        Globals callbacks = Scripts.load("telephonymanager_registertelephonycallback", String.join("\n",
                "hooked = {}",
                "function hook(object, method, fun) assert(fun == nocells) hooked[#hooked + 1] = method end",
                // The callback is the last parameter: (executor, callback) and of Android 13 (flag, executor, callback)
                "function hookof(count) return { getParameterTypes = function(self) return { length = count } end } end",
                "function paramof(interfaces, index)",
                "  local callback = {}",
                "  return { result = 'none',",
                "    getArgument = function(self, i) if i == index then return callback end return nil end,",
                "    isInstance = function(self, object, name)",
                "      if interfaces.scan and name:find('TelephonyCallback') then error(name) end",
                "      return interfaces[name] == true end,",
                "    putValue = function(self, name, value, scope) assert(name == 'nocells' and scope == callback)",
                "      self.reset = value end,",
                "    setResult = function(self, value) self.result = value end }",
                "end"));
        Varargs result = Scripts.run(callbacks,
                "local param = paramof({ ['android.telephony.TelephonyCallback$CellInfoListener'] = true,\n" +
                        "  ['android.telephony.TelephonyCallback$CellLocationListener'] = true,\n" +
                        "  ['android.telephony.TelephonyCallback$RegistrationFailedListener'] = true,\n" +
                        "  ['android.telephony.TelephonyCallback$BarringInfoListener'] = true,\n" +
                        "  ['android.telephony.TelephonyCallback$CallStateListener'] = true }, 2)\n" +
                        "local restricted = before(hookof(3), param)\n" +
                        "local cells = table.concat(hooked, ',')\n" +
                        "hooked = {}\n" +
                        "local calls = paramof({ ['android.telephony.TelephonyCallback$CallStateListener'] = true }, 1)\n" +
                        "local quiet = before(hookof(2), calls)\n" +
                        "local scan = paramof({ scan = true,\n" +
                        "  ['android.telephony.TelephonyScanManager$NetworkScanCallback'] = true }, 2)\n" +
                        "local scanned, scanhooked = before(hookof(3), scan), table.concat(hooked, ',')\n" +
                        "hooked = {}\n" +
                        "local thrown = paramof({ ['android.telephony.TelephonyCallback$CellLocationListener'] = true }, 1)\n" +
                        "thrown.putValue = function() error('hashCode') end\n" +
                        "return restricted, param.result, cells, quiet, calls.result, scanned, scanhooked, param.reset,\n" +
                        "  before(hookof(2), thrown), table.concat(hooked, ',')");
        assertTrue(result.arg(1).toboolean());
        // Registered as it is
        assertEquals("none", result.arg(2).tojstring());
        assertEquals("onCellInfoChanged,onCellLocationChanged,onRegistrationFailed,onBarringInfoChanged", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
        assertEquals("none", result.arg(5).tojstring());
        assertTrue(result.arg(6).toboolean());
        assertEquals("onResults", result.arg(7).tojstring());
        // Each registration gets its first location again (nocells)
        assertFalse(result.arg(8).isnil() || result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        assertEquals("onCellLocationChanged", result.arg(10).tojstring());
    }

    // The methods of the cells run, at the registration too, with the cells without their location: no cells in a list,
    // the cell location made empty, the cell of a registration failure and of the barring info like Android gives them
    // to apps without fine location; null stays, and when the argument cannot be made the method does not run; a
    // registration gets one empty location, which Android does not send again while it stays the same
    @Test
    public void telephonyCallbacksGetNoCells() throws Exception {
        Globals callbacks = Scripts.load("telephonymanager_registertelephonycallback", String.join("\n",
                // The identity of an object, which a Lua table has in its text
                "luajava = { newInstance = function(name) return { class = name } end,",
                "  bindClass = function(name) assert(name == 'java.lang.System')",
                "    return { identityHashCode = function(self, object) return tostring(object) end } end }",
                "function valueof(class, value) value.class = class return value end",
                "values = {}",
                "function paramof(value, this)",
                "  return { args = { [0] = value }, result = 'none', this = this or 'callback',",
                "    getArgument = function(self, i) return self.args[i] end,",
                "    setArgument = function(self, i, v) self.args[i] = v end,",
                "    getThis = function(self) return self.this end,",
                "    getApplicationContext = function(self) return 'context' end,",
                "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end,",
                "    putValue = function(self, name, value, scope) values[scope] = values[scope] or {} values[scope][name] = value end,",
                "    isInstance = function(self, object, name) return object.class == name end,",
                "    setResult = function(self, value) self.result = value end }",
                "end",
                "function cells(value, when, this) local param = paramof(value, this) nocells(when or 'before', param)",
                "  return param.args[0], param.result end",
                "function locationof(cid)",
                "  return valueof('android.telephony.CellLocation', { cid = cid, setStateInvalid = function(self) self.cid = -1 end })",
                "end"));
        Varargs result = Scripts.run(callbacks,
                "local list = cells(valueof('java.util.List', {}))\n" +
                        "local location = valueof('android.telephony.CellLocation', { cid = 1234,\n" +
                        "  setStateInvalid = function(self) self.cid = -1 end })\n" +
                        "local empty, ran = cells(location)\n" +
                        // The hook of the other form of registerTelephonyCallback, on the same delivery
                        "local _, same = cells(location)\n" +
                        "local barring = cells(valueof('android.telephony.BarringInfo', {\n" +
                        "  createLocationInfoSanitizedCopy = function(self) return 'barring without cell' end }))\n" +
                        "local identity = cells(valueof('android.telephony.CellIdentityLte', {\n" +
                        "  sanitizeLocationInfo = function(self) return 'cell without location' end }))\n" +
                        "local none, noneran = cells(nil)\n" +
                        "local refused = valueof('android.telephony.CellIdentityLte', {\n" +
                        "  sanitizeLocationInfo = function(self) error('refused') end })\n" +
                        "local kept, skipped = cells(refused)\n" +
                        "local after = cells(valueof('java.util.List', {}), 'after')\n" +
                        "return list.class, empty == location, location.cid, ran, barring, identity, none, noneran,\n" +
                        "  kept == refused, skipped, after.class, same");
        assertEquals("java.util.ArrayList", result.arg(1).tojstring());
        // The location the callback got, of its own class, made empty
        assertTrue(result.arg(2).toboolean());
        assertEquals(-1, result.arg(3).toint());
        assertEquals("none", result.arg(4).tojstring());
        assertEquals("barring without cell", result.arg(5).tojstring());
        assertEquals("cell without location", result.arg(6).tojstring());
        assertTrue(result.arg(7).isnil());
        assertEquals("none", result.arg(8).tojstring());
        // Refused, the method does not run rather than get the cell
        assertTrue(result.arg(9).toboolean());
        assertTrue(result.arg(10).isnil());
        assertEquals("java.util.List", result.arg(11).tojstring());
        assertEquals("none", result.arg(12).tojstring());

        // The next location of the same callback does not run, that of another callback does, and after a new
        // registration (before, which resets the value) the callback gets one again, once
        result = Scripts.run(callbacks, "local handover = locationof(5678)\n" +
                "local _, repeated = cells(handover)\n" +
                "local other = locationof(5678)\n" +
                "local _, otherran = cells(other, nil, 'other')\n" +
                "values['callback'].nocells = false\n" +
                "local again = locationof(5678)\n" +
                "local _, againran = cells(again)\n" +
                "local _, twice = cells(locationof(5678))\n" +
                "return repeated, handover.cid, otherran, other.cid, againran, again.cid, twice");
        assertTrue(result.arg(1).isnil());
        assertEquals(5678, result.arg(2).toint());
        assertEquals("none", result.arg(3).tojstring());
        assertEquals(-1, result.arg(4).toint());
        assertEquals("none", result.arg(5).tojstring());
        assertEquals(-1, result.arg(6).toint());
        assertTrue(result.arg(7).isnil());
        // The copy in telephonymanager_listen, which is loaded apart, is the same
        assertEquals(Scripts.function("telephonymanager_registertelephonycallback", "nocells"),
                Scripts.function("telephonymanager_listen", "nocells"));
    }

    // Whether the script restricted a location and the location it left, of a fix at a time
    private Varargs after(double latitude, double longitude, long time, String settings) {
        return Scripts.run(globals,
                "local result = location(" + latitude + ", " + longitude + ")\n" +
                        "result.time = " + time + "\n" +
                        "local restricted = after(LOCATION, paramof(result, " + settings + "))\n" +
                        "return restricted, result.latitude, result.longitude, result.accuracy");
    }

    private Varargs after(double latitude, double longitude, String settings) {
        return after(latitude, longitude, 1700000000000L, settings);
    }

    // Within the accuracy of the fix around the place set
    @Test
    public void parcelGetsSetLocation() {
        Varargs result = after(52.37, 4.89,
                "{ ['location.type'] = 'set', ['location.latitude'] = '10.5', ['location.longitude'] = '-20' }");
        assertTrue(result.arg(1).toboolean());
        assertEquals(10.5, result.arg(2).todouble(), 4 / 111000.0);
        assertEquals(-20, result.arg(3).todouble(), 4 / 111000.0 / Math.cos(Math.toRadians(10.5)));
        assertEquals(4, result.arg(4).todouble(), 0);
    }

    // The altitude and the movement are of a device at sea level which stays where it is; the extras keep only what
    // tells nothing of the place, like the number of satellites, which a fix of the GNSS always has
    @Test
    public void realDetailsAreOfAStillDevice() {
        Varargs result = Scripts.run(globals,
                "local result = location(52.37, 4.89)\n" +
                        "after(LOCATION, paramof(result, {}))\n" +
                        "local other = location(52.37, 4.89)\n" +
                        "other.extras.values = { noGPSLocation = 'real' }\n" +
                        "after(LOCATION, paramof(other, {}))\n" +
                        "return result.altitude, result.speed, result.bearing, result.extras.values.satellites,\n" +
                        "  result.extras.values.noGPSLocation, other.extras");
        assertEquals(0, result.arg(1).todouble(), 0);
        assertEquals(0, result.arg(2).todouble(), 0);
        assertTrue(result.arg(3).isnil());
        assertEquals(9, result.arg(4).toint());
        assertTrue(result.arg(5).isnil());
        assertTrue(result.arg(6).isnil());
    }

    // The same fix read again, like from a parcel the app wrote, gives the same fake; the fixes of a device which stays
    // where it is move slowly, within the accuracy, not each to anywhere in it
    @Test
    public void setLocationDriftsSlowly() {
        String settings = "{ ['location.type'] = 'set', ['location.latitude'] = '10.5', ['location.longitude'] = '20', " +
                "['location.key'] = 'a' }";
        long time = 1700000000000L;
        Varargs first = after(52.37, 4.89, time, settings);
        Varargs again = after(first.arg(2).todouble(), first.arg(3).todouble(), time, settings);
        assertEquals(first.arg(2).todouble(), again.arg(2).todouble(), 0);
        assertEquals(first.arg(3).todouble(), again.arg(3).todouble(), 0);

        double moved = 0;
        Varargs previous = first;
        for (int second = 1; second <= 600; second++) {
            Varargs next = after(52.37, 4.89, time + second * 1000L, settings);
            double north = (next.arg(2).todouble() - previous.arg(2).todouble()) * 111000;
            double east = (next.arg(3).todouble() - previous.arg(3).todouble()) * 111000 * Math.cos(Math.toRadians(10.5));
            assertTrue("step " + second, Math.hypot(north, east) < 0.25);
            double off = Math.hypot((next.arg(2).todouble() - 10.5) * 111000,
                    (next.arg(3).todouble() - 20) * 111000 * Math.cos(Math.toRadians(10.5)));
            assertTrue("offset " + off, off <= 4 + 1e-6);
            moved = Math.max(moved, Math.hypot((next.arg(2).todouble() - first.arg(2).todouble()) * 111000,
                    (next.arg(3).todouble() - first.arg(3).todouble()) * 111000 * Math.cos(Math.toRadians(10.5))));
            previous = next;
        }
        assertTrue("moved " + moved, moved > 0.5);
    }

    // The coarse location is the center of a cell of a grid with the size of the accuracy: another process or start
    // of the app, or another location close by, gives the same location, and the location read again stays
    @Test
    public void coarseLocationIsStable() throws Exception {
        Varargs first = after(52.37, 4.89, COARSE);
        assertTrue(first.arg(1).toboolean());

        load(); // Like another process
        Varargs again = after(52.37, 4.89, COARSE);
        assertEquals(first.arg(2).todouble(), again.arg(2).todouble(), 0);
        assertEquals(first.arg(3).todouble(), again.arg(3).todouble(), 0);

        Varargs nearby = after(52.37001, 4.89001, COARSE);
        assertEquals(first.arg(2).todouble(), nearby.arg(2).todouble(), 0);
        assertEquals(first.arg(3).todouble(), nearby.arg(3).todouble(), 0);

        Varargs reread = after(first.arg(2).todouble(), first.arg(3).todouble(), COARSE);
        assertFalse(reread.arg(1).toboolean());
        assertEquals(first.arg(2).todouble(), reread.arg(2).todouble(), 0);
        assertEquals(first.arg(3).todouble(), reread.arg(3).todouble(), 0);

        Varargs far = after(48.85, 2.35, COARSE);
        assertTrue(Math.abs(far.arg(2).todouble() - 48.85) < 0.2);
    }

    // A center of a cell, near the real location: within half a cell and the offset, of a quarter of a cell normally
    @Test
    public void coarseLocationIsInCell() {
        for (double latitude = -89.9; latitude < 90; latitude += 7.3)
            for (double longitude = -179.9; longitude < 180; longitude += 11.7) {
                Varargs result = after(latitude, longitude, COARSE);
                double clatitude = result.arg(2).todouble();
                double clongitude = result.arg(3).todouble();
                assertTrue("latitude " + clatitude, clatitude >= -90 && clatitude <= 90);
                assertTrue("longitude " + clongitude, clongitude >= -180 && clongitude < 180);
                assertTrue(latitude + " " + clatitude, Math.abs(clatitude - latitude) <= STEP * 1.8);
                Varargs center = globals.get("snaptogrid").invoke(LuaValue.varargsOf(
                        LuaValue.valueOf(clatitude), LuaValue.valueOf(clongitude), LuaValue.valueOf(10000)));
                assertEquals(clatitude, center.arg(1).todouble(), 1e-9);
                assertEquals(clongitude, center.arg(2).todouble(), 1e-9);
            }
    }

    // Walking north, the coarse location changes cell where the real location crosses a line moved by the offset of
    // the app, not where it crosses a line of the grid, which anyone can compute
    @Test
    public void coarseCellChangesAtSecretLines() {
        double one = changesAt("a");
        double other = changesAt("b");
        double line = (Math.floor(one / STEP) + 0.5) * STEP;
        double nearest = Math.min(Math.abs(one - line), Math.abs(one - line + STEP));
        assertTrue("line " + nearest * 111000, Math.min(nearest, Math.abs(one - line - STEP)) * 111000 > 50);
        assertTrue("apps " + Math.abs(one - other) * 111000, Math.abs(one - other) * 111000 > 50);
    }

    // The real latitude, in steps of 10 m, at which the coarse latitude changes first
    private double changesAt(String key) {
        String settings = "{ ['location.type'] = 'coarse', ['location.accuracy'] = '10000', ['location.key'] = '" + key + "' }";
        double start = after(52.0, 4.89, settings).arg(2).todouble();
        for (int meters = 10; meters < 20000; meters += 10) {
            double latitude = 52.0 + meters / 111000.0;
            if (after(latitude, 4.89, settings).arg(2).todouble() != start)
                return latitude;
        }
        throw new AssertionError("no change");
    }

    // A coarse location is not more accurate than its grid, like the coarse locations of Android, and has no
    // altitude, movement or extras; a set location keeps its accuracy
    @Test
    public void coarseAccuracyIsTheGrid() {
        Varargs result = Scripts.run(globals,
                "local precise = location(52.1, 4.3)\n" +
                        "after(LOCATION, paramof(precise, { ['location.type'] = 'coarse', ['location.accuracy'] = '2000' }))\n" +
                        "local default = location(52.1, 4.3)\n" +
                        "after(LOCATION, paramof(default, { ['location.type'] = 'coarse' }))\n" +
                        "local set = location(52.1, 4.3)\n" +
                        "after(LOCATION, paramof(set, { ['location.type'] = 'set', ['location.latitude'] = '1',\n" +
                        "  ['location.longitude'] = '2' }))\n" +
                        "return precise.accuracy, default.accuracy, set.accuracy, precise.altitude, precise.speed, precise.extras");
        assertEquals(2000, result.arg(1).todouble(), 0);
        assertEquals(10000, result.arg(2).todouble(), 0);
        assertEquals(4, result.arg(3).todouble(), 0);
        assertTrue(result.arg(4).isnil());
        assertTrue(result.arg(5).isnil());
        assertTrue(result.arg(6).isnil());
    }

    // Intents and their extras as Android keeps them, with the parcel of the Java runtime: a value stays in the parcel
    // until a read, which reads all values before Android 13 and only the one from Android 13, and a read of a location
    // runs the hook of Location.CREATOR; a parcel has the classes of the values as UTF-16 and cannot be marshalled with
    // a binder; the parcel of extras is in a field which Class lists (BaseBundle.mParcelledData) unless hidden is set;
    // the hook of Location.CREATOR is there unless hooked is false
    private static final String INTENT_STUBS = String.join("\n",
            "real = luajava sdk = 34 reads = 0 writes = 0 hidden = false hooked = true MARKS = {}",
            "UTF16 = real.bindClass('java.nio.charset.StandardCharsets').UTF_16LE",
            "FIELD = { getName = function(self) return 'mParcelledData' end, setAccessible = function(self, on) end,",
            "  get = function(self, bundle) return bundle.inparcel and 'parcel' or nil end }",
            "luajava = { newInstance = real.newInstance, bindClass = function(name)",
            "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "  if name == 'android.os.Parcel' then return { obtain = function() return parcelof() end } end",
            "  if name == 'android.os.BaseBundle' then return 'BaseBundle' end",
            "  if name == 'java.lang.Class' then return { getDeclaredFields = function(class) assert(class == 'BaseBundle')",
            "    return hidden and { length = 0 } or { FIELD, length = 1 } end } end",
            "  return real.bindClass(name) end }",
            "function parcelof() return {",
            "  marshall = function(self) assert(self.text ~= nil, 'not written')",
            "    if self.binder then error('Tried to marshall a Parcel that contained Binder objects.') end",
            "    return UTF16:encode(self.text):array() end,",
            "  setDataPosition = function(self, position) end, recycle = function(self) end,",
            "  readBundle = function(self, loader) return bundleof(self.values, self.binder, loader, true) end } end",
            "function bundleof(values, binder, loader, inparcel)",
            "  local bundle = { values = {}, binder = binder, loader = loader, inparcel = inparcel }",
            "  for key, value in pairs(values) do bundle.values[key] = value end",
            "  function bundle:getClassLoader() return self.loader end",
            "  function bundle:setClassLoader(loader) self.loader = loader end",
            "  function bundle:get(key)",
            "    reads = reads + 1 readloader = self.loader self.inparcel = false",
            "    for name, value in pairs(self.values) do",
            "      if value.parcelled and (name == key or sdk < 33) then",
            "        if value.broken then error('BadParcelableException') end",
            "        local read = { class = value.class, locations = {} }",
            "        for index, original in ipairs(value.locations) do",
            "          local copy = location(original.latitude, original.longitude) copy.provider = original.provider",
            "          if hooked then after(LOCATION, paramof(copy, {})) end",
            "          read.locations[index] = copy",
            "        end",
            "        self.values[name] = read",
            "      end",
            "    end",
            "    return self.values[key]",
            "  end",
            "  function bundle:writeToParcel(parcel, flags)",
            "    local text = {} parcel.values = {} parcel.binder = self.binder writes = writes + 1",
            "    for key, value in pairs(self.values) do",
            "      text[#text + 1] = key .. '=' .. value.class",
            "      parcel.values[key] = { class = value.class, parcelled = true, locations = value.locations, broken = value.broken }",
            "    end",
            "    parcel.text = table.concat(text, ';')",
            "  end",
            "  return bundle",
            "end",
            // An intent read from a parcel, or one the app made itself, with values of its own
            "function intentof(values, binder, class, made)",
            "  local intent = { extras = bundleof(values, binder, 'boot', not made), replaced = 0,",
            "    class = class or 'android.content.Intent' }",
            "  function intent:getExtras() local e = self.extras return bundleof(e.values, e.binder, e.loader, e.inparcel) end",
            "  function intent:setExtrasClassLoader(loader) self.extras.loader = loader end",
            "  function intent:replaceExtras(bundle) self.replaced = self.replaced + 1",
            "    self.extras = bundleof(bundle.values, bundle.binder, bundle.loader, bundle.inparcel) end",
            "  return intent",
            "end",
            "function fix(latitude, provider) local fix = location(latitude, 4.3) fix.provider = provider or 'gps' return fix end",
            "function parcelable(class, ...) return { class = class, parcelled = true, locations = { ... } } end",
            "INTENT = { getClassName = function(self) return 'android.content.Intent' end,",
            "  getMethodName = function(self) return 'CREATOR:createFromParcel' end }",
            "LOADER = { getClassName = function(self) return 'android.content.Intent' end,",
            "  getMethodName = function(self) return 'setExtrasClassLoader' end }",
            "CONTEXT = { getClassLoader = function(self) return 'loader' end }",
            "function intentparam(param)",
            "  param.getValue = function(self, name, scope) assert(name == 'location.intent') return MARKS[scope] end",
            "  param.putValue = function(self, name, value, scope) assert(name == 'location.intent') MARKS[scope] = value end",
            "  return param",
            "end",
            "function fromparcel(intent) return intentparam({ getResult = function(self) return intent end,",
            "  getApplicationContext = function(self) return CONTEXT end }) end",
            // The answer, the number of replaced extras, their class loader and the latitudes of the extras, with * when
            // read; of the hook of Intent.CREATOR, and of the one when Android gives the intent with the class loader
            "function describe(restricted, intent)",
            "  local keys, out = {}, { tostring(restricted), intent.replaced, tostring(intent.extras.loader) }",
            "  for key in pairs(intent.extras.values) do keys[#keys + 1] = key end",
            "  table.sort(keys)",
            "  for _, key in ipairs(keys) do",
            "    local value, latitudes = intent.extras.values[key], {}",
            "    for _, fix in ipairs(value.locations) do latitudes[#latitudes + 1] = math.floor(fix.latitude + 0.5) end",
            "    out[#out + 1] = key .. '=' .. table.concat(latitudes, ',') .. (value.parcelled and '' or '*')",
            "  end",
            "  return table.concat(out, ' ')",
            "end",
            "function check(version, values, binder)",
            "  sdk = version",
            "  local intent = intentof(values, binder)",
            "  return describe(after(INTENT, fromparcel(intent)), intent)",
            "end",
            "function deliver(version, intent, loader)",
            "  sdk = version",
            "  intent:setExtrasClassLoader(loader)",
            "  return describe(after(LOADER, intentparam({ getThis = function(self) return intent end,",
            "    getArgument = function(self, index) assert(index == 0) return loader end })), intent)",
            "end");

    // A location update of a PendingIntent is faked in the extras of the intent, also in their parcel, which the app can
    // marshal without reading them: parcelled again, like the extras of Android, with the class loader they had, read
    // with the one of the app; the other extras stay parcelled from Android 13, extras without an update are not
    // replaced, also with another location or a LocationRequest, and extras with a value the app cannot read stay as
    // they are, like a location the app made itself
    @Test
    public void locationUpdatesOfIntentsAreFaked() throws Exception {
        Scripts.run(globals, INTENT_STUBS);
        String location = "parcelable('android.location.Location', fix(52))";
        String place = "place = parcelable('com.example.Place')";
        Varargs result = Scripts.run(globals,
                "return check(34, { location = " + location + ", " + place + " }), readloader,\n" +
                        "  check(34, { location = " + location + ", locations = parcelable('android.location.Location', " +
                        "fix(52), fix(53)) }),\n" +
                        "  check(30, { location = " + location + ", " + place + " }),\n" +
                        "  check(34, { " + place + " }), reads,\n" +
                        "  check(34, { location = " + location + " }, true), check(34, { " + place + " }, true),\n" +
                        "  check(30, { location = " + location + " }, true),\n" +
                        "  check(30, { location = " + location + ", other = { class = 'com.other.Value', parcelled = true, " +
                        "locations = {}, broken = true } }),\n" +
                        "  check(34, { location = parcelable('android.location.Location', fix(52, 'picker')) }),\n" +
                        "  after(INTENT, fromparcel(nil)),\n" +
                        "  after(INTENT, fromparcel({ getExtras = function(self) return nil end })),\n" +
                        "  check(30, { mine = " + location + " }), check(34, { mine = " + location + " }),\n" +
                        "  check(30, { request = parcelable('android.location.LocationRequest') }),\n" +
                        "  check(34, { ['com.google.android.location.LOCATION'] = " + location + " })");
        assertEquals("false 1 boot location=0 place=", result.arg(1).tojstring());
        assertEquals("loader", result.arg(2).tojstring());
        assertEquals("false 1 boot location=0 locations=0,0", result.arg(3).tojstring());
        assertEquals("false 1 boot location=0 place=", result.arg(4).tojstring());
        assertEquals("false 0 boot place=", result.arg(5).tojstring());
        assertEquals(9, result.arg(6).toint());
        assertEquals("false 1 boot location=0", result.arg(7).tojstring());
        assertEquals("false 0 boot place=", result.arg(8).tojstring());
        assertEquals("false 0 boot location=52", result.arg(9).tojstring());
        assertEquals("false 0 boot location=52 other=", result.arg(10).tojstring());
        assertEquals("false 1 boot location=52", result.arg(11).tojstring());
        assertFalse(result.arg(12).toboolean());
        assertFalse(result.arg(13).toboolean());
        assertEquals("false 0 boot mine=52", result.arg(14).tojstring());
        assertEquals("false 0 boot mine=52", result.arg(15).tojstring());
        assertEquals("false 0 boot request=", result.arg(16).tojstring());
        assertEquals("false 1 boot com.google.android.location.LOCATION=0", result.arg(17).tojstring());
    }

    // An update which starts the app is read before the hooks are there, so it is faked when Android gives the intent to
    // the component, read with its class loader, which the extras keep, also none; once for each intent given, and
    // again after the hook of Intent.CREATOR, so an intent it read before the hook of Location.CREATOR was there is
    // faked too, also an intent of a subclass, like ReferrerIntent, which is marked by itself, since the marks are kept
    // by identity; never extras the app put or read, which are not even written, nor without the field of the parcel, which
    // an intent just read from its parcel does not need
    @Test
    public void locationUpdatesOfIntentsAreFakedWhenGiven() throws Exception {
        Scripts.run(globals, INTENT_STUBS);
        Varargs result = Scripts.run(globals,
                "local function update() return { location = parcelable('android.location.Location', fix(52)) } end\n" +
                        "local cold = intentof(update())\n" +
                        "local first = deliver(34, cold, 'split')\n" +
                        "local loader, counts = readloader, reads .. '/' .. writes\n" +
                        "local again = deliver(34, cold, 'split')\n" +
                        "local recounts = reads .. '/' .. writes\n" +
                        "local warm = intentof(update())\n" +
                        "after(INTENT, fromparcel(warm))\n" +
                        "local given = deliver(34, warm, 'split')\n" +
                        "local referrer = intentof(update(), false, 'android.content.pm.LabeledIntent')\n" +
                        "local referred = deliver(34, referrer, 'split') .. ' ' .. tostring(MARKS[referrer])\n" +
                        "referrer.extras:get('location')\n" +
                        "local read = deliver(34, referrer, 'split')\n" +
                        "local mine = intentof({ location = { class = 'android.location.Location', locations = { fix(52) } } }, " +
                        "false, nil, true)\n" +
                        "local written = writes\n" +
                        "local made = deliver(34, mine, 'split') .. ' ' .. (writes - written)\n" +
                        "hidden = true\n" +
                        "local unlisted = deliver(34, intentof(update()), 'split') .. ' ' .. (writes - written)\n" +
                        "local parcel = check(34, update())\n" +
                        "hidden = false\n" +
                        "hooked = false\n" +
                        "local early = intentof(update())\n" +
                        "local unhooked = describe(after(INTENT, fromparcel(early)), early)\n" +
                        "hooked = true\n" +
                        "return first, loader, counts, again, recounts, deliver(30, intentof(update()), nil), given, referred, " +
                        "read, made, unlisted, parcel, unhooked, deliver(34, early, 'split')");
        assertEquals("false 1 split location=0", result.arg(1).tojstring());
        assertEquals("split", result.arg(2).tojstring());
        assertEquals("3/2", result.arg(3).tojstring());
        assertEquals("false 1 split location=0", result.arg(4).tojstring());
        assertEquals("3/2", result.arg(5).tojstring());
        assertEquals("false 1 nil location=0", result.arg(6).tojstring());
        assertEquals("false 2 split location=0", result.arg(7).tojstring());
        assertEquals("false 1 split location=0 true", result.arg(8).tojstring());
        assertEquals("false 1 split location=0*", result.arg(9).tojstring());
        assertEquals("false 0 split location=52* 0", result.arg(10).tojstring());
        assertEquals("false 0 split location=52 0", result.arg(11).tojstring());
        assertEquals("false 1 boot location=0", result.arg(12).tojstring());
        assertEquals("false 1 boot location=52", result.arg(13).tojstring());
        assertEquals("false 2 split location=0", result.arg(14).tojstring());
    }

    // The Android classes of the GNSS registrations, as Lua sees them
    private static final String GNSS_STUBS = String.join("\n",
            "sdk = 34 granted = 0 target = 34 looper = {} gps = true enabled = true",
            "luajava = {",
            "  bindClass = function(name)",
            "    if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "    assert(name == 'android.os.Looper', name)",
            "    return { myLooper = function(self) return looper end }",
            "  end,",
            "  createProxy = function(name, implementation) return implementation end,",
            "  newInstance = function(name, runnable)",
            "    return { class = name, start = function(self) runnable.run() end, post = function(self, r) r.run() end } end }",
            "CONTEXT = { checkSelfPermission = function(self, name) return granted end,",
            "  getApplicationInfo = function(self) return { targetSdkVersion = target } end,",
            "  getSystemService = function(self, name) return {",
            "    getAllProviders = function(self) return { contains = function(self, p) return p == 'gps' and gps end } end,",
            "    isProviderEnabled = function(self, p) return enabled end } end }",
            "function register(method, types, args)",
            "  types.length = #types",
            "  local hook = { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return types end }",
            "  local param = { result = 'none', getApplicationContext = function(self) return CONTEXT end,",
            "    getArgument = function(self, index) return args[index + 1] end,",
            "    setResult = function(self, value) self.result = value end }",
            "  return before(hook, param), param.result",
            "end",
            "function callback() return { onStatusChanged = function(self, status) self.status = status end } end",
            "EXECUTOR = { execute = function(self, runnable) runnable.run() end }",
            "STATUS = 'android.location.GnssStatus$Callback'",
            "MEASUREMENTS = 'android.location.GnssMeasurementsEvent$Callback'",
            "EX = 'java.util.concurrent.Executor'");

    // Answered like Android answers a registration, which then sends nothing more; where Android throws or refuses,
    // before it registers, the call runs; measurement callbacks get the status Android sends on a registration
    @Test
    public void gnssRegistrationsAnswerLikeAndroid() throws Exception {
        Globals gnss = Scripts.load("gnss_register", GNSS_STUBS);
        Varargs result = Scripts.run(gnss,
                "local out = {}\n" +
                        "local function add(...) for _, v in ipairs({ ... }) do out[#out + 1] = tostring(v) end end\n" +
                        "add(register('registerGnssStatusCallback', { EX, STATUS }, { EXECUTOR, callback() }))\n" +
                        "add(register('registerGnssStatusCallback', { EX, STATUS }, { nil, callback() }))\n" +
                        "add(register('registerGnssStatusCallback', { EX, STATUS }, { EXECUTOR, nil }))\n" +
                        "local measured = callback()\n" +
                        "add(register('registerGnssMeasurementsCallback', { EX, MEASUREMENTS }, { EXECUTOR, measured }), measured.status)\n" +
                        "looper = nil\n" +
                        "add(register('registerGnssStatusCallback', { STATUS }, { callback() }))\n" +
                        "local direct = callback()\n" +
                        "add(register('registerGnssMeasurementsCallback', { MEASUREMENTS }, { direct }), direct.status)\n" +
                        "looper = {}\n" +
                        "add(register('addGpsStatusListener', { 'android.location.GpsStatus$Listener' }, { callback() }))\n" +
                        "target = 30\n" +
                        "add(register('addGpsStatusListener', { 'android.location.GpsStatus$Listener' }, { callback() }))\n" +
                        "granted = -1\n" +
                        "add(register('registerGnssStatusCallback', { EX, STATUS }, { EXECUTOR, callback() }))\n" +
                        "granted = 0 sdk = 29 gps = false\n" +
                        "add(register('registerGnssStatusCallback', { STATUS }, { callback() }))\n" +
                        "gps = true enabled = false\n" +
                        "local old = callback()\n" +
                        "add(register('registerGnssMeasurementsCallback', { MEASUREMENTS }, { old }), old.status)\n" +
                        "return table.concat(out, ' ')");
        assertEquals(String.join(" ",
                "true true", // Registered, true
                "false none", "false none", // Null executor or callback: IllegalArgumentException
                "true 1", // STATUS_READY on the executor (add keeps the first value of register here)
                "false none", // No Looper for the Handler
                "true 1", // The thread of the system
                "false none", // UnsupportedOperationException for an app for Android 12
                "true true", // An app for Android 11
                "false none", // No permission: SecurityException
                "false none", // No GNSS before Android 12: false
                "true 2"), // STATUS_LOCATION_DISABLED
                result.arg(1).tojstring());
    }

    @Test
    public void offsetsAreValidCoordinates() {
        for (int i = 0; i < 1000; i++) {
            Varargs pole = globals.get("move").invoke(new LuaValue[]{
                    LuaValue.valueOf(89.99), LuaValue.valueOf(179.99), LuaValue.valueOf(i * 37 % 2001 - 1000),
                    LuaValue.valueOf(100000 - i * 200)});
            double latitude = pole.arg(1).todouble();
            double longitude = pole.arg(2).todouble();
            assertTrue("latitude " + latitude, latitude >= -90 && latitude <= 90);
            assertTrue("longitude " + longitude, longitude >= -180 && longitude < 180);
        }
    }
}
