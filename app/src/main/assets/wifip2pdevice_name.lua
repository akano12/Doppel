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

-- Copyright 2026 akano12

-- The own device of Wi-Fi Direct, like requestDeviceInfo and the broadcast THIS_DEVICE_CHANGED give it, has the name
-- users give the device, often with their own name, which the settings device_name and wifi_p2p_device_name hide
-- (see settingssecure_getstring); it gets the name Android gives a device which was never named. It is told apart by
-- its address, which Android hides from apps since Android 10; Android 8.1 and 9 give the real one (see
-- wifip2p_own_address), so there it is told by the broadcast, which only has the own device, and requestDeviceInfo does
-- not exist yet. A group the device owns, like requestGroupInfo and the broadcast CONNECTION_STATE_CHANGE give it, has
-- a network name which ends in the real name (setP2pSsidPostfix), so it is made again with the fake one; the network
-- name then differs from the one the group has on the air, which a device without Wi-Fi Direct connects to.
-- shortcut: a network name which another app chose, or this one without WifiP2pConfig.Builder (by reflection or in
-- another of its processes), gets the fake name too; upgrade if apps are seen comparing such a name with the group.
local ANONYMOUS = '02:00:00:00:00:00'

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    if hook:getMethodName() == 'setNetworkName' then
        -- A network name the app chose for its group, which it knows, so it stays (see network)
        param:putValue('wifip2p.network/' .. param:getArgument(0), true, param:getApplicationContext())
        return false
    end
    if hook:getClassName() == 'android.content.Intent' then
        return broadcast(param, result)
    end
    if hook:getClassName() == 'android.net.wifi.p2p.WifiP2pGroup' then
        return owned(hook, param, result)
    end
    if result.deviceAddress ~= ANONYMOUS then
        return false
    end
    return renamed(param, result)
end

-- The own device in the sticky broadcast THIS_DEVICE_CHANGED of Android 8.1 and 9, in a copy of the extras, like
-- wifip2p_own_address does for its address, since unparcelling those of the intent itself, still without the class
-- loader of the app, would lose parcelables of the app; its device is a new object, which reaches the app with
-- replaceExtras
function broadcast(param, intent)
    if intent:getAction() ~= 'android.net.wifi.p2p.THIS_DEVICE_CHANGED' then
        return false
    end
    local extras = intent:getExtras()
    local device = (extras ~= nil and extras:getParcelable('wifiP2pDevice') or nil)
    if device == nil then
        return false
    end
    local restricted, old, new = renamed(param, device)
    if restricted then
        intent:replaceExtras(extras)
    end
    return restricted, old, new
end

-- The own device with the fake name; one without a name, like Android has it before Wi-Fi Direct starts, stays
function renamed(param, device)
    if device.deviceName == nil or device.deviceName == '' then
        return false
    end
    local fake = fakename(param)
    if device.deviceName == fake then
        return false
    end
    local old = device.deviceName
    device.deviceName = fake
    return true, old, fake
end

-- The name Android gives a device which was never named: Android_ and 4 characters, of the Android ID of the system
-- up to Android 12 and random from 13 on; hex characters of a hash of the fake Android ID fit both
function fakename(param)
    return 'Android_' .. string.sub(param:uuidOf('wifip2p/' .. (param:getSetting('value.android_id') or '')), 1, 4)
end

-- A group the device owns, made again with the fake name in its network name. It is told by its group owner flag and,
-- from Android 10, by the anonymous address of its owner; Android 8.1 and 9 give the real address there. The owner
-- has no name, since the supplicant reports it with its address only (onGroupStarted) and Android fills in only the
-- address, so the empty name stays, like the hook of the device leaves it. The owner of a persistent group is the own
-- device with its name (updatePersistentNetworks), which the hook of the device renames from Android 10 and this on 8.1
-- and 9, where it still has the real address.
function owned(hook, param, result)
    local owner = result:getOwner()
    if owner == nil or not result:isGroupOwner() or
            (owner.deviceAddress ~= ANONYMOUS and luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 29) then
        return false
    end
    local named, was, fake = renamed(param, owner)
    local made, old, new = remade(param, result, luajava.bindClass(hook:getClassName()).CREATOR, network)
    if made == nil then
        return named, was, fake
    end
    param:setResult(made)
    return true, old, new
end

-- The network name of a group, which Android writes first on every version, with a dash and the fake name of the device
-- after DIRECT- and two characters; the old and the new name, nil when it stays. Android makes the rest of the name of
-- the device (setP2pSsidPostfix): a dash and the name, which some versions cut to 22 bytes and others leave out when it
-- is longer, since wpa_supplicant refuses a postfix over 23 bytes, and a persistent group keeps the name it was made
-- with. So the rest is not compared with the real name, which a hook cannot read at once (the broadcast of the device
-- is not sticky from Android 10 on, the setting not used from 11 on, requestDeviceInfo answers later); only a name
-- the app chose for its group (WifiP2pConfig.Builder.setNetworkName) stays.
function network(param, group, real, fake)
    local name = real:readString()
    if name == nil or name ~= group:getNetworkName() or string.match(name, '^DIRECT%-%w%w') == nil or
            param:getValue('wifip2p.network/' .. name, param:getApplicationContext()) then
        return nil
    end
    local new = string.sub(name, 1, 9) .. '-' .. fakename(param)
    if new == name then
        return nil
    end
    fake:writeString(new)
    return name, new
end

-- The object made again by the creator from the parcel of the object, of which write copies the first fields into
-- the new parcel with the fake values; nil when they stay or the new parcel does not read back whole
function remade(param, object, creator, write)
    local parcel = luajava.bindClass('android.os.Parcel')
    local real = parcel:obtain()
    local fake = parcel:obtain()
    local ok, made, old, new = pcall(function()
        param:unhooked(function()
            object:writeToParcel(real, 0)
        end)
        real:setDataPosition(0)
        local old, new = write(param, object, real, fake)
        if old == nil then
            return nil
        end
        local rest = real:dataPosition()
        fake:appendFrom(real, rest, real:dataSize() - rest)
        fake:setDataPosition(0)
        local made = param:unhooked(function()
            return creator:createFromParcel(fake)
        end)
        if fake:dataPosition() ~= fake:dataSize() then
            return nil
        end
        return made, old, new
    end)
    real:recycle()
    fake:recycle()
    -- The app gets the object of Android, the error goes to the log of the hook
    if not ok then
        error(made, 0)
    end
    return made, old, new
end
