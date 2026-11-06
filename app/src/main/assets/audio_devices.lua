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

-- The devices of audio tell what is connected, like a headset with the name of its owner: only the speaker and the
-- earpiece stay, since a device without an output does not exist and apps playing sound ask for them.
local TYPE_BUILTIN_EARPIECE = 1
local TYPE_BUILTIN_SPEAKER = 2

function after(hook, param)
    local result = param:getResult()
    if result == nil or result['length'] == 0 then
        return false
    end
    local kept = {}
    for index = 1, result['length'] do
        local device = result[index]
        if device:isSink() and (device:getType() == TYPE_BUILTIN_SPEAKER or device:getType() == TYPE_BUILTIN_EARPIECE) then
            kept[#kept + 1] = device
        end
    end
    if #kept == result['length'] then
        return false
    end
    local fake = luajava.bindClass('java.lang.reflect.Array'):newInstance(
            luajava.bindClass('android.media.AudioDeviceInfo'), #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(#kept)
end
