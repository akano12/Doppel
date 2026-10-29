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
import static org.junit.Assert.assertTrue;

// The script of notifications of listeners (assets/statusbarnotification_getnotification.lua)
// with Lua stand-ins for the notification and its builder, like LocationScriptTest
public class NotificationScriptTest {
    private static final String STUBS = String.join("\n",
            "function notificationof(channel, icon)",
            "  return { when = 1700000000000, icon = 17, largeIcon = 'photo', tickerText = 'Hello', flags = 2, category = 'msg',",
            "    getChannelId = function(self) return channel end,",
            "    getSmallIcon = function(self) return icon end }",
            "end",
            "built = 0",
            "values = {}",
            "function paramof(result)",
            "  return { getResult = function(self) return result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getValue = function(self, name, scope) return values[scope] end,",
            "    putValue = function(self, name, value, scope) values[scope] = value end,",
            "    getApplicationContext = function(self) return 'context' end }",
            "end",
            "luajava = {",
            "  bindClass = function(name)",
            "    assert(name == 'android.os.Build$VERSION', name)",
            "    return { SDK_INT = sdk }",
            "  end,",
            "  newInstance = function(name, context, channel)",
            "    assert(name == 'android.app.Notification$Builder', name)",
            "    local builder = { fake = { context = context, channel = channel } }",
            "    function builder:setSmallIcon(icon) self.fake.smallIcon = icon return self end",
            "    function builder:setWhen(when) self.fake.when = when return self end",
            "    function builder:build() built = built + 1 return self.fake end",
            "    return builder",
            "  end }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("statusbarnotification_getnotification", STUBS);
    }

    // Listeners show the small icon, which posted notifications have, and use the channel
    @Test
    public void fakeHasSmallIconAndChannel() {
        Varargs result = Scripts.run(globals,
                "sdk = 34\n" +
                        "local param = paramof(notificationof('messages', 'icon'))\n" +
                        "local restricted = after(nil, param)\n" +
                        "local fake = param.result\n" +
                        "return restricted, fake.smallIcon, fake.channel, fake.when, fake.icon, fake.largeIcon, fake.tickerText");
        assertTrue(result.arg(1).toboolean());
        assertEquals("icon", result.arg(2).tojstring());
        assertEquals("messages", result.arg(3).tojstring());
        assertEquals(1700000000000L, result.arg(4).tolong());
        assertEquals(17, result.arg(5).toint());
        assertTrue(result.arg(6).isnil());
        assertTrue(result.arg(7).isnil());
    }

    // The flags and the category stay, listeners act on them; a notification is made once for each notification
    @Test
    public void fakeIsMadeOnceWithFlags() {
        Varargs result = Scripts.run(globals,
                "sdk = 34\n" +
                        "local real = notificationof('messages', 'icon')\n" +
                        "local first = paramof(real)\n" +
                        "after(nil, first)\n" +
                        "local again = paramof(real)\n" +
                        "local restricted = after(nil, again)\n" +
                        "return restricted, again.result == first.result, built, first.result.flags, first.result.category");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals(2, result.arg(4).toint());
        assertEquals("msg", result.arg(5).tojstring());
    }
}
