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

-- Written in 2021 by LuigiVampa92, the author of the definition, for XPrivacyLua

function after(hook, param)
    local result = param:getResult()
    if result == nil or result.length == 0 then
        return false
    end

    -- The provisioning id of Widevine is a device id too, on newer devices the inverted bits of the device id
    local propertyString = param:getArgument(0)
    if propertyString ~= 'deviceUniqueId' and propertyString ~= 'provisioningUniqueId' then
        return false
    end

    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local byteClass = luajava.bindClass('java.lang.Byte')
    local rawByteType = byteClass.TYPE
    local fake = arrayClass:newInstance(rawByteType, result.length)
    param:setResult(fake)
    return true
end
