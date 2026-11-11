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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of files of providers (assets/contentresolver_open.lua) with Lua stand-ins, like LocationScriptTest;
// the exception is a stand-in, because it is of java.io, which luajava cannot use on a desktop JVM, and the test
// reads its class and message back; the uris which the script builds are text
public class ContentResolverOpenScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(name, method, class)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class end }",
            "end",
            // A uri with its path as a java.util.List
            "function uriof(text)",
            "  local scheme, authority, path = string.match(text, '^([^:]+)://([^/]*)(.*)$')",
            "  local segments = {}",
            "  for segment in string.gmatch(path, '[^/]+') do table.insert(segments, segment) end",
            "  return { getScheme = function(self) return scheme end,",
            "    getAuthority = function(self) return authority end,",
            "    getEncodedAuthority = function(self) return authority end,",
            "    getPathSegments = function(self)",
            "      return { size = function(self) return #segments end,",
            "        get = function(self, index) return segments[index + 1] end }",
            "    end,",
            "    toString = function(self) return text end }",
            "end",
            // The queries of the contacts the provider finds, which are a uri and a selection; without them
            // the queries fail. The permissions the app holds, all without a list, with the flag of a grant
            // of the uri, the default and the system dialer (false when the method is hidden) and the app op of
            // writing SMS; the checks of the permissions and the grants are kept in order
            "function paramof(uri, mode, starred, found, held)",
            "  local queries, checks = {}, {}",
            "  local resolver = { query = function(self, uri, projection, where, args, order)",
            "    local query = uri .. ' ' .. where",
            "    table.insert(queries, query)",
            "    if found == nil then error('Permission Denial: reading com.android.providers.contacts') end",
            "    local count = 0",
            "    for _, row in ipairs(found) do if row == query then count = 1 end end",
            "    return { getCount = function(self) return count end, close = function(self) end }",
            "  end }",
            "  local context = { getContentResolver = function(self) return resolver end,",
            "    getPackageName = function(self) return 'com.example' end,",
            "    getSystemService = function(self, service)",
            "      if service == 'telecom' then",
            "        return { getDefaultDialerPackage = function(self) return held ~= nil and held.dialer or nil end,",
            "          getSystemDialerPackage = function(self)",
            "            if held ~= nil and held.system == false then error('NoSuchMethodError') end",
            "            return held ~= nil and held.system or nil",
            "          end }",
            "      end",
            "      assert(service == 'appops', service)",
            "      return { checkOpNoThrow = function(self, op, uid, package)",
            "        assert(op == 'android:write_sms' and uid == 10123 and package == 'com.example', op)",
            "        return (held == nil or held.sms) and 0 or 1",
            "      end }",
            "    end,",
            "    checkSelfPermission = function(self, permission)",
            "      table.insert(checks, permission)",
            "      if held == nil then return 0 end",
            "      for _, name in ipairs(held) do if name == permission then return 0 end end",
            "      return -1",
            "    end,",
            "    checkUriPermission = function(self, uri, pid, uid, flag)",
            "      assert(pid == 1234 and uid == 10123, pid .. ' ' .. uid)",
            "      table.insert(checks, uri:toString() .. ' ' .. flag)",
            "      return (held.grant == flag) and 0 or -1",
            "    end }",
            "  local arguments = { [0] = uriof(uri), [1] = mode }",
            "  return { queries = queries, checks = checks,",
            "    getArgument = function(self, index) return arguments[index] end,",
            "    getSetting = function(self, name) assert(name == 'contacts.starred', name) return starred end,",
            "    getApplicationContext = function(self) return context end,",
            "    unhooked = function(self, run) return run() end,",
            "    setResult = function(self, value) self.result = value end }",
            "end",
            // The pipe of the last vCard, which fails without a pipe, and the version of Android
            "pipes = true",
            "sdk = 34",
            "luajava = {",
            "  newInstance = function(name, ...)",
            "    if name == 'android.net.Uri$Builder' then",
            "      local builder = { path = '' }",
            "      function builder:scheme(value) self.s = value return self end",
            "      function builder:encodedAuthority(value) self.a = value return self end",
            "      function builder:appendPath(value) self.path = self.path .. '/' .. value return self end",
            "      function builder:build() return self.s .. '://' .. self.a .. self.path end",
            "      return builder",
            "    end",
            "    local args = { ... }",
            "    return { name = name, message = args[1], args = args }",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'android.os.Process' then",
            "      return { myPid = function(self) return 1234 end, myUid = function(self) return 10123 end }",
            "    elseif name == 'android.os.ParcelFileDescriptor' then",
            "      return { createPipe = function(self)",
            "        if not pipes then error('java.io.IOException: Too many open files') end",
            "        pipe = { { side = 'read' }, { side = 'write', close = function(self) self.closed = true end } }",
            "        return pipe",
            "      end }",
            "    elseif name == 'android.os.Build$VERSION' then",
            "      return { SDK_INT = sdk }",
            "    elseif name == 'android.content.ClipDescription' then",
            // Like ClipDescription.compareMimeTypes
            "      return { compareMimeTypes = function(self, concrete, desired)",
            "        if desired == '*/*' then return true end",
            "        local prefix = string.match(desired, '^([^/]+/)%*$')",
            "        if prefix ~= nil then return string.sub(concrete, 1, #prefix) == prefix end",
            "        return string.find(desired, '/', 2, true) ~= nil and desired == concrete",
            "      end }",
            "    end",
            "    assert(name == 'android.net.Uri', name)",
            "    return { decode = function(self, text)",
            "      return (string.gsub(text, '%%(%x%x)', function(hex) return string.char(tonumber(hex, 16)) end))",
            "    end }",
            "  end }");

    private static final String RESOLVER = "android.content.ContentResolver";
    private static final String CLIENT = "android.content.ContentProviderClient";

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("contentresolver_open", STUBS);
    }

    // Whether restricted, the class and the message of the exception, the queries and the checks of permissions,
    // separated by a comma; the setting, the rows which the queries find and the permissions are Lua expressions
    private Varargs open(String hook, String className, String uri, String mode, String starred, String found,
                         String held) {
        String method = hook.substring(hook.indexOf('.') + 1, hook.indexOf('/'));
        return Scripts.run(globals,
                "local param = paramof('" + uri + "', " + mode + ", " + starred + ", " + found + ", " + held + ")\n" +
                        "local restricted = before(hookof('" + hook + "', '" + method + "', '" + className + "'), param)\n" +
                        "local queries, checks = table.concat(param.queries, ','), table.concat(param.checks, ',')\n" +
                        "if param.result == nil then return restricted, nil, nil, queries, checks end\n" +
                        "return restricted, param.result.name, param.result.message, queries, checks");
    }

    private Varargs open(String hook, String className, String uri, String mode, String starred, String found) {
        return open(hook, className, uri, mode, starred, found, "nil");
    }

    private Varargs open(String hook, String className, String uri, String mode) {
        return open(hook, className, uri, mode, "nil", "{}");
    }

    private Varargs held(String hook, String uri, String mode, String held) {
        return open(hook, hook.startsWith("ContentResolver.") ? RESOLVER : CLIENT, uri, mode, "nil", "{}", held);
    }

    // Attachments of MMS messages and recordings of voicemails are not found, like their rows,
    // the voicemails also with the user in the authority, which the provider does not get
    @Test
    public void attachmentIsNotFound() {
        Varargs result = open("ContentResolver.openAssetFileDescriptor/mmssms", RESOLVER,
                "content://mms/part/1", "'r'");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.io.FileNotFoundException", result.arg(2).tojstring());
        assertEquals("No entry for content://mms/part/1", result.arg(3).tojstring());

        Varargs voicemail = open("ContentResolver.openTypedAssetFileDescriptor/mmssms", RESOLVER,
                "content://0@com.android.voicemail/voicemail/1", "'vnd.android.cursor.item/*'");
        assertTrue(voicemail.arg(1).toboolean());
        assertEquals("No entry for content://com.android.voicemail/voicemail/1", voicemail.arg(3).tojstring());

        // The second argument of the typed methods is a type
        Varargs photo = open("ContentProviderClient.openTypedAssetFile/contacts", CLIENT,
                "content://com.android.contacts/contacts/1/display_photo", "'image/*'");
        assertTrue(photo.arg(1).toboolean());
    }

    // MmsProvider opens only parts, and with the user in the uri none, since it keeps the user (singleUser), so
    // the uri does not match and it answers null; MmsSmsProvider opens nothing, ContactsProvider2 has no openFile
    // and nothing of the former contacts API, and CallLogProvider opens only call composer pictures and only for r
    // or w, and opens or refuses its folders . and all_users and its parent ..: the providers answer these themselves,
    // whatever is there
    @Test
    public void providersAnswerWithoutFiles() {
        String mms = "ContentProviderClient.openFile/mmssms";
        assertFalse(open(mms, CLIENT, "content://mms/1", "'r'").arg1().toboolean());
        assertFalse(open(mms, CLIENT, "content://mms/part/1/x", "'r'").arg1().toboolean());
        assertFalse(open(mms, CLIENT, "content://0@mms/part/1", "'r'").arg1().toboolean());
        assertFalse(open(mms, CLIENT, "content://mms-sms/conversations/1", "'r'").arg1().toboolean());
        assertFalse(open("ContentProviderClient.openFile/contacts", CLIENT,
                "content://com.android.contacts/contacts/1/photo", "'r'").arg1().toboolean());
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://contacts/people/1/photo", "'r'").arg1().toboolean());
        String calls = "ContentResolver.openAssetFileDescriptor/call_log";
        assertFalse(open(calls, RESOLVER, "content://call_log/calls/1", "'r'").arg1().toboolean());
        assertFalse(open(calls, RESOLVER, "content://call_log/call_composer/a/b", "'r'").arg1().toboolean());
        assertFalse(open(calls, RESOLVER, "content://call_log/call_composer/a", "'rw'").arg1().toboolean());
        assertFalse(open(calls, RESOLVER, "content://call_log/call_composer/a", "'wt'").arg1().toboolean());
        for (String folder : new String[]{".", "..", "all_users"}) {
            assertFalse(folder, open(calls, RESOLVER, "content://call_log/call_composer/" + folder, "'r'").arg1().toboolean());
        }
    }

    // The answers of the providers for a file which is not there, with the uri without the user: of CallLogProvider
    // for a call composer picture, of ContactsProvider2 for a contact, a photo row and a photo file, the file 0 for
    // the photo of a raw contact which is not there
    @Test
    public void answersOfTheProviders() {
        Varargs picture = open("ContentProviderClient.openTypedAssetFile/call_log", CLIENT,
                "content://0@call_log/call_composer/a.jpg", "'*/*'");
        assertTrue(picture.arg(1).toboolean());
        assertEquals("java.io.FileNotFoundException", picture.arg(2).tojstring());
        assertEquals("content://call_log/call_composer/a.jpg does not correspond to a valid file.", picture.arg(3).tojstring());

        String hook = "ContentResolver.openAssetFileDescriptor/contacts";
        assertEquals("content://com.android.contacts/contacts/1/photo",
                open(hook, RESOLVER, "content://0@com.android.contacts/contacts/1/photo", "'r'").arg(3).tojstring());
        assertEquals("content://com.android.contacts/data/1",
                open(hook, RESOLVER, "content://com.android.contacts/data/1", "'r'").arg(3).tojstring());
        assertEquals("No photo file found for ID 7",
                open(hook, RESOLVER, "content://com.android.contacts/display_photo/007", "'r'").arg(3).tojstring());
        assertEquals("No photo file found for ID 0",
                open(hook, RESOLVER, "content://com.android.contacts/display_photo/0", "'r'").arg(3).tojstring());
        assertEquals("No photo file found for ID 0",
                open(hook, RESOLVER, "content://com.android.contacts/raw_contacts/3/display_photo", "'r'").arg(3).tojstring());
    }

    // A typed open compares the type which the provider gives a file which is not there with the filter before it
    // opens anything (ContentProvider.openTypedAssetFile): application/octet-stream for call composer pictures, */*
    // for parts of MMS, the type of one voicemail, image/jpeg for contact photos, text/x-vcard for vCards and none
    // for photo rows
    @Test
    public void typedOpensCompareTheType() {
        String[][] cases = {
                {"call_log", "content://0@call_log/call_composer/a.jpg", "image/*",
                        "Can't open content://call_log/call_composer/a.jpg as type image/*"},
                {"call_log", "content://call_log/call_composer/a.jpg", "application/*",
                        "content://call_log/call_composer/a.jpg does not correspond to a valid file."},
                {"mmssms", "content://mms/part/1", "image/*", "Can't open content://mms/part/1 as type image/*"},
                {"mmssms", "content://mms/part/1", "*/*", "No entry for content://mms/part/1"},
                {"mmssms", "content://0@com.android.voicemail/voicemail/1", "audio/*",
                        "Can't open content://com.android.voicemail/voicemail/1 as type audio/*"},
                {"mmssms", "content://com.android.voicemail/voicemail/1", "vnd.android.cursor.item/voicemail",
                        "No entry for content://com.android.voicemail/voicemail/1"},
                {"contacts", "content://com.android.contacts/contacts/1/photo", "image/*",
                        "content://com.android.contacts/contacts/1/photo"},
                {"contacts", "content://com.android.contacts/display_photo/1", "image/jpeg", "No photo file found for ID 1"},
                {"contacts", "content://com.android.contacts/contacts/1/photo", "text/*",
                        "Can't open content://com.android.contacts/contacts/1/photo as type text/*"},
                {"contacts", "content://com.android.contacts/contacts/as_vcard/k", "image/*",
                        "Can't open content://com.android.contacts/contacts/as_vcard/k as type image/*"},
                {"contacts", "content://com.android.contacts/data/1", "image/*",
                        "Can't open content://com.android.contacts/data/1 as type image/*"},
        };
        for (String[] c : cases) {
            Varargs result = open("ContentResolver.openTypedAssetFileDescriptor/" + c[0], RESOLVER, c[1], "'" + c[2] + "'");
            assertTrue(c[1], result.arg(1).toboolean());
            assertEquals(c[1], "java.io.FileNotFoundException", result.arg(2).tojstring());
            assertEquals(c[1] + " " + c[2], c[3], result.arg(3).tojstring());
        }

        Varargs vcard = open("ContentProviderClient.openTypedAssetFile/contacts", CLIENT,
                "content://com.android.contacts/contacts/as_vcard/k", "'text/*'");
        assertEquals("android.content.res.AssetFileDescriptor", vcard.arg(2).tojstring());
    }

    // A vCard of contacts which are not there is empty, a pipe whose writing end ContactsProvider2 closes at once,
    // of any length; without a pipe it answers null
    @Test
    public void vCardIsEmpty() {
        for (String uri : new String[]{"contacts/as_vcard/k1", "contacts/as_multi_vcard/k1:k2", "profile/as_vcard"}) {
            Varargs result = Scripts.run(globals,
                    "local param = paramof('content://com.android.contacts/" + uri + "', 'r', nil, {})\n" +
                            "local restricted = before(hookof('ContentProviderClient.openAssetFile/contacts', " +
                            "'openAssetFile', '" + CLIENT + "'), param)\n" +
                            "local args = param.result.args\n" +
                            "return restricted, param.result.name, args[1] == pipe[1], pipe[2].closed, args[2], args[3]");
            assertTrue(uri, result.arg(1).toboolean());
            assertEquals(uri, "android.content.res.AssetFileDescriptor", result.arg(2).tojstring());
            assertTrue(uri, result.arg(3).toboolean());
            assertTrue(uri, result.arg(4).toboolean());
            assertEquals(uri, 0, result.arg(5).toint());
            assertEquals(uri, -1, result.arg(6).toint());
        }

        Varargs failed = Scripts.run(globals, "pipes = false\n" +
                "local param = paramof('content://com.android.contacts/contacts/as_vcard/k1', 'r', nil, {})\n" +
                "return before(hookof('ContentResolver.openAssetFileDescriptor/contacts', 'openAssetFileDescriptor', '" +
                RESOLVER + "'), param), param.result == nil");
        assertTrue(failed.arg(1).toboolean());
        assertTrue(failed.arg(2).toboolean());
    }

    // Without the permission of the provider, of writing for modes with w, and without a grant of the uri Android
    // throws a SecurityException before the provider is called; with the grant the file is not found
    @Test
    public void withoutPermissionAndroidRefuses() {
        String contacts = "ContentResolver.openAssetFileDescriptor/contacts";
        String photo = "content://com.android.contacts/contacts/1/photo";
        Varargs denied = held(contacts, photo, "'r'", "{}");
        assertFalse(denied.arg(1).toboolean());
        assertEquals("android.permission.READ_CONTACTS," + photo + " 1", denied.arg(5).tojstring());
        assertTrue(held(contacts, photo, "'r'", "{ grant = 1 }").arg1().toboolean());
        assertTrue(held(contacts, photo, "'r'", "{ 'android.permission.READ_CONTACTS' }").arg1().toboolean());

        String mms = "ContentProviderClient.openAssetFile/mmssms";
        assertFalse(held(mms, "content://mms/part/1", "'r'", "{}").arg1().toboolean());
        assertTrue(held(mms, "content://mms/part/1", "'r'", "{ 'android.permission.READ_SMS' }").arg1().toboolean());
        // Writing MMS needs the app op of the default SMS app, without it Android says "App op not allowed"
        for (String mode : new String[]{"'rw'", "'w'"}) {
            assertFalse(mode, held(mms, "content://mms/part/1", mode, "{ 'android.permission.READ_SMS' }").arg1().toboolean());
            assertTrue(mode, held(mms, "content://mms/part/1", mode, "{ sms = true }").arg1().toboolean());
        }
        // The permissions of Google Messages are not known
        assertTrue(held(mms, "content://com.google.android.apps.messaging.shared.datamodel.BugleContentProvider/1",
                "'r'", "{}").arg1().toboolean());

        String calls = "ContentResolver.openTypedAssetFileDescriptor/call_log";
        assertFalse(held(calls, "content://call_log/call_composer/a", "'image/*'", "{}").arg1().toboolean());
        assertTrue(held(calls, "content://call_log/call_composer/a", "'image/*'",
                "{ 'android.permission.READ_CALL_LOG' }").arg1().toboolean());
        String picture = "ContentResolver.openAssetFileDescriptor/call_log";
        assertFalse(held(picture, "content://call_log/call_composer/a", "'w'",
                "{ 'android.permission.READ_CALL_LOG' }").arg1().toboolean());
        assertTrue(held(picture, "content://call_log/call_composer/a", "'w'",
                "{ 'android.permission.WRITE_CALL_LOG' }").arg1().toboolean());

        // VoicemailContentProvider opens the voicemails of all apps for the default and the system dialer, with
        // READ_VOICEMAIL for r and also WRITE_VOICEMAIL for other modes; other apps, also with a grant,
        // ADD_VOICEMAIL or only WRITE_VOICEMAIL, get a SecurityException or only their own voicemails
        String voicemails = "ContentProviderClient.openFile/mmssms";
        String voicemail = "content://com.android.voicemail/voicemail/1";
        String read = "'com.android.voicemail.permission.READ_VOICEMAIL'";
        String write = "'com.android.voicemail.permission.WRITE_VOICEMAIL'";
        String add = "'com.android.voicemail.permission.ADD_VOICEMAIL'";
        assertFalse(held(voicemails, voicemail, "'r'", "{ dialer = 'com.android.dialer' }").arg1().toboolean());
        assertTrue(held(voicemails, voicemail, "'rw'", "{ dialer = 'com.example' }").arg1().toboolean());
        assertTrue(held(voicemails, voicemail, "'w'", "{ system = 'com.example' }").arg1().toboolean());
        // Hidden before Android 10
        assertFalse(held(voicemails, voicemail, "'r'", "{ system = false }").arg1().toboolean());
        assertTrue(held(voicemails, voicemail, "'r'", "{ " + read + " }").arg1().toboolean());
        assertFalse(held(voicemails, voicemail, "'rw'", "{ " + read + " }").arg1().toboolean());
        assertTrue(held(voicemails, voicemail, "'w'", "{ " + read + ", " + write + " }").arg1().toboolean());
        assertFalse(held(voicemails, voicemail, "'r'", "{ grant = 1 }").arg1().toboolean());
        assertFalse(held(voicemails, voicemail, "'r'", "{ " + add + ", " + write + " }").arg1().toboolean());
        assertFalse(held(voicemails, voicemail, "'rw'", "{ " + add + ", " + write + " }").arg1().toboolean());

        // Without a mode or a type Android throws a NullPointerException
        assertFalse(held(voicemails, voicemail, "nil", "nil").arg1().toboolean());
        assertFalse(held("ContentResolver.openTypedAssetFileDescriptor/contacts", photo, "nil", "nil").arg1().toboolean());
    }

    // Other providers and other groups stay
    @Test
    public void otherFilesStay() {
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://com.example.provider/file", "'r'").arg1().toboolean());
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://mms/part/1", "'r'").arg1().toboolean());
    }

    // ContactsProvider2 refuses every mode but r before it reads ("can only be read", "Write is not supported",
    // "Mode rw not supported"), but for the display photo of a raw contact, which it writes for every other mode,
    // like the rw of RawContacts.DisplayPhoto, and as of Android 12 only for modes with w, reading for the others;
    // MmsProvider and VoicemailContentProvider look for the file for every mode
    @Test
    public void modesOfTheProviders() {
        String hook = "ContentResolver.openAssetFileDescriptor/contacts";
        for (String uri : new String[]{"contacts/1/photo", "contacts/1/display_photo", "display_photo/1",
                "contacts/lookup/k/photo", "contacts/as_vcard/k", "data/1"}) {
            assertFalse(uri, open(hook, RESOLVER, "content://com.android.contacts/" + uri, "'rw'").arg1().toboolean());
            assertFalse(uri, open(hook, RESOLVER, "content://com.android.contacts/" + uri, "'rt'").arg1().toboolean());
        }

        String raw = "content://com.android.contacts/raw_contacts/3/display_photo";
        assertTrue(open(hook, RESOLVER, raw, "'r'").arg1().toboolean());
        assertTrue(open("ContentResolver.openTypedAssetFileDescriptor/contacts", RESOLVER, raw, "'image/*'").arg1().toboolean());
        Scripts.run(globals, "sdk = 30");
        for (String mode : new String[]{"'rw'", "'w'", "'wt'", "'rt'", "'R'", "''"})
            assertFalse(mode, open(hook, RESOLVER, raw, mode).arg1().toboolean());
        Scripts.run(globals, "sdk = 31");
        for (String mode : new String[]{"'rw'", "'w'", "'wt'"})
            assertFalse(mode, open(hook, RESOLVER, raw, mode).arg1().toboolean());
        for (String mode : new String[]{"'rt'", "'R'", "''"}) {
            Varargs read = open(hook, RESOLVER, raw, mode);
            assertTrue(mode, read.arg(1).toboolean());
            assertEquals(mode, "No photo file found for ID 0", read.arg(3).tojstring());
        }

        String mms = "ContentResolver.openAssetFileDescriptor/mmssms";
        for (String mode : new String[]{"'rw'", "'w'", "''"})
            assertTrue(mode, open(mms, RESOLVER, "content://mms/part/1", mode).arg1().toboolean());
        assertTrue(open(mms, RESOLVER, "content://com.android.voicemail/voicemail/1", "'w'").arg1().toboolean());
    }

    // ContentResolver opens resources itself, like of the app com.android.contacts;
    // a ContentProviderClient of the provider of contacts gives it any uri
    @Test
    public void resourcesStay() {
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "android.resource://com.android.contacts/raw/ringtone", "'r'").arg1().toboolean());
        assertTrue(open("ContentProviderClient.openAssetFile/contacts", CLIENT,
                "android.resource://com.android.contacts/contacts/1/photo", "'r'").arg1().toboolean());
    }

    // Files of the contacts which the queries keep with contacts.starred are found, of the same user
    @Test
    public void starredContactFilesAreFound() {
        String found = "{ 'content://10@com.android.contacts/contacts/1 starred = 1'," +
                " 'content://com.android.contacts/contacts/lookup/k1/1 starred = 1'," +
                " 'content://com.android.contacts/contacts/lookup/k1 starred = 1'," +
                " 'content://com.android.contacts/contacts/lookup/k:2 starred = 1'," +
                " 'content://com.android.contacts/raw_contacts/3 starred = 1'," +
                " 'content://com.android.contacts/data starred = 1 AND" +
                " mimetype = \\'vnd.android.cursor.item/photo\\' AND data14 = 4' }";
        String hook = "ContentResolver.openAssetFileDescriptor/contacts";

        Varargs photo = open(hook, RESOLVER, "content://10@com.android.contacts/contacts/1/photo", "'r'", "'1'", found);
        assertFalse(photo.arg(1).toboolean());
        assertEquals("content://10@com.android.contacts/contacts/1 starred = 1", photo.arg(4).tojstring());

        assertFalse(open(hook, RESOLVER, "content://com.android.contacts/contacts/lookup/k1/1/display_photo",
                "'r'", "'1'", found).arg1().toboolean());
        assertFalse(open(hook, RESOLVER, "content://com.android.contacts/raw_contacts/3/display_photo",
                "'r'", "'1'", found).arg1().toboolean());
        assertFalse(open(hook, RESOLVER, "content://com.android.contacts/display_photo/4",
                "'r'", "'1'", found).arg1().toboolean());
        assertFalse(open(hook, RESOLVER, "content://com.android.contacts/contacts/as_vcard/k1",
                "'r'", "'1'", found).arg1().toboolean());

        // The keys of more vCards are encoded once more
        Varargs vcards = open(hook, RESOLVER, "content://com.android.contacts/contacts/as_multi_vcard/k1:k%3A2",
                "'r'", "'1'", found);
        assertFalse(vcards.arg(1).toboolean());
        assertEquals("content://com.android.contacts/contacts/lookup/k1 starred = 1," +
                "content://com.android.contacts/contacts/lookup/k:2 starred = 1", vcards.arg(4).tojstring());
    }

    // Files of other contacts, of the profile, without the setting and without the permission are not found
    @Test
    public void otherContactFilesAreNotFound() {
        String found = "{ 'content://com.android.contacts/contacts/1 starred = 1'," +
                " 'content://com.android.contacts/contacts/lookup/k1 starred = 1' }";
        String hook = "ContentProviderClient.openAssetFile/contacts";

        assertTrue(open(hook, CLIENT, "content://com.android.contacts/contacts/2/photo",
                "'r'", "'1'", found).arg1().toboolean());
        assertTrue(open(hook, CLIENT, "content://com.android.contacts/contacts/1/photo",
                "'r'", "'0'", found).arg1().toboolean());
        assertTrue(open(hook, CLIENT, "content://com.android.contacts/contacts/as_multi_vcard/k1:k2",
                "'r'", "'1'", found).arg1().toboolean());

        Varargs profile = open(hook, CLIENT, "content://com.android.contacts/profile/photo", "'r'", "'1'", found);
        assertTrue(profile.arg(1).toboolean());
        assertEquals("", profile.arg(4).tojstring());

        Varargs unset = open(hook, CLIENT, "content://com.android.contacts/contacts/1/photo", "'r'", "nil", found);
        assertTrue(unset.arg(1).toboolean());
        assertEquals("", unset.arg(4).tojstring());

        Varargs denied = open(hook, CLIENT, "content://com.android.contacts/contacts/1/photo", "'r'", "'1'", "nil");
        assertTrue(denied.arg(1).toboolean());
        assertEquals("java.io.FileNotFoundException", denied.arg(2).tojstring());

    }
}
