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
            // the app has no permission to read contacts
            "function paramof(uri, mode, starred, found)",
            "  local queries = {}",
            "  local resolver = { query = function(self, uri, projection, where, args, order)",
            "    local query = uri .. ' ' .. where",
            "    table.insert(queries, query)",
            "    if found == nil then error('Permission Denial: reading com.android.providers.contacts') end",
            "    local count = 0",
            "    for _, row in ipairs(found) do if row == query then count = 1 end end",
            "    return { getCount = function(self) return count end, close = function(self) end }",
            "  end }",
            "  local arguments = { [0] = uriof(uri), [1] = mode }",
            "  return { queries = queries,",
            "    getArgument = function(self, index) return arguments[index] end,",
            "    getSetting = function(self, name) assert(name == 'contacts.starred', name) return starred end,",
            "    getApplicationContext = function(self)",
            "      return { getContentResolver = function(self) return resolver end }",
            "    end,",
            "    setResult = function(self, value) self.result = value end }",
            "end",
            "luajava = {",
            "  newInstance = function(name, message)",
            "    if name == 'android.net.Uri$Builder' then",
            "      local builder = { path = '' }",
            "      function builder:scheme(value) self.s = value return self end",
            "      function builder:encodedAuthority(value) self.a = value return self end",
            "      function builder:appendPath(value) self.path = self.path .. '/' .. value return self end",
            "      function builder:build() return self.s .. '://' .. self.a .. self.path end",
            "      return builder",
            "    end",
            "    return { name = name, message = message }",
            "  end,",
            "  bindClass = function(name)",
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

    // Whether restricted, the class and the message of the exception and the queries, separated by a comma;
    // the setting and the rows which the queries find are Lua expressions
    private Varargs open(String hook, String className, String uri, String mode, String starred, String found) {
        String method = hook.substring(hook.indexOf('.') + 1, hook.indexOf('/'));
        return Scripts.run(globals,
                "local param = paramof('" + uri + "', " + mode + ", " + starred + ", " + found + ")\n" +
                        "local restricted = before(hookof('" + hook + "', '" + method + "', '" + className + "'), param)\n" +
                        "local queries = table.concat(param.queries, ',')\n" +
                        "if param.result == nil then return restricted, nil, nil, queries end\n" +
                        "return restricted, param.result.name, param.result.message, queries");
    }

    private Varargs open(String hook, String className, String uri, String mode) {
        return open(hook, className, uri, mode, "nil", "{}");
    }

    // Attachments of MMS messages and recordings of voicemails are not found, like their rows,
    // also with the user in the authority
    @Test
    public void attachmentIsNotFound() {
        Varargs result = open("ContentResolver.openAssetFileDescriptor/mmssms", RESOLVER,
                "content://0@mms/part/1", "'r'");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.io.FileNotFoundException", result.arg(2).tojstring());
        assertEquals("No entry for content://0@mms/part/1", result.arg(3).tojstring());

        assertTrue(open("ContentResolver.openTypedAssetFileDescriptor/mmssms", RESOLVER,
                "content://com.android.voicemail/voicemail/1", "'audio/*'").arg1().toboolean());

        // The second argument of the typed methods is a type
        Varargs photo = open("ContentProviderClient.openTypedAssetFile/contacts", CLIENT,
                "content://com.android.contacts/contacts/1/display_photo", "'image/*'");
        assertTrue(photo.arg(1).toboolean());
    }

    // Writing reads nothing; other providers and other groups stay
    @Test
    public void otherFilesStay() {
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://com.android.contacts/display_photo/1", "'w'").arg1().toboolean());
        assertFalse(open("ContentResolver.openAssetFileDescriptor/mmssms", RESOLVER,
                "content://com.android.voicemail/voicemail/1", "'w'").arg1().toboolean());
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://com.example.provider/file", "'r'").arg1().toboolean());
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://mms/part/1", "'r'").arg1().toboolean());
    }

    // The display photo of a raw contact is written for rw, like in the documentation of RawContacts.DisplayPhoto;
    // other files are read for rw
    @Test
    public void rawContactPhotoIsWritten() {
        assertFalse(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://com.android.contacts/raw_contacts/1/display_photo", "'rw'").arg1().toboolean());
        assertTrue(open("ContentResolver.openAssetFileDescriptor/contacts", RESOLVER,
                "content://com.android.contacts/raw_contacts/1/display_photo", "'r'").arg1().toboolean());
        assertTrue(open("ContentResolver.openTypedAssetFileDescriptor/contacts", RESOLVER,
                "content://com.android.contacts/raw_contacts/1/display_photo", "'image/*'").arg1().toboolean());
        assertTrue(open("ContentResolver.openAssetFileDescriptor/mmssms", RESOLVER,
                "content://mms/part/1", "'rw'").arg1().toboolean());
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

        // The former contacts API has no starred filter
        assertTrue(open(hook, CLIENT, "content://contacts/people/1/photo", "'r'", "'1'", found).arg1().toboolean());
    }
}
