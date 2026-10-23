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
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The location script (assets/location.lua) with Lua stand-ins for the hook, the parameter and the location,
// because the Android classes are stubs in unit tests
public class LocationScriptTest {
    private static final String STUBS = String.join("\n",
            "function location(latitude, longitude)",
            "  return { latitude = latitude, longitude = longitude, altitude = 12, extras = {},",
            "    getLatitude = function(self) return self.latitude end,",
            "    getLongitude = function(self) return self.longitude end,",
            "    setLatitude = function(self, value) self.latitude = value end,",
            "    setLongitude = function(self, value) self.longitude = value end,",
            "    hasAccuracy = function(self) return false end,",
            "    removeAltitude = function(self) self.altitude = nil end,",
            "    setExtras = function(self, extras) self.extras = extras end,",
            "    toString = function(self) return self.latitude .. ',' .. self.longitude end }",
            "end",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            "function paramof(result, key, isLocation, settings)",
            "  local values = {}",
            "  return {",
            "    getResult = function(self) return result end,",
            "    getException = function(self) return nil end,",
            "    getArgument = function(self, index) return key end,",
            "    isInstance = function(self, object, name) return isLocation end,",
            "    getSetting = function(self, name) return settings[name] end,",
            "    getApplicationContext = function(self) return values end,",
            "    getValue = function(self, name, scope) return scope[name] end,",
            "    putValue = function(self, name, value, scope) scope[name] = value end }",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("location", STUBS);
    }

    private Varargs after(String method, String key, boolean isLocation, String settings) {
        return globals.load(
                "local result = location(52.37, 4.89)\n" +
                        "local restricted = after(hookof('" + method + "'), paramof(result, '" + key + "', " +
                        isLocation + ", " + settings + "))\n" +
                        "return restricted, result.latitude, result.longitude", "test").invoke();
    }

    @Test
    public void parcelGetsSetLocation() {
        Varargs result = after("CREATOR:createFromParcel", "", true,
                "{ ['location.type'] = 'set', ['location.latitude'] = '10.5', ['location.longitude'] = '-20' }");
        assertTrue(result.arg(1).toboolean());
        assertEquals(10.5, result.arg(2).todouble(), 0);
        assertEquals(-20, result.arg(3).todouble(), 0);
    }

    // The altitude and the extras, like the number of satellites, are of the real location
    @Test
    public void realDetailsAreRemoved() {
        Varargs result = globals.load(
                "local result = location(52.37, 4.89)\n" +
                        "after(hookof('CREATOR:createFromParcel'), paramof(result, '', true, {}))\n" +
                        "return result.altitude, result.extras", "test").invoke();
        assertTrue(result.arg(1).isnil());
        assertTrue(result.arg(2).isnil());
    }

    @Test
    public void bundleLocationGetsSetLocation() {
        Varargs result = after("get", "location", true,
                "{ ['location.type'] = 'set', ['location.latitude'] = '10.5', ['location.longitude'] = '-20' }");
        assertTrue(result.arg(1).toboolean());
        assertEquals(10.5, result.arg(2).todouble(), 0);
    }

    @Test
    public void bundleKeepsOtherExtras() {
        Varargs other = after("get", "other", true, "{}");
        assertFalse(other.arg(1).toboolean());
        assertEquals(52.37, other.arg(2).todouble(), 0);

        // An extra named location which is not a location, like a string, would crash the app when replaced
        Varargs text = after("get", "location", false, "{}");
        assertFalse(text.arg(1).toboolean());
        assertEquals(52.37, text.arg(2).todouble(), 0);
    }

    // The coarse location is the center of a cell of a grid with the size of the accuracy,
    // so another process or start of the app, or another location in the cell, gives the same location
    @Test
    public void coarseLocationIsStable() throws Exception {
        String settings = "{ ['location.type'] = 'coarse', ['location.accuracy'] = '10000' }";
        Varargs first = coarse(52.37, 4.89, settings);
        assertTrue(first.arg(1).toboolean());

        load(); // Like another process
        Varargs again = coarse(52.37, 4.89, settings);
        assertEquals(first.arg(2).todouble(), again.arg(2).todouble(), 0);
        assertEquals(first.arg(3).todouble(), again.arg(3).todouble(), 0);

        Varargs nearby = coarse(first.arg(2).todouble() + 0.01, first.arg(3).todouble() - 0.01, settings);
        assertEquals(first.arg(2).todouble(), nearby.arg(2).todouble(), 0);
        assertEquals(first.arg(3).todouble(), nearby.arg(3).todouble(), 0);

        Varargs far = coarse(48.85, 2.35, settings);
        assertTrue(Math.abs(far.arg(2).todouble() - 48.85) < 0.1);
    }

    @Test
    public void coarseLocationIsInCell() {
        double step = 10000 / 111000.0;
        for (double latitude = -89.9; latitude < 90; latitude += 7.3)
            for (double longitude = -179.9; longitude < 180; longitude += 11.7) {
                Varargs result = coarse(latitude, longitude,
                        "{ ['location.type'] = 'coarse', ['location.accuracy'] = '10000' }");
                double clatitude = result.arg(2).todouble();
                double clongitude = result.arg(3).todouble();
                assertTrue("latitude " + clatitude, clatitude >= -90 && clatitude <= 90);
                assertTrue("longitude " + clongitude, clongitude >= -180 && clongitude < 180);
                assertTrue(latitude + " " + clatitude, Math.abs(clatitude - latitude) <= step / 2 + 1e-9);
                double lonstep = step / Math.max(Math.cos(Math.toRadians(clatitude)), 0.01);
                double distance = Math.abs(clongitude - longitude);
                assertTrue(longitude + " " + clongitude,
                        Math.min(distance, 360 - distance) <= lonstep / 2 + 1e-9);
            }
    }

    private Varargs coarse(double latitude, double longitude, String settings) {
        return globals.load(
                "local result = location(" + latitude + ", " + longitude + ")\n" +
                        "local restricted = after(hookof('CREATOR:createFromParcel'), paramof(result, '', true, " +
                        settings + "))\n" +
                        "return restricted, result.latitude, result.longitude", "test").invoke();
    }

    @Test
    public void offsetsAreValidCoordinates() {
        for (int i = 0; i < 1000; i++) {
            Varargs pole = globals.get("randomoffset").invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(89.99), LuaValue.valueOf(179.99), LuaValue.valueOf(100000)));
            double latitude = pole.arg(1).todouble();
            double longitude = pole.arg(2).todouble();
            assertTrue("latitude " + latitude, latitude >= -90 && latitude <= 90);
            assertTrue("longitude " + longitude, longitude >= -180 && longitude < 180);
        }
    }
}
