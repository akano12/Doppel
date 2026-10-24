-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- An empty notification without content, extras and actions, built with the public API, so it has only the
-- time, the small icon and the channel of the notification, which posted notifications have and listeners use;
-- not the large icon, which is often a photo of the sender, nor the ids of the conversation or of its shortcut

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local context = param:getApplicationContext()
    local channel = nil
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 26 then
        channel = result:getChannelId()
    end
    local builder
    if channel == nil then
        builder = luajava.newInstance('android.app.Notification$Builder', context)
    else
        builder = luajava.newInstance('android.app.Notification$Builder', context, channel)
    end
    if result:getSmallIcon() ~= nil then
        builder:setSmallIcon(result:getSmallIcon())
    end
    builder:setWhen(result.when)

    local fake = builder:build()
    fake.icon = result.icon
    param:setResult(fake)
    return true
end
