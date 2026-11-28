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
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The script which leaves the results of Play services out of the extras of intents (assets/intent_results.lua), with
// Lua stand-ins of intents and their extras as Android keeps them, like LocationScriptTest: a value stays in the parcel
// until a look up of a name, which reads all values before Android 13 and none from Android 13; a parcel has the names
// of the extras as UTF-16 and cannot be marshalled with a binder; the parcel of extras is in a field which Class lists
// (BaseBundle.mParcelledData); the classes of the app are in CLASSES, with the names of their methods
public class IntentResultsScriptTest {
    private static final String STUBS = String.join("\n",
            "real = luajava sdk = 34 reads = 0 MARKS = {} CLASSES = {}",
            "UTF16 = real.bindClass('java.nio.charset.StandardCharsets').UTF_16LE",
            "FIELD = { getName = function(self) return 'mParcelledData' end, setAccessible = function(self, on) end,",
            "  get = function(self, bundle) return bundle.inparcel and 'parcel' or nil end }",
            "luajava = { newInstance = real.newInstance, bindClass = function(name)",
            "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "  if name == 'android.os.Parcel' then return { obtain = function() return parcelof() end } end",
            "  if name == 'android.os.BaseBundle' then return 'BaseBundle' end",
            "  if name == 'java.lang.Class' then return { getDeclaredFields = function(class) assert(class == 'BaseBundle')",
            "    return { FIELD, length = 1 } end,",
            "    getDeclaredMethods = function(class) return class.methods end } end",
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
            "  function bundle:containsKey(key)",
            "    reads = reads + 1 self.inparcel = false",
            "    for name, value in pairs(self.values) do",
            "      if value.parcelled and sdk < 33 then",
            "        if value.broken then error('BadParcelableException') end",
            "        self.values[name] = { class = value.class, size = value.size, data = value.data }",
            "      end",
            "    end",
            "    return self.values[key] ~= nil",
            "  end",
            "  function bundle:remove(key) assert(not self.inparcel, 'parcelled') self.values[key] = nil end",
            "  function bundle:putSerializable(key, value) assert(not self.inparcel, 'parcelled')",
            "    self.values[key] = { class = real.bindClass('java.lang.Class').getName(value:getClass()), size = value:size(),",
            "      data = value }",
            "  end",
            "  function bundle:getSerializable(key) assert(not self.inparcel, 'parcelled') return self.values[key].data end",
            "  function bundle:getByteArray(key) assert(not self.inparcel, 'parcelled')",
            "    local value = self.values[key] return value.class == '[B' and value.data or nil end",
            "  function bundle:putByteArray(key, value) assert(not self.inparcel, 'parcelled')",
            "    self.values[key] = { class = '[B', data = value } end",
            "  function bundle:writeToParcel(parcel, flags)",
            "    local text = {} parcel.values = {} parcel.binder = self.binder",
            "    for key, value in pairs(self.values) do",
            "      text[#text + 1] = key .. '=' .. value.class",
            "      parcel.values[key] = { class = value.class, parcelled = true, broken = value.broken, size = value.size,",
            "        data = value.data }",
            "    end",
            "    parcel.text = table.concat(text, ';')",
            "  end",
            "  return bundle",
            "end",
            // An intent read from a parcel, or one the app made itself
            "function intentof(values, binder, made)",
            "  local intent = { extras = bundleof(values, binder, 'boot', not made), replaced = 0 }",
            "  function intent:getAction() return self.action end",
            "  function intent:getExtras() local e = self.extras return bundleof(e.values, e.binder, e.loader, e.inparcel) end",
            "  function intent:setExtrasClassLoader(loader) self.extras.loader = loader end",
            "  function intent:replaceExtras(bundle) self.replaced = self.replaced + 1",
            "    self.extras = bundleof(bundle.values, bundle.binder, bundle.loader, bundle.inparcel) end",
            "  return intent",
            "end",
            "function parcelable(class) return { class = class, parcelled = true } end",
            "INTERNAL = 'com.google.android.location.internal.'",
            "EXTRA = 'com.google.android.location.intent.extra.'",
            "function activity() return { [INTERNAL .. 'EXTRA_ACTIVITY_RESULT'] = parcelable('[B'),",
            "  [INTERNAL .. 'EXTRA_ACTIVITY_RESULT_LIST'] = parcelable('java.util.ArrayList'),",
            "  [INTERNAL .. 'EXTRA_ACTIVITY_TRANSITION_RESULT'] = parcelable('[B'),",
            "  [INTERNAL .. 'EXTRA_SLEEP_SEGMENT_RESULT'] = parcelable('java.util.ArrayList'),",
            "  [INTERNAL .. 'EXTRA_SLEEP_CLASSIFY_RESULT'] = parcelable('java.util.ArrayList'),",
            "  place = parcelable('com.example.Place') } end",
            "function geofence() return { [EXTRA .. 'transition'] = parcelable('java.lang.Integer'),",
            "  [EXTRA .. 'geofence_list'] = parcelable('java.util.ArrayList'),",
            "  [EXTRA .. 'triggering_location'] = parcelable('android.location.Location') } end",
            "function hookof(method, variant)",
            "  return { getName = function(self) return 'Intent.' .. method .. '/' .. variant end,",
            "    getMethodName = function(self) return method == 'createFromParcel' and 'CREATOR:' .. method or method end }",
            "end",
            "function classof(...)",
            "  local methods = { length = select('#', ...) }",
            "  for index, name in ipairs({ ... }) do methods[index] = { getName = function(self) return name end } end",
            "  return { methods = methods }",
            "end",
            "CONTEXT = { getClassLoader = function(self) return 'app' end }",
            "function paramof(intent, loader)",
            "  return { getResult = function(self) return intent end, getThis = function(self) return intent end,",
            "    getArgument = function(self, index) assert(index == 0) return loader end,",
            "    getApplicationContext = function(self) return CONTEXT end,",
            "    findClass = function(self, name) return CLASSES[name] or error('ClassNotFoundException: ' .. name) end,",
            "    getValue = function(self, name, scope) return MARKS[name .. ' ' .. tostring(scope)] end,",
            "    putValue = function(self, name, value, scope) MARKS[name .. ' ' .. tostring(scope)] = value end }",
            "end",
            // The answer, the number of replaced extras, their class loader and the names of the extras without the
            // package of Play services, with * when read and the size of a list the script put
            "function describe(restricted, intent)",
            "  local keys, out = {}, { tostring(restricted), intent.replaced, tostring(intent.extras.loader) }",
            "  for key in pairs(intent.extras.values) do keys[#keys + 1] = key end",
            "  table.sort(keys)",
            "  for _, key in ipairs(keys) do",
            "    local short = string.gsub(key, '^com%.google%.android%.location%.', '')",
            "    local value = intent.extras.values[key]",
            "    out[#out + 1] = short .. (value.size and '[' .. value.size .. ']' or '') .. (value.parcelled and '' or '*')",
            "  end",
            "  return table.concat(out, ' ')",
            "end",
            "function check(variant, version, values, binder)",
            "  sdk = version",
            "  local intent = intentof(values, binder)",
            "  return describe(after(hookof('createFromParcel', variant), paramof(intent)), intent)",
            "end",
            "function deliver(variant, version, intent, loader)",
            "  sdk = version",
            "  intent:setExtrasClassLoader(loader)",
            "  return describe(after(hookof('setExtrasClassLoader', variant), paramof(intent, loader)), intent)",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("intent_results", STUBS);
    }

