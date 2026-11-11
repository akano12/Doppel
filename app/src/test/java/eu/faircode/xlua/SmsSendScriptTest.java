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

// Sending messages (assets/sms_send.lua): calls Android refuses without sending go to Android, the others are skipped
// and fail from Android 10 like a send of which the user denied the app op
public class SmsSendScriptTest {
    private static final String TEXT = "'java.lang.String', 'java.lang.String', 'java.lang.String', " +
            "'android.app.PendingIntent', 'android.app.PendingIntent'";
    private static final String PARTS = "'java.lang.String', 'java.lang.String', 'java.util.ArrayList', " +
            "'java.util.ArrayList', 'java.util.ArrayList'";
    private static final String DATA = "'java.lang.String', 'java.lang.String', 'short', 'byte[]', " +
            "'android.app.PendingIntent', 'android.app.PendingIntent'";
    private static final String MMS = "'android.content.Context', 'android.net.Uri', 'java.lang.String', " +
            "'android.os.Bundle', 'android.app.PendingIntent'";

    private static final String STUBS = String.join("\n",
            "function hookof(method, ...)",
            "  local types = arrayof(...)",
            "  return { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return types end }",
            "end",
            "sdk = 34",
            // The answer of checkSelfPermission, 'error' when the check fails; asked without the hooks of the app
            "permission = 0",
            // The SMS service, nil when missing, 'error' when the lookup fails; looked up without the hooks of the app
            "isms = {}",
            "unhooked = false",
            "servicemanager = { getService = function(self, name)",
            "  assert(name == 'isms' and unhooked) if isms == 'error' then error('NoSuchMethodException') end return isms end }",
            "luajava = { bindClass = function(name)",
            "  if name == 'android.os.ServiceManager' then return servicemanager end",
            "  if name == 'android.os.Process' then return { myUid = function(self) return 10123 end } end",
            "  assert(name == 'android.os.Build$VERSION') return { SDK_INT = sdk } end,",
            "  newInstance = function(name, message) return name .. ': ' .. message end }",
            // The same for MODIFY_PHONE_STATE, and whether the app has carrier privileges, or 'error'
            "phonestate = -1",
            "privileged = false",
            "context = { checkSelfPermission = function(self, name)",
            "  local answer = ({ ['android.permission.SEND_SMS'] = permission,",
            "    ['android.permission.MODIFY_PHONE_STATE'] = phonestate })[name]",
            "  assert(answer ~= nil and unhooked)",
            "  if answer == 'error' then error('DeadSystemException') end return answer end,",
            "  getSystemService = function(self, name) assert(name == 'phone' and unhooked)",
            "    return { hasCarrierPrivileges = function(self)",
            "      assert(unhooked) if privileged == 'error' then error('DeadSystemException') end return privileged end } end }",
            "function paramof(args)",
            "  return { args = args, set = false,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    getApplicationContext = function(self) return context end,",
            "    unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end,",
            "    setResult = function(self, value) self.result = value self.set = true end }",
            "end",
            "sent = {}",
            "function intentof(name)",
            "  return { send = function(self, code) sent[#sent + 1] = name .. ' ' .. code end }",
            "end",
            "canceled = { send = function(self, code) error('CanceledException') end }",
            "function listof(...)",
            "  local items, count = { ... }, select('#', ...)",
            "  return { size = function(self) return count end, get = function(self, index) return items[index + 1] end }",
            "end",
            // Returns whether restricted, whether skipped, which intents were sent how and what is thrown
            "function call(hook, args)",
            "  sent = {}",
            "  local param = paramof(args)",
            "  local restricted = before(hook, param)",
            "  return restricted, param.set, table.concat(sent, ','), param.result",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("sms_send", Scripts.ARRAYOF + "\n" + STUBS);
    }

    // The call goes to Android, which answers it without sending
    private void android(String lua) {
        Varargs result = Scripts.run(globals, "return call(" + lua + ")");
        assertFalse(lua, result.arg(1).toboolean());
        assertFalse(lua, result.arg(2).toboolean());
        assertEquals(lua, "", result.arg(3).tojstring());
    }

    // The call is skipped, with the sent intents which were answered
    private void skipped(String lua, String answered) {
        Varargs result = Scripts.run(globals, "return call(" + lua + ")");
        assertTrue(lua, result.arg(1).toboolean());
        assertTrue(lua, result.arg(2).toboolean());
        assertEquals(lua, answered, result.arg(3).tojstring());
        assertTrue(lua, result.arg(4).isnil());
    }

    // The call is skipped with the exception Android throws, and no intent is answered
    private void thrown(String lua, String exception) {
        Varargs result = Scripts.run(globals, "return call(" + lua + ")");
        assertTrue(lua, result.arg(1).toboolean());
        assertTrue(lua, result.arg(2).toboolean());
        assertEquals(lua, "", result.arg(3).tojstring());
        assertEquals(lua, exception, result.arg(4).tojstring());
    }

