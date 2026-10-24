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
            "  return { when = 1700000000000, icon = 17, largeIcon = 'photo', tickerText = 'Hello',",
            "    getChannelId = function(self) return channel end,",
            "    getSmallIcon = function(self) return icon end }",
            "end",
            "function paramof(result)",
            "  return { getResult = function(self) return result end,",
            "    setResult = function(self, value) self.result = value end,",
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
            "    function builder:build() return self.fake end",
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
        Varargs result = globals.load(
                "sdk = 34\n" +
                        "local param = paramof(notificationof('messages', 'icon'))\n" +
                        "local restricted = after(nil, param)\n" +
                        "local fake = param.result\n" +
                        "return restricted, fake.smallIcon, fake.channel, fake.when, fake.icon, fake.largeIcon, fake.tickerText",
                "test").invoke();
        assertTrue(result.arg(1).toboolean());
        assertEquals("icon", result.arg(2).tojstring());
        assertEquals("messages", result.arg(3).tojstring());
        assertEquals(1700000000000L, result.arg(4).tolong());
        assertEquals(17, result.arg(5).toint());
        assertTrue(result.arg(6).isnil());
        assertTrue(result.arg(7).isnil());
    }

    // Before Android 8 there are no channels
    @Test
    public void noChannelBeforeOreo() {
        Varargs result = globals.load(
                "sdk = 25\n" +
                        "local param = paramof(notificationof(nil, 'icon'))\n" +
                        "after(nil, param)\n" +
                        "return param.result.channel, param.result.smallIcon", "test").invoke();
        assertTrue(result.arg(1).isnil());
        assertEquals("icon", result.arg(2).tojstring());
    }
}
