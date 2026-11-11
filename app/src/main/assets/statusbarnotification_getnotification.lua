-- This file is part of Doppel.

-- Doppel is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- Doppel is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12


-- An empty notification without content, extras and actions, built with the public API, so it has only the
-- time, the small icon and the channel of the notification, which posted notifications have and listeners use;
-- not the large icon, which is often a photo of the sender, nor the ids of the conversation or of its shortcut.
-- The flags and the category stay, which listeners act on, like for ongoing notifications and group summaries,
-- and tell nothing of the content. Each notification is made once, listeners get it several times.
-- The app's own notifications stay: apps read them back with getActiveNotifications to update or merge them.
-- The same notification is in the text and in the parcel of the StatusBarNotification and in the accessibility events
-- of notifications, its group key is the one of a notification without a group, and the channels of the rankings
-- lose the conversation.

function fakeof(param, real)
    local made = param:getValue('fake', real)
    if made ~= nil then
        return made
    end

    local context = param:getApplicationContext()
    local channel = real:getChannelId()
    local builder
    if channel == nil then
        builder = luajava.newInstance('android.app.Notification$Builder', context)
    else
        builder = luajava.newInstance('android.app.Notification$Builder', context, channel)
    end
    if real:getSmallIcon() ~= nil then
        builder:setSmallIcon(real:getSmallIcon())
    end
    builder:setWhen(real.when)

    local fake = builder:build()
    fake.icon = real.icon
    fake.flags = real.flags
    fake.category = real.category
    -- The builder puts the ApplicationInfo of this app (EXTRA_BUILDER_APPLICATION_INFO), where Android puts the one
    -- of the app which posted the notification; light notifications of listeners have none
    local info = (real.extras ~= nil and real.extras:getParcelable('android.appInfo') or nil)
    if info == nil then
        fake.extras:remove('android.appInfo')
    else
        fake.extras:putParcelable('android.appInfo', info)
    end
    -- The builder puts the time of the read as the time the notification was made, which differs for each read of
    -- getActiveNotifications, as each one gives new notifications. The field is hidden, but a parcel of a notification
    -- has it after the version, the token and the time (see Notification.writeToParcelImpl), so the fake is read back
    -- from its parcel with the time of the real one.
    -- shortcut: should the parcel fail, the fake keeps the time of the read; upgrade should Android have a getter of it.
    local ok, copy = pcall(function()
        local parcel = luajava.bindClass('android.os.Parcel'):obtain()
        real:writeToParcel(parcel, 0)
        parcel:setDataPosition(0)
        parcel:readInt()
        parcel:readStrongBinder()
        parcel:readLong()
        local creation = parcel:readLong()
        parcel:setDataSize(0)
        fake:writeToParcel(parcel, 0)
        parcel:setDataPosition(0)
        parcel:readInt()
        parcel:readStrongBinder()
        parcel:readLong()
        parcel:writeLong(creation)
        parcel:setDataPosition(0)
        local copy = luajava.bindClass('android.app.Notification').CREATOR:createFromParcel(parcel)
        parcel:recycle()
        return copy
    end)
    if ok and copy ~= nil then
        fake = copy
    end
    param:putValue('fake', fake, real)
    return fake
end

-- An accessibility service gets an event for each notification (TYPE_NOTIFICATION_STATE_CHANGED) with
-- the notification, or its public version on a locked device, and its ticker text, see
-- NotificationManagerService.sendAccessibilityEvent. Events for services are sealed, and the setter of the seal is
-- hidden, so the getter of the data and toString give the fake; the list of the text is not sealed, so the ticker text
-- goes when the event is read from the parcel, like for a notification without a ticker, which the fake is.
-- shortcut: the parcel of the event (writeToParcel) keeps the real notification, which only reading the parcel by
-- hand shows; upgrade like the parcel of a StatusBarNotification should apps do that.
function notificationevent(param, method)
    local event = param:getThis()
    if not param:isInstance(event, 'android.view.accessibility.AccessibilityEvent') or event:getEventType() ~= 64 or
            tostring(event:getClassName()) ~= 'android.app.Notification' or
            tostring(event:getPackageName()) == param:getPackageName() then
        return false
    end

    if method == 'initFromParcel' then
        local text = event:getText()
        if text:isEmpty() then
            return false
        end
        text:clear()
        return true
    end

    local real = param:unhooked(function()
        return event:getParcelableData()
    end)
    if not param:isInstance(real, 'android.app.Notification') then
        return false
    end
    if method == 'getParcelableData' then
        param:setResult(fakeof(param, real))
        return true
    end

    -- The text has the one of the notification after the name of the data, see AccessibilityRecord.appendTo
    local result = param:getResult()
    local from, to = string.find(result, '; ParcelableData: ' .. real:toString(), 1, true)
    if from == nil then
        return false
    end
    param:setResult(string.sub(result, 1, from - 1) .. '; ParcelableData: ' .. fakeof(param, real):toString() ..
            string.sub(result, to + 1))
    return true
end