    // Android throws an IllegalArgumentException for these before it sends, on all versions
    @Test
    public void argumentsAndroidRefusesGoToAndroid() {
        String sent = "intentof('sent'), intentof('delivered')";
        android("hookof('sendTextMessage', " + TEXT + "), { [0] = '', [2] = 'hi', [3] = intentof('sent') }");
        android("hookof('sendTextMessage', " + TEXT + "), { [0] = nil, [2] = 'hi' }");
        android("hookof('sendTextMessage', " + TEXT + ", 'long'), { [0] = '+15551234', [2] = '' }");
        android("hookof('sendTextMessageWithoutPersisting', " + TEXT + "), { [0] = '+15551234', [2] = nil }");
        android("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = nil }");
        android("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof() }");
        // A single part is sent as a text, which Android refuses without a body
        android("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof(''), [3] = listof(" + sent + ") }");
        android("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '', [2] = listof('a', 'b') }");
        android("hookof('sendDataMessage', " + DATA + "), { [0] = '+15551234', [2] = 1, [3] = { length = 0 } }");
        android("hookof('sendDataMessage', " + DATA + "), { [0] = '+15551234', [2] = 1, [3] = nil }");
        android("hookof('sendDataMessage', " + DATA + "), { [0] = '', [2] = 1, [3] = { length = 1 } }");
        android("hookof('sendMultimediaMessage', " + MMS + "), { [0] = context, [1] = nil }");
        // From Android 11 the context looks up the service; before it is not used, so the call is skipped
        android("hookof('sendMultimediaMessage', " + MMS + ", 'long'), { [0] = nil, [1] = 'content://mms/1' }");
        Scripts.run(globals, "sdk = 29");
        skipped("hookof('sendMultimediaMessage', " + MMS + "), { [0] = nil, [1] = 'content://mms/1' }", "");
    }

    // Android 9 refuses the priority and validity out of range, also when not given (-1); later versions ignore them
    @Test
    public void optionsOutOfRangeGoToAndroid9() {
        Scripts.run(globals, "sdk = 28");
        String options = TEXT + ", 'int', 'boolean', 'int'";
        android("hookof('sendTextMessage', " + options + "), { [0] = '+15551234', [2] = 'hi', [5] = -1, [6] = false, [7] = -1 }");
        android("hookof('sendTextMessage', " + options + "), { [0] = '+15551234', [2] = 'hi', [5] = 1, [6] = false, [7] = 4 }");
        android("hookof('sendMultipartTextMessage', " + PARTS + ", 'int', 'boolean', 'int'), " +
                "{ [0] = '+15551234', [2] = listof('a', 'b'), [5] = 4, [6] = false, [7] = 10 }");
        skipped("hookof('sendTextMessage', " + options + "), { [0] = '+15551234', [2] = 'hi', [5] = 3, [6] = true, [7] = 10 }", "");
        Scripts.run(globals, "sdk = 29");
        skipped("hookof('sendTextMessage', " + options + "), " +
                "{ [0] = '+15551234', [2] = 'hi', [3] = intentof('sent'), [5] = -1, [6] = false, [7] = -1 }", "sent 1");
    }

    // Android refuses an app without SEND_SMS in the phone process, so the call is not sent there, where a permission
    // granted meanwhile would send it
    @Test
    public void appsWithoutPermissionGetTheSecurityException() {
        Scripts.run(globals, "permission = -1");
        String sms = "java.lang.SecurityException: Sending SMS message: uid 10123 does not have android.permission.SEND_SMS.";
        thrown("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi', [3] = intentof('sent') }", sms);
        thrown("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof('a', 'b'), " +
                "[3] = listof(intentof('first'), intentof('second')) }", sms);
        thrown("hookof('sendDataMessage', " + DATA + "), { [0] = '+15551234', [2] = 1, [3] = { length = 1 } }", sms);
        thrown("hookof('sendMultimediaMessage', " + MMS + "), { [0] = context, [1] = 'content://mms/1' }",
                "java.lang.SecurityException: Send MMS message: uid 10123 does not have android.permission.SEND_SMS.");
        Scripts.run(globals, "sdk = 27");
        thrown("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi', [3] = intentof('sent') }", sms);
        Scripts.run(globals, "sdk = 34");
        // Android throws for the arguments before it asks for the permission
        android("hookof('sendTextMessage', " + TEXT + "), { [0] = '', [2] = 'hi' }");
        // A check which fails counts as granted, since the message is then skipped and not sent
        Scripts.run(globals, "permission = 'error'");
        skipped("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi', [3] = intentof('sent') }", "sent 1");
    }

