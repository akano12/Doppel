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
            // XParam.uuidOf, a hash of the seed
            "local UUID = luajava.bindClass('java.util.UUID')",
            "local UTF8 = luajava.bindClass('java.nio.charset.StandardCharsets').UTF_8",
            "function paramof(result, settings)",
            "  return {",
            "    getResult = function(self) return result end,",
            "    getSetting = function(self, name) return settings[name] end,",
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
                        "local mine = after(nil, paramwith(own))\n" +
                        "local vendor = location(52.1, 4.3)\n" +
                        "vendor.provider = 'vendor'\n" +
                        "local fused = location(52.1, 4.3)\n" +
                        "fused.provider = 'fused'\n" +
                        "local picked = location(52.1, 4.3)\n" +
                        "picked.elapsed = 0\n" +
                        "return mine, own.latitude, after(nil, paramwith(vendor)), vendor.latitude,\n" +
                        "  after(nil, paramwith(fused)), fused.latitude, after(nil, paramwith(picked)), picked.latitude");
        assertFalse(result.arg(1).toboolean());
        assertEquals(52.1, result.arg(2).todouble(), 0);
        assertTrue(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).todouble(), 1e-4);
        assertTrue(result.arg(5).toboolean());
        assertEquals(0, result.arg(6).todouble(), 1e-4);
        assertFalse(result.arg(7).toboolean());
        assertEquals(52.1, result.arg(8).todouble(), 0);
    }

    // Callbacks with cell info are registered for their other events, like the call state, and their method of the cell
    // info does not run (telephonymanager_registertelephonycallback); callbacks without cell events stay
    @Test
    public void telephonyCallbacksKeepTheirOtherEvents() throws Exception {
        Globals callbacks = Scripts.load("telephonymanager_registertelephonycallback", String.join("\n",
                "hooked = {}",
                "function hook(object, method, fun) hooked[#hooked + 1] = method end",
                // The callback is the last parameter: (executor, callback) and of Android 13 (flag, executor, callback)
                "function hookof(count) return { getParameterTypes = function(self) return { length = count } end } end",
                "function paramof(interfaces, index)",
                "  local callback = {}",
                "  return { result = 'none',",
                "    getArgument = function(self, i) if i == index then return callback end return nil end,",
                "    isInstance = function(self, object, name) return interfaces[name] == true end,",
                "    setResult = function(self, value) self.result = value end }",
                "end"));
        Varargs result = Scripts.run(callbacks,
                "local param = paramof({ ['android.telephony.TelephonyCallback$CellInfoListener'] = true,\n" +
                        "  ['android.telephony.TelephonyCallback$CallStateListener'] = true }, 2)\n" +
                        "local restricted = before(hookof(3), param)\n" +
                        "local cell = paramof({}, 0)\n" +
                        "skip('before', cell)\n" +
                        "local calls = paramof({ ['android.telephony.TelephonyCallback$CallStateListener'] = true }, 1)\n" +
                        "return restricted, param.result, table.concat(hooked, ','), cell.result,\n" +
                        "  before(hookof(2), calls), calls.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals("none", result.arg(2).tojstring());
        assertEquals("onCellInfoChanged", result.arg(3).tojstring());
        assertTrue(result.arg(4).isnil());
        assertFalse(result.arg(5).toboolean());
        assertEquals("none", result.arg(6).tojstring());
    }

    // Whether the script restricted a location and the location it left, of a fix at a time
    private Varargs after(double latitude, double longitude, long time, String settings) {
        return Scripts.run(globals,
                "local result = location(" + latitude + ", " + longitude + ")\n" +
                        "result.time = " + time + "\n" +
                        "local restricted = after(nil, paramof(result, " + settings + "))\n" +
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
                        "after(nil, paramof(result, {}))\n" +
                        "local other = location(52.37, 4.89)\n" +
                        "other.extras.values = { noGPSLocation = 'real' }\n" +
                        "after(nil, paramof(other, {}))\n" +
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
                        "after(nil, paramof(precise, { ['location.type'] = 'coarse', ['location.accuracy'] = '2000' }))\n" +
                        "local default = location(52.1, 4.3)\n" +
                        "after(nil, paramof(default, { ['location.type'] = 'coarse' }))\n" +
                        "local set = location(52.1, 4.3)\n" +
                        "after(nil, paramof(set, { ['location.type'] = 'set', ['location.latitude'] = '1', ['location.longitude'] = '2' }))\n" +
                        "return precise.accuracy, default.accuracy, set.accuracy, precise.altitude, precise.speed, precise.extras");
        assertEquals(2000, result.arg(1).todouble(), 0);
        assertEquals(10000, result.arg(2).todouble(), 0);
        assertEquals(4, result.arg(3).todouble(), 0);
        assertTrue(result.arg(4).isnil());
        assertTrue(result.arg(5).isnil());
        assertTrue(result.arg(6).isnil());
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
