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

-- The address of the own device of Wi-Fi Direct is a fixed hardware id, like the MAC address of Wi-Fi. Android 8.1
-- and 9 give it to any app: in the sticky broadcast THIS_DEVICE_CHANGED, which needs no permission, and as the owner
-- of a group of which the device is the group owner, in the broadcast CONNECTION_STATE_CHANGE, requestGroupInfo and
-- the persistent groups. Android 10 and later give 02:00:00:00:00:00 there (eraseOwnDeviceAddress), and so does this;
-- an empty address stays, like Android leaves it, and the devices around keep theirs.
local ANONYMOUS = '02:00:00:00:00:00'

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local device, extras
    if hook:getClassName() == 'android.content.Intent' then
        if result:getAction() ~= 'android.net.wifi.p2p.THIS_DEVICE_CHANGED' then
            return false
        end
        -- A copy of the extras, like intent_createfromparcel, since unparcelling those of the intent itself, still without
        -- the class loader of the app, would lose parcelables of the app it put in too; its device is a new object, which
        -- reaches the app with replaceExtras
        extras = result:getExtras()
        device = (extras ~= nil and extras:getParcelable('wifiP2pDevice') or nil)
    elseif result:isGroupOwner() then
        device = result:getOwner()
    end

    if device == nil or device.deviceAddress == nil or device.deviceAddress == '' or device.deviceAddress == ANONYMOUS then
        return false
    end
    local old = device.deviceAddress
    device.deviceAddress = ANONYMOUS
    if extras ~= nil then
        result:replaceExtras(extras)
    end
    return true, old, ANONYMOUS
end