    // The results are left out by their names, in an app without the readers of the library (like one R8 shrank), the
    // transitions are a fake result without any (see resultsAreFakeOnes; the stand-ins of the activity are not the
    // bytes of a result, so they are left out), and the fences of a geofence are an empty list; the other extras
    // parcelled again, like the extras of Android, with the class loader they had, before and from Android 13; a variant
    // leaves out its own names only, also not the request of transitions which an app sends or the error of geofences;
    // extras without the names are neither replaced nor read; with a binder in the parcel the names are looked up from
    // Android 13 only, and extras with a value the app cannot read stay as they are
    @Test
    public void resultsOfPlayServicesAreLeftOut() throws Exception {
        Varargs result = Scripts.run(globals,
                "local out = {}\n" +
                        "local function add(value) out[#out + 1] = value end\n" +
                        "add(check('activity', 34, activity()))\n" +
                        "add(check('activity', 30, activity()))\n" +
                        "add(check('geofence', 34, geofence()))\n" +
                        "add(check('geofence', 27, geofence()))\n" +
                        "add(check('geofence', 34, activity()))\n" +
                        "add(check('activity', 34, geofence()))\n" +
                        "add(check('activity', 34, { [INTERNAL .. 'EXTRA_ACTIVITY_TRANSITION_REQUEST'] = parcelable('[B') }))\n" +
                        "reads = 0\n" +
                        "add(check('geofence', 34, { gms_error_code = parcelable('java.lang.Integer') }))\n" +
                        "add(check('activity', 30, { place = parcelable('com.example.Place') }))\n" +
                        "add(tostring(reads))\n" +
                        "add(check('activity', 34, activity(), true))\n" +
                        "add(check('activity', 30, activity(), true))\n" +
                        "local broken = activity() broken.place.broken = true\n" +
                        "add(check('activity', 30, broken))\n" +
                        "add(tostring(after(hookof('createFromParcel', 'activity'), paramof(nil))))\n" +
                        "add(tostring(after(hookof('createFromParcel', 'geofence'), " +
                        "paramof({ getExtras = function(self) return nil end }))))\n" +
                        "return table.concat(out, '\\n')");
        String all = "internal.EXTRA_ACTIVITY_RESULT internal.EXTRA_ACTIVITY_RESULT_LIST internal.EXTRA_ACTIVITY_TRANSITION_RESULT " +
                "internal.EXTRA_SLEEP_CLASSIFY_RESULT internal.EXTRA_SLEEP_SEGMENT_RESULT place";
        assertEquals(String.join("\n",
                "true 1 boot internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "true 1 boot internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "true 1 boot intent.extra.geofence_list[0]",
                "true 1 boot intent.extra.geofence_list[0]",
                "false 0 boot " + all,
                "false 0 boot intent.extra.geofence_list intent.extra.transition intent.extra.triggering_location",
                "false 0 boot internal.EXTRA_ACTIVITY_TRANSITION_REQUEST",
                "false 0 boot gms_error_code",
                "false 0 boot place",
                "0",
                "true 1 boot internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "false 0 boot " + all,
                "false 0 boot " + all,
                "false",
                "false"), result.arg(1).tojstring());
    }

