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

import static org.junit.Assert.assertArrayEquals;
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
            "  local param = { result = result, args = arguments or {}, set = false }",
            "  param.getResult = function(self) return self.result end",
            "  param.setResult = function(self, value) self.result = value self.set = true end",
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
                {"c2.hw.avc.decoder", "c2.hw.avc.decoder"}, {"aac.encoder", "aac.encoder"},
                // Software codecs of vendors stay software for ExoPlayer before Android 10; FFmpeg names no chip
                {"OMX.SEC.avc.sw.dec", "OMX.google.avc.sw.dec"},
                {"OMX.qcom.video.decoder.hevcswvdec", "OMX.google.video.decoder.hevcswvdec"},
                {"OMX.SEC.avc.dec", "OMX.hw.avc.dec"}, {"OMX.ffmpeg.h264.decoder", "OMX.ffmpeg.h264.decoder"}};
        for (String[] c : cases)
            assertEquals(c[0], c[1], Scripts.run(globals, "return fakename('" + c[0] + "')").arg1().tojstring());

        // MediaCodecInfo.getName: faked; the codecs of Android stay
        Varargs result = Scripts.run(globals, "local p = paramof('c2.qti.avc.decoder')\n" +
                "local restricted = after(hookof('getName'), p) return restricted, p.result");
        assertTrue(result.arg1().toboolean());
        assertEquals("c2.hw.avc.decoder", result.arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return after(hookof('getName'), paramof('c2.android.avc.decoder'))").arg1().toboolean());
        // MediaCodecList.findDecoderForFormat: the real name of the picked codec is faked, a fake one stays
        result = Scripts.run(globals, "local p = paramof('c2.qti.hevc.decoder')\n" +
                "local q = paramof('c2.hw.avc.decoder')\n" +
                "return after(hookof('findDecoderForFormat'), p), p.result, after(hookof('findDecoderForFormat'), q)");
        assertTrue(result.arg1().toboolean());
        assertEquals("c2.hw.hevc.decoder", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());

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

        // Two codecs never get the same fake name, nor the name of a real codec
        result = Scripts.run(globals,
                "local fakes, reals = mapping({ 'OMX.SEC.avc.dec', 'OMX.Exynos.avc.dec', 'OMX.google.avc.sw.dec',\n" +
                        "  'OMX.SEC.avc.sw.dec', 'c2.android.avc.decoder' })\n" +
                        "return fakes['OMX.SEC.avc.dec'], fakes['OMX.Exynos.avc.dec'], fakes['OMX.SEC.avc.sw.dec'],\n" +
                        "  reals['OMX.hw2.avc.dec'], fakes['c2.android.avc.decoder'], reals['c2.android.avc.decoder']");
        assertEquals("OMX.hw.avc.dec", result.arg1().tojstring());
        assertEquals("OMX.hw2.avc.dec", result.arg(2).tojstring());
        assertEquals("OMX.hw2.avc.sw.dec", result.arg(3).tojstring());
        assertEquals("OMX.Exynos.avc.dec", result.arg(4).tojstring());
        assertEquals("c2.android.avc.decoder", result.arg(5).tojstring());
        assertTrue(result.arg(6).isnil());

        // getName by the mapping of the codecs of the device: the second one numbered
        result = Scripts.run(load("media_codecs"),
                "luajava.newInstance = function(name, kind)\n" +
                        "  local names = { 'OMX.SEC.avc.dec', 'OMX.Exynos.avc.dec' }\n" +
                        "  return { getCodecInfos = function(self)\n" +
                        "    local infos = { length = #names }\n" +
                        "    for i, n in ipairs(names) do infos[i] = { getName = function(self) return n end } end\n" +
                        "    return infos end }\n" +
                        "end\n" +
                        "local p = paramof('OMX.Exynos.avc.dec') after(hookof('getName'), p)\n" +
                        "local open = paramof(nil, { [0] = 'OMX.hw2.avc.dec' }) before(hookof('createByCodecName'), open)\n" +
                        "return p.result, open.args[0]");
        assertEquals("OMX.hw2.avc.dec", result.arg1().tojstring());
        assertEquals("OMX.Exynos.avc.dec", result.arg(2).tojstring());

        // findDecoderForFormat gets the fake name of the hooked getName it calls: a numbered one stays
        result = Scripts.run(load("media_codecs"),
                "luajava.newInstance = function(name, kind)\n" +
                        "  local names = { 'OMX.SEC.avc.dec', 'OMX.Exynos.avc.dec' }\n" +
                        "  return { getCodecInfos = function(self)\n" +
                        "    local infos = { length = #names }\n" +
                        "    for i, n in ipairs(names) do infos[i] = { getName = function(self) return n end } end\n" +
                        "    return infos end }\n" +
                        "end\n" +
                        "local p = paramof('OMX.hw2.avc.dec')\n" +
                        "return after(hookof('findDecoderForFormat'), p), p.result");
        assertFalse(result.arg1().toboolean());
        assertEquals("OMX.hw2.avc.dec", result.arg(2).tojstring());
    }

    // The metrics of a codec, a new bundle per call, have its name too; other values stay
    @Test
    public void codecMetricsHaveTheFakeName() throws Exception {
        Varargs result = Scripts.run(load("media_codecs"),
                "local key = 'android.media.mediacodec.codec'\n" +
                        "local values = { [key] = 'c2.goldfish.h264.decoder', mime = 'video/avc' }\n" +
                        "local bundle = { getString = function(self, name) return values[name] end,\n" +
                        "  putString = function(self, name, value) values[name] = value end }\n" +
                        "luajava.newInstance = function(name, kind)\n" +
                        "  return { getCodecInfos = function(self)\n" +
                        "    return { length = 1, { getName = function(self) return 'c2.goldfish.h264.decoder' end } } end }\n" +
                        "end\n" +
                        "local p = paramof(bundle)\n" +
                        "local restricted, old, new = after(hookof('getMetrics'), p)\n" +
                        "local empty = { getString = function(self, name) return nil end }\n" +
                        "return restricted, old, new, values[key], values.mime, p.result == bundle,\n" +
                        "  after(hookof('getMetrics'), p), after(hookof('getMetrics'), paramof(nil)),\n" +
                        "  after(hookof('getMetrics'), paramof(empty))");
        assertTrue(result.arg(1).toboolean());
        assertEquals("c2.goldfish.h264.decoder", result.arg(2).tojstring());
        assertEquals("c2.hw.h264.decoder", result.arg(3).tojstring());
        assertEquals("c2.hw.h264.decoder", result.arg(4).tojstring());
        assertEquals("video/avc", result.arg(5).tojstring());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
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

        // EGL: the name is the second argument; the vendor (Android of the EGL loader on every device) and the
        // version stay
        result = Scripts.run(globals, "local p = paramof('EGL_KHR_image EGL_QCOM_create_image EGL_ANDROID_recordable', { [0] = 'display', [1] = 12373 })\n" +
                "local restricted = after(hookof('eglQueryString'), p)\n" +
                "local vendor = paramof('Android', { [0] = 'display', [1] = 12371 })\n" +
                "return restricted, p.result, after(hookof('eglQueryString'), vendor), vendor.result,\n" +
                "  after(hookof('eglQueryString'), paramof('1.4 Android META-EGL', { [0] = 'display', [1] = 12372 }))");
        assertTrue(result.arg1().toboolean());
        assertEquals("EGL_KHR_image EGL_ANDROID_recordable", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertEquals("Android", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());

        // By index: the kept ones, beyond them an empty name; the list is read once per context
        result = Scripts.run(globals,
                "reads = 0\n" +
                        "context = 1\n" +
                        "luajava.bindClass = function(name) return {\n" +
                        "  glGetString = function(self, n) reads = reads + 1 return '" + real + "' end,\n" +
                        "  eglGetCurrentContext = function(self) return { getNativeHandle = function(self) return context end } end } end\n" +
                        "local second = paramof('GL_QCOM_tiled_rendering', { [0] = 7939, [1] = 1 })\n" +
                        "after(hookof('glGetStringi'), second)\n" +
                        "local beyond = paramof('GL_IMG_texture_compression_pvrtc', { [0] = 7939, [1] = 7 })\n" +
                        "after(hookof('glGetStringi'), beyond)\n" +
                        "local once = reads\n" +
                        "context = 2\n" +
                        "after(hookof('glGetStringi'), paramof('GL_OES_EGL_image', { [0] = 7939, [1] = 0 }))\n" +
                        "return second.result, beyond.result, once, reads");
        assertEquals("GL_EXT_color_buffer_float", result.arg1().tojstring());
        assertEquals("", result.arg(2).tojstring());
        assertEquals(1, result.arg(3).toint());
        assertEquals(2, result.arg(4).toint());
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

        // The processors possible, present and online of /sys are 0-7; the other files and the folder stay
        assertTrue(Scripts.run(globals, "return filter(hookof('availableProcessors'))").arg1().isnil());
        assertEquals("*system/cpu/*", Scripts.run(globals, "return filter(hookof('open'))[1]").arg1().tojstring());
        result = Scripts.run(globals,
                "local function fileof(path, text)\n" +
                        "  local p = paramof('fd', { [0] = path })\n" +
                        "  p.opens = function(self, file) return string.gsub(path, '^/proc/self/root', '') == file end\n" +
                        "  p.getFileText = function(self) return text end\n" +
                        "  p.setFileText = function(self, value) text = value end\n" +
                        "  p.text = function(self) return text end\n" +
                        "  return p\n" +
                        "end\n" +
                        "local possible = fileof('/sys/devices/system/cpu/possible', '0-5\\n')\n" +
                        "local online = fileof('/proc/self/root/sys/devices/system/cpu/online', '0-3,6\\n')\n" +
                        "local core = fileof('/sys/devices/system/cpu/cpu0/online', '1\\n')\n" +
                        "return after(hookof('open'), possible), possible:text(), after(hookof('open'), online), online:text(),\n" +
                        "  after(hookof('open'), core), core:text(), after(hookof('open'), fileof('/sys/devices/system/cpu/present', '0-7\\n'))");
        assertTrue(result.arg1().toboolean());
        assertEquals("0-7\n", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("0-7\n", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertEquals("1\n", result.arg(6).tojstring());
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
                        "return restricted, old, new, p.result:size(), after(hookof('getAvailableFonts'), paramof(collection({ font('Roboto-Bold.ttf') })))");
        assertTrue(result.arg1().toboolean());
        assertEquals("4", result.arg(2).tojstring());
        assertEquals("3", result.arg(3).tojstring());
        assertEquals(3, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
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
                "total = 0\n" +
                        "luajava.bindClass = function(name) return { getRuntime = function(self)\n" +
                        "  return { totalMemory = function(self) return total end } end } end\n" +
                        "local big = paramof(603979776) after(hookof('maxMemory'), big)\n" +
                        "local exact = paramof(268435456)\n" +
                        "local unlimited = paramof(9223372036854775807)\n" +
                        "return big.result, after(hookof('maxMemory'), exact), after(hookof('maxMemory'), unlimited)");
        assertEquals(536870912L, result.arg1().tolong());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        // Never below the heap in use (550 of 576 MB): the real maximum, the next step being above it
        result = Scripts.run(globals,
                "total = 576716800\n" +
                        "local used = paramof(603979776)\n" +
                        "return after(hookof('maxMemory'), used), used.result");
        assertFalse(result.arg1().toboolean());
        assertEquals(603979776L, result.arg(2).tolong());

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

    @Test
    public void fontConfigWithoutMakers() throws Exception {
        assertEquals(list("hardware_files", "FONTS"), list("font_config", "FONTS"));
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

        // The app reads the text without the families of makers; nothing changes for a configuration without them,
        // for another file, a file which was not opened and a failed call
        result = Scripts.run(globals,
                "local function fileof(path, text, opened)\n" +
                        "  local p = paramof(opened, { [0] = path })\n" +
                        "  p.getFileText = function(self) return text end\n" +
                        "  p.setFileText = function(self, value) text = value p.set = true end\n" +
                        "  p.text = function(self) return text end\n" +
                        "  return p\n" +
                        "end\n" +
                        "local xml = [[" + xml + "]]\n" +
                        "local maker = fileof('/system/etc/fonts.xml', xml, 'fd')\n" +
                        "local plain = fileof('/system/etc/fonts.xml', '<familyset>\\n</familyset>\\n', 'fd')\n" +
                        "local other = fileof('/data/user/0/app/files/fonts.xml', xml, 'fd')\n" +
                        "local missing = fileof('/system/etc/fonts.xml', xml, nil)\n" +
                        "local failed = fileof('/system/etc/fonts.xml', xml, 'fd')\n" +
                        "failed.getException = function(self) return 'error' end\n" +
                        "local restricted, count = after(hookof('open'), maker)\n" +
                        "return restricted, count, maker:text(), after(hookof('open'), plain), plain.set,\n" +
                        "  after(hookof('open'), other), other.set, after(hookof('open'), missing), missing.set,\n" +
                        "  after(hookof('open'), failed), failed.set");
        assertTrue(result.arg1().toboolean());
        assertEquals("1", result.arg(2).tojstring());
        assertEquals(text, result.arg(3).tojstring());
        for (int i = 4; i <= 11; i++)
            assertFalse(String.valueOf(i), result.arg(i).toboolean());

        // Also by other paths: /./, //, the root of /proc and links, like /etc to /system/etc
        result = Scripts.run(globals,
                "local function path(p) return { getArgument = function(self) return p end } end\n" +
                        "local plain = { config(path('/system/./etc/fonts.xml')), config(path('/system//etc/./././fonts.xml')),\n" +
                        "  config(path('/proc/self/root/system/etc/fonts.xml')), config(path('system/etc/fonts.xml')) }\n" +
                        "luajava.newInstance = function(name, p) return { getCanonicalPath = function(self)\n" +
                        "  return (p == '/etc/fonts.xml' and '/system/etc/fonts.xml' or p) end } end\n" +
                        "return plain[1], plain[2], plain[3], plain[4], config(path('/etc/fonts.xml')),\n" +
                        "  config(path('/data/user/0/app/./etc/fonts.xml'))");
        for (int i = 1; i <= 5; i++)
            assertTrue("path " + i, result.arg(i).toboolean());
        assertFalse(result.arg(6).toboolean());
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

        // The time of AndroidX is an object, null when there is no GPS time
        result = Scripts.run(globals, "local time = paramof(1700000000000)\n" +
                "return after(hookof('getGpsDateTime', 'java.lang.Long'), time), time.result, time.set");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).toboolean());

        // The location columns of MediaStore are null, the others, the notification URI and the cursor stay;
        // other providers stay
        result = Scripts.run(globals,
                "luajava.createProxy = function(interface, methods) return methods end\n" +
                        "local function uri(authority) return { getAuthority = function(self) return authority end } end\n" +
                        "local function cursor()\n" +
                        "  local data = { { '1', 52.5, 13.4, 1700000000000 } }\n" +
                        "  local names = { 'title', 'latitude', 'longitude', 'datetaken' }\n" +
                        "  local position = 0\n" +
                        "  return { closed = false,\n" +
                        "    getColumnIndex = function(self, n) for i, c in ipairs(names) do if c == n then return i - 1 end end return -1 end,\n" +
                        "    getType = function(self, i) return (i == 0 and 3) or (i == 3 and 1) or 2 end,\n" +
                        "    isNull = function(self, i) return false end,\n" +
                        "    getString = function(self, i) return tostring(data[position + 1][i + 1]) end,\n" +
                        "    getLong = function(self, i) return data[position + 1][i + 1] end,\n" +
                        "    getDouble = function(self, i) return data[position + 1][i + 1] end,\n" +
                        "    getNotificationUri = function(self) return 'content://media/external' end,\n" +
                        "    close = function(self) self.closed = true end }\n" +
                        "end\n" +
                        "local real = cursor()\n" +
                        "local media = paramof(real, { [0] = uri('media') })\n" +
                        "local restricted = after(hookof('query'), media)\n" +
                        "local c = media.result\n" +
                        "return restricted, c.getDouble(1), c.getString(2), c.getType(1), c.isNull(2), c.getString(0),\n" +
                        "  c.getLong(3), c.getType(3), c.getNotificationUri(), c.equals, real.closed,\n" +
                        "  after(hookof('query'), paramof(cursor(), { [0] = uri('com.android.contacts') })),\n" +
                        "  after(hookof('query'), paramof(cursor(), { [0] = uri('0@media') })),\n" +
                        "  after(hookof('query'), paramof(cursor(), { [0] = uri(nil) }))");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).todouble(), 0);
        assertTrue(result.arg(3).isnil());
        assertEquals(0, result.arg(4).toint()); // Cursor.FIELD_TYPE_NULL
        assertTrue(result.arg(5).toboolean());
        assertEquals("1", result.arg(6).tojstring());
        // Whole numbers keep their exact digits and type, like a date in milliseconds
        assertEquals(1700000000000L, result.arg(7).tolong());
        assertEquals(1, result.arg(8).toint());
        assertEquals("content://media/external", result.arg(9).tojstring());
        // equals and hashCode of the proxy itself
        assertTrue(result.arg(10).isnil());
        assertFalse(result.arg(11).toboolean());
        assertFalse(result.arg(12).toboolean());
        // The media provider by the user in front too, and an uri without an authority
        assertTrue(result.arg(13).toboolean());
        assertFalse(result.arg(14).toboolean());
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

        // The serial numbers of other users are like those of users which do not exist
        result = Scripts.run(globals,
                "local function user(id) return { id = id, equals = function(self, other) return self.id == other.id end } end\n" +
                        "luajava.bindClass = function(name) return { myUserHandle = function(self) return user(0) end } end\n" +
                        "local other = paramof(user(10), { [0] = 16 })\n" +
                        "local restricted = after(hookof('getUserForSerialNumber'), other)\n" +
                        "local own = paramof(user(0), { [0] = 0 })\n" +
                        "local stays = after(hookof('getUserForSerialNumber'), own)\n" +
                        "local serial = paramof(16, { [0] = user(10) })\n" +
                        "local hidden = after(hookof('getSerialNumberForUser'), serial)\n" +
                        "local mine = paramof(0, { [0] = user(0) })\n" +
                        "local kept = after(hookof('getSerialNumberForUser'), mine)\n" +
                        "local missing = paramof(-1, { [0] = user(11) })\n" +
                        "return restricted, other.result, stays, own.result.id, hidden, serial.result, kept, mine.result,\n" +
                        "  after(hookof('getSerialNumberForUser'), missing)");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertTrue(result.arg(5).toboolean());
        assertEquals(-1, result.arg(6).toint());
        assertFalse(result.arg(7).toboolean());
        assertEquals(0, result.arg(8).toint());
        assertFalse(result.arg(9).toboolean());
    }

    // The filter of aliases passes all values with /../ (see XCore.Filter), of which only those of the user are faked
    @Test
    public void onlyAliasesOfTheUserAreHidden() throws Exception {
        Varargs result = Scripts.run(load("device_details"),
                "local system = paramof(true, { [0] = 'system:./../cacerts/abc.0' })\n" +
                        "local certificate = paramof('certificate', { [0] = 'system:./../cacerts/abc.0' })\n" +
                        "local user = paramof('certificate', { [0] = 'user:./../cacerts-added/abc.0' })\n" +
                        "return after(hookof('containsAlias'), system), system.result,\n" +
                        "  after(hookof('getCertificate'), certificate), certificate.result,\n" +
                        "  after(hookof('getCertificate'), user), user.result");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertEquals("certificate", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());
    }

    // Android answers a new listener of the heat with the real values, here those of the getters, once, and throws for
    // removing a listener it does not know, which the blocked ones are to it
    @Test
    public void thermalListenersAreAnswered() throws Exception {
        Globals globals = load("device_details");
        Scripts.run(globals,
                "values, posted, answers = {}, {}, {}\n" +
                        "function hook(method, count)\n" +
                        "  return { getMethodName = function(self) return method end,\n" +
                        "    getParameterTypes = function(self) return { length = count } end }\n" +
                        "end\n" +
                        "function call(method, ...)\n" +
                        "  local args = { ... }\n" +
                        "  local count = select('#', ...)\n" +
                        "  local p = paramof(nil, {})\n" +
                        "  for index = 1, count do p.args[index - 1] = args[index] end\n" +
                        "  p.set = false\n" +
                        "  p.setResult = function(self, value) self.set = true end\n" +
                        "  p.getValue = function(self, name, scope) return values[scope] end\n" +
                        "  p.putValue = function(self, name, value, scope) values[scope] = value end\n" +
                        "  local restricted = before(hook(method, count), p)\n" +
                        "  return restricted, p.set\n" +
                        "end\n" +
                        "function drain()\n" +
                        "  for _, runnable in ipairs(posted) do runnable.run() end\n" +
                        "  posted = {}\n" +
                        "end\n" +
                        "luajava.createProxy = function(name, object) assert(name == 'java.lang.Runnable') return object end\n" +
                        "luajava.bindClass = function(name) assert(name == 'android.os.Looper')\n" +
                        "  return { getMainLooper = function(self) return 'main' end } end\n" +
                        "luajava.newInstance = function(name, looper)\n" +
                        "  if name == 'java.util.HashMap' then return 'empty' end\n" +
                        "  assert(name == 'android.os.Handler' and looper == 'main')\n" +
                        "  return { post = function(self, runnable) posted[#posted + 1] = runnable end }\n" +
                        "end\n" +
                        "executor = { execute = function(self, runnable) posted[#posted + 1] = runnable end }\n" +
                        "listener = {\n" +
                        "  onThermalStatusChanged = function(self, status) answers[#answers + 1] = 'status ' .. status end,\n" +
                        "  onThermalHeadroomChanged = function(self, headroom, forecast, seconds, thresholds)\n" +
                        "    answers[#answers + 1] = tostring(headroom ~= headroom) .. ' ' .. tostring(forecast ~= forecast) ..\n" +
                        "      ' ' .. seconds .. ' ' .. thresholds end }");

        // By the main thread, once, and the blocked listener is removed once; Android throws for the second time
        Varargs result = Scripts.run(globals,
                "local added, skipped = call('addThermalStatusListener', listener)\n" +
                        "local count = #posted\n" +
                        "drain() drain()\n" +
                        "local removed, skipped2 = call('removeThermalStatusListener', listener)\n" +
                        "local _, skipped3 = call('removeThermalStatusListener', listener)\n" +
                        "return added, skipped, count, table.concat(answers, ','), removed, skipped2, skipped3");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals("status 0", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());

        // By the executor of the call; headroom like the getters: not known, no thresholds
        result = Scripts.run(globals,
                "answers = {}\n" +
                        "local real = luajava.bindClass\n" +
                        "luajava.bindClass = function(name) error('no main thread') end\n" +
                        "local added = call('addThermalHeadroomListener', executor, listener)\n" +
                        "luajava.bindClass = real\n" +
                        "drain()\n" +
                        "local _, skipped = call('removeThermalHeadroomListener', listener)\n" +
                        "return added, table.concat(answers, ','), skipped");
        assertTrue(result.arg(1).toboolean());
        assertEquals("true true 0 empty", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());

        // Android throws for null itself, the other hooks of the script do nothing before the call
        result = Scripts.run(globals,
                "local a, b = call('addThermalStatusListener', nil)\n" +
                        "local c, d = call('addThermalStatusListener', nil, listener)\n" +
                        "local e, f = call('getCurrentThermalStatus')\n" +
                        "return a, b, c, d, e, f");
        for (int i = 1; i <= 6; i++)
            assertFalse("Value " + i, result.arg(i).toboolean());
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
                        "  amplitude.result, effects.set, effects.result, aliases.result:size(),\n" +
                        "  aliases.result:contains('system:abc.0'), contains.result,\n" +
                        "  table.concat(filter(hookof('containsAlias')), ','), filter(hookof('aliases'))");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).toint());
        // The sample rate stays, the frames per buffer are common
        assertEquals("44100", result.arg(3).tojstring());
        assertEquals("256", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        // A Java array of the length asked for, all unknown
        assertArrayEquals(new int[3], (int[]) result.arg(7).touserdata());
        assertEquals(1, result.arg(8).toint());
        assertTrue(result.arg(9).toboolean());
        assertFalse(result.arg(10).toboolean());
        assertEquals("user:*", result.arg(11).tojstring());
        assertTrue(result.arg(12).isnil());

        // Arrays which are unknown or not supported already are not reported, like what Vibrator makes of the details
        // of the hooked getInfo
        result = Scripts.run(globals,
                "return after(hookof('areEffectsSupported'), paramof({ length = 2, 0, 0 })),\n" +
                        "  after(hookof('arePrimitivesSupported'), paramof({ length = 1, false })),\n" +
                        "  after(hookof('getPrimitiveDurations'), paramof({ length = 0 }))");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());

        // The primitives asked for are not supported, also a Java array
        result = Scripts.run(globals, "local primitives = paramof({ length = 2, true, false })\n" +
                "return after(hookof('arePrimitivesSupported'), primitives), primitives.result");
        assertTrue(result.arg1().toboolean());
        assertArrayEquals(new boolean[2], (boolean[]) result.arg(2).touserdata());

        // No thresholds of the heat and no aliases of certificates the user installed; nothing to report without them
        for (String[] c : new String[][]{{"getThermalHeadroomThresholds", "java.util.HashMap", "put(0.5, 40)"},
                {"userAliases", "java.util.HashSet", "add('user:def.0')"}}) {
            result = Scripts.run(globals, "local values = luajava.newInstance('" + c[1] + "')\n" +
                    "local none = paramof(values)\n" +
                    "local reported = after(hookof('" + c[0] + "'), none)\n" +
                    "values:" + c[2] + "\n" +
                    "local some = paramof(values)\n" +
                    "local restricted, old, new = after(hookof('" + c[0] + "'), some)\n" +
                    "return reported, none.set, restricted, old, new, some.result:isEmpty()");
            assertFalse(c[0], result.arg1().toboolean());
            assertFalse(c[0], result.arg(2).toboolean());
            assertTrue(c[0], result.arg(3).toboolean());
            assertEquals(c[0], "1", result.arg(4).tojstring());
            assertEquals(c[0], "0", result.arg(5).tojstring());
            assertTrue(c[0], result.arg(6).toboolean());
        }

        // The certificate of a user alias, like of one which is not there, runs by the filter of containsAlias
        result = Scripts.run(globals,
                "local certificate = paramof('certificate', { [0] = 'user:def.0' })\n" +
                        "local none = paramof(nil, { [0] = 'user:def.0' })\n" +
                        "return after(hookof('getCertificate'), certificate), certificate.result, certificate.set,\n" +
                        "  after(hookof('getCertificate'), none), none.set, filter(hookof('getCertificate'))[1]");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertEquals("user:*", result.arg(6).tojstring());

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

    // The screen lock is set, so it is a way to authenticate, which biometrics alone say nothing about
    @Test
    public void screenLockCanAuthenticate() throws Exception {
        Varargs result = Scripts.run(load("device_details"),
                "local lock = paramof(11, { [0] = 32768 })\n" +
                        "local changed = after(hookof('canAuthenticate'), lock)\n" +
                        "local either = paramof(11, { [0] = 32768 + 15 })\n" +
                        "after(hookof('canAuthenticate'), either)\n" +
                        "local biometric = paramof(11, { [0] = 15 })\n" +
                        "after(hookof('canAuthenticate'), biometric)\n" +
                        "local real = paramof(0, { [0] = 32768 })\n" +
                        "local kept = after(hookof('canAuthenticate'), real)\n" +
                        "return lock.result, either.result, biometric.result, biometric.set, kept, real.set, changed");
        assertEquals(0, result.arg(1).toint());
        assertEquals(0, result.arg(2).toint());
        assertEquals(11, result.arg(3).toint());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertTrue(result.arg(7).toboolean());
    }
}
