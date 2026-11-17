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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The scripts of ways around the hooks which audits found: the kernel in /proc, speech recognition, the addresses
// of a VPN, blocked methods without a return type, the HDR luminance of the panel and the MMS user agent of the
// carrier configuration; Android classes are Lua stand-ins
public class AuditScriptsTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method, types)",
            "  return { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return types end }",
            "end",
            "values = {}",
            "function paramof(result, args, this)",
            "  return { result = result, args = args or {}, set = false, path = nil, text = nil,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    getThis = function(self) return this end,",
            "    getException = function(self) return nil end,",
            "    isSystem = function(self) return false end,",
            "    unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end,",
            "    opens = function(self, file) return self.path == file end,",
            "    getFileText = function(self) return self.text end,",
            "    setFileText = function(self, text) self.text = text self.set = true end,",
            "    putValue = function(self, name, value, scope) values[name] = { scope = scope, value = value } end,",
            "    getValue = function(self, name, scope)",
            "      local entry = values[name]",
            "      return entry and entry.scope == scope and entry.value or nil end }",
            "end",
            "posted = {}",
            "luajava = {",
            "  newInstance = function(name, ...)",
            "    if name == 'java.lang.ref.WeakReference' then",
            "      return { referent = ..., get = function(self) return self.referent end }",
            "    end",
            "    if name == 'android.os.Handler' then",
            "      return { post = function(self, runnable) table.insert(posted, runnable) end }",
            "    end",
            "    return { class = name }",
            "  end,",
            "  createProxy = function(interface, methods) return methods end,",
            "  bindClass = function(name)",
            "    if name == 'android.os.Looper' then return { getMainLooper = function() return 'main' end } end",
            "    if name == 'java.lang.Thread' then",
            "      return { currentThread = function() return { getStackTrace = function() return FRAMES end } end }",
            "    end",
            "    return CLASSES[name]",
            "  end }",
            "FRAMES = { length = 0 }",
            "CLASSES = {}");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, STUBS);
    }

    // The kernel release and version of /proc like those of Os.uname, with the builder and compiler of the AOSP build
    // of the same kernel version in /proc/version, never those of the vendor, other files stay
    @Test
    public void kernelInProc() throws Exception {
        Globals globals = load("kernel_version");
        String compiler = "(Android (11368308, +pgo, +bolt, +lto, +mlgo, based on r510928) clang version 18.0.0 " +
                "(https://android.googlesource.com/toolchain/llvm-project 477610d4d0d988e69dbc3fae4fe86bff3f07f2b5), LLD 18.0.0)";
        String faked = "Linux version 6.6.144-android15-8-maybe-dirty (kleaf@build-host) " + compiler +
                " #1 SMP PREEMPT Thu Jan  1 00:00:00 UTC 1970\n";
        Varargs result = Scripts.run(globals,
                "local function read(path, text)\n" +
                        "  local param = paramof('fd')\n" +
                        "  param.path, param.text = path, text\n" +
                        "  local restricted = after(hookof('open'), param)\n" +
                        "  return tostring(restricted) .. ':' .. tostring(param.text)\n" +
                        "end\n" +
                        "return read('/proc/sys/kernel/osrelease', '6.6.50-android15-8-g8adecb593e9b\\n'),\n" +
                        "  read('/proc/sys/kernel/version', '#1 SMP PREEMPT Tue Oct 1 12:00:00 UTC 2024\\n'),\n" +
                        "  read('/proc/version', 'Linux version 6.6.50-android15-8-g8adecb593e9b (builder@abfarm-01) " +
                        "(Proton clang version 19.0.0 (https://github.com/kdrag0n/proton-clang), LLD 19.0.0) " +
                        "#1 SMP PREEMPT Tue Oct 1 12:00:00 UTC 2024\\n'),\n" +
                        "  read('/proc/sys/kernel/osrelease', '6.6.144-android15-8-maybe-dirty\\n'), read('/proc/self/version', 'x'),\n" +
                        "  filter(nil)[1], read('/proc/version', '" + faked.replace("\n", "\\n") + "'),\n" +
                        "  read('/proc/version', 'Linux version 6.6.50\\n'),\n" +
                        "  read('/proc/version', 'Linux version 3.18.91+ (android-build@xpcd3.ams.corp.google.com) " +
                        "(gcc version 4.9 20140827 (prerelease) (GCC) ) #1 SMP PREEMPT Thu Jan 25 02:43:49 UTC 2018\\n'),\n" +
                        "  read('/proc/version', 'Linux version 4.19.110-perf+ (user@vendor) (Linaro GCC 7.3) #1 SMP PREEMPT\\n'),\n" +
                        "  read('/proc/sys/kernel/osrelease', '5.4.249-qgki-g212a7aaded26\\n'),\n" +
                        "  read('/proc/version', 'Linux version 6.1.75-android14-11-g8b1f2a9 (builder@abfarm-01) (clang) #1\\n'),\n" +
                        "  read('/proc/version', 'Linux version 6.6.50-android15-8-g8adecb593e9b-ab12345678-4k (builder@abfarm-01) " +
                        "(clang) #1\\n')");
        assertEquals("true:6.6.144-android15-8-maybe-dirty\n", result.arg(1).tojstring());
        assertEquals("true:#1 SMP PREEMPT Thu Jan  1 00:00:00 UTC 1970\n", result.arg(2).tojstring());
        assertEquals("true:" + faked, result.arg(3).tojstring());
        assertFalse(result.arg(3).tojstring(), result.arg(3).tojstring().contains("Proton"));
        // Faked already, and files of other paths
        assertEquals("false:6.6.144-android15-8-maybe-dirty\n", result.arg(4).tojstring());
        assertEquals("false:x", result.arg(5).tojstring());
        assertEquals("*kernel/osrelease*", result.arg(6).tojstring());
        assertEquals("false:" + faked, result.arg(7).tojstring());
        // A text without the compiler in its parentheses
        assertEquals("true:" + faked, result.arg(8).tojstring());
        // Older kernels, built with GCC and clang without LLD, get the last release of their branch without git
        assertEquals("true:Linux version 3.18.140 (build-user@build-host) (gcc version 4.9 20140827 (prerelease) (GCC) ) " +
                "#1 SMP PREEMPT Thu Jan  1 00:00:00 UTC 1970\n", result.arg(9).tojstring());
        assertEquals("true:Linux version 4.19.325 (build-user@build-host) (Android (5900059 based on r365631c) clang version " +
                "9.0.8 (https://android.googlesource.com/toolchain/llvm-project 207d7abc1a2abf3ef8d4301736d6a7ebc224a290) " +
                "(based on LLVM 9.0.8svn)) #1 SMP PREEMPT Thu Jan  1 00:00:00 UTC 1970\n", result.arg(10).tojstring());
        assertEquals("true:5.4.302\n", result.arg(11).tojstring());
        // GKI kernels before 6.6 were built by the default builder of AOSP
        assertEquals("true:Linux version 6.1.177-android14-11-maybe-dirty (build-user@build-host) (Android (9796371, based on " +
                "r487747) clang version 17.0.0 (https://android.googlesource.com/toolchain/llvm-project " +
                "d9f89f4d16663d5012e5c09495f3b30ece3d2362), LLD 17.0.0) #1 SMP PREEMPT Thu Jan  1 00:00:00 UTC 1970\n",
                result.arg(12).tojstring());
        // The page size of an arm64 kernel stays at the end
        assertEquals("true:" + faked.replace("dirty", "dirty-4k"), result.arg(13).tojstring());
    }

    // The stand-ins of speech recognition: the context of the app, which holds RECORD_AUDIO when granted is true, the
    // SDK of Android, Handlers which keep what they post with its delay, to run or remove it, and values kept for each
    // scope, read by valueof
    private static final String SPEECH = String.join("\n",
            "granted, SDK = false, 34",
            "context = { checkSelfPermission = function(self, name)",
            "  assert(name == 'android.permission.RECORD_AUDIO', name) return granted and 0 or -1 end }",
            "local newInstance, bindClass = luajava.newInstance, luajava.bindClass",
            "luajava.newInstance = function(name, ...)",
            "  if name ~= 'android.os.Handler' then return newInstance(name, ...) end",
            "  return { postDelayed = function(self, runnable, delay)",
            "      table.insert(posted, { run = runnable.run, delay = delay, handler = self }) end,",
            "    removeCallbacksAndMessages = function(self, token)",
            "      assert(token == nil)",
            "      for index = #posted, 1, -1 do if posted[index].handler == self then table.remove(posted, index) end end",
            "    end }",
            "end",
            "luajava.bindClass = function(name)",
            "  if name == 'android.os.Build$VERSION' then return { SDK_INT = SDK } end",
            "  return bindClass(name)",
            "end",
            "stored = {}",
            "function valueof(scope, name) return stored[scope] and stored[scope][name] end",
            "function speechof(this, args)",
            "  local param = paramof(nil, args or { [0] = 'intent' }, this)",
            "  param.getApplicationContext = function() return context end",
            "  param.putValue = function(self, name, value, scope)",
            "    stored[scope] = stored[scope] or {} stored[scope][name] = value end",
            "  param.getValue = function(self, name, scope) return valueof(scope, name) end",
            "  return param",
            "end",
            "function listenerof(calls)",
            "  return { onError = function(self, code) table.insert(calls, code) end,",
            "    onReadyForSpeech = function(self, params) table.insert(calls, 'ready:' .. params.class) end }",
            "end");

    // Without RECORD_AUDIO listening does not start; the listener the app set gets ERROR_INSUFFICIENT_PERMISSIONS on the
    // main thread, like from the recognition service of every Android version
    @Test
    public void speechWithoutPermissionAnswersWithError() throws Exception {
        Globals globals = load("speech_recognizer");
        Scripts.run(globals, SPEECH);
        Varargs result = Scripts.run(globals,
                "local recognizer = {}\n" +
                        "local calls = {}\n" +
                        "local listener = listenerof(calls)\n" +
                        "local set = speechof(recognizer, { [0] = listener })\n" +
                        "local kept = before(hookof('setRecognitionListener'), set)\n" +
                        "local start = speechof(recognizer)\n" +
                        "local restricted = before(hookof('startListening'), start)\n" +
                        "local count = #calls\n" +
                        "posted[1].run()\n" +
                        // Kept weakly, not the listener itself; once collected nobody is answered
                        "local reference = valueof(recognizer, 'speech.listener')\n" +
                        "local weak = reference ~= listener and reference:get() == listener\n" +
                        "reference.referent = nil\n" +
                        "before(hookof('startListening'), speechof(recognizer))\n" +
                        "posted[2].run()\n" +
                        "return kept, set.set, restricted, start.set, count, calls[1], posted[1].delay, #calls, weak,\n" +
                        "  valueof(recognizer, 'speech.session') == nil and valueof(context, 'speech.session') == nil");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        // Posted, not called during startListening, like Android
        assertEquals(0, result.arg(5).toint());
        assertEquals(9, result.arg(6).toint());
        assertEquals(0, result.arg(7).toint());
        assertEquals(1, result.arg(8).toint());
        assertTrue(result.arg(9).toboolean());
        // No session, so nothing for cancel to end
        assertTrue(result.arg(10).toboolean());
    }

    // With RECORD_AUDIO the microphone is silent: ready for speech, then ERROR_SPEECH_TIMEOUT after five seconds, to
    // the listener set at that time; a second start during it is refused like by the recognition service of the
    // version, a cancel or destroy ends it without an answer and still goes to Android, and destroy drops the listener
    @Test
    public void speechWithPermissionAnswersLikeSilence() throws Exception {
        Globals globals = load("speech_recognizer");
        Scripts.run(globals, SPEECH);
        Varargs result = Scripts.run(globals,
                "granted = true\n" +
                        "local recognizer = {}\n" +
                        "local calls, later = {}, {}\n" +
                        "before(hookof('setRecognitionListener'), speechof(recognizer, { [0] = listenerof(calls) }))\n" +
                        "local start = speechof(recognizer)\n" +
                        "local restricted = before(hookof('startListening'), start)\n" +
                        "local delays = posted[1].delay .. ',' .. posted[2].delay\n" +
                        "posted[1].run()\n" +
                        "before(hookof('startListening'), speechof(recognizer))\n" +
                        "SDK = 33\n" +
                        "before(hookof('startListening'), speechof(recognizer))\n" +
                        "posted[3].run()\n" +
                        "posted[4].run()\n" +
                        "before(hookof('setRecognitionListener'), speechof(recognizer, { [0] = listenerof(later) }))\n" +
                        "posted[2].run()\n" +
                        "local ended = valueof(recognizer, 'speech.session') == nil and valueof(context, 'speech.session') == nil\n" +
                        "local sequence = table.concat(calls, ',') .. '|' .. table.concat(later, ',')\n" +
                        // Ended by cancel and by destroy before the answers
                        "local function stopped(method)\n" +
                        "  posted = {}\n" +
                        "  before(hookof('startListening'), speechof(recognizer))\n" +
                        "  local param = speechof(recognizer, {})\n" +
                        "  local restricted = before(hookof(method), param)\n" +
                        "  return tostring(restricted) .. ':' .. tostring(param.set) .. ':' .. #posted .. ':' ..\n" +
                        "    tostring(valueof(recognizer, 'speech.session') == nil and valueof(context, 'speech.session') == nil) ..\n" +
                        "    ':' .. tostring(valueof(recognizer, 'speech.listener') ~= nil)\n" +
                        "end\n" +
                        "local cancelled, destroyed = stopped('cancel'), stopped('destroy')\n" +
                        // After destroy a start reaches no listener until one is set again
                        "posted = {}\n" +
                        "before(hookof('startListening'), speechof(recognizer))\n" +
                        "for _, post in ipairs(posted) do post.run() end\n" +
                        "return restricted, start.set, delays, sequence, ended, cancelled, destroyed,\n" +
                        "  before(hookof('cancel'), speechof(recognizer, {})), #posted .. ':' .. table.concat(later, ',')");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals("0,5000", result.arg(3).tojstring());
        // ERROR_CLIENT (5) from Android 14, ERROR_RECOGNIZER_BUSY (8) before; the timeout to the listener set last
        assertEquals("ready:android.os.Bundle,5,8|6", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("false:false:0:true:true", result.arg(6).tojstring());
        assertEquals("false:false:0:true:false", result.arg(7).tojstring());
        // Without a session cancel only goes to Android
        assertFalse(result.arg(8).toboolean());
        assertEquals("2:6", result.arg(9).tojstring());
    }

    // The app has one session at a time: a start of another recognizer during it gets ERROR_RECOGNIZER_BUSY (8) on every
    // version, its cancel leaves the session running, and once the session ended the other recognizer can start one
    @Test
    public void speechOfAnotherRecognizerIsBusy() throws Exception {
        Globals globals = load("speech_recognizer");
        Scripts.run(globals, SPEECH);
        Varargs result = Scripts.run(globals,
                "granted = true\n" +
                        "local first, second = {}, {}\n" +
                        "local calls, others = {}, {}\n" +
                        "before(hookof('setRecognitionListener'), speechof(first, { [0] = listenerof(calls) }))\n" +
                        "before(hookof('setRecognitionListener'), speechof(second, { [0] = listenerof(others) }))\n" +
                        "before(hookof('startListening'), speechof(first))\n" +
                        "local restricted = {}\n" +
                        "for _, sdk in ipairs({ 27, 33, 34 }) do\n" +
                        "  SDK = sdk\n" +
                        "  table.insert(restricted, tostring(before(hookof('startListening'), speechof(second))))\n" +
                        "end\n" +
                        "before(hookof('cancel'), speechof(second, {}))\n" +
                        "local running = #posted .. ':' .. tostring(valueof(first, 'speech.session') ~= nil)\n" +
                        "for _, post in ipairs(posted) do post.run() end\n" +
                        "posted = {}\n" +
                        "before(hookof('startListening'), speechof(second))\n" +
                        "before(hookof('startListening'), speechof(first))\n" +
                        "for _, post in ipairs(posted) do post.run() end\n" +
                        "return table.concat(restricted, ','), running, table.concat(calls, ','), table.concat(others, ',')");
        assertEquals("true,true,true", result.arg(1).tojstring());
        assertEquals("5:true", result.arg(2).tojstring());
        // The first is answered 8 too, not ERROR_CLIENT (5), while the second has the session
        assertEquals("ready:android.os.Bundle,6,8", result.arg(3).tojstring());
        assertEquals("8,8,8,ready:android.os.Bundle,6", result.arg(4).tojstring());
    }

    // The stand-ins of the screens of recognizers: intents with their action, package, component, selector and extras,
    // which copy like Intent.clone and refuse a package next to a selector like Android, arrays of them, exceptions of
    // Android with a stack trace and a cause, and calls of the app com.example.app
    private static final String RECOGNIZER = Scripts.ARRAYOF + "\n" + String.join("\n",
            "RECOGNIZE = 'android.speech.action.RECOGNIZE_SPEECH'",
            "function componentof(pkg, cls)",
            "  return { getPackageName = function(self) return pkg end, getClassName = function(self) return cls end }",
            "end",
            "function intentof(action, pkg, component, selector)",
            "  return { action = action, pkg = pkg, component = component, selector = selector, extras = {},",
            "    getAction = function(self) return self.action end, getPackage = function(self) return self.pkg end,",
            "    getComponent = function(self) return self.component end,",
            "    getSelector = function(self) return self.selector end,",
            "    setComponent = function(self, value) self.component = value end,",
            "    setPackage = function(self, value)",
            "      assert(value == nil or self.selector == nil, 'Cannot set package name when selector is already set')",
            "      self.pkg = value end,",
            "    setSelector = function(self, value)",
            "      assert(value == nil or self.pkg == nil, 'Can not set selector when package name is already set')",
            "      self.selector = value end,",
            "    cloneFilter = function(self) return intentof(self.action, self.pkg, self.component) end,",
            "    hasExtra = function(self, name) return self.extras[name] ~= nil end,",
            "    getParcelableExtra = function(self, name) return self.extras[name] end,",
            "    getParcelableArrayExtra = function(self, name) return self.extras[name] end,",
            "    putExtras = function(self, other) for k, v in pairs(other.extras) do self.extras[k] = v end end,",
            "    clone = function(self)",
            "      local copy = intentof(self.action, self.pkg, self.component, self.selector and self.selector:clone())",
            "      for k, v in pairs(self.extras) do copy.extras[k] = v end",
            "      return copy end }",
            "end",
            "function exceptionof(message)",
            "  return { message = message, trace = 'trace', cause = 'remote',",
            "    getMessage = function(self) return self.message end, getClass = function(self) return 'ANFE' end,",
            "    getStackTrace = function(self) return self.trace end, getCause = function(self) return self.cause end,",
            "    setStackTrace = function(self, trace) self.trace = trace end, initCause = function(self, cause) self.cause = cause end }",
            "end",
            "luajava.new = function(class, message) assert(class == 'ANFE') return exceptionof(message) end",
            "function listof(...)",
            "  local items = { ... }",
            "  return { list = true, items = items, n = select('#', ...), size = function(self) return self.n end,",
            "    get = function(self, index) return self.items[index + 1] end,",
            "    set = function(self, index, value) self.items[index + 1] = value end }",
            "end",
            "function bundleof()",
            "  return { extras = {}, putParcelable = function(self, name, value) self.extras[name] = value end,",
            "    putParcelableArray = function(self, name, value) self.extras[name] = value end,",
            "    isEmpty = function(self) return next(self.extras) == nil end }",
            "end",
            "local newInstance = luajava.newInstance",
            "luajava.newInstance = function(name, ...)",
            "  if name == 'android.content.ComponentName' then return componentof(...) end",
            "  if name == 'android.os.Bundle' then return bundleof() end",
            "  if name == 'java.util.ArrayList' then local list = ... return listof(table.unpack(list.items, 1, list.n)) end",
            "  return newInstance(name, ...)",
            "end",
            "CLASSES['java.lang.reflect.Array'] = {",
            "  newInstance = function(self, type, length) return { length = length, type = type } end }",
            "CLASSES['android.content.Intent'] = 'Intent'",
            "CLASSES['android.os.Parcelable'] = 'Parcelable'",
            "function screenof(args)",
            "  local param = paramof(nil, args)",
            "  param.getPackageName = function(self) return 'com.example.app' end",
            "  param.setArgument = function(self, index, value) self.args[index] = value end",
            "  param.getException = function(self) return self.exception end",
            "  param.isInstance = function(self, value, class) return class == 'java.util.List' and value.list == true end",
            "  return param",
            "end",
            // What Android gets for an intent through a method: whether it was restricted, the package and the
            // component the copy names, the package of the selector of the copy, and whether the app's intent stayed;
            // the flags of a PendingIntent are the third argument
            "function sent(method, index, intent, flags)",
            "  local pkg, component = intent and intent.pkg, intent and intent.component",
            "  local args = { [index] = intent }",
            "  if flags ~= nil then args[3] = flags end",
            "  local param = screenof(args)",
            "  local restricted = before(hookof(method), param)",
            "  after(hookof(method), param)",
            "  local copy = param.args[index]",
            "  local name = copy and copy.component",
            "  return tostring(restricted) .. ':' .. tostring(copy and copy.pkg) .. ':' ..",
            "    tostring(name and name:getPackageName() .. '/' .. name:getClassName()) .. ':' ..",
            "    tostring(copy and copy.selector and copy.selector.pkg) .. ':' ..",
            "    tostring(intent == nil or (intent.pkg == pkg and intent.component == component and",
            "      (copy == intent) == not restricted))",
            "end");

    // The screen of a recognizer of another app is not found, like on a device without one: Android gets a copy which
    // only the app itself handles, the package of the app for an intent which names none, else a name which cannot
    // exist; the intent of the app stays, and so do other intents, those of the app itself and the copies of
    // component_hidden, for every method which starts or looks up a screen
    @Test
    public void recognizerScreensAreNotFound() throws Exception {
        Globals globals = load("recognizer_intent");
        Scripts.run(globals, RECOGNIZER);
        String calls = "local results = {}\n" +
                "for _, call in ipairs({ { 'execStartActivity', 4 }, { 'startActivityIfNeeded', 0 }, { 'startActivity', 1 },\n" +
                "    { 'resolveActivity', 0 }, { 'queryIntentActivities', 0 }, { 'queryIntentActivityOptions', 2 } }) do\n" +
                "  table.insert(results, sent(call[1], call[2], %s))\n" +
                "end\n" +
                "return table.concat(results, ',')";
        String[][] cases = {
                // Implicit, which Android resolves among the recognizers of all apps, here of the app itself
                {"intentof(RECOGNIZE)", "true:com.example.app:nil:nil:true"},
                {"intentof('android.speech.action.WEB_SEARCH')", "true:com.example.app:nil:nil:true"},
                {"intentof('android.speech.action.VOICE_SEARCH_HANDS_FREE')", "true:com.example.app:nil:nil:true"},
                // The sound recorder, which also gives the app what it records
                {"intentof('android.provider.MediaStore.RECORD_SOUND')", "true:com.example.app:nil:nil:true"},
                // By a selector, which Android resolves instead, and which gets the package itself, also when only the
                // intent Android delivers is a recognizer; a selector of another app gets a name which cannot exist
                {"intentof('x', nil, nil, intentof(RECOGNIZE))", "true:nil:nil:com.example.app:true"},
                {"intentof(RECOGNIZE, nil, nil, intentof('android.intent.action.MAIN'))", "true:nil:nil:com.example.app:true"},
                {"intentof(RECOGNIZE, nil, nil, intentof('android.intent.action.MAIN', 'com.google.android.tts'))",
                        "true:nil:nil:com.google.android.tts:hidden:true"},
                // Of another app by its package or its component
                {"intentof(RECOGNIZE, 'com.google.android.tts')", "true:com.google.android.tts:hidden:nil:nil:true"},
                {"intentof(RECOGNIZE, nil, componentof('com.google.android.tts', '.Voice'))",
                        "true:nil:com.google.android.tts:hidden/.Voice:nil:true"},
                // Others stay: the app itself, other screens, a selector which is not a recognizer, copies of
                // component_hidden and no intent
                {"intentof(RECOGNIZE, 'com.example.app')", "false:com.example.app:nil:nil:true"},
                {"intentof(RECOGNIZE, nil, componentof('com.example.app', '.Voice'))",
                        "false:nil:com.example.app/.Voice:nil:true"},
                {"intentof('android.intent.action.VIEW')", "false:nil:nil:nil:true"},
                {"intentof('android.intent.action.VIEW', nil, nil, intentof('android.intent.action.MAIN'))",
                        "false:nil:nil:nil:true"},
                {"intentof(RECOGNIZE, 'com.other.hidden:hidden')", "false:com.other.hidden:hidden:nil:nil:true"},
                {"nil", "false:nil:nil:nil:true"},
        };
        for (String[] test : cases) {
            String expected = String.join(",", test[1], test[1], test[1], test[1], test[1], test[1]);
            assertEquals(test[0], expected, Scripts.run(globals, String.format(calls, test[0])).arg(1).tojstring());
        }

        // A selector which names the screen of the recognizer by its component, while the intent has the action
        Varargs selected = Scripts.run(globals,
                "local intent = intentof(RECOGNIZE, nil, nil, intentof('android.intent.action.MAIN', nil,\n" +
                        "  componentof('com.google.android.tts', 'com.google.android.tts.TranscriptionActivity')))\n" +
                        "local param = screenof({ [4] = intent })\n" +
                        "local restricted = before(hookof('execStartActivity'), param)\n" +
                        "after(hookof('execStartActivity'), param)\n" +
                        "return restricted, param.args[4].selector.component:getPackageName(),\n" +
                        "  intent.selector.component:getPackageName()");
        assertTrue(selected.arg(1).toboolean());
        assertEquals("com.google.android.tts:hidden", selected.arg(2).tojstring());
        assertEquals("com.google.android.tts", selected.arg(3).tojstring());

        // Several screens: a copy of the array with a copy of each recognizer and the others as they are, so Android
        // starts the screens before it
        Varargs screens = Scripts.run(globals,
                "local own = intentof(nil, nil, componentof('com.example.app', '.Main'))\n" +
                        "local recognizer = intentof(RECOGNIZE)\n" +
                        "local intents = arrayof(own, recognizer)\n" +
                        "local param = screenof({ [4] = intents })\n" +
                        "local restricted = before(hookof('execStartActivitiesAsUser'), param)\n" +
                        "local copies = param.args[4]\n" +
                        "local unchanged = before(hookof('execStartActivitiesAsUser'), screenof({ [4] = arrayof(own) }))\n" +
                        "return restricted, copies ~= intents and copies.length == 2 and copies[1] == own, copies[2].pkg,\n" +
                        "  recognizer.pkg, intents[2] == recognizer, unchanged");
        assertTrue(screens.arg(1).toboolean());
        assertTrue(screens.arg(2).toboolean());
        assertEquals("com.example.app", screens.arg(3).tojstring());
        assertTrue(screens.arg(4).isnil());
        assertTrue(screens.arg(5).toboolean());
        assertFalse(screens.arg(6).toboolean());
    }

    // The exception of Android for the copy has the text of the intent of the app, with the same class, stack trace
    // and cause, and the intent of the app gets the referrer Android put in the copy; an exception naming another
    // intent, like the first of several screens, stays
    @Test
    public void recognizerScreenExceptionsHaveTheIntentOfTheApp() throws Exception {
        Globals globals = load("recognizer_intent");
        Scripts.run(globals, RECOGNIZER);
        String run = "local intent = %s\n" +
                "local param = screenof({ [4] = intent })\n" +
                "before(hookof('execStartActivity'), param)\n" +
                "param.args[4].extras['android.intent.extra.REFERRER'] = 'referrer'\n" +
                "param.exception = exceptionof(%s)\n" +
                "local restricted = after(hookof('execStartActivity'), param)\n" +
                "local fake = param.result\n" +
                "return restricted, fake and fake.message, fake and fake.trace .. ':' .. fake.cause,\n" +
                "  intent.extras['android.intent.extra.REFERRER']";
        String found = "'No Activity found to handle Intent { act=android.speech.action.RECOGNIZE_SPEECH%s (has extras) }'";
        String explicit = "'Unable to find explicit activity class {%s/com.google.android.tts.Voice}; " +
                "have you declared this activity in your AndroidManifest.xml?'";

        Varargs result = Scripts.run(globals, String.format(run, "intentof(RECOGNIZE)",
                String.format(found, " pkg=com.example.app")));
        assertFalse(result.arg(1).toboolean());
        assertEquals(String.format(found, "").replace("'", ""), result.arg(2).tojstring());
        assertEquals("trace:remote", result.arg(3).tojstring());
        assertEquals("referrer", result.arg(4).tojstring());

        result = Scripts.run(globals, String.format(run,
                "intentof(RECOGNIZE, nil, componentof('com.google.android.tts', 'com.google.android.tts.Voice'))",
                String.format(explicit, "com.google.android.tts:hidden")));
        assertEquals(String.format(explicit, "com.google.android.tts").replace("'", ""), result.arg(2).tojstring());

        // A selector naming the recognizer by its component: the text of an intent leaves out the package in front
        // of the class
        result = Scripts.run(globals, String.format(run, "intentof('x', nil, nil, intentof(RECOGNIZE, nil, " +
                        "componentof('com.google.android.tts', 'com.google.android.tts.Voice')))",
                "'No Activity found to handle Intent { act=x sel=act=android.speech.action.RECOGNIZE_SPEECH " +
                        "cmp=com.google.android.tts:hidden/com.google.android.tts.Voice} }'"));
        assertEquals("No Activity found to handle Intent { act=x sel=act=android.speech.action.RECOGNIZE_SPEECH " +
                "cmp=com.google.android.tts/.Voice} }", result.arg(2).tojstring());

        // A selector with nothing but the package of the app, for a recognizer by the action of the intent
        result = Scripts.run(globals, String.format(run, "intentof(RECOGNIZE, nil, nil, intentof(nil))",
                "'No Activity found to handle Intent { act=android.speech.action.RECOGNIZE_SPEECH sel=pkg=com.example.app} }'"));
        assertEquals("No Activity found to handle Intent { act=android.speech.action.RECOGNIZE_SPEECH sel=} }",
                result.arg(2).tojstring());

        // Other screens and exceptions without the changed text stay
        result = Scripts.run(globals, String.format(run, "intentof('android.intent.action.VIEW')",
                "'No Activity found to handle Intent { act=android.intent.action.VIEW pkg=com.example.app }'"));
        assertTrue(result.arg(2).isnil());
        result = Scripts.run(globals, String.format(run, "intentof(RECOGNIZE)", "'file:///sdcard/a exposed beyond app'"));
        assertTrue(result.arg(2).isnil());
        Varargs screens = Scripts.run(globals,
                "local param = screenof({ [4] = arrayof(intentof(nil, 'com.example.app'), intentof(RECOGNIZE)) })\n" +
                        "before(hookof('execStartActivitiesAsUser'), param)\n" +
                        "param.exception = exceptionof('No Activity found to handle Intent { act=a pkg=com.example.app }')\n" +
                        "after(hookof('execStartActivitiesAsUser'), param)\n" +
                        "return param.set");
        assertFalse(screens.arg(1).toboolean());
    }

    // queryIntentActivityOptions lists the specifics first, also one named by its component: an array or a list of
    // them gets a copy with copies of the recognizers, the specifics of the app stay, and so does a call without
    // recognizers
    @Test
    public void recognizerSpecificsAreNotFound() throws Exception {
        Globals globals = load("recognizer_intent");
        Scripts.run(globals, RECOGNIZER);
        Varargs result = Scripts.run(globals,
                "local named = intentof(RECOGNIZE, nil, componentof('com.google.android.tts', '.Voice'))\n" +
                        "local other = intentof('android.intent.action.VIEW')\n" +
                        "local specifics = arrayof(other, named)\n" +
                        "local param = screenof({ [1] = specifics, [2] = other })\n" +
                        "local restricted = before(hookof('queryIntentActivityOptions'), param)\n" +
                        "local copies = param.args[1]\n" +
                        "local list = listof(other, named)\n" +
                        "local listed = screenof({ [1] = list, [2] = intentof(RECOGNIZE) })\n" +
                        "local both = before(hookof('queryIntentActivityOptions'), listed)\n" +
                        "local unchanged = before(hookof('queryIntentActivityOptions'),\n" +
                        "  screenof({ [1] = arrayof(other), [2] = other }))\n" +
                        "return restricted, copies.type .. ':' .. tostring(copies ~= specifics and copies[1] == other) .. ':' ..\n" +
                        "  copies[2].component:getPackageName(), param.args[2] == other and specifics[2] == named,\n" +
                        "  both, listed.args[1]:get(1).component:getPackageName() .. ':' .. listed.args[2].pkg,\n" +
                        "  listed.args[1] ~= list and list:get(1) == named and named.component:getPackageName(), unchanged");
        assertTrue(result.arg(1).toboolean());
        assertEquals("Intent:true:com.google.android.tts:hidden", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals("com.google.android.tts:hidden:com.example.app", result.arg(5).tojstring());
        assertEquals("com.google.android.tts", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
    }

    // A PendingIntent of a recognizer of another app starts nothing when sent: an implicit intent stays implicit,
    // with the package of the app in a selector, so Android refuses a mutable one from Android 14 like without Doppel,
    // and one of another app or with a selector gets the copy of the screens; the same for each of several intents;
    // the PendingIntent of a copy takes no component, package or selector of a fill-in, and keeps its other flags
    @Test
    public void recognizerPendingIntentsStayImplicit() throws Exception {
        Globals globals = load("recognizer_intent");
        Scripts.run(globals, RECOGNIZER);
        String[][] cases = {
                {"intentof(RECOGNIZE)", "true:nil:nil:com.example.app:true"},
                {"intentof('x', nil, nil, intentof(RECOGNIZE))", "true:nil:nil:com.example.app:true"},
                {"intentof(RECOGNIZE, 'com.google.android.tts')", "true:com.google.android.tts:hidden:nil:nil:true"},
                {"intentof(RECOGNIZE, 'com.example.app')", "false:com.example.app:nil:nil:true"},
                {"intentof('android.intent.action.VIEW')", "false:nil:nil:nil:true"},
        };
        for (String[] test : cases)
            assertEquals(test[0], test[1],
                    Scripts.run(globals, "return sent('getActivity', 2, " + test[0] + ", 0)").arg(1).tojstring());

        // FLAG_MUTABLE, FILL_IN_ACTION and the three of names, which a copy loses, while other PendingIntents keep them
        String flags = "local param = screenof({ [2] = %s, [3] = 0x2000000 + 1 + 8 + 16 + 64 })\n" +
                "before(hookof('getActivity'), param)\n" +
                "return param.args[3]";
        assertEquals(0x2000001, Scripts.run(globals, String.format(flags, "intentof(RECOGNIZE)")).arg(1).toint());
        assertEquals(0x2000059, Scripts.run(globals, String.format(flags, "intentof('android.intent.action.VIEW')")).arg(1).toint());

        Varargs screens = Scripts.run(globals,
                "local own = intentof(nil, nil, componentof('com.example.app', '.Main'))\n" +
                        "local recognizer = intentof(RECOGNIZE)\n" +
                        "local intents = arrayof(own, recognizer)\n" +
                        "local param = screenof({ [2] = intents, [3] = 16 })\n" +
                        "local restricted = before(hookof('getActivities'), param)\n" +
                        "local copies = param.args[2]\n" +
                        "return restricted, copies ~= intents and copies[1] == own, copies[2].pkg == nil and copies[2].selector.pkg,\n" +
                        "  recognizer.selector == nil and intents[2] == recognizer, param.args[3]");
        assertTrue(screens.arg(1).toboolean());
        assertTrue(screens.arg(2).toboolean());
        assertEquals("com.example.app", screens.arg(3).tojstring());
        assertTrue(screens.arg(4).toboolean());
        assertEquals(0, screens.arg(5).toint());
    }

    // A fill-in with the action of a recognizer, also in its selector, which the app gives when it sends a
    // PendingIntent or starts one, gets the package of the app and no selector, so the filled intent stays in the
    // app, and a component of another app a name which cannot exist; other fill-ins stay, and so do those which stay
    // in the app already, like the copy of a call which another hooked one makes
    @Test
    public void recognizerFillInsStayInTheApp() throws Exception {
        Globals globals = load("recognizer_intent");
        Scripts.run(globals, RECOGNIZER);
        String calls = "local results = {}\n" +
                "for _, call in ipairs({ { 'startIntentSenderForResultInner', 3 }, { 'startIntentSender', 1 },\n" +
                "    { 'send', 2 }, { 'sendAndReturnResult', 2 }, { 'sendIntent', 2 } }) do\n" +
                "  table.insert(results, sent(call[1], call[2], %s))\n" +
                "end\n" +
                "return table.concat(results, ',')";
        String[][] cases = {
                {"intentof(RECOGNIZE)", "true:com.example.app:nil:nil:true"},
                {"intentof('android.provider.MediaStore.RECORD_SOUND')", "true:com.example.app:nil:nil:true"},
                {"intentof(RECOGNIZE, 'com.google.android.tts')", "true:com.example.app:nil:nil:true"},
                {"intentof(RECOGNIZE, nil, componentof('com.google.android.tts', '.Voice'))",
                        "true:com.example.app:com.google.android.tts:hidden/.Voice:nil:true"},
                {"intentof('x', nil, nil, intentof(RECOGNIZE, 'com.google.android.tts'))", "true:com.example.app:nil:nil:true"},
                {"intentof(RECOGNIZE, nil, nil, intentof('android.intent.action.MAIN'))", "true:com.example.app:nil:nil:true"},
                {"intentof(RECOGNIZE, nil, componentof('com.example.app', '.Voice'))",
                        "true:com.example.app:com.example.app/.Voice:nil:true"},
                // Stays: in the app already, also a copy, other fill-ins and none
                {"intentof(RECOGNIZE, 'com.example.app')", "false:com.example.app:nil:nil:true"},
                {"intentof(RECOGNIZE, 'com.example.app', componentof('com.google.android.tts:hidden', '.Voice'))",
                        "false:com.example.app:com.google.android.tts:hidden/.Voice:nil:true"},
                {"intentof('android.intent.action.VIEW')", "false:nil:nil:nil:true"},
                {"intentof('android.intent.action.VIEW', nil, nil, intentof('android.intent.action.MAIN'))",
                        "false:nil:nil:nil:true"},
                {"nil", "false:nil:nil:nil:true"},
        };
        for (String[] test : cases) {
            String expected = String.join(",", test[1], test[1], test[1], test[1], test[1]);
            assertEquals(test[0], expected, Scripts.run(globals, String.format(calls, test[0])).arg(1).tojstring());
        }
    }

    // A chooser around a recognizer of another app offers none: a copy of the chooser with copies of its intent and
    // of the recognizers among its initial intents, while the chooser of the app keeps its own intents and gets only
    // the referrer back; no text changes, because Android starts the chooser, and choosers of other screens stay
    @Test
    public void recognizerChoosersOfferNone() throws Exception {
        Globals globals = load("recognizer_intent");
        Scripts.run(globals, RECOGNIZER);
        Varargs result = Scripts.run(globals,
                "local inner = intentof(RECOGNIZE)\n" +
                        "local other = intentof('android.intent.action.VIEW')\n" +
                        "local explicit = intentof(RECOGNIZE, nil, componentof('com.google.android.tts', '.Voice'))\n" +
                        "local initial = arrayof(other, explicit)\n" +
                        "local chooser = intentof('android.intent.action.CHOOSER')\n" +
                        "chooser.extras['android.intent.extra.INTENT'] = inner\n" +
                        "chooser.extras['android.intent.extra.INITIAL_INTENTS'] = initial\n" +
                        "local param = screenof({ [4] = chooser })\n" +
                        "local restricted = before(hookof('execStartActivity'), param)\n" +
                        "local copy = param.args[4]\n" +
                        "copy.extras['android.intent.extra.REFERRER'] = 'referrer'\n" +
                        "param.exception = exceptionof('Unable to find explicit activity class')\n" +
                        "after(hookof('execStartActivity'), param)\n" +
                        "local offered = copy.extras['android.intent.extra.INITIAL_INTENTS']\n" +
                        "local viewer = intentof('android.intent.action.CHOOSER')\n" +
                        "viewer.extras['android.intent.extra.INTENT'] = other\n" +
                        "local unchanged = before(hookof('execStartActivity'), screenof({ [4] = viewer }))\n" +
                        "return restricted, copy ~= chooser and copy.action, copy.extras['android.intent.extra.INTENT'].pkg,\n" +
                        "  offered.type .. ':' .. tostring(offered ~= initial and offered[1] == other) .. ':' ..\n" +
                        "  offered[2].component:getPackageName(),\n" +
                        "  chooser.extras['android.intent.extra.INTENT'] == inner and inner.pkg == nil and initial[2] == explicit and\n" +
                        "  chooser.extras['android.intent.extra.INITIAL_INTENTS'] == initial and explicit.component:getPackageName(),\n" +
                        "  chooser.extras['android.intent.extra.REFERRER'], param.set, unchanged");
        assertTrue(result.arg(1).toboolean());
        assertEquals("android.intent.action.CHOOSER", result.arg(2).tojstring());
        assertEquals("com.example.app", result.arg(3).tojstring());
        assertEquals("Parcelable:true:com.google.android.tts:hidden", result.arg(4).tojstring());
        assertEquals("com.google.android.tts", result.arg(5).tojstring());
        assertEquals("referrer", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
    }

    @Test
    public void recognizerReplaceLikePackageProbe() throws Exception {
        assertEquals(Scripts.function("package_probe", "replace"), Scripts.function("recognizer_intent", "replace"));
    }

    // The addresses, DNS servers, routes, search domains and MTU of a VPN are the ones of the network under it, found by
    // the interface the hook of vpn gives the VPN among the real names and read through the hooks, also where the VPN has
    // none; without that network they are left out
    @Test
    public void vpnPropertiesOfNetworkUnder() throws Exception {
        Globals globals = load("vpn_addresses");
        Varargs result = Scripts.run(globals,
                "local function list(name, empty) return { name = name, isEmpty = function() return empty end } end\n" +
                        "local wifi = { getInterfaceName = function() return 'wlan0' end,\n" +
                        "  getLinkAddresses = function() assert(not unhooked, 'unhooked') return list('wifi addresses') end,\n" +
                        "  getDnsServers = function() return list('wifi dns') end,\n" +
                        // Like Read.Network empties them
                        "  getRoutes = function() return list('emptied', true) end,\n" +
                        "  getDomains = function() return nil end,\n" +
                        "  getMtu = function() return 1500 end }\n" +
                        "local function tunnel(fake)\n" +
                        "  return { getInterfaceName = function() return unhooked and 'tun0' or fake end }\n" +
                        "end\n" +
                        "local tun = tunnel('wlan0')\n" +
                        // An IWLAN network, which the hooks name like the network under the VPN, listed first like in the
                        // test of the text of the VPN (see NetworkScriptsTest)
                        "local ims = { getInterfaceName = function() return unhooked and 'ipsec1' or 'wlan0' end }\n" +
                        "local cm = { getAllNetworks = function() assert(unhooked, 'hooked')\n" +
                        "    return { length = 3, 'ims', 'vpn', 'wifi' } end,\n" +
                        "  getLinkProperties = function(self, network) return ({ ims = ims, vpn = tun })[network] or wifi end }\n" +
                        "local context = { getSystemService = function(self, name) assert(name == 'connectivity') return cm end }\n" +
                        "local function call(method, value, this)\n" +
                        "  local param = paramof(value, {}, this or tun)\n" +
                        "  param.getApplicationContext = function() return context end\n" +
                        "  local restricted = after(hookof(method), param)\n" +
                        "  local shown = (type(param.result) == 'table' and (param.result.name or param.result.class) or param.result)\n" +
                        "  return tostring(restricted) .. ':' .. tostring(shown)\n" +
                        "end\n" +
                        "local none = tunnel('rmnet_data0')\n" +
                        "local kept = tunnel('tun0')\n" +
                        "return call('getLinkAddresses', list('tunnel addresses')), call('getDnsServers', list('', true)),\n" +
                        "  call('getRoutes', list('tunnel routes')), call('getDomains', 'corp.example'), call('getDomains', nil),\n" +
                        "  call('getMtu', 1420), call('getMtu', 1500), call('getLinkAddresses', list('wifi addresses'), wifi),\n" +
                        "  call('getLinkAddresses', list('tunnel addresses'), none), call('getMtu', 1420, none),\n" +
                        "  call('getDnsServers', list('', true), none), call('getDomains', 'corp.example', none),\n" +
                        "  call('getRoutes', list('tunnel routes'), kept)");
        assertEquals("true:wifi addresses", result.arg(1).tojstring());
        // A VPN without DNS servers has the ones of the network under it too
        assertEquals("true:wifi dns", result.arg(2).tojstring());
        assertEquals("true:emptied", result.arg(3).tojstring());
        assertEquals("true:nil", result.arg(4).tojstring());
        assertEquals("false:nil", result.arg(5).tojstring());
        assertEquals("true:1500", result.arg(6).tojstring());
        assertEquals("false:1500", result.arg(7).tojstring());
        // The network under the VPN itself
        assertEquals("false:wifi addresses", result.arg(8).tojstring());
        // Without the network of the interface the VPN has
        assertEquals("true:java.util.ArrayList", result.arg(9).tojstring());
        assertEquals("true:0", result.arg(10).tojstring());
        assertEquals("false:", result.arg(11).tojstring());
        assertEquals("true:nil", result.arg(12).tojstring());
        // The hook of vpn keeps the name of the tunnel, which would find the VPN itself
        assertEquals("true:java.util.ArrayList", result.arg(13).tojstring());
    }

    // The addresses and DNS servers of a VPN are left out, by its real interface; other networks and the calls of
    // LinkProperties itself keep them
    @Test
    public void vpnAddresses() throws Exception {
        Globals globals = load("vpn_addresses");
        Varargs result = Scripts.run(globals,
                "local function list() return { isEmpty = function() return false end } end\n" +
                        "local function properties(name)\n" +
                        "  return { getInterfaceName = function() assert(unhooked, 'hooked') return name end }\n" +
                        "end\n" +
                        "local vpn = paramof(list(), {}, properties('tun0'))\n" +
                        "local wifi = paramof(list(), {}, properties('wlan0'))\n" +
                        "local restricted = after(hookof('getLinkAddresses'), vpn)\n" +
                        "local kept = after(hookof('getDnsServers'), wifi)\n" +
                        "FRAMES = { length = 1, { getClassName = function() return 'android.net.LinkProperties' end,\n" +
                        "  getMethodName = function() return 'equals' end } }\n" +
                        "local own = after(hookof('getLinkAddresses'), paramof(list(), {}, properties('tun0')))\n" +
                        // Marked by vpn_parcel, which fakes its parcel, whatever frames the hook framework shows
                        "local tun = properties('tun0')\n" +
                        "local marked = paramof(list(), {}, tun)\n" +
                        "marked:putValue('vpn.parcel', true, tun)\n" +
                        "local parcelled = after(hookof('getLinkAddresses'), marked)\n" +
                        "return restricted, vpn.result.class, kept, wifi.set, own, parcelled");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.util.ArrayList", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
    }

    // The HTTP proxy of a VPN, a ProxyInfo without isEmpty, is null; other networks and the calls of LinkProperties
    // itself keep it
    @Test
    public void vpnHttpProxy() throws Exception {
        Globals globals = load("vpn_addresses");
        Varargs result = Scripts.run(globals,
                "local function properties(name)\n" +
                        "  return { getInterfaceName = function() assert(unhooked, 'hooked') return name end }\n" +
                        "end\n" +
                        "local proxy = { getHost = function() return '127.0.0.1' end }\n" +
                        "local vpn = paramof(proxy, {}, properties('tun0'))\n" +
                        "local wifi = paramof(proxy, {}, properties('wlan0'))\n" +
                        "local none = paramof(nil, {}, properties('tun0'))\n" +
                        "local restricted = after(hookof('getHttpProxy'), vpn)\n" +
                        "local kept = after(hookof('getHttpProxy'), wifi)\n" +
                        "local absent = after(hookof('getHttpProxy'), none)\n" +
                        "FRAMES = { length = 1, { getClassName = function() return 'android.net.LinkProperties' end,\n" +
                        "  getMethodName = function() return 'isIdenticalHttpProxy' end } }\n" +
                        "local own = paramof(proxy, {}, properties('tun0'))\n" +
                        "return restricted, vpn.set, vpn.result, kept, wifi.set, absent, none.set,\n" +
                        "  after(hookof('getHttpProxy'), own), own.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
    }

    // Without a return type: false for a boolean result, else nothing, which the type check of XParam decides
    @Test
    public void blockWithoutReturnType() throws Exception {
        Globals globals = load("generic_block_untyped");
        Varargs result = Scripts.run(globals,
                "local function checked(accepts)\n" +
                        "  local param = paramof()\n" +
                        "  param.setResult = function(self, value)\n" +
                        "    if value ~= nil and not accepts then error('Invalid result') end\n" +
                        "    self.result = value self.set = true\n" +
                        "  end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local boolean = checked(true)\n" +
                        "local void = checked(false)\n" +
                        "return before(hookof('initialize'), boolean), boolean.result, before(hookof('initialize'), void),\n" +
                        "  void.set, void.result");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).isnil());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).isnil());
    }

    // The luminance of the panel becomes unknown, the HDR types stay; the product of the panel is not known
    @Test
    public void displayHdrAndProduct() throws Exception {
        Globals globals = load("display_panel");
        Varargs result = Scripts.run(globals,
                "local function hdrof(types, max, average, min)\n" +
                        "  return { types = types, getSupportedHdrTypes = function(self) return self.types end,\n" +
                        "    getDesiredMaxLuminance = function() return max end,\n" +
                        "    getDesiredMaxAverageLuminance = function() return average end,\n" +
                        "    getDesiredMinLuminance = function() return min end }\n" +
                        "end\n" +
                        "local written = {}\n" +
                        "local parcel = { writeInt = function(self, v) table.insert(written, v) end,\n" +
                        "  writeFloat = function(self, v) table.insert(written, v) end,\n" +
                        "  setDataPosition = function() end, recycled = false,\n" +
                        "  recycle = function(self) self.recycled = true end }\n" +
                        "CLASSES['android.os.Parcel'] = { obtain = function() return parcel end }\n" +
                        "CLASSES['android.view.Display$HdrCapabilities'] = { CREATOR = { createFromParcel = function(self, p)\n" +
                        "  local count = written[1]\n" +
                        "  local types = { length = count }\n" +
                        "  for i = 1, count do types[i] = written[1 + i] end\n" +
                        "  return hdrof(types, written[count + 2], written[count + 3], written[count + 4]) end } }\n" +
                        "local param = paramof(hdrof({ length = 2, 2, 3 }, 1000, 500, 0.0005))\n" +
                        "local restricted = after(hookof('getHdrCapabilities'), param)\n" +
                        "local fake = param.result\n" +
                        "local unknown = after(hookof('getHdrCapabilities'), paramof(hdrof({ length = 0 }, -1, -1, -1)))\n" +
                        "local product = paramof({})\n" +
                        "return restricted, fake.types.length, fake.types[2], fake:getDesiredMaxLuminance(),\n" +
                        "  fake:getDesiredMinLuminance(), parcel.recycled, unknown,\n" +
                        "  after(hookof('getDeviceProductInfo'), product), product.set, product.result,\n" +
                        "  after(hookof('getDeviceProductInfo'), paramof(nil))");
        assertTrue(result.arg(1).toboolean());
        assertEquals(2, result.arg(2).toint());
        assertEquals(3, result.arg(3).toint());
        assertEquals(-1, result.arg(4).toint());
        assertEquals(-1, result.arg(5).toint());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        assertTrue(result.arg(10).isnil());
        assertFalse(result.arg(11).toboolean());
    }

    // The configuration of CarrierConfigManager has the MMS user agent of the carrier too
    @Test
    public void carrierConfigMms() throws Exception {
        Globals globals = load("mms_useragent");
        for (String method : new String[]{"getConfig", "getConfigForSubId"}) {
            Varargs result = Scripts.run(globals,
                    "local values = { userAgent = 'SAMSUNG-SM-G991B', uaProfUrl = 'http://wap.samsungmobile.com/uaprof/SM-G991B.xml' }\n" +
                            "local bundle = { getString = function(self, key) return values[key] end,\n" +
                            "  getBoolean = function(self, key) return values[key] == true end,\n" +
                            "  putString = function(self, key, value) values[key] = value end }\n" +
                            "return after(hookof('" + method + "'), paramof(bundle)), values.userAgent, values.uaProfUrl");
            assertTrue(method, result.arg(1).toboolean());
            assertEquals(method, "Android-Mms/2.0", result.arg(2).tojstring());
            assertEquals(method, "http://www.google.com/oha/rdf/ua-profile-kila.xml", result.arg(3).tojstring());
        }
    }
}
