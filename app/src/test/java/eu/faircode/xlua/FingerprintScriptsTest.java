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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
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
            "    getClassName = function(self) return nil end,",
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
            "  param.getThis = function(self) return self.this end",
            "  param.getValue = function(self, name, scope) return nil end",
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

    private static String text(Varargs values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= values.narg(); i++)
            sb.append(i > 1 ? " " : "").append(values.arg(i).tojstring());
        return sb.toString();
    }

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

        // createByCodecName with a fake name opens the real codec; a real name, which the faked list does not have,
        // goes to Android as the fake name, of no codec of the device, also its secure variant, which Android 9+ finds
        // by the name without .secure; the codecs of Android and unknown names stay
        result = Scripts.run(globals,
                "luajava.newInstance = function(name, kind)\n" +
                        "  local names = { 'c2.android.avc.decoder', 'c2.qti.avc.decoder', 'c2.qti.hevc.decoder' }\n" +
                        "  return { getCodecInfos = function(self)\n" +
                        "    local infos = { length = #names }\n" +
                        "    for i, n in ipairs(names) do infos[i] = { getName = function(self) return n end } end\n" +
                        "    return infos end }\n" +
                        "end\n" +
                        "local fake = paramof(nil, { [0] = 'c2.hw.hevc.decoder' })\n" +
                        "local opened = before(hookof('createByCodecName'), fake)\n" +
                        "local real = paramof(nil, { [0] = 'c2.qti.avc.decoder' })\n" +
                        "local refused = before(hookof('createByCodecName'), real)\n" +
                        "local secure = paramof(nil, { [0] = 'c2.qti.avc.decoder.secure' })\n" +
                        "before(hookof('createByCodecName'), secure)\n" +
                        "local kept = paramof(nil, { [0] = 'c2.android.avc.decoder' })\n" +
                        "local unknown = paramof(nil, { [0] = 'c2.exynos.avc.decoder' })\n" +
                        "return fake.args[0], opened, real.args[0], refused, secure.args[0],\n" +
                        "  before(hookof('createByCodecName'), kept), kept.args[0],\n" +
                        "  before(hookof('createByCodecName'), unknown), unknown.args[0],\n" +
                        "  after(hookof('createByCodecName'), paramof('codec'))");
        assertEquals("c2.qti.hevc.decoder", result.arg(1).tojstring());
        assertFalse(result.arg(2).toboolean());
        assertEquals("c2.hw.avc.decoder", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertEquals("c2.hw.avc.decoder.secure", result.arg(5).tojstring());
        assertFalse(result.arg(6).toboolean());
        assertEquals("c2.android.avc.decoder", result.arg(7).tojstring());
        assertFalse(result.arg(8).toboolean());
        assertEquals("c2.exynos.avc.decoder", result.arg(9).tojstring());
        assertFalse(result.arg(10).toboolean());

        // The exception of the name Android was given names the codec the app asked for, in both directions, with
        // the same class and stack trace; one without a constructor with a message, like CodecException, gets the
        // message itself, and one of a name which went to Android unchanged stays
        globals.set("coded", CoerceJavaToLua.coerce(PackageScriptsTest.CodedException.class));
        result = Scripts.run(globals,
                "local function fails(name, class, format)\n" +
                        "  local p = paramof(nil, { [0] = name })\n" +
                        "  before(hookof('createByCodecName'), p)\n" +
                        "  local message = string.format(format, p.args[0])\n" +
                        "  local ex = (class == coded and luajava.new(class, -12, message) or luajava.new(class, message))\n" +
                        "  p.getException = function(self) return ex end\n" +
                        "  p.setMessage = function(self, ex, message) self.message = message end\n" +
                        "  return after(hookof('createByCodecName'), p), ex, p.result, p.message\n" +
                        "end\n" +
                        "local real = { fails('c2.qti.avc.decoder', luajava.bindClass('java.lang.IllegalArgumentException'),\n" +
                        "  'Failed to initialize %s, error 0xfffffffe (NAME_NOT_FOUND)') }\n" +
                        "local fake = { fails('c2.hw.hevc.decoder', luajava.bindClass('java.io.IOException'),\n" +
                        "  'Failed to find matching codec %s, error 0x80000000 (?)') }\n" +
                        "local coded = { fails('c2.hw.hevc.decoder', coded, 'Failed to initialize %s, error 0xfffffff4 (NO_MEMORY)') }\n" +
                        "local unknown = { fails('c2.exynos.avc.decoder', luajava.bindClass('java.lang.IllegalArgumentException'),\n" +
                        "  'Failed to initialize %s, error 0xfffffffe') }\n" +
                        "return real[1], real[2], real[3], fake[1], fake[2], fake[3], coded[1], coded[3], coded[4],\n" +
                        "  unknown[1], unknown[3], unknown[4]");
        assertFalse(result.arg(1).toboolean());
        Throwable genuine = (Throwable) result.arg(2).touserdata();
        Throwable thrown = (Throwable) result.arg(3).touserdata();
        assertEquals(IllegalArgumentException.class, thrown.getClass());
        assertEquals("Failed to initialize c2.qti.avc.decoder, error 0xfffffffe (NAME_NOT_FOUND)", thrown.getMessage());
        assertArrayEquals(genuine.getStackTrace(), thrown.getStackTrace());
        assertFalse(result.arg(4).toboolean());
        genuine = (Throwable) result.arg(5).touserdata();
        thrown = (Throwable) result.arg(6).touserdata();
        assertEquals(IOException.class, thrown.getClass());
        assertEquals("Failed to find matching codec c2.hw.hevc.decoder, error 0x80000000 (?)", thrown.getMessage());
        assertArrayEquals(genuine.getStackTrace(), thrown.getStackTrace());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).isnil());
        assertEquals("Failed to initialize c2.hw.hevc.decoder, error 0xfffffff4 (NO_MEMORY)", result.arg(9).tojstring());
        assertFalse(result.arg(10).toboolean());
        assertTrue(result.arg(11).isnil());
        assertTrue(result.arg(12).isnil());

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

        // The metrics of a player name the codecs it decoded with: the one of the chip is faked, the one of Android stays
        result = Scripts.run(load("media_codecs"),
                "local video, audio = 'android.media.mediaplayer.video.codec', 'android.media.mediaplayer.audio.codec'\n" +
                        "local values = { [video] = 'OMX.qcom.video.decoder.avc', [audio] = 'c2.android.aac.decoder' }\n" +
                        "local bundle = { getString = function(self, name) return values[name] end,\n" +
                        "  putString = function(self, name, value) values[name] = value end }\n" +
                        "luajava.newInstance = function(name, kind)\n" +
                        "  return { getCodecInfos = function(self)\n" +
                        "    return { length = 1, { getName = function(self) return 'OMX.qcom.video.decoder.avc' end } } end }\n" +
                        "end\n" +
                        "local restricted, old, new = after(hookof('getMetrics'), paramof(bundle))\n" +
                        "return restricted, old, new, values[video], values[audio]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("OMX.qcom.video.decoder.avc", result.arg(2).tojstring());
        assertEquals("OMX.hw.video.decoder.avc", result.arg(3).tojstring());
        assertEquals("OMX.hw.video.decoder.avc", result.arg(4).tojstring());
        assertEquals("c2.android.aac.decoder", result.arg(5).tojstring());
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

        // By index: the kept ones, beyond them what the driver gives for an index beyond its own ones, with its
        // GL_INVALID_VALUE: an empty name, like the one of the emulator, or none; the list is read once per context
        result = Scripts.run(globals,
                "reads = 0\n" +
                        "context = 1\n" +
                        "none = ''\n" +
                        "luajava.bindClass = function(name) return {\n" +
                        "  glGetString = function(self, n) reads = reads + 1 return '" + real + "' end,\n" +
                        "  glGetStringi = function(self, n, i) invalid = name .. ' ' .. n .. ' ' .. i return none end,\n" +
                        "  eglGetCurrentContext = function(self) return { getNativeHandle = function(self) return context end } end } end\n" +
                        "local second = paramof('GL_QCOM_tiled_rendering', { [0] = 7939, [1] = 1 })\n" +
                        "after(hookof('glGetStringi'), second)\n" +
                        "local beyond = paramof('GL_IMG_texture_compression_pvrtc', { [0] = 7939, [1] = 5 })\n" +
                        "after(hookof('glGetStringi'), beyond)\n" +
                        // Beyond the real ones too, the empty name of the driver stays
                        "local past = paramof('', { [0] = 7939, [1] = 8 })\n" +
                        "local changed = after(hookof('glGetStringi'), past)\n" +
                        "none = nil\n" +
                        "local null = paramof('GL_IMG_texture_compression_pvrtc', { [0] = 7939, [1] = 5 })\n" +
                        "after(hookof('glGetStringi'), null)\n" +
                        "local once = reads\n" +
                        "context = 2\n" +
                        "after(hookof('glGetStringi'), paramof('GL_OES_EGL_image', { [0] = 7939, [1] = 0 }))\n" +
                        "return second.result, beyond.result, beyond.set, invalid, once, reads, changed, past.result,\n" +
                        "  null.result, null.set");
        assertEquals("GL_EXT_color_buffer_float", result.arg1().tojstring());
        assertEquals("", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("android.opengl.GLES30 7939 -1", result.arg(4).tojstring());
        assertEquals(1, result.arg(5).toint());
        assertEquals(2, result.arg(6).toint());
        assertFalse(result.arg(7).toboolean());
        assertEquals("", result.arg(8).tojstring());
        assertTrue(result.arg(9).isnil());
        assertTrue(result.arg(10).toboolean());
    }

    // GL_NUM_EXTENSIONS is the number of the kept extensions, into the array at the offset or the buffer at its position,
    // of int, long and float; other names do not read the list, nor does a call without a current context change it
    @Test
    public void glNumberOfExtensionsIsOfTheKeptOnes() throws Exception {
        Globals globals = load("gles_getstring");
        String real = "GL_OES_EGL_image GL_QCOM_tiled_rendering GL_EXT_color_buffer_float GL_ARM_shader_framebuffer_fetch ";
        Scripts.run(globals, "binds = 0\n" +
                "context = 1\n" +
                "extensions = '" + real + "'\n" +
                // The classes of Java the script swaps the bytes with are the real ones
                "local bind = luajava.bindClass\n" +
                "luajava.bindClass = function(name) if string.sub(name, 1, 5) == 'java.' then return bind(name) end\n" +
                "  binds = binds + 1 return {\n" +
                "  glGetString = function(self, n) return extensions end,\n" +
                "  eglGetCurrentContext = function(self) return { getNativeHandle = function(self) return context end } end } end\n" +
                "function counted(method, types, params, offset)\n" +
                "  local hook = hookof(method)\n" +
                "  hook.getParameterTypes = function(self) return types end\n" +
                "  return after(hook, paramof(nil, { [0] = 33309, [1] = params, [2] = offset }))\n" +
                "end");
        int[] ints = {7, 4, 7};
        IntBuffer buffer = IntBuffer.wrap(new int[]{7, 4, 7});
        buffer.position(1);
        long[] longs = {4};
        FloatBuffer floats = FloatBuffer.wrap(new float[]{4});
        globals.set("ints", CoerceJavaToLua.coerce(ints));
        globals.set("buffer", CoerceJavaToLua.coerce(buffer));
        globals.set("longs", CoerceJavaToLua.coerce(longs));
        globals.set("floats", CoerceJavaToLua.coerce(floats));
        globals.set("three", CoerceJavaToLua.coerce(new String[]{"int", "int[]", "int"}));
        globals.set("two", CoerceJavaToLua.coerce(new String[]{"int", "java.nio.IntBuffer"}));
        Varargs result = Scripts.run(globals, "return counted('glGetIntegerv', three, ints, 1), counted('glGetIntegerv', two, buffer),\n" +
                "  counted('glGetInteger64v', three, longs, 0), counted('glGetFloatv', two, floats),\n" +
                // The JNI of glGetInteger64v does not check the length of the array
                "  counted('glGetInteger64v', three, longs, 1)");
        assertTrue(result.arg1().toboolean());
        assertArrayEquals(new int[]{7, 2, 7}, ints);
        assertTrue(result.arg(2).toboolean());
        assertArrayEquals(new int[]{7, 2, 7}, buffer.array());
        assertEquals(1, buffer.position());
        assertTrue(result.arg(3).toboolean());
        assertArrayEquals(new long[]{2}, longs);
        assertTrue(result.arg(4).toboolean());
        assertEquals(2, floats.get(0), 0);
        assertFalse(result.arg(5).toboolean());

        // A direct buffer of the other byte order reads what the driver wrote swapped, so the fake too (the second call
        // finds it there already); a read-only one keeps the real count
        ByteOrder other = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        IntBuffer swappedInts = ByteBuffer.allocateDirect(4).order(other).asIntBuffer().put(0, Integer.reverseBytes(4));
        LongBuffer swappedLongs = ByteBuffer.allocateDirect(8).order(other).asLongBuffer().put(0, Long.reverseBytes(4));
        FloatBuffer swappedFloats = ByteBuffer.allocateDirect(4).order(other).asFloatBuffer()
                .put(0, Float.intBitsToFloat(Integer.reverseBytes(Float.floatToRawIntBits(4))));
        IntBuffer readOnly = ByteBuffer.allocateDirect(4).asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asIntBuffer();
        globals.set("swappedInts", CoerceJavaToLua.coerce(swappedInts));
        globals.set("swappedLongs", CoerceJavaToLua.coerce(swappedLongs));
        globals.set("swappedFloats", CoerceJavaToLua.coerce(swappedFloats));
        globals.set("readOnly", CoerceJavaToLua.coerce(readOnly));
        result = Scripts.run(globals, "return counted('glGetIntegerv', two, swappedInts),\n" +
                "  counted('glGetInteger64v', two, swappedLongs), counted('glGetFloatv', two, swappedFloats),\n" +
                "  counted('glGetIntegerv', two, swappedInts),\n" +
                "  counted('glGetIntegerv', two, readOnly)");
        assertTrue(result.arg1().toboolean());
        assertEquals(Integer.reverseBytes(2), swappedInts.get(0));
        assertTrue(result.arg(2).toboolean());
        assertEquals(Long.reverseBytes(2), swappedLongs.get(0));
        assertTrue(result.arg(3).toboolean());
        assertEquals(Integer.reverseBytes(Float.floatToRawIntBits(2)), Float.floatToRawIntBits(swappedFloats.get(0)));
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertEquals(0, readOnly.get(0));

        // The real count already, another name (without reading the list) and no current context change nothing
        result = Scripts.run(globals, "local same = counted('glGetIntegerv', three, ints, 1)\n" +
                "binds = 0\n" +
                "ints[1] = 4\n" +
                "local other = after(hookof('glGetIntegerv'), paramof(nil, { [0] = 3379, [1] = ints, [2] = 0 }))\n" +
                "local unbound = binds\n" +
                "context = 0\n" +
                "extensions = nil\n" +
                "return same, other, unbound, counted('glGetIntegerv', three, ints, 0)");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals(0, result.arg(3).toint());
        assertFalse(result.arg(4).toboolean());
        assertArrayEquals(new int[]{4, 2, 7}, ints);
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

        // The processors possible, present and online of /sys are 0-7, none is offline and each of the 8 is online,
        // like the kernel prints them (offline is possible and not online); the other files and the folder stay
        assertTrue(Scripts.run(globals, "return filter(hookof('availableProcessors'))").arg1().isnil());
        assertEquals("*system/cpu/*", Scripts.run(globals, "return filter(hookof('open'))[1]").arg1().tojstring());
        assertEquals("*/status*", Scripts.run(globals, "return filter(hookof('open'))[2]").arg1().tojstring());
        assertEquals("*bus/cpu/*", Scripts.run(globals, "return filter(hookof('open'))[3]").arg1().tojstring());
        // The canonical path of each file, by default the path without /proc/self/root, and how often one was resolved
        String files = "local CANONICAL, resolved = {}, 0\n" +
                "luajava.newInstance = function(name, path) assert(name == 'java.io.File')\n" +
                "  return { getCanonicalPath = function(self) resolved = resolved + 1\n" +
                "    return CANONICAL[path] or error('No file ' .. path) end } end\n" +
                "local function fileof(path, text, canonical)\n" +
                "  CANONICAL[path] = canonical or string.gsub(path, '^/proc/self/root', '')\n" +
                "  local p = paramof('fd', { [0] = path })\n" +
                "  p.getFileText = function(self) return text end\n" +
                "  p.setFileText = function(self, value) text = value end\n" +
                "  p.text = function(self) return text end\n" +
                "  return p\n" +
                "end\n";
        result = Scripts.run(globals, files +
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
        result = Scripts.run(globals, files +
                "local offline = fileof('/sys/devices/system/cpu/offline', '6-7\\n')\n" +
                "local six = fileof('/sys/devices/system/cpu/cpu6/online', '0\\n')\n" +
                "local nine = fileof('/sys/devices/system/cpu/cpu9/online', '0\\n')\n" +
                "local small = fileof('/sys/devices/system/cpu/kernel_max', '3\\n')\n" +
                "local max = fileof('/sys/devices/system/cpu/kernel_max', '31\\n')\n" +
                "local freq = fileof('/sys/devices/system/cpu/cpu0/cpufreq/online', '0\\n')\n" +
                "local function said(...) return table.concat({ tostring((...)), tostring((select(2, ...))),\n" +
                "  tostring((select(3, ...))) }, ' ') end\n" +
                "return said(after(hookof('open'), offline)), offline:text(), said(after(hookof('open'), six)), six:text(),\n" +
                "  after(hookof('open'), nine), nine:text(), after(hookof('open'), small), small:text(),\n" +
                "  after(hookof('open'), max), max:text(), after(hookof('open'), freq), freq:text()");
        assertEquals("true 6-7 ", result.arg1().tojstring());
        assertEquals("\n", result.arg(2).tojstring());
        assertEquals("true 0 1", result.arg(3).tojstring());
        assertEquals("1\n", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertEquals("0\n", result.arg(6).tojstring());
        assertTrue(result.arg(7).toboolean());
        assertEquals("7\n", result.arg(8).tojstring());
        assertFalse(result.arg(9).toboolean());
        assertEquals("31\n", result.arg(10).tojstring());
        assertFalse(result.arg(11).toboolean());
        assertEquals("0\n", result.arg(12).tojstring());
        // A processor by its link in /sys/bus/cpu/devices is the same file
        result = Scripts.run(globals, files +
                "local bus = fileof('/sys/bus/cpu/devices/cpu6/online', '0\\n', '/sys/devices/system/cpu/cpu6/online')\n" +
                "local restricted, old, new = after(hookof('open'), bus)\n" +
                "return restricted, old, new, bus:text()");
        assertTrue(result.arg1().toboolean());
        assertEquals("0", result.arg(2).tojstring());
        assertEquals("1", result.arg(3).tojstring());
        assertEquals("1\n", result.arg(4).tojstring());
        // So is a processor by // or /./, by .. after its own folder and by .. after its link; cpu6/.. is the folder of
        // all of them; a file of another folder, like of the memory, and a path which cannot be resolved stay
        String[][] spellings = {
                {"/sys/devices/system/cpu/cpu6//online", "/sys/devices/system/cpu/cpu6/online", "0\n", "true 0 1", "1\n"},
                {"/sys/devices/system/cpu/cpu6/./online", "/sys/devices/system/cpu/cpu6/online", "0\n", "true 0 1", "1\n"},
                {"/sys/devices/system/cpu/cpu6/power/../online", "/sys/devices/system/cpu/cpu6/online", "0\n", "true 0 1", "1\n"},
                {"/sys/bus/cpu/devices/cpu6//online", "/sys/devices/system/cpu/cpu6/online", "0\n", "true 0 1", "1\n"},
                {"/sys/devices/system/cpu/cpu6/../online", "/sys/devices/system/cpu/online", "0-5\n", "true 0-5 0-7", "0-7\n"},
                {"/sys/bus/cpu/devices/cpu6/../offline", "/sys/devices/system/cpu/offline", "6-7\n", "true 6-7 ", "\n"},
                {"/sys/devices/system/cpu/../memory/memory0/online", "/sys/devices/system/memory/memory0/online", "0\n",
                        "false nil nil", "0\n"},
                {"/sys/devices/system/cpu/cpu6/../online", null, "0-5\n", "false nil nil", "0-5\n"}};
        for (String[] spelling : spellings) {
            result = Scripts.run(globals, files +
                    "local p = fileof('" + spelling[0] + "', [[" + spelling[2] + "]], " +
                    (spelling[1] == null ? "nil" : "'" + spelling[1] + "'") + ")\n" +
                    (spelling[1] == null ? "CANONICAL['" + spelling[0] + "'] = nil\n" : "") +
                    "local restricted, old, new = after(hookof('open'), p)\n" +
                    "return tostring(restricted) .. ' ' .. tostring(old) .. ' ' .. tostring(new), p:text(), resolved");
            assertEquals(spelling[0], spelling[3], result.arg1().tojstring());
            assertEquals(spelling[0], spelling[4], result.arg(2).tojstring());
            assertEquals(spelling[0], 1, result.arg(3).toint());
        }
        // A file which ends in no name of these is not resolved, also when it ends in no letter
        for (String path : new String[]{"/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
                "/sys/devices/system/cpu/cpu6/..", "/data/user/0/app/files/status.v2"}) {
            result = Scripts.run(globals, files +
                    "local p = fileof('" + path + "', '300000\\n')\n" +
                    "return after(hookof('open'), p), p:text(), resolved");
            assertFalse(path, result.arg1().toboolean());
            assertEquals(path, "300000\n", result.arg(2).tojstring());
            assertEquals(path, 0, result.arg(3).toint());
        }

        // The processors a task may run on in the status files of /proc, also by links, are the 8, the other lines stay
        String status = "Name:\tprobe\nState:\tR (running)\nTracerPid:\t0\nCpus_allowed:\tf\nCpus_allowed_list:\t0-3\n" +
                "Mems_allowed:\t1\nMems_allowed_list:\t0\nvoluntary_ctxt_switches:\t5\n";
        String links = files +
                "local REAL = { ['/proc/self/status'] = '/proc/123/status',\n" +
                "  ['/proc/thread-self/status'] = '/proc/123/task/124/status',\n" +
                "  ['/proc/self/root/proc/self/status'] = '/proc/123/status',\n" +
                "  ['/proc/self/task/124/status'] = '/proc/123/task/124/status',\n" +
                "  ['/data/user/0/app/files/status'] = '/data/user/0/app/files/status',\n" +
                "  ['/sys/class/power_supply/battery/status'] = '/sys/devices/battery/status' }\n" +
                "luajava.newInstance = function(name, path) assert(name == 'java.io.File')\n" +
                "  return { getCanonicalPath = function(self) return REAL[path] or error('No file ' .. path) end } end\n" +
                "local function statusof(path, text)\n" +
                "  local p = fileof(path, text)\n" +
                "  local restricted, old, new = after(hookof('open'), p)\n" +
                "  return tostring(restricted) .. ' ' .. tostring(old) .. ' ' .. tostring(new), p:text()\n" +
                "end\n";
        String fake = status.replace("Cpus_allowed:\tf\n", "Cpus_allowed:\tff\n").replace("_list:\t0-3\n", "_list:\t0-7\n");
        for (String path : new String[]{"/proc/self/status", "/proc/thread-self/status", "/proc/self/root/proc/self/status",
                "/proc/self/task/124/status"}) {
            result = Scripts.run(globals, links + "return statusof('" + path + "', [[" + status + "]])");
            assertEquals(path, "true 0-3 0-7", result.arg1().tojstring());
            assertEquals(path, fake, result.arg(2).tojstring());
        }
        String eight = status.replace("Cpus_allowed:\tf\n", "Cpus_allowed:\tff\n").replace("_list:\t0-3\n", "_list:\t0-5,7\n");
        result = Scripts.run(globals, links + "return statusof('/proc/self/status', [[" + eight + "]])");
        assertEquals("true 0-5,7 0-7", result.arg1().tojstring());
        assertEquals(fake, result.arg(2).tojstring());
        for (String path : new String[]{"/proc/self/status", "/data/user/0/app/files/status",
                "/sys/class/power_supply/battery/status", "/proc/self/stat"}) {
            String same = path.startsWith("/proc") ? fake : status;
            result = Scripts.run(globals, links + "return statusof('" + path + "', [[" + same + "]])");
            assertEquals(path, "false nil nil", result.arg1().tojstring());
            assertEquals(path, same, result.arg(2).tojstring());
        }
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

        // From Android 12 the set Android keeps, which cannot be changed, the same for each call
        Globals cached = load("system_fonts");
        result = Scripts.run(cached,
                "local bind = luajava.bindClass\n" +
                        "luajava.bindClass = function(name) if name == 'android.os.Build$VERSION' then return { SDK_INT = 34 } end\n" +
                        "  return bind(name) end\n" +
                        "local values = {}\n" +
                        "local function call(fonts) local p = paramof(fonts)\n" +
                        "  p.getValue = function(self, name, scope) return values[name] end\n" +
                        "  p.putValue = function(self, name, value, scope) values[name] = value end\n" +
                        "  after(hookof('getAvailableFonts'), p) return p.result end\n" +
                        "local function font(name) return { getFile = function(self)\n" +
                        "  return { getName = function(self) return name end } end } end\n" +
                        "local one = call(collection({ font('Roboto-Regular.ttf'), font('SamsungOne-400.ttf') }))\n" +
                        "local two = call(collection({ font('Roboto-Regular.ttf'), font('SamsungOne-400.ttf') }))\n" +
                        "return one == two, one:size(), pcall(function() one:clear() end)");
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
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
                "return restricted, p.result");
        assertTrue(result.arg1().toboolean());
        assertEquals("512m", result.arg(2).tojstring());
        // The other settings of the heap stay real, like the runtime which uses them (VMRuntime.getTargetHeapUtilization)
        String[][] others = {{"dalvik.vm.heapstartsize", "8m"}, {"dalvik.vm.heapminfree", "512k"},
                {"dalvik.vm.heapmaxfree", "8m"}, {"dalvik.vm.heaptargetutilization", "0.75"}};
        for (String[] c : others) {
            result = Scripts.run(properties, "local p = paramof('" + c[1] + "', { [0] = '" + c[0] + "' })\n" +
                    "return after(hookof('SystemProperties.get/resources'), p), p.result");
            assertFalse(c[0], result.arg1().toboolean());
            assertEquals(c[0], c[1], result.arg(2).tojstring());
        }
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
        // As long as the file, by spaces after the root element
        assertEquals(text + " ".repeat(xml.length() - text.length()), result.arg(3).tojstring());
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

    // Typeface looks up a family or alias the app reads without by a name no family has, which keeps the name of the app;
    // the configuration is read once, without StrictMode, and the hooks of opening files keep their filter
    @Test
    public void typefaceLooksUpFamiliesOfMakersLikeUnknownOnes() throws Exception {
        Globals globals = load("font_config");
        // A family of AOSP with a font of the maker in it keeps its name, like its aliases
        String fonts = "<familyset>\n" +
                "    <family name=\"sans-serif\">\n        <font weight=\"400\">Roboto-Regular.ttf</font>\n" +
                "        <font weight=\"500\">SamsungOne-500.ttf</font>\n    </family>\n" +
                "    <alias name=\"sans-serif-medium\" to=\"sans-serif\" weight=\"500\" />\n" +
                "    <family name=\"sec-roboto-light\">\n        <font weight=\"300\">SECRobotoLight-Regular.ttf</font>\n" +
                "    </family>\n" +
                "    <alias name=\"sec-roboto-light-bold\" to=\"sec-roboto-light\" weight=\"700\" />\n" +
                "    <alias name=\"sans-serif-light\" to=\"sans-serif\" weight=\"300\" />\n" +
                "    <family name=\"roboto\">\n        <font weight=\"400\">SamsungOne-400.ttf</font>\n    </family>\n" +
                "</familyset>\n";
        String customization = "<fonts-modification version=\"1\">\n" +
                "    <family customizationType=\"new-named-family\" name=\"google-sans\">\n" +
                "        <font weight=\"400\">GoogleSans-Regular.ttf</font>\n    </family>\n" +
                "</fonts-modification>\n";
        Varargs result = Scripts.run(globals,
                "local files = { ['/system/etc/fonts.xml'] = [[" + fonts + "]],\n" +
                        "  ['/product/etc/fonts_customization.xml'] = [[" + customization + "]] }\n" +
                        "local reads, policies = {}, {}\n" +
                        "luajava.bindClass = function(name) return { allowThreadDiskReads = function(self) return 'old' end,\n" +
                        "  setThreadPolicy = function(self, policy) policies[#policies + 1] = policy end } end\n" +
                        "local function lookup(method, name)\n" +
                        "  local p = paramof(nil, { [0] = name })\n" +
                        "  p.readFile = function(self, path) reads[#reads + 1] = path return files[path] end\n" +
                        "  local restricted = before(hookof(method), p)\n" +
                        "  return tostring(restricted) .. ':' .. tostring(p.args[0])\n" +
                        "end\n" +
                        "return lookup('create', 'sec-roboto-light'), lookup('create', 'sec-roboto-light-bold'),\n" +
                        "  lookup('create', 'google-sans'), lookup('setFallback', 'google-sans'),\n" +
                        "  lookup('setSystemFallback', 'sec-roboto-light'), lookup('hasFontFamily', 'google-sans'),\n" +
                        "  lookup('create', 'sans-serif-medium'), lookup('create', 'sans-serif-light'), lookup('create', nil),\n" +
                        "  lookup('open', 'google-sans'), table.concat(reads, ' '), table.concat(policies, ' '),\n" +
                        "  filter(hookof('create')), filter(hookof('open'))[1], after(hookof('create'), paramof('typeface')),\n" +
                        "  lookup('create', 'roboto')");
        assertEquals("true:sec-roboto-light\0", result.arg(1).tojstring());
        assertEquals("true:sec-roboto-light-bold\0", result.arg(2).tojstring());
        assertEquals("true:google-sans\0", result.arg(3).tojstring());
        assertEquals("true:google-sans\0", result.arg(4).tojstring());
        assertEquals("true:sec-roboto-light\0", result.arg(5).tojstring());
        assertEquals("true:google-sans\0", result.arg(6).tojstring());
        assertEquals("false:sans-serif-medium", result.arg(7).tojstring());
        assertEquals("false:sans-serif-light", result.arg(8).tojstring());
        assertEquals("false:nil", result.arg(9).tojstring());
        assertEquals("false:google-sans", result.arg(10).tojstring());
        assertEquals("/system/etc/fonts.xml /system/etc/font_fallback.xml /product/etc/fonts_customization.xml",
                result.arg(11).tojstring());
        assertEquals("old", result.arg(12).tojstring());
        assertTrue(result.arg(13).isnil());
        assertEquals("*etc/font*", result.arg(14).tojstring());
        assertFalse(result.arg(15).toboolean());
        // The roboto family of Android 16 and 17
        assertEquals("false:roboto", result.arg(16).tojstring());
    }

    // The GPS tags the app set on the object read back, also as the location; tags the media provider zeroed stay like
    // Android gives them; the XMP has its GPS properties blanked with the same length, as text and as bytes
    @Test
    public void photoTagsOfTheAppAndOfAndroidStay() throws Exception {
        Globals globals = load("photo_location");
        Varargs result = Scripts.run(globals,
                "local exif = { getAttribute = function(self, tag) return ({ GPSLatitudeRef = '', GPSAltitude = '0/0' })[tag] end }\n" +
                        "local values = {}\n" +
                        "local function on(param) param.getThis = function() return exif end\n" +
                        "  param.putValue = function(self, name, value, scope) values[name] = value end\n" +
                        "  param.getValue = function(self, name, scope) return values[name] end return param end\n" +
                        "after(hookof('setAttribute'), on(paramof(nil, { [0] = 'GPSLatitude', [1] = '52/1,31/1,0/1' })))\n" +
                        "after(hookof('setAttribute'), on(paramof(nil, { [0] = 'GPSLongitude', [1] = '13/1,24/1,0/1' })))\n" +
                        "local set = on(paramof('52/1,31/1,0/1', { [0] = 'GPSLatitude' }))\n" +
                        "local latlong = on(paramof(true, { [0] = 'array' }))\n" +
                        "after(hookof('getLatLong', 'boolean'), latlong)\n" +
                        "local ref = on(paramof('', { [0] = 'GPSLatitudeRef' }))\n" +
                        "local has = on(paramof(true, { [0] = 'GPSAltitude' }))\n" +
                        "local double = on(paramof(0, { [0] = 'GPSAltitude', [1] = -1 }))\n" +
                        "local real = on(paramof('120/1', { [0] = 'GPSImgDirection' }))\n" +
                        "after(hookof('setAttribute'), on(paramof(nil, { [0] = 'GPSLongitude' })))\n" +
                        "local removed = on(paramof(true, { [0] = 'array' }))\n" +
                        "after(hookof('getLatLong', 'boolean'), removed)\n" +
                        "return after(hookof('getAttribute'), set), set.result, latlong.result, after(hookof('getAttribute'), ref),\n" +
                        "  after(hookof('hasAttribute', 'boolean'), has), has.result, after(hookof('getAttributeDouble'), double),\n" +
                        "  after(hookof('getAttribute'), real), real.result, removed.result");
        assertEquals("false 52/1,31/1,0/1 true false false true false true nil false", text(result));

        String xmp = "<rdf:Description exif:GPSLatitude=\"52,30.12N\" tiff:Make=\"Pixel\">" +
                "<exif:GPSLongitude>13,24.5E</exif:GPSLongitude><dc:title>Caf\u00e9</dc:title></rdf:Description>";
        String blanked = "<rdf:Description exif:GPSLatitude=\"         \" tiff:Make=\"Pixel\">" +
                "<exif:GPSLongitude>        </exif:GPSLongitude><dc:title>Caf\u00e9</dc:title></rdf:Description>";
        LuaValue blank = globals.get("blankxmp").call(LuaValue.valueOf(xmp));
        assertEquals(blanked, blank.tojstring());
        assertEquals(xmp.length(), blank.tojstring().length());
        // The bytes of getAttributeBytes, of the same length
        LuaValue bytes = globals.get("xmp").call(LuaValue.NIL,
                org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(xmp.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertArrayEquals(blanked.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), (byte[]) bytes.touserdata(byte[].class));
    }

    @Test
    public void photosHaveNoLocation() throws Exception {
        Globals globals = load("photo_location");
        Varargs result = Scripts.run(globals,
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
                        "return 'unused', tag.result, after(hookof('getAttribute'), model), model.result, int.result,\n" +
                        "  latlong.result, doubles.result, altitude.result, time.result, video.result,\n" +
                        "  after(hookof('extractMetadata'), title)");
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

        // hasAttribute of a GPS tag is false, the others stay; a range of a GPS tag is null
        result = Scripts.run(globals,
                "local lat = paramof(true, { [0] = 'GPSLatitude' })\n" +
                        "local make = paramof(true, { [0] = 'Make' })\n" +
                        "local range = paramof({ 10, 20 }, { [0] = 'GPSLatitude' })\n" +
                        "return after(hookof('hasAttribute', 'boolean'), lat), lat.result,\n" +
                        "  after(hookof('hasAttribute', 'boolean'), make), make.result,\n" +
                        "  after(hookof('getAttributeRange', 'long[]'), range), range.result");
        assertTrue(result.arg(1).toboolean());
        // false itself: nil would not fit the boolean of hasAttribute
        assertTrue(result.arg(2).isboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());

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

    // On Android 8.1 and 9 the SQL of a query of the media provider has the location columns NULL, like on Android 10,
    // so the provider answers with its own cursor, also to aliases, expressions and duplicates, and its selection and
    // sort order agree; aliases, types and strings of that name stay, and a * of the table gets the columns in place.
    // A column stays for SQLite to resolve (NULLIF of itself), so one which is not there fails like without Doppel.
    @Test
    public void mediaQueriesHaveNullLocations() throws Exception {
        Globals globals = Scripts.load("photo_location", STUBS + "\n" + Scripts.ARRAYOF);
        String[][] columns = {{"latitude", "NULLIF(latitude, latitude) AS latitude"},
                {"LATITUDE", "NULLIF(LATITUDE, LATITUDE) AS latitude"},
                {"\"Latitude\"", "NULLIF(\"Latitude\", \"Latitude\") AS latitude"},
                {"[longitude]", "NULLIF([longitude], [longitude]) AS longitude"},
                {"`latitude`", "NULLIF(`latitude`, `latitude`) AS latitude"},
                {"images.latitude", "NULLIF(images.latitude, images.latitude) AS latitude"},
                {"main . \"images\".latitude", "NULLIF(main.\"images\".latitude, main.\"images\".latitude) AS latitude"},
                {"bogus.latitude", "NULLIF(bogus.latitude, bogus.latitude) AS latitude"},
                // SQLite takes a string next to a dot as a name
                {"images.'latitude'", "NULLIF(images.'latitude', images.'latitude') AS latitude"},
                {"'images'.latitude", "NULLIF('images'.latitude, 'images'.latitude) AS latitude"},
                {"main.images.'longitude'", "NULLIF(main.images.'longitude', main.images.'longitude') AS longitude"},
                {"'images'.*", "!'images'.*"}, {"'latitude'.x", "'latitude'.x"},
                // A column of a subquery keeps its name
                {"(SELECT f.'latitude' FROM files AS f LIMIT 1)", "(SELECT NULLIF(f.'latitude', f.'latitude') AS latitude " +
                        "FROM files AS f LIMIT 1) AS \"(SELECT f.'latitude' FROM files AS f LIMIT 1)\""},
                // A comment at the end stays, as it hides the rest of the SQL of the provider
                {"(latitude)", "NULLIF(latitude, latitude) AS latitude"},
                {"/* ' */ latitude -- '", "NULLIF(latitude, latitude) AS latitude -- '"},
                {"latitude AS la", "NULLIF(latitude, latitude) AS la"}, {"latitude la", "NULLIF(latitude, latitude) la"},
                {"latitude longitude", "NULLIF(latitude, latitude) longitude"},
                // SQLite names an expression without an alias by its text
                {"latitude+0", "NULLIF(latitude, latitude)+0 AS \"latitude+0\""},
                {" latitude  +  0 ", "NULLIF(latitude, latitude)  +  0 AS \"latitude  +  0\""},
                {"count(latitude)", "count(NULLIF(latitude, latitude)) AS \"count(latitude)\""},
                {"latitude COLLATE NOCASE", "NULLIF(latitude, latitude) COLLATE NOCASE AS \"latitude COLLATE NOCASE\""},
                {"CASE WHEN latitude IS NULL THEN 0 END",
                        "CASE WHEN NULLIF(latitude, latitude) IS NULL THEN 0 END AS \"CASE WHEN latitude IS NULL THEN 0 END\""},
                // ? takes digits only, like in SQLite, so ?THEN is ? and THEN
                {"CASE WHEN ?THEN latitude END AS x", "CASE WHEN ?THEN NULLIF(latitude, latitude) END AS x"},
                {"? latitude", "? latitude"}, {"?1 latitude", "?1 latitude"},
                // SQLite names a column with a FROM of its own up to there, by the first SELECT of a compound
                {"latitude FROM files UNION SELECT _id", "NULLIF(latitude, latitude) AS latitude FROM files UNION SELECT _id"},
                {"latitude+0 FROM files", "NULLIF(latitude, latitude)+0 AS \"latitude+0\" FROM files"},
                {"_id FROM files GROUP BY latitude, longitude --",
                        "_id FROM files GROUP BY NULLIF(latitude, latitude), NULLIF(longitude, longitude) --"},
                {"_id, latitude", "_id|NULLIF(latitude, latitude) AS latitude"}, {"_id latitude", "_id latitude"},
                {"_id AS latitude", "_id AS latitude"},
                {"CAST(_id AS latitude)", "CAST(_id AS latitude)"}, {"'latitude'", "'latitude'"}, {"latitudes", "latitudes"},
                {":latitude", ":latitude"}, {"\"lat\"\"itude\"", "\"lat\"\"itude\""}, {"_id * 2", "_id * 2"},
                {"count(*)", "count(*)"}, {"*", "!*"}, {"images.*", "!images.*"}, {"_id, *", "_id|!*"},
                // Without the columns of the table, a * of a subquery is NULL
                {"(SELECT * FROM files)", "(SELECT NULL FROM files) AS \"(SELECT * FROM files)\""}};
        String projection = "local sql, inner = ...\n" +
                "local items = {}\n" +
                "local text = rewrite(sql, items, inner)\n" +
                "local list = {}\n" +
                "for i, item in ipairs(items) do list[i] = (item.star and '!' or '') .. item.text end\n" +
                "return table.concat(list, '|'), text";
        for (String[] c : columns) {
            Varargs result = globals.load(projection, "test").invoke(LuaValue.valueOf(c[0]));
            assertEquals(c[0], c[1], result.arg1().tojstring());
            if (c[0].equals(c[1]))
                assertEquals(c[0], result.arg(2).tojstring());
        }
        assertEquals(" NULLIF(latitude, latitude)  +  0 AS \"latitude  +  0\" ",
                globals.get("rewrite").call(LuaValue.valueOf(" latitude  +  0 "), LuaValue.tableOf()).tojstring());

        // Selections and sort orders, also of subqueries, of which a * is NULL without the columns of its table;
        // arguments, strings and positions stay
        String[][] where = {{"latitude IS NOT NULL", "NULLIF(latitude, latitude) IS NOT NULL"},
                {"latitude BETWEEN ? AND ? AND _id = ?", "NULLIF(latitude, latitude) BETWEEN ? AND ? AND _id = ?"},
                {"_id > ?AND latitude > 50", "_id > ?AND NULLIF(latitude, latitude) > 50"},
                {"title = 'latitude' OR \"title\" LIKE ?", "title = 'latitude' OR \"title\" LIKE ?"},
                {"_id IN (SELECT _id FROM files WHERE files.LATITUDE > 50)",
                        "_id IN (SELECT _id FROM files WHERE NULLIF(files.LATITUDE, files.LATITUDE) > 50)"},
                {"files.latitude > 0", "NULLIF(files.latitude, files.latitude) > 0"},
                {"images.'latitude' > 50", "NULLIF(images.'latitude', images.'latitude') > 50"},
                {"_id IN (SELECT _id FROM images AS i WHERE i.'latitude' > 0)",
                        "_id IN (SELECT _id FROM images AS i WHERE NULLIF(i.'latitude', i.'latitude') > 0)"},
                {"_id IN (SELECT x._id FROM (SELECT _id, latitude FROM files) AS x WHERE x.latitude IS NULL)",
                        "_id IN (SELECT x._id FROM (SELECT _id, NULLIF(latitude, latitude) AS latitude FROM files) AS x " +
                                "WHERE NULLIF(x.latitude, x.latitude) IS NULL)"},
                {"max(_id, latitude) > 0", "max(_id, NULLIF(latitude, latitude)) > 0"},
                {"EXISTS (SELECT * FROM files WHERE _id = 1)", "EXISTS (SELECT NULL FROM files WHERE _id = 1)"},
                {"0) UNION SELECT * FROM files WHERE (1", "0) UNION SELECT NULL FROM files WHERE (1"},
                {"latitude DESC, _id", "NULLIF(latitude, latitude) DESC, _id"}, {"12", "12"},
                {"1) GROUP BY (bucket_id", "1) GROUP BY (bucket_id"}};
        for (String[] c : where)
            assertEquals(c[0], c[1], globals.get("rewrite").call(LuaValue.valueOf(c[0])).tojstring());

        // A table without location columns, like audio, refuses them like on Android 10, also *; the SELECTs of the
        // app, like subqueries and a UNION, still get NULL
        String[][] inner = {{"latitude", "latitude"}, {"count(latitude)", "count(latitude)"}, {"*", "*"}, {"audio.*", "audio.*"},
                {"(SELECT latitude FROM images LIMIT 1)",
                        "(SELECT NULLIF(latitude, latitude) AS latitude FROM images LIMIT 1) " +
                                "AS \"(SELECT latitude FROM images LIMIT 1)\""}};
        for (String[] c : inner)
            assertEquals(c[0], c[1], globals.load(projection, "test").invoke(LuaValue.valueOf(c[0]), LuaValue.TRUE).arg1().tojstring());
        String[][] innerWhere = {{"latitude > 0 AND _id IN (SELECT _id FROM files WHERE (latitude > 0)) AND latitude < 1",
                "latitude > 0 AND _id IN (SELECT _id FROM files WHERE (NULLIF(latitude, latitude) > 0)) AND latitude < 1"},
                {"0) UNION SELECT latitude FROM files WHERE (1",
                        "0) UNION SELECT NULLIF(latitude, latitude) AS latitude FROM files WHERE (1"},
                {"latitude DESC", "latitude DESC"}};
        for (String[] c : innerWhere)
            assertEquals(c[0], c[1], globals.get("rewrite").call(LuaValue.valueOf(c[0]), LuaValue.NIL, LuaValue.TRUE).tojstring());
        LuaValue rewrite = globals.get("rewrite");
        assertTrue(rewrite.invoke(LuaValue.valueOf("latitude FROM files --"), LuaValue.NIL, LuaValue.TRUE).arg(2).toboolean());
        assertFalse(rewrite.invoke(LuaValue.valueOf("(SELECT _id FROM files)"), LuaValue.NIL, LuaValue.TRUE).arg(2).toboolean());

        String stubs = "local probes = {}\n" +
                "local function bundleof(values)\n" +
                "  local b = { values = values }\n" +
                "  b.getString = function(self, k) return self.values[k] end\n" +
                "  b.putString = function(self, k, v) self.values[k] = v end\n" +
                "  b.getStringArray = function(self, k) return self.values[k] end\n" +
                "  b.putStringArray = function(self, k, v) self.values[k] = v end\n" +
                "  return b\n" +
                "end\n" +
                "luajava.newInstance = function(name, from)\n" +
                "  local values = {}\n" +
                "  for k, v in pairs(from ~= nil and from.values or {}) do values[k] = v end\n" +
                "  return bundleof(values)\n" +
                "end\n" +
                "local proxy = luajava.createProxy\n" +
                "luajava.createProxy = function(interface, methods)\n" +
                "  if interface == 'android.database.Cursor' then return methods end\n" +
                "  return proxy(interface, methods)\n" +
                "end\n" +
                "local function provider(...)\n" +
                "  local names = arrayof(...)\n" +
                "  return { query = function(self, uri, projection, args, signal)\n" +
                "    table.insert(probes, tostring(projection) .. ' ' .. args:getString('android:query-arg-sql-selection'))\n" +
                "    return { getColumnNames = function(self) return names end, close = function(self) end } end }\n" +
                "end\n" +
                "local function uri(authority, path)\n" +
                "  path = path or '/external/images/media'\n" +
                "  local segments = {}\n" +
                "  for segment in string.gmatch(path, '[^/]+') do table.insert(segments, segment) end\n" +
                "  return { getAuthority = function(self) return authority end, getPath = function(self) return path end,\n" +
                "    getPathSegments = function(self)\n" +
                "      return { size = function(self) return #segments end, get = function(self, i) return segments[i + 1] end } end }\n" +
                "end\n" +
                // The app op of storage and the grant of the uri, which the provider checks
                "local access = { op = 0, grant = -1 }\n" +
                "local context = { getPackageName = function(self) return 'com.example' end,\n" +
                "  getSystemService = function(self, name) assert(name == 'appops')\n" +
                "    return { checkOpNoThrow = function(self, op, uid, package)\n" +
                "      assert(op == 'android:read_external_storage' and package == 'com.example') return access.op end } end,\n" +
                "  checkUriPermission = function(self, uri, pid, uid, flags) assert(flags == 1) return access.grant end }\n" +
                "local function joined(array)\n" +
                "  if array == nil then return 'null' end\n" +
                "  local list = {}\n" +
                "  for i = 1, array['length'] do list[i] = tostring(array[i]) end\n" +
                "  return table.concat(list, '|')\n" +
                "end\n" +
                "local function cursor()\n" +
                "  return { getColumnIndex = function(self, n) return n == 'latitude' and 0 or -1 end,\n" +
                "    getDouble = function(self, i) return 52.5 end }\n" +
                "end\n" +
                "local function query(this, authority, projection, bundle, path)\n" +
                "  local p = paramof(cursor(), { [0] = uri(authority, path), [1] = projection, [2] = bundle })\n" +
                "  p.this = this\n" +
                "  p.getApplicationContext = function(self) return context end\n" +
                "  before(hookof('query'), p)\n" +
                "  return p\n" +
                "end\n" +
                "local photos = provider('_id', 'title', 'latitude', 'longitude')\n";

        // No projection: the columns of the table, which the provider gives for no rows, and the cursor stays
        Varargs result = Scripts.run(globals, stubs +
                "local all = query(photos, 'media', nil, nil)\n" +
                "local real = all.result\n" +
                "local empty = query(photos, '0@media', arrayof(), nil)\n" +
                "local mixed = query(photos, 'media', arrayof('*', 'latitude'), nil)\n" +
                "return joined(all.args[1]), all.args[2], after(hookof('query'), all), all.result == real,\n" +
                "  joined(empty.args[1]), joined(mixed.args[1]), table.concat(probes, ',')");
        assertEquals("_id|title|NULL AS latitude|NULL AS longitude", result.arg1().tojstring());
        assertTrue(result.arg(2).isnil());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals("_id|title|NULL AS latitude|NULL AS longitude", result.arg(5).tojstring());
        assertEquals("_id|title|NULL AS latitude|NULL AS longitude|NULLIF(latitude, latitude) AS latitude", result.arg(6).tojstring());
        assertEquals("nil 0,nil 0,nil 0", result.arg(7).tojstring());

        // The selection, sort order and sort columns in a copy of the Bundle, without asking the provider; other
        // queries, tables without a location and other hooks stay
        result = Scripts.run(globals, stubs +
                "local bundle = bundleof({ ['android:query-arg-sql-selection'] = 'latitude IS NOT NULL',\n" +
                "  ['android:query-arg-sql-selection-args'] = 'args', ['android:query-arg-sql-sort-order'] = 'latitude',\n" +
                "  ['android:query-arg-sort-columns'] = arrayof('longitude', '_id') })\n" +
                "local p = query(photos, 'media', arrayof('_id', 'latitude AS la'), bundle)\n" +
                "local copy = p.args[2]\n" +
                "local plain = arrayof('_id', 'title')\n" +
                "local kept = query(photos, 'media', plain, bundleof({ ['android:query-arg-sql-selection'] = '_id = ?' }))\n" +
                "local audio = query(provider('_id', 'title'), 'media', nil, nil)\n" +
                "local other = query(photos, 'com.android.contacts', nil, nil)\n" +
                "local exif = paramof(nil, {})\n" +
                "exif.getArgument = function() error('no arguments') end\n" +
                "return joined(p.args[1]), copy ~= bundle, copy.values['android:query-arg-sql-selection'],\n" +
                "  copy.values['android:query-arg-sql-sort-order'], joined(copy.values['android:query-arg-sort-columns']),\n" +
                "  copy.values['android:query-arg-sql-selection-args'], bundle.values['android:query-arg-sql-selection'],\n" +
                "  kept.args[1] == plain, kept.args[2]:getString('android:query-arg-sql-selection'),\n" +
                "  audio.args[1], other.args[1], before(hookof('getGpsDateTime'), exif), table.concat(probes, ',')");
        assertEquals("_id|NULLIF(latitude, latitude) AS la", result.arg1().tojstring());
        assertTrue(result.arg(2).toboolean());
        assertEquals("NULLIF(latitude, latitude) IS NOT NULL", result.arg(3).tojstring());
        assertEquals("NULLIF(latitude, latitude)", result.arg(4).tojstring());
        assertEquals("NULLIF(longitude, longitude)|_id", result.arg(5).tojstring());
        assertEquals("args", result.arg(6).tojstring());
        assertEquals("latitude IS NOT NULL", result.arg(7).tojstring());
        assertTrue(result.arg(8).toboolean());
        assertEquals("_id = ?", result.arg(9).tojstring());
        assertTrue(result.arg(10).isnil());
        assertTrue(result.arg(11).isnil());
        assertFalse(result.arg(12).toboolean());
        // Only the query without a projection of the table without a location asked the provider
        assertEquals("nil 0", result.arg(13).tojstring());

        // A provider which gives no columns leaves the cursor to the proxy of after
        result = Scripts.run(globals, stubs +
                "local failing = { query = function(self) error('provider died') end }\n" +
                "local p = query(failing, 'media', nil, nil)\n" +
                "return p.args[1], after(hookof('query'), p), p.result.getDouble(0)");
        assertTrue(result.arg1().isnil());
        assertTrue(result.arg(2).toboolean());
        assertEquals(0, result.arg(3).todouble(), 0);

        // Without the app op of storage the provider gives no rows on /external/, named like the projection: the
        // query stays, without a proxy; not on /internal/, and not with a grant of the uri
        result = Scripts.run(globals, stubs +
                "access.op = 1\n" +
                "local plain = arrayof('_id', 'latitude')\n" +
                "local bundle = bundleof({ ['android:query-arg-sql-selection'] = 'latitude IS NOT NULL' })\n" +
                "local p = query(photos, 'media', plain, bundle)\n" +
                "local all = query(photos, 'media', nil, nil)\n" +
                "local internal = query(photos, 'media', arrayof('latitude'), nil, '/internal/images/media')\n" +
                "access.grant = 0\n" +
                "local granted = query(photos, 'media', arrayof('latitude'), nil)\n" +
                "return p.args[1] == plain, p.args[2] == bundle, after(hookof('query'), p), p.set, all.args[1],\n" +
                "  joined(internal.args[1]), joined(granted.args[1]), table.concat(probes, ',')");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).isnil());
        assertEquals("NULLIF(latitude, latitude) AS latitude", result.arg(6).tojstring());
        assertEquals("NULLIF(latitude, latitude) AS latitude", result.arg(7).tojstring());
        assertEquals("", result.arg(8).tojstring());

        // Only images, video and files (also of MTP) have the location columns, as the UriMatcher of the provider finds
        // them; on others, like audio, only the SELECTs of the app get NULL, and no * is asked for
        result = Scripts.run(globals, stubs +
                "local audio = provider('_id', 'title')\n" +
                "local p = query(audio, 'media', arrayof('latitude', '(SELECT latitude FROM images LIMIT 1)', '*'),\n" +
                "  bundleof({ ['android:query-arg-sql-selection'] =\n" +
                "    'latitude > 0 OR _id IN (SELECT _id FROM files WHERE latitude > 0)',\n" +
                "    ['android:query-arg-sql-sort-order'] = 'latitude' }), '/external/audio/media')\n" +
                "local all = query(audio, 'media', nil, nil, '/external/audio/media/5')\n" +
                "local video = query(photos, 'media', arrayof('latitude'), nil, '/external/video/media/5')\n" +
                "local files = query(photos, 'media', arrayof('latitude'), nil, '//external/file/')\n" +
                "local references = query(photos, 'media', arrayof('latitude'), nil, '/external/object/5/references')\n" +
                // A FROM of its own selects from another table, like files
                "local from = query(audio, 'media', arrayof('latitude FROM files UNION SELECT _id'),\n" +
                "  bundleof({ ['android:query-arg-sql-selection'] = 'latitude > 0' }), '/external/audio/media')\n" +
                "return joined(p.args[1]), p.args[2].values['android:query-arg-sql-selection'],\n" +
                "  p.args[2].values['android:query-arg-sql-sort-order'], all.args[1], joined(video.args[1]),\n" +
                "  joined(files.args[1]), joined(references.args[1]), after(hookof('query'), p), table.concat(probes, ','),\n" +
                "  joined(from.args[1]), from.args[2].values['android:query-arg-sql-selection']");
        assertEquals("latitude|(SELECT NULLIF(latitude, latitude) AS latitude FROM images LIMIT 1) " +
                "AS \"(SELECT latitude FROM images LIMIT 1)\"|*", result.arg1().tojstring());
        assertEquals("latitude > 0 OR _id IN (SELECT _id FROM files WHERE NULLIF(latitude, latitude) > 0)",
                result.arg(2).tojstring());
        assertEquals("latitude", result.arg(3).tojstring());
        assertTrue(result.arg(4).isnil());
        assertEquals("NULLIF(latitude, latitude) AS latitude", result.arg(5).tojstring());
        assertEquals("NULLIF(latitude, latitude) AS latitude", result.arg(6).tojstring());
        assertEquals("latitude", result.arg(7).tojstring());
        assertFalse(result.arg(8).toboolean());
        assertEquals("", result.arg(9).tojstring());
        // Named by the first SELECT, like SQLite does; the selection on audio fails like without Doppel
        assertEquals("NULLIF(latitude, latitude) AS latitude FROM files UNION SELECT _id", result.arg(10).tojstring());
        assertEquals("NULLIF(latitude, latitude) > 0", result.arg(11).tojstring());

        // ORDER BY sorts a bare name by an alias of the projection, where WHERE and GROUP BY take the column
        result = Scripts.run(globals, stubs +
                "local p = query(photos, 'media', arrayof('_id AS latitude'),\n" +
                "  bundleof({ ['android:query-arg-sql-selection'] = '1) GROUP BY (latitude',\n" +
                "    ['android:query-arg-sql-sort-order'] = 'LATITUDE COLLATE NOCASE DESC, images.latitude, latitude+0',\n" +
                "    ['android:query-arg-sort-columns'] = arrayof('latitude', 'longitude') }))\n" +
                "local mine = query(photos, 'media', arrayof('_id', 'latitude AS la'),\n" +
                "  bundleof({ ['android:query-arg-sql-sort-order'] = 'latitude DESC' }))\n" +
                "return p.args[2].values['android:query-arg-sql-selection'], p.args[2].values['android:query-arg-sql-sort-order'],\n" +
                "  joined(p.args[2].values['android:query-arg-sort-columns']), mine.args[2].values['android:query-arg-sql-sort-order']");
        assertEquals("1) GROUP BY (NULLIF(latitude, latitude)", result.arg1().tojstring());
        assertEquals("LATITUDE COLLATE NOCASE DESC, NULLIF(images.latitude, images.latitude), NULLIF(latitude, latitude)+0",
                result.arg(2).tojstring());
        assertEquals("latitude|NULLIF(longitude, longitude)", result.arg(3).tojstring());
        assertEquals("NULLIF(latitude, latitude) DESC", result.arg(4).tojstring());

        // A * of a SELECT of the app over files, images or video gets the columns of that table on the volume of the
        // uri, asked once, with the location columns NULL; a * of another table, like audio, stays; one of a join is NULL
        result = Scripts.run(globals, stubs +
                "local bind = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.net.Uri' then return { parse = function(self, text) return text end } end\n" +
                "  return bind(name)\n" +
                "end\n" +
                "local asked = {}\n" +
                "local tables = { query = function(self, uri, projection, args, signal)\n" +
                "  table.insert(asked, uri)\n" +
                "  return { getColumnNames = function(self) return arrayof('_id', 'latitude', 'title') end,\n" +
                "    close = function(self) end } end }\n" +
                "local p = query(tables, '0@media', arrayof('_id'), bundleof({ ['android:query-arg-sql-selection'] =\n" +
                "  '_id IN (SELECT x._id FROM (SELECT * FROM files) AS x) OR EXISTS (SELECT * FROM main.files f WHERE _id = 1) ' ..\n" +
                "  'OR _id IN (SELECT * FROM files, audio) OR EXISTS (SELECT * FROM audio)' }))\n" +
                "local own = query(tables, 'media', arrayof('* FROM video --'), nil, '/internal/images/media')\n" +
                "return p.args[2].values['android:query-arg-sql-selection'], joined(own.args[1]), table.concat(asked, ',')");
        assertEquals("_id IN (SELECT x._id FROM (SELECT _id, NULL AS latitude, title FROM files) AS x) OR " +
                "EXISTS (SELECT _id, NULL AS latitude, title FROM main.files f WHERE _id = 1) OR " +
                "_id IN (SELECT NULL FROM files, audio) OR EXISTS (SELECT * FROM audio)", result.arg1().tojstring());
        assertEquals("_id, NULL AS latitude, title FROM video --", result.arg(2).tojstring());
        assertEquals("content://0@media/external/file,content://media/internal/video/media", result.arg(3).tojstring());

        // An error of SQLite shows the SQL it compiled: the app gets the error of its own arguments, with the stack trace
        // of its call; when its own SQL works the error stays, as its cursor gives the location, and so do other
        // exceptions and queries
        result = Scripts.run(globals, stubs +
                "local seen = {}\n" +
                "local opened = cursor()\n" +
                "opened.close = function(self) self.closed = true end\n" +
                "local function answering(fails)\n" +
                "  return { query = function(self, uri, projection, args, signal)\n" +
                "    table.insert(seen, joined(projection) .. ' ' .. args:getString('android:query-arg-sql-selection'))\n" +
                "    if fails then return luajava.bindClass('java.lang.Integer'):parseInt('no such column: x') end\n" +
                "    return opened\n" +
                "  end }\n" +
                "end\n" +
                "local function refused(this, projection, class)\n" +
                "  local p = query(this, 'media', projection, bundleof({ ['android:query-arg-sql-selection'] = 'x = 1' }))\n" +
                "  local ex = luajava.new(luajava.bindClass('java.lang.IllegalStateException'), 'compiling: SELECT NULL AS latitude')\n" +
                "  p.result = nil\n" +
                "  p.getException = function(self) return ex end\n" +
                "  p.isInstance = function(self, object, name)\n" +
                "    return name == class and not string.find(tostring(object), 'LuaError', 1, true) end\n" +
                "  return p, ex\n" +
                "end\n" +
                "local sqlite = 'android.database.sqlite.SQLiteException'\n" +
                "local p, ex = refused(answering(true), arrayof('latitude'), sqlite)\n" +
                "local faked = after(hookof('query'), p)\n" +
                "local works = refused(answering(false), arrayof('latitude'), sqlite)\n" +
                "local proxied = after(hookof('query'), works)\n" +
                "local other = refused(answering(true), arrayof('latitude'), 'java.lang.SecurityException')\n" +
                "local kept = refused(answering(true), arrayof('_id'), sqlite)\n" +
                "local broken = refused({ query = function(self) error('a bug') end }, arrayof('latitude'), sqlite)\n" +
                "return faked, p.result:toString(), p.result:getStackTrace()['length'], p.result:getStackTrace()[1]:toString(),\n" +
                "  ex:getStackTrace()[1]:toString(), proxied, works.set, opened.closed, after(hookof('query'), other), other.set,\n" +
                "  after(hookof('query'), kept), kept.set, after(hookof('query'), broken), broken.set, table.concat(seen, ',')");
        assertFalse(result.arg1().toboolean());
        assertEquals("java.lang.NumberFormatException: For input string: \"no such column: x\"", result.arg(2).tojstring());
        assertTrue(result.arg(3).toint() > 0);
        assertEquals(result.arg(5).tojstring(), result.arg(4).tojstring());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
        assertFalse(result.arg(10).toboolean());
        assertFalse(result.arg(11).toboolean());
        assertFalse(result.arg(12).toboolean());
        assertFalse(result.arg(13).toboolean());
        assertFalse(result.arg(14).toboolean());
        // Asked with the projection and the Bundle of the app, only for the errors of SQLite after a change
        assertEquals("latitude x = 1,latitude x = 1", result.arg(15).tojstring());
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

        // The hidden lists of the profiles of the own user: the own user only, made at 0 like getUserCreationTime
        result = Scripts.run(globals,
                "local function user(id) return { id = id, equals = function(self, other) return self.id == other.id end,\n" +
                        "  getIdentifier = function(self) return self.id end } end\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name == 'java.lang.reflect.Array' then return { newInstance = function(self, type, n) return { length = n } end } end\n" +
                        "  if name == 'java.lang.Integer' then return { TYPE = 'int' } end\n" +
                        "  return { myUserHandle = function(self) return user(0) end } end\n" +
                        "luajava.newInstance = function(name) local list = { items = {} }\n" +
                        "  list.add = function(self, v) self.items[#self.items + 1] = v end\n" +
                        "  list.size = function(self) return #self.items end return list end\n" +
                        "local function info(id) return { id = id, creationTime = 1700000000000, lastLoggedInTime = 1700000500000 } end\n" +
                        "local function users(method) local hook = hookof(method)\n" +
                        "  hook.getClassName = function(self) return 'android.os.UserManager' end return hook end\n" +
                        "local profiles = paramof(collection({ info(0), info(10) }))\n" +
                        "after(users('getProfiles'), profiles)\n" +
                        "local ids = paramof({ length = 2, 0, 10 })\n" +
                        "after(users('getProfileIdsWithDisabled'), ids)\n" +
                        "local first = profiles.result.items[1]\n" +
                        "return profiles.result:size(), first.id, first.creationTime, first.lastLoggedInTime, ids.result.length, ids.result[1]");
        assertEquals(1, result.arg1().toint());
        assertEquals(0, result.arg(2).toint());
        assertEquals(0, result.arg(3).tolong());
        assertEquals(0, result.arg(4).tolong());
        assertEquals(1, result.arg(5).toint());
        assertEquals(0, result.arg(6).toint());

        // An app in a profile, like a work profile, also sees the parent of the profile, which it can switch to
        result = Scripts.run(globals,
                "local function user(id) return { id = id, equals = function(self, other) return self.id == other.id end,\n" +
                        "  getIdentifier = function(self) return self.id end } end\n" +
                        "luajava.bindClass = function(name) return { myUserHandle = function(self) return user(10) end } end\n" +
                        "local function inprofile(param) param.getApplicationContext = function(self) return {\n" +
                        "  getSystemService = function(self, name) return { isProfile = function(self) return true end,\n" +
                        "    getUserProfiles = function(self) return collection({ user(10), user(0), user(11) }) end } end } end\n" +
                        "  param.unhooked = function(self, f) return f() end return param end\n" +
                        "local profiles = inprofile(paramof(collection({ user(0), user(10), user(11) })))\n" +
                        "after(hookof('getUserProfiles'), profiles)\n" +
                        "local targets = inprofile(paramof(collection({ user(0) })))\n" +
                        "local count = inprofile(paramof(3))\n" +
                        "after(hookof('getUserCount'), count)\n" +
                        "local serial = inprofile(paramof(0, { [0] = user(0) }))\n" +
                        "return profiles.result:size(), after(hookof('getTargetUserProfiles'), targets), count.result,\n" +
                        "  after(hookof('getSerialNumberForUser'), serial)");
        assertEquals(2, result.arg1().toint());
        assertFalse(result.arg(2).toboolean());
        assertEquals(2, result.arg(3).toint());
        assertFalse(result.arg(4).toboolean());
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

    // A system alias with ../ which leaves the folder of the system certificates is the user's one, so it is hidden too
    @Test
    public void aliasesLeavingTheSystemFolderAreHidden() throws Exception {
        Varargs result = Scripts.run(load("device_details"),
                "local up = paramof(true, { [0] = 'system:../../../data/misc/user/0/cacerts-added/abc.0' })\n" +
                        "local upCertificate = paramof('certificate', { [0] = 'system:../../../data/misc/user/0/cacerts-added/abc.0' })\n" +
                        "local down = paramof(true, { [0] = 'system:abc/../../../../data/misc/user/0/cacerts-added/abc.0' })\n" +
                        "local downCertificate = paramof('certificate', { [0] = 'system:abc/../../../../data/misc/user/0/cacerts-added/abc.0' })\n" +
                        "local plain = paramof(true, { [0] = 'system:abc.0' })\n" +
                        "return after(hookof('containsAlias'), up), up.result, after(hookof('getCertificate'), upCertificate),\n" +
                        "  upCertificate.result, after(hookof('containsAlias'), down), down.result,\n" +
                        "  after(hookof('getCertificate'), downCertificate), downCertificate.result,\n" +
                        "  after(hookof('containsAlias'), plain), plain.result");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
        assertTrue(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).isnil());
        assertFalse(result.arg(9).toboolean());
        assertTrue(result.arg(10).toboolean());
    }

    // The alias of a certificate the user added is the one a store without it gives: of the same certificate among the
    // system ones, whose index can differ, unless the user disabled that one; and the user added none
    @Test
    public void certificatesOfTheUserHaveNoAlias() throws Exception {
        Varargs result = Scripts.run(load("device_details"),
                "local function certificate() return { equals = function(self, other) return self == other end } end\n" +
                        "local mine, other, ca = certificate(), certificate(), certificate()\n" +
                        "local disabled = {}\n" +
                        "local store = { system = { ['system:abc.0'] = other, ['system:abc.1'] = mine },\n" +
                        "  getCertificate = function(self, alias, deleted)\n" +
                        "    local found = self.system[alias]\n" +
                        "    return (deleted or not disabled[alias]) and found or nil end }\n" +
                        "local function alias(result, certificate, ...)\n" +
                        "  local count = select('#', ...)\n" +
                        "  local hook = hookof('getCertificateAlias')\n" +
                        "  hook.getParameterTypes = function(self) return { length = 1 + count } end\n" +
                        "  local param = paramof(result, { [0] = certificate, ... })\n" +
                        "  param.this = store\n" +
                        "  local restricted = after(hook, param)\n" +
                        "  return restricted, param.result, param.set\n" +
                        "end\n" +
                        "local a, b = alias('user:abc.0', mine)\n" +
                        "local c, d = alias('user:def.0', ca, false)\n" +
                        "local e, f, g = alias('system:abc.1', mine)\n" +
                        "local _, h, i = alias(nil, ca)\n" +
                        "disabled['system:abc.1'] = true\n" +
                        "local _, j = alias('user:abc.0', mine)\n" +
                        "local _, k = alias('user:abc.0', mine, true)\n" +
                        "local added = paramof(true, { [0] = ca })\n" +
                        "local l = after(hookof('isUserAddedCertificate'), added)\n" +
                        "local m = after(hookof('isUserAddedCertificate'), paramof(false, { [0] = ca }))\n" +
                        "store = {}\n" +
                        "local n, o = alias('user:abc.0', mine, true)\n" +
                        "return a, b, c, d, e, f, g, h, i, j, k, l, added.result, m, filter(hookof('getCertificateAlias')), n, o");
        assertTrue(result.arg(1).toboolean());
        assertEquals("system:abc.1", result.arg(2).tojstring());
        // Only the user has it: none, like Android for a certificate the store does not have
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
        // The aliases of the system and none stay
        assertFalse(result.arg(5).toboolean());
        assertEquals("system:abc.1", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).isnil());
        assertFalse(result.arg(9).toboolean());
        // A system certificate the user disabled has none, unless the caller asks for those, like Android
        assertTrue(result.arg(10).isnil());
        assertEquals("system:abc.1", result.arg(11).tojstring());
        assertTrue(result.arg(12).toboolean());
        assertFalse(result.arg(13).toboolean());
        assertFalse(result.arg(14).toboolean());
        // The certificate is no text, so the filter would never run the script
        assertTrue(result.arg(15).isnil());
        // A store whose methods the hidden API rules hide from the app: none, not the alias of the user
        assertTrue(result.arg(16).toboolean());
        assertTrue(result.arg(17).isnil());
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
                        "  p.setResult = function(self, value) self.set = true thrown = value end\n" +
                        "  p.getValue = function(self, name, scope) return values[name] and values[name][scope] end\n" +
                        "  p.putValue = function(self, name, value, scope)\n" +
                        "    values[name] = values[name] or {}\n" +
                        "    values[name][scope] = value\n" +
                        "  end\n" +
                        "  local restricted = before(hook(method, count), p)\n" +
                        "  return restricted, p.set\n" +
                        "end\n" +
                        "function drain()\n" +
                        "  for _, runnable in ipairs(posted) do runnable.run() end\n" +
                        "  posted = {}\n" +
                        "end\n" +
                        "luajava.createProxy = function(name, object) assert(name == 'java.lang.Runnable') return object end\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end\n" +
                        "  assert(name == 'android.os.Looper')\n" +
                        "  return { getMainLooper = function(self) return 'main' end } end\n" +
                        "sdk = 34\n" +
                        "luajava.newInstance = function(name, looper)\n" +
                        "  if name == 'java.util.HashMap' then return 'empty' end\n" +
                        "  if name == 'java.lang.IllegalArgumentException' then return 'IllegalArgumentException: ' .. looper end\n" +
                        "  assert(name == 'android.os.Handler' and looper == 'main')\n" +
                        "  return { post = function(self, runnable) posted[#posted + 1] = runnable end }\n" +
                        "end\n" +
                        "executor = { execute = function(self, runnable) posted[#posted + 1] = runnable end }\n" +
                        "listener = { toString = function(self) return 'L' end,\n" +
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

        // Headroom like a device whose sensors have no samples yet: no call back, since Android does not call back
        // with NaN
        result = Scripts.run(globals,
                "answers = {}\n" +
                        "local added = call('addThermalHeadroomListener', executor, listener)\n" +
                        "local count = #posted\n" +
                        "local _, skipped = call('removeThermalHeadroomListener', listener)\n" +
                        "return added, count, #answers, skipped");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertEquals(0, result.arg(3).toint());
        assertTrue(result.arg(4).toboolean());

        // A listener added twice: Android refuses it with the message of its version
        result = Scripts.run(globals,
                "call('addThermalStatusListener', listener)\n" +
                        "local again, set = call('addThermalStatusListener', listener)\n" +
                        "local old = thrown\n" +
                        "call('removeThermalStatusListener', listener)\n" +
                        "sdk = 36\n" +
                        "call('addThermalHeadroomListener', executor, listener)\n" +
                        "call('addThermalHeadroomListener', executor, listener)\n" +
                        "local new = thrown\n" +
                        "call('removeThermalHeadroomListener', listener)\n" +
                        "sdk = 34 posted = {}\n" +
                        "return again, set, old, new");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals("IllegalArgumentException: Listener already registered: L", result.arg(3).tojstring());
        assertEquals("IllegalArgumentException: Thermal headroom listener already registered: L", result.arg(4).tojstring());

        // One object listening to both is removed from both: Android would throw for the second one too
        result = Scripts.run(globals,
                "call('addThermalStatusListener', listener)\n" +
                        "call('addThermalHeadroomListener', listener)\n" +
                        "local _, status = call('removeThermalStatusListener', listener)\n" +
                        "local _, headroom = call('removeThermalHeadroomListener', listener)\n" +
                        "local _, again = call('removeThermalHeadroomListener', listener)\n" +
                        "posted = {}\n" +
                        "return status, headroom, again");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());

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

        // A device without a vibrator keeps its details, which answer no; of no effects or primitives Android answers
        // yes, and an effect which is not supported (no) stays
        result = Scripts.run(load("device_details"),
                "local none = paramof({})\n" +
                        "none.isInstance = function(self, object, name) return name == 'android.os.SystemVibrator$NoVibratorInfo' end\n" +
                        "return after(hookof('getInfo'), none),\n" +
                        "  after(hookof('areAllEffectsSupported'), paramof(1, { [0] = { length = 0 } })),\n" +
                        "  after(hookof('areAllEffectsSupported'), paramof(2, { [0] = { length = 1, 0 } })),\n" +
                        "  after(hookof('areAllPrimitivesSupported'), paramof(true, { [0] = { length = 0 } })),\n" +
                        "  after(hookof('areAllEffectsSupported'), paramof(1, { [0] = { length = 1, 0 } }))");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
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
