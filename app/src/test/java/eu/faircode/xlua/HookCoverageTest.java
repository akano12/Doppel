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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

// Definitions of hooks.json which close gaps: the method, its versions and its script
public class HookCoverageTest {
    // hooks.json without its comments: the licence on top and the lines of // comments
    private static JSONObject hook(String name) throws Exception {
        String json = new String(Files.readAllBytes(new File("src/main/assets/hooks.json").toPath()), StandardCharsets.UTF_8)
                .replaceFirst("(?s)^/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
        JSONArray hooks = new JSONArray(json);
        for (int i = 0; i < hooks.length(); i++)
            if (name.equals(hooks.getJSONObject(i).getString("name")))
                return hooks.getJSONObject(i);
        return null;
    }

    // A definition as "class method(params) script minSdk-maxSdk optional"
    private static void check(String name, String expected) throws Exception {
        JSONObject hook = hook(name);
        assertNotNull(name + " is not defined", hook);
        JSONArray params = hook.getJSONArray("parameterTypes");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length(); i++)
            sb.append(i == 0 ? "" : ",").append(params.getString(i));
        String actual = hook.getString("className") + " " + hook.getString("methodName") + "(" + sb + ") " +
                hook.getString("luaScript") + " " + hook.getInt("minSdk") + "-" + hook.optString("maxSdk", "") +
                (hook.optBoolean("optional", false) ? " optional" : "");
        assertEquals(name, expected, actual);
    }

    // Each overload calls Android itself, so each one is hooked (see component_hidden)
    @Test
    public void componentsOfHiddenAppsByEveryOverload() throws Exception {
        String i = "android.content.Intent,";
        check("Context.bindServiceAsUser/hidden", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,int,android.os.UserHandle) @component_hidden 30-");
        check("Context.bindServiceAsUser.flags/hidden", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,android.content.Context$BindServiceFlags,android.os.UserHandle) @component_hidden 34-");
        check("Context.sendBroadcast.options/hidden", "android.app.ContextImpl sendBroadcast(" + i +
                "java.lang.String,android.os.Bundle) @component_hidden 34-");
        check("Context.sendBroadcastWithMultiplePermissions/hidden", "android.content.Context sendBroadcastWithMultiplePermissions(" + i +
                "java.lang.String[]) @component_hidden 30-");
        check("Context.sendBroadcastAsUser/hidden", "android.app.ContextImpl sendBroadcastAsUser(" + i +
                "android.os.UserHandle) @component_hidden 17-");
        check("Context.sendBroadcastAsUser.permission/hidden", "android.app.ContextImpl sendBroadcastAsUser(" + i +
                "android.os.UserHandle,java.lang.String) @component_hidden 17-");
        check("Activity.startActivityIfNeeded/hidden", "android.app.Activity startActivityIfNeeded(" + i +
                "int,android.os.Bundle) @component_hidden 16-");
    }

    // Permissions name the app which defines them (see package_resolve)
    @Test
    public void permissionsOfHiddenApps() throws Exception {
        check("PackageManager.getPermissionInfo", "android.content.pm.PackageManager getPermissionInfo(" +
                "java.lang.String,int) @package_resolve 1-");
        check("PackageManager.queryPermissionsByGroup", "android.content.pm.PackageManager queryPermissionsByGroup(" +
                "java.lang.String,int) @package_resolve 1-");
    }

    // Files.getFileStore(path).getTotalSpace() and the others, also by getAttribute, read statvfs themselves
    @Test
    public void fileStoreSizesAreRounded() throws Exception {
        for (String method : new String[]{"getTotalSpace", "getUsableSpace", "getUnallocatedSpace"})
            check("UnixFileStore." + method, "sun.nio.fs.UnixFileStore " + method + "() @resources 26-");
    }

    // The version of the method in api-versions, else it is tried and missed on Android 13 at every start
    @Test
    public void alarmWindowWithExecutorFromApi34() throws Exception {
        check("AlarmManager.setWindow/executor", "android.app.AlarmManager setWindow(int,long,long,java.lang.String," +
                "java.util.concurrent.Executor,android.app.AlarmManager$OnAlarmListener) @boot_offset_alarm 34- optional");
    }

    // Apps targeting Android 9 or older still get the neighboring cells on Android 10 and newer; the method is not
    // in the android.jar of recent versions, so it is optional
    @Test
    public void neighboringCellsOnAllVersions() throws Exception {
        check("TelephonyManager.getNeighboringCellInfo", "android.telephony.TelephonyManager getNeighboringCellInfo() " +
                "@generic_empty 3- optional");
    }

    // The clipboard of the app is read by the clipboard manager; data of a parcel is also of drag and drop and of
    // intents, like the app's own drags, which are not the clipboard
    @Test
    public void clipboardIsReadFromTheClipboardManager() throws Exception {
        check("ClipboardManager.getPrimaryClip", "android.content.ClipboardManager getPrimaryClip() " +
                "@clipdata_createfromparcel 11-");
        check("ClipboardManager.getPrimaryClipDescription", "android.content.ClipboardManager getPrimaryClipDescription() " +
                "@clipdescription_createfromparcel 11-");
    }
}