    // A result which starts the app is read before the hooks are there, so it is left out when Android gives the intent
    // to the component, parcelled with its class loader; once for each intent and variant, so the mark of one variant
    // does not stop another; an intent the first hook did already is left as it is, also its empty list of fences, and
    // extras the app put or read stay as they are
    @Test
    public void resultsOfPlayServicesAreLeftOutWhenGiven() throws Exception {
        Varargs result = Scripts.run(globals,
                "local out = {}\n" +
                        "local function add(value) out[#out + 1] = value end\n" +
                        "local cold = intentof(activity())\n" +
                        "add(deliver('activity', 34, cold, 'split'))\n" +
                        "cold.extras = intentof(activity()).extras cold.extras.loader = 'split'\n" +
                        "add(deliver('activity', 34, cold, 'split'))\n" +
                        "add(deliver('activity', 30, intentof(activity()), nil))\n" +
                        "local warm = intentof(geofence())\n" +
                        "after(hookof('createFromParcel', 'geofence'), paramof(warm))\n" +
                        "add(deliver('geofence', 34, warm, 'split'))\n" +
                        "local both = intentof(activity())\n" +
                        "add(deliver('geofence', 34, both, 'split'))\n" +
                        "add(deliver('activity', 34, both, 'split'))\n" +
                        "local made = intentof({ [INTERNAL .. 'EXTRA_ACTIVITY_RESULT'] = { class = '[B' } }, false, true)\n" +
                        "add(deliver('activity', 34, made, 'split'))\n" +
                        "return table.concat(out, '\\n')");
        assertEquals(String.join("\n",
                "true 1 split internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "false 1 split internal.EXTRA_ACTIVITY_RESULT internal.EXTRA_ACTIVITY_RESULT_LIST " +
                        "internal.EXTRA_ACTIVITY_TRANSITION_RESULT internal.EXTRA_SLEEP_CLASSIFY_RESULT " +
                        "internal.EXTRA_SLEEP_SEGMENT_RESULT place",
                "true 1 nil internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "false 1 split intent.extra.geofence_list[0]",
                "false 0 split internal.EXTRA_ACTIVITY_RESULT internal.EXTRA_ACTIVITY_RESULT_LIST " +
                        "internal.EXTRA_ACTIVITY_TRANSITION_RESULT internal.EXTRA_SLEEP_CLASSIFY_RESULT " +
                        "internal.EXTRA_SLEEP_SEGMENT_RESULT place",
                "true 1 split internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "false 0 split internal.EXTRA_ACTIVITY_RESULT*"), result.arg(1).tojstring());
    }

    // An app with the readers of the activity and its transitions under their names, with their extractResult, keeps
    // a result which cannot be faked, like these stand-ins, for the hooks of extractResult, which answer with a fake
    // result; not one where R8 kept the name of ActivityRecognitionResult but not its extractResult, nor the sleep
    // events, which no hook answers like that; the transitions get the fake result either way; an intent with a result
    // is marked per variant also when nothing is left out, one without, or with only the error of geofences, is not
    @Test
    public void resultsOfReadersWithHooksStay() throws Exception {
        Varargs result = Scripts.run(globals,
                "local out = {}\n" +
                        "local function add(value) out[#out + 1] = value end\n" +
                        "local function marked(variant, values)\n" +
                        "  local intent = intentof(values)\n" +
                        "  after(hookof('createFromParcel', variant), paramof(intent))\n" +
                        "  return tostring(MARKS[variant .. '.result ' .. tostring(intent)])\n" +
                        "end\n" +
                        "CLASSES['com.google.android.gms.location.ActivityRecognitionResult'] = " +
                        "classof('hasResult', 'extractResult')\n" +
                        "CLASSES['com.google.android.gms.location.ActivityTransitionResult'] = classof('extractResult')\n" +
                        "add(check('activity', 34, activity()))\n" +
                        "add(check('activity', 34, { [INTERNAL .. 'EXTRA_ACTIVITY_RESULT'] = parcelable('[B') }))\n" +
                        "add(marked('activity', { [INTERNAL .. 'EXTRA_ACTIVITY_RESULT'] = parcelable('[B') }))\n" +
                        "CLASSES['com.google.android.gms.location.ActivityRecognitionResult'] = classof('a', 'writeToParcel')\n" +
                        "add(check('activity', 30, activity()))\n" +
                        "add(marked('activity', activity()))\n" +
                        "add(marked('activity', { place = parcelable('com.example.Place') }))\n" +
                        "add(marked('geofence', geofence()))\n" +
                        "add(marked('geofence', { gms_error_code = parcelable('java.lang.Integer') }))\n" +
                        "add(marked('geofence', activity()))\n" +
                        "return table.concat(out, '\\n')");
        assertEquals(String.join("\n",
                "true 1 boot internal.EXTRA_ACTIVITY_RESULT internal.EXTRA_ACTIVITY_RESULT_LIST " +
                        "internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "false 0 boot internal.EXTRA_ACTIVITY_RESULT",
                "true",
                "true 1 boot internal.EXTRA_ACTIVITY_TRANSITION_RESULT place",
                "true",
                "nil",
                "true",
                "nil",
                "nil"), result.arg(1).tojstring());
    }

    // The activity and its transitions answer like the hooks of extractResult also in an app without them: each result
    // of the activity, alone and in its list, is the unknown activity at 100% with the times and detector id of the
    // real one, without its extras or a field of a later version, and the transitions are a result without any, as
    // SafeParcelReader reads them; bytes which are not a result, run past their end or have a negative size are left
    // out
    @Test
    public void resultsAreFakeOnes() throws Exception {
        byte[] still = recognition(1700000000000L, 123456789L, 7, 3, 80, 0, 20);
        byte[] walking = recognition(1700000001000L, 123457789L, 7, 7, 100);
        assertEquals("1=[1=3 2=80, 1=0 2=20] 2=1700000000000 3=123456789 4=7 5=? 6=9", read(still));
        globals.set("STILL", CoerceJavaToLua.coerce(still));
        globals.set("WALKING", CoerceJavaToLua.coerce(walking));
        globals.set("JUNK", CoerceJavaToLua.coerce(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        globals.set("SHORT", CoerceJavaToLua.coerce(Arrays.copyOf(still, still.length - 4)));
        globals.set("MOVED", CoerceJavaToLua.coerce(transition()));
        // A field whose size goes back to its header
        byte[] back = Arrays.copyOf(still, still.length);
        ByteBuffer.wrap(back).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0xFFFF0001).putInt(12, -8);
        globals.set("BACK", CoerceJavaToLua.coerce(back));
        Varargs result = Scripts.run(globals,
                "local function list(...) local list = luajava.newInstance('java.util.ArrayList')\n" +
                        "  for _, value in ipairs({ ... }) do list:add(value) end return list end\n" +
                        "local function results(one, all)\n" +
                        "  return { [INTERNAL .. 'EXTRA_ACTIVITY_RESULT'] = { class = '[B', parcelled = true, data = one },\n" +
                        "    [INTERNAL .. 'EXTRA_ACTIVITY_RESULT_LIST'] = { class = 'java.util.ArrayList', parcelled = true," +
                        " data = all },\n" +
                        "    [INTERNAL .. 'EXTRA_ACTIVITY_TRANSITION_RESULT'] = { class = '[B', parcelled = true, data = MOVED } }\n" +
                        "end\n" +
                        "local intent = intentof(results(WALKING, list(STILL, WALKING)))\n" +
                        "local out = describe(after(hookof('createFromParcel', 'activity'), paramof(intent)), intent)\n" +
                        "local values = intent.extras.values\n" +
                        "local junk = intentof(results(JUNK, list(STILL, SHORT)))\n" +
                        "local short = intentof(results(SHORT, list()))\n" +
                        "local back = intentof(results(BACK, list(BACK)))\n" +
                        "return out, values[INTERNAL .. 'EXTRA_ACTIVITY_RESULT'].data,\n" +
                        "  values[INTERNAL .. 'EXTRA_ACTIVITY_RESULT_LIST'].data,\n" +
                        "  values[INTERNAL .. 'EXTRA_ACTIVITY_TRANSITION_RESULT'].data,\n" +
                        "  describe(after(hookof('createFromParcel', 'activity'), paramof(junk)), junk),\n" +
                        "  describe(after(hookof('createFromParcel', 'activity'), paramof(short)), short),\n" +
                        "  describe(after(hookof('createFromParcel', 'activity'), paramof(back)), back)");
        assertEquals("true 1 boot internal.EXTRA_ACTIVITY_RESULT internal.EXTRA_ACTIVITY_RESULT_LIST[2] " +
                "internal.EXTRA_ACTIVITY_TRANSITION_RESULT", result.arg(1).tojstring());
        String unknown = "1=[1=4 2=100] 2=";
        assertEquals(unknown + "1700000001000 3=123457789 4=7", read((byte[]) result.arg(2).touserdata()));
        java.util.List<?> list = (java.util.List<?>) result.arg(3).touserdata();
        assertEquals(2, list.size());
        assertEquals(unknown + "1700000000000 3=123456789 4=7", read((byte[]) list.get(0)));
        assertEquals(unknown + "1700000001000 3=123457789 4=7", read((byte[]) list.get(1)));
        assertEquals("1=[]", read((byte[]) result.arg(4).touserdata()));
        assertEquals("true 1 boot internal.EXTRA_ACTIVITY_TRANSITION_RESULT", result.arg(5).tojstring());
        assertEquals("true 1 boot internal.EXTRA_ACTIVITY_RESULT_LIST[0] internal.EXTRA_ACTIVITY_TRANSITION_RESULT",
                result.arg(6).tojstring());
        assertEquals("true 1 boot internal.EXTRA_ACTIVITY_TRANSITION_RESULT", result.arg(7).tojstring());
    }

    // A parcel of ActivityRecognitionResult like its writeToParcel with SafeParcelWriter of play-services-basement
    // writes it: the activities (type and confidence), the times, the detector id and the extras, and a field of a later
    // version
    private static byte[] recognition(long time, long elapsed, int detector, int... activities) {
        ByteBuffer out = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        int object = begin(out, 20293);
        int list = begin(out, 1);
        out.putInt(activities.length / 2);
        for (int i = 0; i < activities.length; i += 2) {
            // Each item after its size, which takes the place of the 1 of Parcel.writeTypedObject (zzd)
            out.putInt(1);
            int item = out.position();
            int activity = begin(out, 20293);
            field(out, 1, 4).putInt(activities[i]);
            field(out, 2, 4).putInt(activities[i + 1]);
            finish(out, activity);
            out.putInt(item - 4, out.position() - item);
        }
        finish(out, list);
        field(out, 2, 8).putLong(time);
        field(out, 3, 8).putLong(elapsed);
        field(out, 4, 4).putInt(detector);
        int extras = begin(out, 5);
        out.putInt(42);
        finish(out, extras);
        field(out, 6, 4).putInt(9);
        finish(out, object);
        return Arrays.copyOf(out.array(), out.position());
    }

    // A parcel of ActivityTransitionResult with one transition (ActivityTransitionEvent: activity, transition, time)
    private static byte[] transition() {
        ByteBuffer out = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        int object = begin(out, 20293);
        int list = begin(out, 1);
        out.putInt(1);
        out.putInt(1);
        int item = out.position();
        int event = begin(out, 20293);
        field(out, 1, 4).putInt(7);
        field(out, 2, 4).putInt(0);
        field(out, 3, 8).putLong(123456789000000L);
        finish(out, event);
        out.putInt(item - 4, out.position() - item);
        finish(out, list);
        finish(out, object);
        return Arrays.copyOf(out.array(), out.position());
    }

    // The header of an object or a field whose size follows (SafeParcelWriter.zza), with where its value starts
    private static int begin(ByteBuffer out, int id) {
        out.putInt(0xFFFF0000 | id);
        out.putInt(0);
        return out.position();
    }

    // The size of the value of an object or a field (SafeParcelWriter.zzb)
    private static void finish(ByteBuffer out, int start) {
        out.putInt(start - 4, out.position() - start);
    }

    // The header of a field of a fixed size (SafeParcelWriter.zzc)
    private static ByteBuffer field(ByteBuffer out, int id, int size) {
        return out.putInt(size << 16 | id);
    }

    // The fields of a parcel as SafeParcelReader reads it: the header of the object (validateObjectHeader), a list of
    // field 1 like createTypedList, with Parcel.createTypedArrayList, which reads an item when the int before it is not
    // 0, ints and longs of their exact size (readInt, readLong), an object read to its end exactly (ensureAtEnd); other
    // fields are skipped (skipUnknownField) and shown as ?
    private static String read(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return object(in);
    }

    private static String object(ByteBuffer in) {
        int header = in.getInt();
        int size = size(in, header);
        assertEquals(20293, header & 0xFFFF);
        int end = in.position() + size;
        assertTrue(end >= in.position() && end <= in.limit());
        StringBuilder out = new StringBuilder();
        while (in.position() < end) {
            int field = in.getInt();
            int id = field & 0xFFFF;
            int length = size(in, field);
            int start = in.position();
            out.append(out.length() == 0 ? "" : " ").append(id).append('=');
            boolean sized = ((field & 0xFFFF0000) == 0xFFFF0000);
            if (sized && id == 1) {
                java.util.List<String> items = new java.util.ArrayList<>();
                for (int count = in.getInt(); count > 0; count--)
                    items.add(in.getInt() != 0 ? object(in) : "null");
                in.position(start + length);
                out.append(items);
            } else if (!sized && length == 8)
                out.append(in.getLong());
            else if (!sized && length == 4)
                out.append(in.getInt());
            else {
                in.position(start + length);
                out.append('?');
            }
        }
        assertEquals(end, in.position());
        return out.toString();
    }

    // SafeParcelReader.readSize
    private static int size(ByteBuffer in, int header) {
        return ((header & 0xFFFF0000) != 0xFFFF0000 ? header >>> 16 : in.getInt());
    }

    // The intents with a result do not reach the receivers (see receiver_skip), by the marks of this script, per
    // variant: a receiver of the manifest is finished first, a registered one only when nothing goes back to Android;
    // intents without a result are delivered. A result which starts the app is not marked, so a receiver of the
    // manifest looks for each name of the results of its variant in a copy of the parcel of the extras, which stay in
    // their parcel, not the request of transitions an app sends; with a binder in the parcel from Android 13 only; a
    // registered receiver and the other variants do not look
    @Test
    public void resultsDoNotReachReceivers() throws Exception {
        Globals skip = Scripts.load("receiver_skip", String.join("\n",
                "calls = {}",
                "function dispatchof(name, method, count)",
                "  return { getName = function(self) return name end, getMethodName = function(self) return method end,",
                "    getParameterTypes = function(self) return { length = count } end }",
                "end",
                "function callof(args)",
                "  return { getArgument = function(self, index) return args[index] end,",
                "    getValue = function(self, name, scope) return MARKS[name .. ' ' .. tostring(scope)] end,",
                "    setResult = function(self, value) calls[#calls + 1] = 'skip' end }",
                "end",
                "function dataof(intent)",
                "  return { intent = intent, finish = function(self) calls[#calls + 1] = 'finish' end }",
                "end",
                "function deliver(hook, args)",
                "  calls = {}",
                "  local skipped = before(hook, callof(args))",
                "  return skipped, table.concat(calls, ',')",
                "end"));
        Scripts.run(globals, "moving = intentof(activity()) fence = intentof(geofence())\n" +
                "other = intentof({ place = parcelable('com.example.Place') })\n" +
                "for _, case in ipairs({ { 'activity', moving }, { 'geofence', fence }, { 'activity', other } }) do\n" +
                "  after(hookof('createFromParcel', case[1]), paramof(case[2]))\n" +
                "end\n" +
                "cold = intentof(activity()) coldfence = intentof(geofence()) bound = intentof(activity(), true)\n" +
                "request = intentof({ [INTERNAL .. 'EXTRA_ACTIVITY_TRANSITION_REQUEST'] = parcelable('[B') })");
        for (String name : new String[]{"MARKS", "moving", "fence", "other", "cold", "coldfence", "bound", "request",
                "luajava", "intentof", "parcelable", "INTERNAL", "EXTRA"})
            skip.set(name, globals.get(name));
        String[][] cases = {
                {"dispatchof('ActivityThread.handleReceiver/activity', 'handleReceiver', 1), { [0] = dataof(moving) }",
                        "true", "finish,skip"},
                {"dispatchof('ActivityThread.handleReceiver/geofence', 'handleReceiver', 1), { [0] = dataof(fence) }",
                        "true", "finish,skip"},
                {"dispatchof('ActivityThread.handleReceiver/geofence', 'handleReceiver', 1), { [0] = dataof(moving) }",
                        "false", ""},
                {"dispatchof('ActivityThread.handleReceiver/activity', 'handleReceiver', 1), { [0] = dataof(other) }",
                        "false", ""},
                {"dispatchof('ReceiverDispatcher.performReceive/activity', 'performReceive', 7), " +
                        "{ [0] = moving, [4] = false, [5] = false }", "true", "skip"},
                {"dispatchof('ReceiverDispatcher.performReceive/activity', 'performReceive', 7), " +
                        "{ [0] = moving, [4] = true, [5] = false }", "false", ""},
                {"dispatchof('ReceiverDispatcher.performReceive.sender/geofence', 'performReceive', 10), " +
                        "{ [0] = fence, [4] = false, [5] = false, [6] = true }", "true", "skip"},
                {"dispatchof('ReceiverDispatcher.performReceive.sender/geofence', 'performReceive', 10), " +
                        "{ [0] = fence, [4] = false, [5] = false, [6] = false }", "false", ""},
                {"dispatchof('ActivityThread.handleReceiver/activity', 'handleReceiver', 1), { [0] = dataof(cold) }",
                        "true", "finish,skip"},
                {"dispatchof('ActivityThread.handleReceiver/geofence', 'handleReceiver', 1), { [0] = dataof(coldfence) }",
                        "true", "finish,skip"},
                {"dispatchof('ActivityThread.handleReceiver/geofence', 'handleReceiver', 1), { [0] = dataof(cold) }",
                        "false", ""},
                {"dispatchof('ActivityThread.handleReceiver/activity', 'handleReceiver', 1), { [0] = dataof(request) }",
                        "false", ""},
                {"dispatchof('ActivityThread.handleReceiver/network', 'handleReceiver', 1), { [0] = dataof(cold) }",
                        "false", ""},
                {"dispatchof('ReceiverDispatcher.performReceive/activity', 'performReceive', 7), " +
                        "{ [0] = cold, [4] = false, [5] = false }", "false", ""},
                {"dispatchof('ActivityThread.handleReceiver/activity', 'handleReceiver', 1), { [0] = dataof(bound) }",
                        "true", "finish,skip"},
        };
        for (String[] test : cases) {
            Varargs result = Scripts.run(skip, "return deliver(" + test[0] + ")");
            assertEquals(test[0], Boolean.parseBoolean(test[1]), result.arg(1).toboolean());
            assertEquals(test[0], test[2], result.arg(2).tojstring());
        }
        assertEquals("true true", Scripts.run(skip, "return tostring(cold.extras.inparcel) .. ' ' .. " +
                "tostring(coldfence.extras.inparcel)").arg1().tojstring());
        Scripts.run(globals, "sdk = 30");
        assertEquals("false", Scripts.run(skip, "return tostring(deliver(dispatchof('ActivityThread.handleReceiver/activity', " +
                "'handleReceiver', 1), { [0] = dataof(bound) }))").arg1().tojstring());
        String[][] names = {
                {"activity", "internal.EXTRA_ACTIVITY_RESULT", "internal.EXTRA_ACTIVITY_RESULT_LIST",
                        "internal.EXTRA_ACTIVITY_TRANSITION_RESULT", "internal.EXTRA_SLEEP_SEGMENT_RESULT",
                        "internal.EXTRA_SLEEP_CLASSIFY_RESULT"},
                {"geofence", "intent.extra.transition", "intent.extra.geofence_list", "intent.extra.triggering_location"},
        };
        for (String[] variant : names)
            for (int i = 1; i < variant.length; i++) {
                Varargs result = Scripts.run(skip, "return deliver(dispatchof('ActivityThread.handleReceiver/" +
                        variant[0] + "', 'handleReceiver', 1), { [0] = dataof(intentof({ " +
                        "['com.google.android.location." + variant[i] + "'] = parcelable('[B') })) })");
                assertEquals(variant[i], "finish,skip", result.arg(2).tojstring());
            }
    }

    // The look at the parcel of the extras is the one of the location updates, a copy, since scripts are loaded apart
    @Test
    public void parcelledLikeLocation() throws Exception {
        assertEquals(Scripts.function("location", "parcelled"), Scripts.function("intent_results", "parcelled"));
    }

    // The look for the names of the results is the same for the receivers, a copy, since scripts are loaded apart
    @Test
    public void namedLikeReceivers() throws Exception {
        assertEquals(Scripts.function("intent_results", "named"), Scripts.function("receiver_skip", "named"));
    }
}
