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

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The scripts of the details which together recognize a device: the names of the codecs, the extensions of OpenGL,
// the processors, the fonts, the memory of apps, the location of photos, the users, and the heat, audio, vibrator
// and certificates; with Lua stand-ins, like DeviceValuesScriptTest
public class FingerprintScriptsTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method, returnType)",
            "  return { getName = function(self) return method end,",
            "    getMethodName = function(self) return method end,",
            "    getReturnType = function(self) return returnType end }",
            "end",
            "function paramof(result, arguments)",
            "  local param = { result = result, args = arguments or {} }",
            "  param.getResult = function(self) return self.result end",
            "  param.setResult = function(self, value) self.result = value end",
            "  param.getArgument = function(self, index) return self.args[index] end",
            "  param.setArgument = function(self, index, value) self.args[index] = value end",
            "  param.getException = function(self) return nil end",
            "  param.unhooked = function(self, f) return f() end",
            "  param.isInstance = function(self, object, name) return false end",
            "  return param",
            "end",
            // A Java collection of Lua values, with size, isEmpty and iterator
            "function collection(values)",
            "  return { size = function(self) return #values end, isEmpty = function(self) return #values == 0 end,",
            "    iterator = function(self)",
            "      local index = 0",
            "      return { hasNext = function(self) return index < #values end,",
            "        next = function(self) index = index + 1 return values[index] end }",
            "    end }",
            "end");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, STUBS);
    }

    // The table of a local list of a script, like FONTS, to compare copies in scripts which are loaded apart
    private static String list(String script, String name) throws IOException {
        Matcher m = Pattern.compile("local " + name + " = \\{([^}]*)\\}").matcher(Scripts.read(script));
        assertTrue(script + " " + name, m.find());
        return m.group(1).replaceAll("\\s+", " ").trim();
    }

    @Test
    public void codecNamesHaveNoVendor() throws Exception {
        Globals globals = load("media_codecs");
        String[][] cases = {{"c2.qti.avc.decoder", "c2.hw.avc.decoder"},
                {"c2.exynos.h264.decoder.secure", "c2.hw.h264.decoder.secure"},
                {"OMX.MTK.VIDEO.DECODER.AVC", "OMX.hw.VIDEO.DECODER.AVC"}, {"OMX.qcom.video.decoder.avc", "OMX.hw.video.decoder.avc"},
                {"c2.android.avc.decoder", "c2.android.avc.decoder"}, {"OMX.google.h264.decoder", "OMX.google.h264.decoder"},
                {"c2.hw.avc.decoder", "c2.hw.avc.decoder"}, {"aac.encoder", "aac.encoder"}};
        for (String[] c : cases)
            assertEquals(c[0], c[1], Scripts.run(globals, "return fakename('" + c[0] + "')").arg1().tojstring());

        // MediaCodecInfo.getName: faked; the codecs of Android stay
        Varargs result = Scripts.run(globals, "local p = paramof('c2.qti.avc.decoder')\n" +
                "local restricted = after(hookof('getName'), p) return restricted, p.result");
        assertTrue(result.arg1().toboolean());
        assertEquals("c2.hw.avc.decoder", result.arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return after(hookof('getName'), paramof('c2.android.avc.decoder'))").arg1().toboolean());

        // createByCodecName with a fake name opens the real codec; with a real name it stays
        result = Scripts.run(globals,
                "luajava.newInstance = function(name, kind)\n" +
                        "  local names = { 'c2.android.avc.decoder', 'c2.qti.avc.decoder', 'c2.qti.hevc.decoder' }\n" +
                        "  return { getCodecInfos = function(self)\n" +
                        "    local infos = { length = #names }\n" +
                        "    for i, n in ipairs(names) do infos[i] = { getName = function(self) return n end } end\n" +
                        "    return infos end }\n" +
                        "end\n" +
                        "local fake = paramof(nil, { [0] = 'c2.hw.hevc.decoder' })\n" +
                        "before(hookof('createByCodecName'), fake)\n" +
                        "local real = paramof(nil, { [0] = 'c2.qti.avc.decoder' })\n" +
                        "before(hookof('createByCodecName'), real)\n" +
                        "return fake.args[0], real.args[0], after(hookof('createByCodecName'), paramof('codec'))");
        assertEquals("c2.qti.hevc.decoder", result.arg1().tojstring());
        assertEquals("c2.qti.avc.decoder", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
    }

    @Test
    public void glExtensionsOfVendorsAreLeftOut() throws Exception {
        Globals globals = load("gles_getstring");
        String real = "GL_OES_EGL_image GL_QCOM_tiled_rendering GL_EXT_color_buffer_float GL_ARM_shader_framebuffer_fetch " +
                "GL_KHR_debug GL_ANDROID_extension_pack_es31a GL_OVR_multiview GL_IMG_texture_compression_pvrtc ";
        Varargs result = Scripts.run(globals, "local p = paramof('" + real + "', { [0] = 7939 })\n" +
                "local restricted = after(hookof('glGetString'), p) return restricted, p.result");
        assertTrue(result.arg1().toboolean());
        assertEquals("GL_OES_EGL_image GL_EXT_color_buffer_float GL_KHR_debug GL_ANDROID_extension_pack_es31a GL_OVR_multiview ",
                result.arg(2).tojstring());

        // EGL: the name is the second argument; the vendor is unknown, the version stays
        result = Scripts.run(globals, "local p = paramof('EGL_KHR_image EGL_QCOM_create_image EGL_ANDROID_recordable', { [0] = 'display', [1] = 12373 })\n" +
                "local restricted = after(hookof('eglQueryString'), p)\n" +
                "local vendor = paramof('Qualcomm Inc.', { [0] = 'display', [1] = 12371 })\n" +
                "after(hookof('eglQueryString'), vendor)\n" +
                "return restricted, p.result, vendor.result,\n" +
                "  after(hookof('eglQueryString'), paramof('1.4 Android META-EGL', { [0] = 'display', [1] = 12372 }))");
        assertTrue(result.arg1().toboolean());
        assertEquals("EGL_KHR_image EGL_ANDROID_recordable", result.arg(2).tojstring());
        assertEquals("unknown", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());

        // By index: the kept ones, beyond them an empty name
        result = Scripts.run(globals,
                "luajava.bindClass = function(name) return { glGetString = function(self, n) return '" + real + "' end } end\n" +
                        "local second = paramof('GL_QCOM_tiled_rendering', { [0] = 7939, [1] = 1 })\n" +
                        "after(hookof('glGetStringi'), second)\n" +
                        "local beyond = paramof('GL_IMG_texture_compression_pvrtc', { [0] = 7939, [1] = 7 })\n" +
                        "after(hookof('glGetStringi'), beyond)\n" +
                        "return second.result, beyond.result");
        assertEquals("GL_EXT_color_buffer_float", result.arg1().tojstring());
        assertEquals("", result.arg(2).tojstring());
    }

    // Older kernels print the lines of all processors in one block, under Processor with the revision of the core
    @Test
    public void cpuinfoOfOlderKernels() throws Exception {
        Globals globals = load("cpuinfo");
        String old = "Processor\t: AArch64 Processor rev 4 (aarch64)\nprocessor\t: 0\nprocessor\t: 1\nprocessor\t: 2\n" +
                "processor\t: 3\nFeatures\t: fp asimd\nCPU implementer\t: 0x51\nCPU part\t: 0x801\n\n" +
                "Hardware\t: Qualcomm Technologies, Inc MSM8998\n";
        Varargs result = Scripts.run(globals, "local text, left = kept([[" + old + "]])\n" +
                "local eight, counted = processors(text)\n" +
                "return left, eight, counted, processors('processor\\t: 0\\nprocessor\\t: 1\\n" +
                "processor\\t: 2\\nprocessor\\t: 3\\nprocessor\\t: 4\\nprocessor\\t: 5\\nprocessor\\t: 6\\nprocessor\\t: 7\\n')");
        assertEquals(4, result.arg1().toint());
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 8; i++)
            expected.append("processor\t: ").append(i).append('\n');
        expected.append("Features\t: fp asimd\n\n");
        assertEquals(expected.toString(), result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertFalse(result.arg(5).toboolean());
    }

    @Test
    public void processorsAreEight() throws Exception {
        Globals globals = load("cpu_count");
        Varargs result = Scripts.run(globals,
                "luajava.bindClass = function(name) return { _SC_NPROCESSORS_CONF = 96, _SC_NPROCESSORS_ONLN = 97 } end\n" +
                        "local runtime = paramof(6)\n" +
                        "local conf = paramof(12, { [0] = 96 })\n" +
                        "local page = paramof(4096, { [0] = 39 })\n" +
                        "return after(hookof('availableProcessors'), runtime), runtime.result,\n" +
                        "  after(hookof('sysconf'), conf), conf.result, after(hookof('sysconf'), page), page.result,\n" +
                        "  after(hookof('availableProcessors'), paramof(8))");
        assertTrue(result.arg1().toboolean());
        assertEquals(8, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
        assertEquals(8, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertEquals(4096, result.arg(6).toint());
        assertFalse(result.arg(7).toboolean());
    }

    @Test
    public void fontsOfMakersAreHidden() throws Exception {
        assertEquals(list("hardware_files", "FONTS"), list("system_fonts", "FONTS"));
        Globals files = load("hardware_files");
        String[] hidden = {"/system/fonts/SamsungOne-400.ttf", "/product/fonts/GoogleSans-Regular.ttf",
                "/system/fonts/MiSansVF.ttf", "/my_product/fonts/OPlusSans3.0.ttf", "/system/product/fonts/Brand.ttf"};
        String[] stay = {"/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoColorEmoji.ttf",
                "/system/fonts/DroidSansMono.ttf", "/data/user/0/com.app/files/fonts/Brand.ttf",
                "/sdcard/fonts/Brand.ttf", "/system/fonts", "/system/etc/fonts.xml"};
        for (String path : hidden)
            assertTrue(path, Scripts.run(files, "return brandfont('" + path + "')").arg1().toboolean());
        for (String path : stay)
            assertFalse(path, Scripts.run(files, "return brandfont('" + path + "')").arg1().toboolean());

        Globals fonts = load("system_fonts");
        Varargs result = Scripts.run(fonts,
                "local function font(name) return { getFile = function(self)\n" +
                        "  return name and { getName = function(self) return name end } or nil end } end\n" +
                        "local p = paramof(collection({ font('Roboto-Regular.ttf'), font('SamsungOne-400.ttf'), font(nil),\n" +
                        "  font('NotoSansCJK-Regular.ttc') }))\n" +
                        "local restricted, old, new = after(hookof('getAvailableFonts'), p)\n" +
                        "return restricted, old, new, after(hookof('getAvailableFonts'), paramof(collection({ font('Roboto-Bold.ttf') })))");
        assertTrue(result.arg1().toboolean());
        assertEquals("4", result.arg(2).tojstring());
        assertEquals("3", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
    }

    @Test
    public void memoryIsRoundedDown() throws Exception {
        assertEquals(list("memory_class", "STEPS"), list("systemproperties_get", "STEPS"));
        Globals globals = load("memory_class");
        int[][] classes = {{256, 256}, {300, 256}, {576, 512}, {96, 96}, {200, 192}, {1024, 512}};
        for (int[] c : classes)
            assertEquals(c[0] + "", c[1], Scripts.run(globals,
                    "local p = paramof(" + c[0] + ") after(hookof('getMemoryClass'), p) return p.result").arg1().toint());
        Varargs result = Scripts.run(globals,
                "local big = paramof(603979776) after(hookof('maxMemory'), big)\n" +
                        "local exact = paramof(268435456)\n" +
                        "local unlimited = paramof(9223372036854775807)\n" +
                        "return big.result, after(hookof('maxMemory'), exact), after(hookof('maxMemory'), unlimited)");
        assertEquals(536870912L, result.arg1().tolong());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());

        Globals properties = load("systemproperties_get");
        String[][] heaps = {{"384m", "384m"}, {"576m", "512m"}, {"300m", "256m"}, {"1g", "512m"},
                {"262144k", "256m"}, {"64m", "64m"}};
        for (String[] c : heaps)
            assertEquals(c[0], c[1], Scripts.run(properties, "return heap('" + c[0] + "')").arg1().tojstring());
        assertTrue(Scripts.run(properties, "return heap('0.75')").arg1().isnil());
        result = Scripts.run(properties, "local p = paramof('576m', { [0] = 'dalvik.vm.heapsize' })\n" +
                "local restricted = after(hookof('SystemProperties.get/resources'), p)\n" +
                "local target = paramof('0.75', { [0] = 'dalvik.vm.heaptargetutilization' })\n" +
                "after(hookof('SystemProperties.get/resources'), target)\n" +
                "return restricted, p.result, target.result, after(hookof('SystemProperties.get/resources'), " +
                "paramof('0.5', { [0] = 'dalvik.vm.heaptargetutilization' }))");
        assertTrue(result.arg1().toboolean());
        assertEquals("512m", result.arg(2).tojstring());
        assertEquals("0.5", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
    }

    // getprop of the shell rounds the heap like systemproperties_get: each size of 1 to 9999 megabytes matches the
    // command of its step only, and the other settings have the same values
    @Test
    public void shellHeapLikeProperties() throws Exception {
        assertEquals(list("systemproperties_get", "STEPS"), list("shell_commands", "STEPS"));
        Matcher other = Pattern.compile("local OTHERHEAP = \\{(.*?)\\}\\s*\\n\\s*local", Pattern.DOTALL)
                .matcher(Scripts.read("systemproperties_get"));
        assertTrue(other.find());
        for (String[] c : new String[][]{{"dalvik.vm.heapstartsize", "16m"}, {"dalvik.vm.heapminfree", "8m"},
                {"dalvik.vm.heapmaxfree", "32m"}, {"dalvik.vm.heaptargetutilization", "0.5"}}) {
            assertTrue(c[0], other.group(1).contains("['" + c[0] + "'] = '" + c[1] + "'"));
            assertTrue(c[0], Scripts.read("shell_commands").contains("{ '" + c[1] + "', { '" + c[0] + "' } }"));
        }
        Globals shell = Scripts.load("shell_commands", null);
        Varargs commands = Scripts.run(shell, "return table.concat(heapcommands('dalvik.vm.heapsize'), '\\n')");
        java.util.List<Pattern> patterns = new java.util.ArrayList<>();
        java.util.List<String> steps = new java.util.ArrayList<>();
        for (String command : commands.arg1().tojstring().split("\n")) {
            Matcher m = Pattern.compile("^s/(.*)/\\[\\\\1\\]: \\[(.*)\\]/$").matcher(command);
            assertTrue(command, m.matches());
            patterns.add(Pattern.compile(m.group(1).replace("\\(", "(").replace("\\)", ")")));
            steps.add(m.group(2));
        }
        int[] limits = {128, 192, 256, 384, 512};
        for (int n = 1; n <= 9999; n++) {
            String line = "[dalvik.vm.heapsize]: [" + n + "m]";
            String expected = null;
            for (int limit : limits)
                if (n >= limit)
                    expected = limit + "m";
            int matches = 0;
            String got = null;
            for (int i = 0; i < patterns.size(); i++)
                if (patterns.get(i).matcher(line).matches()) {
                    matches++;
                    got = steps.get(i);
                }
            assertEquals(line, expected == null ? 0 : 1, matches);
            assertEquals(line, expected, got);
        }
        assertTrue(patterns.get(patterns.size() - 1).matcher("[dalvik.vm.heapsize]: [2g]").matches());
    }

    @Test
    public void fontConfigWithoutMakers() throws Exception {
        assertEquals(list("hardware_files", "FONTS"), list("font_config", "FONTS"));
        assertEquals(list("hardware_files", "FONTS"), list("shell_commands", "FONTS"));
        Globals globals = load("font_config");
        String xml = "<familyset>\n" +
                "    <family name=\"sans-serif\">\n        <font weight=\"400\" style=\"normal\">Roboto-Regular.ttf</font>\n    </family>\n" +
                "    <family name=\"sec\">\n        <font weight=\"400\" style=\"normal\">SECRobotoLight-Regular.ttf\n" +
                "            <axis tag=\"wght\" stylevalue=\"400\" />\n        </font>\n    </family>\n" +
                "    <alias name=\"sec-light\" to=\"sec\" weight=\"300\" />\n" +
                "    <alias name=\"sans-serif-thin\" to=\"sans-serif\" weight=\"100\" />\n" +
                "    <family lang=\"und-Zsye\">\n        <font weight=\"400\" style=\"normal\">NotoColorEmoji.ttf</font>\n    </family>\n" +
                "</familyset>\n";
        Varargs result = Scripts.run(globals, "local text, count = filtered([[" + xml + "]])\n" +
                "local function path(p) return { getArgument = function(self) return p end } end\n" +
                "return text, count, config(path('/system/etc/fonts.xml')), config(path('/product/etc/fonts_customization.xml')),\n" +
                "  config(path('/system/etc/font_fallback.xml')), config(path('/data/user/0/app/files/fonts.xml')),\n" +
                "  config(path('/system/etc/hosts')), config(path('/data/user/0/app/etc/fonts.xml')),\n" +
                "  config(path('/my_product/etc/fonts_customization.xml'))");
        String text = result.arg1().tojstring();
        assertEquals(1, result.arg(2).toint());
        assertTrue(text.contains("Roboto-Regular.ttf"));
        assertTrue(text.contains("NotoColorEmoji.ttf"));
        assertTrue(text.contains("sans-serif-thin"));
        assertFalse(text.contains("SECRoboto"));
        assertFalse(text.contains("sec-light"));
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        // A configuration of an app's own is not of the system; partitions of device makers are
        assertFalse(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
    }

    @Test
    public void photosHaveNoLocation() throws Exception {
        Globals globals = load("photo_location");
        Varargs result = Scripts.run(globals,
                "local original = paramof('content://media/1?requireOriginal=1', { [0] = 'content://media/1' })\n" +
                        "after(hookof('setRequireOriginal'), original)\n" +
                        "local tag = paramof('52/1,31/1,0/1', { [0] = 'GPSLatitude' })\n" +
                        "after(hookof('getAttribute'), tag)\n" +
                        "local model = paramof('Pixel', { [0] = 'Model' })\n" +
                        "local int = paramof(3, { [0] = 'GPSAltitudeRef', [1] = -1 })\n" +
                        "after(hookof('getAttributeInt'), int)\n" +
                        "local latlong = paramof(true, { [0] = 'array' })\n" +
                        "after(hookof('getLatLong', 'boolean'), latlong)\n" +
                        "local doubles = paramof({ 52.5, 13.4 })\n" +
                        "after(hookof('getLatLong', 'double[]'), doubles)\n" +
                        "local altitude = paramof(120.5, { [0] = 0 })\n" +
                        "after(hookof('getAltitude'), altitude)\n" +
                        "local time = paramof(1700000000000)\n" +
                        "after(hookof('getGpsDateTime', 'long'), time)\n" +
                        "local video = paramof('+52.5200+013.4050/', { [0] = 23 })\n" +
                        "after(hookof('extractMetadata'), video)\n" +
                        "local title = paramof('Holiday', { [0] = 7 })\n" +
                        "return original.result, tag.result, after(hookof('getAttribute'), model), model.result, int.result,\n" +
                        "  latlong.result, doubles.result, altitude.result, time.result, video.result,\n" +
                        "  after(hookof('extractMetadata'), title)");
        assertEquals("content://media/1", result.arg1().tojstring());
        assertTrue(result.arg(2).isnil());
        assertFalse(result.arg(3).toboolean());
        assertEquals("Pixel", result.arg(4).tojstring());
        assertEquals(-1, result.arg(5).toint());
        assertFalse(result.arg(6).toboolean());
        assertTrue(result.arg(7).isnil());
        assertEquals(0, result.arg(8).toint());
        assertEquals(-1, result.arg(9).toint());
        assertTrue(result.arg(10).isnil());
        assertFalse(result.arg(11).toboolean());

        // The location columns of MediaStore are null, the others stay; other providers stay
        result = Scripts.run(globals,
                "luajava.newInstance = function(name, columns)\n" +
                        "  local rows = {}\n" +
                        "  return { rows = rows, newRow = function(self)\n" +
                        "      local row = {} rows[#rows + 1] = row\n" +
                        "      return { add = function(self, v) row[#row + 1] = (v == nil and 'NULL' or v) end } end,\n" +
                        "    moveToPosition = function(self, p) end }\n" +
                        "end\n" +
                        "local function uri(authority) return { getAuthority = function(self) return authority end } end\n" +
                        "local function cursor()\n" +
                        "  local data = { { '1', 52.5, 13.4, '1700000000000' } }\n" +
                        "  local names = { 'title', 'latitude', 'longitude', 'datetaken' }\n" +
                        "  local position = -1\n" +
                        "  return { getColumnIndex = function(self, n) for i, c in ipairs(names) do if c == n then return i - 1 end end return -1 end,\n" +
                        "    getColumnNames = function(self) return { length = 4 } end, getPosition = function(self) return position end,\n" +
                        "    moveToPosition = function(self, p) position = p end,\n" +
                        "    moveToNext = function(self) position = position + 1 return position < #data end,\n" +
                        "    getType = function(self, i) return (i == 0 and 3) or (i == 3 and 1) or 2 end,\n" +
                        "    getString = function(self, i) return data[position + 1][i + 1] end,\n" +
                        // A long as a Lua number, which would come back as a double
                        "    getLong = function(self, i) return 1.7e12 end,\n" +
                        "    getDouble = function(self, i) return data[position + 1][i + 1] end, close = function(self) end }\n" +
                        "end\n" +
                        "local media = paramof(cursor(), { [0] = uri('media') })\n" +
                        "local restricted = after(hookof('query'), media)\n" +
                        "return restricted, table.concat(media.result.rows[1], ','),\n" +
                        "  after(hookof('query'), paramof(cursor(), { [0] = uri('com.android.contacts') }))");
        assertTrue(result.arg1().toboolean());
        // Whole numbers keep their exact digits, like a date in milliseconds
        assertEquals("1,NULL,NULL,1700000000000", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
    }

    @Test
    public void onlyTheOwnUser() throws Exception {
        Globals globals = load("user_profiles");
        Varargs result = Scripts.run(globals,
                "local function user(id) return { id = id, equals = function(self, other) return self.id == other.id end } end\n" +
                        "luajava.bindClass = function(name) return { myUserHandle = function(self) return user(0) end } end\n" +
                        "local profiles = paramof(collection({ user(0), user(10), user(11) }))\n" +
                        "local restricted, old, new = after(hookof('getUserProfiles'), profiles)\n" +
                        "local targets = paramof(collection({ user(10) }))\n" +
                        "after(hookof('getTargetUserProfiles'), targets)\n" +
                        "local count = paramof(3)\n" +
                        "after(hookof('getUserCount'), count)\n" +
                        "return restricted, old, new, profiles.result:size(), targets.result:size(), count.result,\n" +
                        "  after(hookof('getProfiles'), paramof(collection({ user(0) })))");
        assertTrue(result.arg1().toboolean());
        assertEquals("3", result.arg(2).tojstring());
        assertEquals("1", result.arg(3).tojstring());
        assertEquals(1, result.arg(4).toint());
        assertEquals(0, result.arg(5).toint());
        assertEquals(1, result.arg(6).toint());
        assertFalse(result.arg(7).toboolean());
    }

    @Test
    public void detailsOfAPlainDevice() throws Exception {
        Globals globals = load("device_details");
        Varargs result = Scripts.run(globals,
                "local headroom = paramof(0.4, { [0] = 10 })\n" +
                        "after(hookof('getThermalHeadroom'), headroom)\n" +
                        "local status = paramof(2)\n" +
                        "after(hookof('getCurrentThermalStatus'), status)\n" +
                        "local rate = paramof('44100', { [0] = 'android.media.property.OUTPUT_SAMPLE_RATE' })\n" +
                        "after(hookof('getProperty'), rate)\n" +
                        "local frames = paramof('192', { [0] = 'android.media.property.OUTPUT_FRAMES_PER_BUFFER' })\n" +
                        "after(hookof('getProperty'), frames)\n" +
                        "local amplitude = paramof(true)\n" +
                        "after(hookof('hasAmplitudeControl'), amplitude)\n" +
                        "local effects = paramof({ length = 3 })\n" +
                        "after(hookof('areEffectsSupported'), effects)\n" +
                        "local set = luajava.newInstance('java.util.HashSet')\n" +
                        "set:add('system:abc.0') set:add('user:def.0')\n" +
                        "local aliases = paramof(set)\n" +
                        "after(hookof('aliases'), aliases)\n" +
                        "local contains = paramof(true, { [0] = 'user:def.0' })\n" +
                        "after(hookof('containsAlias'), contains)\n" +
                        "return headroom.result ~= headroom.result, status.result, rate.result, frames.result,\n" +
                        "  amplitude.result, effects.result['length'], effects.result[1], aliases.result:size(),\n" +
                        "  aliases.result:contains('system:abc.0'), contains.result,\n" +
                        "  table.concat(filter(hookof('containsAlias')), ','), filter(hookof('aliases'))");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).toint());
        // The sample rate stays, the frames per buffer are common
        assertEquals("44100", result.arg(3).tojstring());
        assertEquals("256", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertEquals(3, result.arg(6).toint());
        assertEquals(0, result.arg(7).toint());
        assertEquals(1, result.arg(8).toint());
        assertTrue(result.arg(9).toboolean());
        assertFalse(result.arg(10).toboolean());
        assertEquals("user:*", result.arg(11).tojstring());
        assertTrue(result.arg(12).isnil());

        // The screen lock is set; the details of the vibrator are empty, by the constant or else built
        result = Scripts.run(globals,
                "local secure = paramof(false)\n" +
                        "after(hookof('isDeviceSecure'), secure)\n" +
                        "local constant = paramof({ getId = function(self) return 1 end })\n" +
                        "luajava.bindClass = function(name) return { EMPTY_VIBRATOR_INFO = 'empty' } end\n" +
                        "after(hookof('getInfo'), constant)\n" +
                        "local built = paramof({ getId = function(self) return 1 end })\n" +
                        "luajava.bindClass = function(name) error('no field') end\n" +
                        "luajava.newInstance = function(name, id) return { build = function(self) return 'built' .. id end } end\n" +
                        "after(hookof('getInfo'), built)\n" +
                        "return secure.result, constant.result, built.result");
        assertTrue(result.arg1().toboolean());
        assertEquals("empty", result.arg(2).tojstring());
        assertEquals("built1", result.arg(3).tojstring());
    }
}
