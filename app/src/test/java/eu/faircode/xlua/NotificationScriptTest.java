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

// The script of notifications of listeners (assets/statusbarnotification_getnotification.lua)
// with Lua stand-ins for the notification and its builder, like LocationScriptTest
public class NotificationScriptTest {
    private static final String STUBS = String.join("\n",
            "function bundleof(values)",
            "  local bundle = { values = values }",
            "  function bundle:getParcelable(key) return self.values[key] end",
            "  function bundle:putParcelable(key, value) self.values[key] = value end",
            "  function bundle:remove(key) self.values[key] = nil end",
            "  return bundle",
            "end",
            // A notification writes its version, its token, its time and the time it was made first, see
            // Notification.writeToParcelImpl; its other fields stand in as one value
            "function writenotification(self, parcel, flags)",
            "  local rest = {}",
            "  for name, value in pairs(self) do rest[name] = value end",
            "  parcel:writeInt(1) parcel:writeStrongBinder(nil) parcel:writeLong(self.when) parcel:writeLong(self.creationTime)",
            "  parcel:write(rest)",
            "end",
            "notificationcreator = { createFromParcel = function(self, parcel)",
            "  assert(parcel:readInt() == 1)",
            "  parcel:readStrongBinder()",
            "  local when, creationTime = parcel:readLong(), parcel:readLong()",
            "  local copy = {}",
            "  for name, value in pairs(parcel:read()) do copy[name] = value end",
            "  copy.when, copy.creationTime, copy.parcelled = when, creationTime, true",
            "  return copy",
            "end }",
            // Android put the ApplicationInfo of the app which posted the notification, unless it is light
            "function notificationof(channel, icon, light)",
            "  return { class = 'android.app.Notification', when = 1700000000000, creationTime = 1699999999000, icon = 17,",
            "    largeIcon = 'photo', tickerText = 'Hello', flags = 2, category = 'msg',",
            "    extras = bundleof({ ['android.appInfo'] = (not light and 'poster' or nil) }),",
            "    getChannelId = function(self) return channel end,",
            "    getSmallIcon = function(self) return icon end,",
            "    writeToParcel = writenotification,",
            "    toString = function(self) return 'Notification(channel=' .. channel .. ' shortcut=secret)' end }",
            "end",
            "built = 0",
            "values = {}",
            "function hookof(method, className)",
            "  return { getMethodName = function(self) return method end,",
            "    getClassName = function(self) return className or 'android.service.notification.StatusBarNotification' end }",
            "end",
            "function sbnof(notification, packageName, key, override)",
            "  return { getPackageName = function(self) return packageName or 'com.other' end,",
            "    getNotification = function(self) return notification end,",
            "    getKey = function(self) return key end,",
            "    getOverrideGroupKey = function(self) return override end }",
            "end",
            // The notification is of another app, unless the package of the app is given
            "function paramof(result, packageName, this, args)",
            "  local sbn = this or sbnof(result, packageName)",
            "  return { getResult = function(self) return result end,",
            "    getThis = function(self) return sbn end,",
            "    getArgument = function(self, index) return args[index + 1] end,",
            "    getException = function(self) return nil end,",
            "    getPackageName = function(self) return 'com.example' end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getValue = function(self, name, scope) return values[name .. tostring(scope)] end,",
            "    putValue = function(self, name, value, scope) values[name .. tostring(scope)] = value end,",
            "    unhooked = function(self, call) return call() end,",
            "    isInstance = function(self, object, name) return type(object) == 'table' and object.class == name end,",
            "    getApplicationContext = function(self) return 'context' end }",
            "end",
            // An accessibility event as services get it, sealed, so without setters
            "function eventof(type, className, packageName, data, ticker)",
            "  local event = { class = 'android.view.accessibility.AccessibilityEvent', type = type, className = className,",
            "    packageName = packageName, data = data, text = { items = { ticker } } }",
            "  function event.text:isEmpty() return #self.items == 0 end",
            "  function event.text:clear() self.items = {} end",
            "  function event:getEventType() return self.type end",
            "  function event:getClassName() return self.className end",
            "  function event:getPackageName() return self.packageName end",
            "  function event:getParcelableData() return self.data end",
            "  function event:getText() return self.text end",
            "  return event",
            "end",
            // A channel writes its id first, as a byte 1 and the text, then its settings and the conversation together:
            // its channel, its id and whether it was demoted and is important, then other settings; the setters of
            // the conversation are left out, the copy is read from the parcel
            "function channelof(id, parent, conversation, sound, important)",
            "  local channel = { id = id, parent = parent, conversation = conversation, sound = sound, important = important }",
            "  function channel:getId() return self.id end",
            "  function channel:getParentChannelId() return self.parent end",
            "  function channel:getConversationId() return self.conversation end",
            "  function channel:isImportantConversation() return self.important end",
            "  function channel:writeToParcel(parcel, flags)",
            "    parcel:writeByte(1) parcel:writeString(self.id) parcel:writeString(self.sound)",
            "    parcel:writeString(self.parent) parcel:writeString(self.conversation)",
            "    parcel:writeInt(self.important and 1 or 0) parcel:writeInt(self.important and 1 or 0) parcel:writeLong(42)",
            "  end",
            "  return channel",
            "end",
            "function rankingof(key)",
            "  return { getKey = function(self) return key end }",
            "end",
            // A parcel as a list of the values written, one position for each, which writes at its position
            "function parcelof()",
            "  local parcel = { items = {}, size = 0, position = 0 }",
            "  function parcel:write(value)",
            "    self.position = self.position + 1",
            "    self.items[self.position] = value",
            "    self.size = math.max(self.size, self.position)",
            "  end",
            "  function parcel:read() self.position = self.position + 1 return self.items[self.position] end",
            "  for _, kind in ipairs({ 'Byte', 'String', 'Int', 'Long', 'StrongBinder' }) do",
            "    parcel['write' .. kind], parcel['read' .. kind] = parcel.write, parcel.read",
            "  end",
            "  function parcel:dataPosition() return self.position end",
            "  function parcel:dataSize() return self.size end",
            "  function parcel:setDataPosition(position) self.position = position end",
            "  function parcel:setDataSize(size)",
            "    for i = size + 1, self.size do self.items[i] = nil end",
            "    self.size, self.position = size, math.min(self.position, size)",
            "  end",
            "  function parcel:appendFrom(other, offset, length)",
            "    assert(length >= 0 and offset + length <= other.size)",
            "    for i = offset + 1, offset + length do self:write(other.items[i]) end",
            "  end",
            "  function parcel:recycle() end",
            "  return parcel",
            "end",
            "channelcreator = { createFromParcel = function(self, parcel)",
            "  assert(parcel:readByte() == 1)",
            "  local id, sound, parent, conversation = parcel:readString(), parcel:readString(), parcel:readString(), parcel:readString()",
            "  local demoted, important = parcel:readInt(), parcel:readInt()",
            "  assert(demoted == 0 and parcel:readLong() == 42 and parcel:dataPosition() == parcel:dataSize())",
            "  return channelof(id, parent, conversation, sound, important == 1)",
            "end }",
            "luajava = {",
            "  newInstance = function(name, context, channel)",
            "    assert(name == 'android.app.Notification$Builder', name)",
            "    local builder = { fake = { context = context, channel = channel, creationTime = 'now',",
            "      extras = bundleof({ ['android.appInfo'] = 'listener' }), writeToParcel = writenotification } }",
            "    function builder.fake:toString() return 'Notification(channel=' .. self.channel .. ' shortcut=null)' end",
            "    function builder:setSmallIcon(icon) self.fake.smallIcon = icon return self end",
            "    function builder:setWhen(when) self.fake.when = when return self end",
            "    function builder:build() built = built + 1 return self.fake end",
            "    return builder",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'android.os.Parcel' then return { obtain = function(self) return parcelof() end } end",
            "    if name == 'android.app.Notification' then return { CREATOR = notificationcreator } end",
            "    assert(name == 'android.app.NotificationChannel', name)",
            "    return { CREATOR = channelcreator }",
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
                "local param = paramof(notificationof('messages', 'icon'))\n" +
                        "local restricted = after(hookof('getNotification'), param)\n" +
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

    // Like posted notifications, the fake has the ApplicationInfo of the app which posted it and the time it was made,
    // not the ones of the listener and of the read, the time by its parcel; light notifications have no ApplicationInfo
    @Test
    public void fakeHasAppInfoAndTimeOfPoster() {
        Varargs result = Scripts.run(globals,
                "local param = paramof(notificationof('messages', 'icon'))\n" +
                        "after(hookof('getNotification'), param)\n" +
                        "local light = paramof(notificationof('messages', 'icon', true))\n" +
                        "after(hookof('getNotification'), light)\n" +
                        "return param.result.extras:getParcelable('android.appInfo'), param.result.creationTime,\n" +
                        "  light.result.extras:getParcelable('android.appInfo'), param.result.parcelled, param.result.when");
        assertEquals("poster", result.arg(1).tojstring());
        assertEquals(1699999999000L, result.arg(2).tolong());
        assertTrue(result.arg(3).isnil());
        assertTrue("not read from its parcel", result.arg(4).toboolean());
        assertEquals(1700000000000L, result.arg(5).tolong());
    }

    // The app's own notifications stay, apps read them back to update or merge them
    @Test
    public void ownNotificationIsNotFaked() {
        Varargs result = Scripts.run(globals,
                "local param = paramof(notificationof('messages', 'icon'), 'com.example')\n" +
                        "local restricted = after(hookof('getNotification'), param)\n" +
                        "return restricted, param.result, built");
        assertFalse("own notification restricted", result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals(0, result.arg(3).toint());
    }

    // The flags and the category stay, listeners act on them; a notification is made once for each notification
    @Test
    public void fakeIsMadeOnceWithFlags() {
        Varargs result = Scripts.run(globals,
                "local real = notificationof('messages', 'icon')\n" +
                        "local first = paramof(real)\n" +
                        "after(hookof('getNotification'), first)\n" +
                        "local again = paramof(real)\n" +
                        "local restricted = after(hookof('getNotification'), again)\n" +
                        "return restricted, again.result == first.result, built, first.result.flags, first.result.category");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals(2, result.arg(4).toint());
        assertEquals("msg", result.arg(5).tojstring());
    }

    // The text of a StatusBarNotification has the fake, the same one getNotification gives, which has no shortcut;
    // the text of the app's own notifications and a text of another form stay
    @Test
    public void textHasFake() {
        Varargs result = Scripts.run(globals,
                "local real = notificationof('messages', 'icon')\n" +
                        "local prefix = 'StatusBarNotification(pkg=com.other user=UserHandle{0} id=1 tag=chat '\n" +
                        "  .. 'key=0|com.other|1|chat|10123: '\n" +
                        "local param = paramof(prefix .. real:toString() .. ')', nil, sbnof(real))\n" +
                        "local restricted = after(hookof('toString'), param)\n" +
                        "local own = paramof(prefix .. real:toString() .. ')', nil, sbnof(real, 'com.example'))\n" +
                        "local kept = after(hookof('toString'), own)\n" +
                        "local other = paramof('StatusBarNotification(' .. real:toString() .. ') more', nil, sbnof(real))\n" +
                        "local unknown = after(hookof('toString'), other)\n" +
                        "local get = paramof(real)\n" +
                        "after(hookof('getNotification'), get)\n" +
                        "return restricted, param.result, kept, own.result, unknown, other.result, built");
        assertTrue(result.arg(1).toboolean());
        assertEquals("StatusBarNotification(pkg=com.other user=UserHandle{0} id=1 tag=chat key=0|com.other|1|chat|10123: " +
                "Notification(channel=messages shortcut=null))", result.arg(2).tojstring());
        assertFalse("own text restricted", result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
        assertFalse("other text restricted", result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());
        assertEquals(1, result.arg(7).toint());
    }

    // A parcel of a StatusBarNotification has the fake, which Notification.CREATOR reads back, between the values
    // before and after it, which stay, like what the parcel had before; the parcel of the app's own notifications stays
    @Test
    public void parcelHasFake() {
        Varargs result = Scripts.run(globals,
                "local function write(sbn, real)\n" +
                        "  local parcel = parcelof()\n" +
                        "  parcel:writeString('before')\n" +
                        "  local param = paramof(nil, nil, sbn, { parcel, 0 })\n" +
                        "  before(hookof('writeToParcel'), param)\n" +
                        "  parcel:writeString(sbn:getPackageName()) parcel:writeString(sbn:getPackageName()) parcel:writeInt(1)\n" +
                        "  parcel:writeInt(1) parcel:writeString('chat') parcel:writeInt(10123) parcel:writeInt(4321)\n" +
                        "  real:writeToParcel(parcel, 0)\n" +
                        "  parcel:writeInt(0) parcel:writeLong(77) parcel:writeInt(0) parcel:writeInt(1) parcel:writeInt(88)\n" +
                        "  local restricted = after(hookof('writeToParcel'), param)\n" +
                        "  parcel:setDataPosition(0)\n" +
                        "  local values = { parcel:readString(), parcel:readString(), parcel:readString(), parcel:readInt(),\n" +
                        "    parcel:readInt(), parcel:readString(), parcel:readInt(), parcel:readInt() }\n" +
                        "  local read = notificationcreator:createFromParcel(parcel)\n" +
                        "  for i = 1, 5 do table.insert(values, parcel:readInt()) end\n" +
                        "  return restricted, table.concat(values, ' '), read, parcel:dataPosition() == parcel:dataSize()\n" +
                        "end\n" +
                        "local real = notificationof('messages', 'icon')\n" +
                        "local restricted, values, read, ended = write(sbnof(real), real)\n" +
                        "local get = paramof(real)\n" +
                        "after(hookof('getNotification'), get)\n" +
                        "local kept, own, mine = write(sbnof(real, 'com.example'), real)\n" +
                        "return restricted, values, read.channel, read.smallIcon, read.largeIcon, read.tickerText, read.creationTime,\n" +
                        "  ended, get.result.tickerText, built, kept, mine.tickerText");
        assertTrue(result.arg(1).toboolean());
        assertEquals("before com.other com.other 1 1 chat 10123 4321 0 77 0 1 88", result.arg(2).tojstring());
        assertEquals("messages", result.arg(3).tojstring());
        assertEquals("icon", result.arg(4).tojstring());
        assertTrue(result.arg(5).isnil());
        assertTrue(result.arg(6).isnil());
        assertEquals(1699999999000L, result.arg(7).tolong());
        assertTrue("parcel not ended after the values", result.arg(8).toboolean());
        assertTrue(result.arg(9).isnil());
        assertEquals("fake made again", 1, result.arg(10).toint());
        assertFalse("own parcel restricted", result.arg(11).toboolean());
        assertEquals("Hello", result.arg(12).tojstring());
    }

    // The group key of a notification of an app's group is its key, like for a group of one, which the fake is;
    // a group made by Android, a key which is the key already and the app's own notifications stay
    @Test
    public void groupKeyIsKey() {
        Varargs result = Scripts.run(globals,
                "local real = notificationof('messages', 'icon')\n" +
                        "local key = '0|com.other|1|chat|10123'\n" +
                        "local grouped = paramof('0|com.other|g:secret', nil, sbnof(real, nil, key))\n" +
                        "local restricted = after(hookof('getGroupKey'), grouped)\n" +
                        "local single = after(hookof('getGroupKey'), paramof(key, nil, sbnof(real, nil, key)))\n" +
                        "local android = paramof('0|com.other|g:ranker_group', nil, sbnof(real, nil, key, 'ranker_group'))\n" +
                        "local kept = after(hookof('getGroupKey'), android)\n" +
                        "local own = paramof('0|com.example|g:secret', nil, sbnof(real, 'com.example', '0|com.example|1|chat|10001'))\n" +
                        "local mine = after(hookof('getGroupKey'), own)\n" +
                        "return restricted, grouped.result, single, kept, android.result, mine, own.result, built");
        assertTrue(result.arg(1).toboolean());
        assertEquals("0|com.other|1|chat|10123", result.arg(2).tojstring());
        assertFalse("key restricted", result.arg(3).toboolean());
        assertFalse("group of Android restricted", result.arg(4).toboolean());
        assertTrue(result.arg(5).isnil());
        assertFalse("own group restricted", result.arg(6).toboolean());
        assertTrue(result.arg(7).isnil());
        assertEquals(0, result.arg(8).toint());
    }

    // The channel of a conversation becomes a copy with the id of its channel, without the conversation and with
    // its settings, once for each channel; other channels and the ones of the app's own conversations stay
    @Test
    public void conversationChannelLosesConversation() {
        Varargs result = Scripts.run(globals,
                "local real = channelof('messages : secret-contact', 'messages', 'secret-contact', 'ring', true)\n" +
                        "local param = paramof(real, nil, rankingof('0|com.other|1|null|10123'))\n" +
                        "local restricted = after(hookof('getChannel'), param)\n" +
                        "local copy = param.result\n" +
                        "local again = paramof(real, nil, rankingof('0|com.other|1|null|10123'))\n" +
                        "after(hookof('getChannel'), again)\n" +
                        "local own = paramof(real, nil, rankingof('0|com.example|1|null|10001'))\n" +
                        "local kept = after(hookof('getChannel'), own)\n" +
                        "local plain = paramof(channelof('messages'), nil, rankingof('0|com.other|2|null|10123'))\n" +
                        "local unchanged = after(hookof('getChannel'), plain)\n" +
                        "return restricted, copy:getId(), copy:getParentChannelId(), copy:getConversationId(), copy.important,\n" +
                        "  copy.sound, real:getId(), real:getConversationId(), again.result == copy, kept, own.result, unchanged");
        assertTrue(result.arg(1).toboolean());
        assertEquals("messages", result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
        assertTrue(result.arg(4).isnil());
        assertFalse(result.arg(5).toboolean());
        assertEquals("ring", result.arg(6).tojstring());
        assertEquals("messages : secret-contact", result.arg(7).tojstring());
        assertEquals("secret-contact", result.arg(8).tojstring());
        assertTrue("copy made again", result.arg(9).toboolean());
        assertFalse("own channel restricted", result.arg(10).toboolean());
        assertTrue(result.arg(11).isnil());
        assertFalse("plain channel restricted", result.arg(12).toboolean());
    }

    // An accessibility event of a notification of another app loses the ticker text when it is read, and the getter of
    // its data and its text give the fake, the same one each time, while the event keeps the real one, as it is sealed;
    // without a notification, like on a locked device without a public version, only the text goes
    @Test
    public void notificationEventGetsFake() {
        Varargs result = Scripts.run(globals,
                "local e, r = 'android.view.accessibility.AccessibilityEvent', 'android.view.accessibility.AccessibilityRecord'\n" +
                        "local real = notificationof('messages', 'icon')\n" +
                        "local event = eventof(64, 'android.app.Notification', 'com.other', real, 'Alice: hi')\n" +
                        "local read = after(hookof('initFromParcel', e), paramof(nil, nil, event))\n" +
                        "local data = paramof(real, nil, event)\n" +
                        "local restricted = after(hookof('getParcelableData', r), data)\n" +
                        "local again = paramof(real, nil, event)\n" +
                        "after(hookof('getParcelableData', r), again)\n" +
                        "local text = 'EventType: TYPE_NOTIFICATION_STATE_CHANGED; PackageName: com.other'\n" +
                        "  .. ' [ ClassName: android.app.Notification; Text: []; ParcelableData: '\n" +
                        "local described = paramof(text .. real:toString() .. '; DisplayId: 0 ]; recordCount: 0', nil, event)\n" +
                        "local replaced = after(hookof('toString', e), described)\n" +
                        "local locked = eventof(64, 'android.app.Notification', 'com.other', nil, 'Alice: hi')\n" +
                        "local cleared = after(hookof('initFromParcel', e), paramof(nil, nil, locked))\n" +
                        "local nothing = after(hookof('getParcelableData', r), paramof(nil, nil, locked))\n" +
                        "return read, event.text:isEmpty(), event.data == real, restricted, data.result.channel,\n" +
                        "  data.result.tickerText, again.result == data.result, replaced, described.result, cleared,\n" +
                        "  locked.text:isEmpty(), nothing, built");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue("event changed", result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals("messages", result.arg(5).tojstring());
        assertTrue(result.arg(6).isnil());
        assertTrue("fake made again", result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertEquals("EventType: TYPE_NOTIFICATION_STATE_CHANGED; PackageName: com.other [ ClassName: android.app.Notification" +
                "; Text: []; ParcelableData: Notification(channel=messages shortcut=null); DisplayId: 0 ]; recordCount: 0",
                result.arg(9).tojstring());
        assertTrue(result.arg(10).toboolean());
        assertTrue(result.arg(11).toboolean());
        assertFalse("event without notification restricted", result.arg(12).toboolean());
        assertEquals(1, result.arg(13).toint());
    }

    // Other events stay: of the app's own notifications, of toasts and of other types, and records which are no events
    @Test
    public void otherEventsStay() {
        Varargs result = Scripts.run(globals,
                "local e, r = 'android.view.accessibility.AccessibilityEvent', 'android.view.accessibility.AccessibilityRecord'\n" +
                        "local real = notificationof('messages', 'icon')\n" +
                        "local events = { eventof(64, 'android.app.Notification', 'com.example', real, 'Hi'),\n" +
                        "  eventof(64, 'android.widget.Toast$TN', 'com.other', real, 'Saved'),\n" +
                        "  eventof(32, 'android.app.Notification', 'com.other', real, 'Hi') }\n" +
                        "for index, event in ipairs(events) do\n" +
                        "  assert(not after(hookof('initFromParcel', e), paramof(nil, nil, event)), index)\n" +
                        "  assert(not after(hookof('getParcelableData', r), paramof(real, nil, event)), index)\n" +
                        "  assert(event.data == real and not event.text:isEmpty(), index)\n" +
                        "end\n" +
                        "local record = { class = r, getParcelableData = function(self) return real end }\n" +
                        "assert(not after(hookof('getParcelableData', r), paramof(real, nil, record)))\n" +
                        "return built");
        assertEquals(0, result.arg1().toint());
    }
}