    // Sending without persisting needs carrier privileges, or MODIFY_PHONE_STATE and SEND_SMS, which Android 9 and
    // later ask for in this order, and Android 8.1 asks for SEND_SMS first and then for the others
    @Test
    public void sendingWithoutPersistingNeedsPrivileges() {
        String call = "hookof('sendTextMessageWithoutPersisting', " + TEXT + "), " +
                "{ [0] = '+15551234', [2] = 'hi', [3] = intentof('sent'), [4] = intentof('delivered') }";
        String uid = "java.lang.SecurityException: Sending SMS message: uid 10123 does not have android.permission.";
        thrown(call, uid + "MODIFY_PHONE_STATE.");
        Scripts.run(globals, "permission = -1");
        thrown(call, uid + "MODIFY_PHONE_STATE.");
        Scripts.run(globals, "phonestate = 0");
        thrown(call, uid + "SEND_SMS.");
        Scripts.run(globals, "permission = 0");
        skipped(call, "sent 1");
        // A carrier app needs neither permission
        Scripts.run(globals, "permission = -1 phonestate = -1 privileged = true");
        skipped(call, "sent 1");
        // A check which fails counts as privileged, since the message is then skipped and not sent
        Scripts.run(globals, "privileged = 'error'");
        skipped(call, "sent 1");
        Scripts.run(globals, "privileged = false phonestate = 'error'");
        thrown(call, uid + "SEND_SMS.");
        Scripts.run(globals, "sdk = 28 phonestate = -1");
        thrown(call, uid + "MODIFY_PHONE_STATE.");
        Scripts.run(globals, "sdk = 27");
        thrown(call, uid + "SEND_SMS.");
        Scripts.run(globals, "permission = 0");
        thrown(call, "java.lang.SecurityException: No Carrier Privilege.");
        Scripts.run(globals, "privileged = true");
        skipped(call, "");
        Scripts.run(globals, "privileged = false phonestate = 0");
        skipped(call, "");
        // Other messages ask neither
        Scripts.run(globals, "sdk = 34 phonestate = -1");
        skipped("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi', [3] = intentof('sent') }", "sent 1");
    }

    // Without the SMS service Android throws an UnsupportedOperationException for a text or data message before it
    // could send, on all versions; a multimedia message of another subscription is sent without it, so it is skipped
    @Test
    public void devicesWithoutSmsServiceGoToAndroid() {
        Scripts.run(globals, "isms = nil");
        android("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi', [3] = intentof('sent') }");
        android("hookof('sendTextMessage', " + TEXT + ", 'long'), { [0] = '+15551234', [2] = 'hi', [3] = intentof('sent') }");
        android("hookof('sendTextMessageWithoutPersisting', " + TEXT + "), { [0] = '+15551234', [2] = 'hi' }");
        android("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof('a', 'b') }");
        android("hookof('sendDataMessage', " + DATA + "), { [0] = '+15551234', [2] = 1, [3] = { length = 1 } }");
        skipped("hookof('sendMultimediaMessage', " + MMS + "), " +
                "{ [0] = context, [1] = 'content://mms/1', [4] = intentof('sent') }", "");
        Scripts.run(globals, "sdk = 27");
        android("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi' }");
        // Android throws before an app without SEND_SMS would be refused
        Scripts.run(globals, "permission = -1");
        android("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi' }");
        Scripts.run(globals, "permission = 0");
        // A lookup which fails counts as a service, since the message would else be sent
        Scripts.run(globals, "sdk = 34 isms = 'error'");
        skipped("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi', [3] = intentof('sent') }", "sent 1");
    }

    // From Android 10 the sent intent of each part fails, the delivery intents get nothing
    @Test
    public void skippedMessagesFailFromAndroid10() {
        skipped("hookof('sendTextMessage', " + TEXT + "), " +
                "{ [0] = '+15551234', [2] = 'hi', [3] = intentof('sent'), [4] = intentof('delivered') }", "sent 1");
        skipped("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi' }", "");
        skipped("hookof('sendDataMessage', " + DATA + "), " +
                "{ [0] = '+15551234', [2] = 1, [3] = { length = 1 }, [4] = intentof('sent'), [5] = intentof('delivered') }", "sent 1");
        skipped("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof('a', 'b'), " +
                "[3] = listof(intentof('first'), nil, intentof('third')), [4] = listof(intentof('delivered')) }", "first 1,third 1");
        // A single part is sent as a text, with the first sent intent
        skipped("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof('a'), " +
                "[3] = listof(intentof('first'), intentof('second')) }", "first 1");
        skipped("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof('a', 'b') }", "");
        // Android ignores a canceled intent
        skipped("hookof('sendTextMessage', " + TEXT + "), { [0] = '+15551234', [2] = 'hi', [3] = canceled }", "");
        // Android does not answer a multimedia message of which the app op is denied
        skipped("hookof('sendMultimediaMessage', " + MMS + ", 'long'), " +
                "{ [0] = context, [1] = 'content://mms/1', [4] = intentof('sent') }", "");
    }

    // Android 8.1 and 9 do not answer a message of which the app op is denied
    @Test
    public void skippedMessagesAreSilentBeforeAndroid10() {
        Scripts.run(globals, "sdk = 28");
        skipped("hookof('sendTextMessage', " + TEXT + "), " +
                "{ [0] = '+15551234', [2] = 'hi', [3] = intentof('sent'), [4] = intentof('delivered') }", "");
        Scripts.run(globals, "sdk = 27");
        skipped("hookof('sendMultipartTextMessage', " + PARTS + "), { [0] = '+15551234', [2] = listof('a', 'b'), " +
                "[3] = listof(intentof('first'), intentof('second')) }", "");
    }
}