-- The channel of a conversation which the user changed, like marked as priority, is a copy of its channel with the id
-- of the conversation, which is the id of its shortcut: "<channel> : <conversation>", see
-- NotificationManagerService.createConversationNotificationChannelForPackage. A copy of it with the id of the channel
-- and without the conversation replaces it, with its settings, like the channel of a notification of no conversation.
-- The copy is read from a parcel of the channel, which starts with a byte 1 and the id, and has the conversation
-- together after the settings: its channel, its id, whether it was demoted and whether it is important, see
-- NotificationChannel.writeToParcel; the setters of these flags are hidden.
function rankingchannel(param)
    local real = param:getResult()
    if real == nil or real:getConversationId() == nil or real:getParentChannelId() == nil then
        return false
    end
    -- The key of a notification starts with the user and the package, see StatusBarNotification.key
    local key = param:getThis():getKey()
    if key ~= nil and string.match(key, '^[^|]*|([^|]*)') == param:getPackageName() then
        return false
    end

    local made = param:getValue('channel', real)
    if made == nil then
        local parent = real:getParentChannelId()
        local conversation = real:getConversationId()
        local ok, copy = pcall(function()
            local class = luajava.bindClass('android.os.Parcel')
            local parcel = class:obtain()
            local written = class:obtain()
            real:writeToParcel(parcel, 0)
            parcel:setDataPosition(0)
            parcel:readByte()
            parcel:readString()
            local rest = parcel:dataPosition()
            local at = rest
            local found = false
            while not found and at < parcel:dataSize() do
                parcel:setDataPosition(at)
                if parcel:readString() == parent and parcel:readString() == conversation then
                    local demoted = parcel:readInt()
                    local important = parcel:readInt()
                    found = (demoted == 0 or demoted == 1) and (important == 0 or important == 1)
                end
                if not found then
                    parcel:setDataPosition(at)
                    parcel:readInt()
                    -- Parcels are of whole ints, this only guards against a loop without end
                    if parcel:dataPosition() == at then
                        break
                    end
                    at = parcel:dataPosition()
                end
            end
            local channel
            if found then
                local tail = parcel:dataPosition()
                written:writeByte(1)
                written:writeString(parent)
                written:appendFrom(parcel, rest, at - rest)
                written:writeString(nil)
                written:writeString(nil)
                written:writeInt(0)
                written:writeInt(0)
                written:appendFrom(parcel, tail, parcel:dataSize() - tail)
                written:setDataPosition(0)
                channel = luajava.bindClass('android.app.NotificationChannel').CREATOR:createFromParcel(written)
            end
            parcel:recycle()
            written:recycle()
            return channel
        end)
        if not ok or copy == nil or copy:getId() ~= parent or copy:getConversationId() ~= nil or
                copy:isImportantConversation() then
            return false
        end
        made = copy
        param:putValue('channel', made, real)
    end
    param:setResult(made)
    return true
end

-- A parcel of a StatusBarNotification has the notification of the field, which Notification.CREATOR reads back,
-- after the packages, the id, the tag, the uid and the pid, see StatusBarNotification.writeToParcel. The fake replaces
-- it, before the user, the time and the keys of the group and of the instance, which are copied; the notification is
-- read from a copy of the parcel, as reading it puts the token of its pending intents in the parcel.
function sbnparcel(param, sbn)
    local parcel = param:getArgument(0)
    local start = param:getValue('parcel', parcel)
    if start == nil or param:getException() ~= nil then
        return false
    end
    local copy = luajava.bindClass('android.os.Parcel'):obtain()
    local ok, from, to = pcall(function()
        copy:appendFrom(parcel, start, parcel:dataPosition() - start)
        copy:setDataPosition(0)
        copy:readString()
        copy:readString()
        copy:readInt()
        if copy:readInt() ~= 0 then
            copy:readString()
        end
        copy:readInt()
        copy:readInt()
        local from = copy:dataPosition()
        luajava.bindClass('android.app.Notification').CREATOR:createFromParcel(copy)
        return from, copy:dataPosition()
    end)
    if ok then
        local fake = fakeof(param, param:unhooked(function()
            return sbn:getNotification()
        end))
        parcel:setDataSize(start + from)
        parcel:setDataPosition(start + from)
        fake:writeToParcel(parcel, param:getArgument(1))
        parcel:appendFrom(copy, to, copy:dataSize() - to)
    end
    copy:recycle()
    return ok
end

-- Where the parcel of a StatusBarNotification starts, see sbnparcel
function before(hook, param)
    if hook:getMethodName() ~= 'writeToParcel' or param:getArgument(0) == nil then
        return false
    end
    param:putValue('parcel', param:getArgument(0):dataPosition(), param:getArgument(0))
    return false
end

function after(hook, param)
    local method = hook:getMethodName()
    if string.find(hook:getClassName(), 'android.view.accessibility.', 1, true) == 1 then
        return notificationevent(param, method)
    elseif method == 'getChannel' then
        return rankingchannel(param)
    end

    local sbn = param:getThis()
    if sbn ~= nil and sbn:getPackageName() == param:getPackageName() then
        return false
    end
    if method == 'writeToParcel' then
        return sbnparcel(param, sbn)
    end
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- The text ends with the one of the notification, see StatusBarNotification.toString; the real one is
    -- the notification of the field, which getNotification without this hook gives
    if method == 'toString' then
        local real = param:unhooked(function()
            return sbn:getNotification()
        end)
        local suffix = ': ' .. real:toString() .. ')'
        if string.sub(result, -#suffix) ~= suffix then
            return false
        end
        param:setResult(string.sub(result, 1, #result - #suffix) .. ': ' .. fakeof(param, real):toString() .. ')')
        return true
    end

    -- The group key is of the group and the sort key of the notification, which the fake has not, so it is the key,
    -- like for a group of one, see StatusBarNotification.groupKey; a group made by Android, which is not of the app,
    -- stays
    if method == 'getGroupKey' then
        local key = sbn:getKey()
        if sbn:getOverrideGroupKey() ~= nil or result == key then
            return false
        end
        param:setResult(key)
        return true
    end

    param:setResult(fakeof(param, result))
    return true
end
