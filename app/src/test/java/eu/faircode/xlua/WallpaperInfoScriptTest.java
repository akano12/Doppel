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
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The live wallpaper info is hidden when its app is hidden from the asking app (assets/wallpaper_info.lua)
public class WallpaperInfoScriptTest {
    private static final String STUBS = String.join("\n",
            "function infoof(packageName)",
            "  if packageName == nil then return nil end",
            "  return { getPackageName = function(self) return packageName end }",
            "end",
            "function paramof(info, visible, system)",
            "  return { result = info, set = false,",
            "    isSystem = function(self) return system == true end,",
            "    isPackageVisible = function(self, name) return visible[name] == true end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end }",
            "end",
            "function run(packageName, visible, system)",
            "  local param = paramof(infoof(packageName), visible, system)",
            "  local restricted = after({}, param)",
            "  return restricted, param.set, param.result == nil",
            "end",
            "VISIBLE = { ['com.android.wallpaper'] = true }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("wallpaper_info", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, "return run(" + lua + ")");
    }

    @Test
    public void hiddenWallpaperAppCleared() {
        Varargs result = run("'com.other.hidden', VISIBLE, false");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
    }

    @Test
    public void visibleWallpaperAppStays() {
        Varargs result = run("'com.android.wallpaper', VISIBLE, false");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    @Test
    public void noLiveWallpaper() {
        assertFalse(run("nil, VISIBLE, false").arg1().toboolean());
    }

    @Test
    public void systemSeesAll() {
        assertFalse(run("'com.other.hidden', VISIBLE, true").arg1().toboolean());
    }
}
