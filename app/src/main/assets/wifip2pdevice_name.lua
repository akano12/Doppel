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
-- (see settingssecure_getstring); its name is empty. It is told apart by its address, which Android hides from apps
-- since Android 10. shortcut: on Android 8.1 and 9 the own device has its real address and keeps its name; a check of
-- the broadcast would be needed for those.
local ANONYMOUS = '02:00:00:00:00:00'

function after(hook, param)
    local device = param:getResult()
    if device == nil or device.deviceAddress ~= ANONYMOUS or device.deviceName == nil or device.deviceName == '' then
        return false
    end
    local old = device.deviceName
    device.deviceName = ''
    return true, old, ''
end
