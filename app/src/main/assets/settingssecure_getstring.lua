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

function after(hook, param)
    -- The hooked methods are called for all settings, so check the name first
    local key = param:getArgument(1)
    if key ~= 'android_id' and key ~= 'bluetooth_name' and key ~= 'device_name' and key ~= 'boot_count' and
            key ~= 'pps_oaid' and key ~= 'default_input_method' and key ~= 'enabled_input_methods' then
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    local h = hook:getName()
    local match = string.gmatch(h, '[^/]+')
    local func = match()
    local name = match()

    --log(key .. '=' .. result .. ' name=' .. name)

    if name == 'android_id' and key == 'android_id' then
        local fake = param:getSetting('value.android_id')
        if fake == nil then
            fake = '0000000000000000'
        end
        param:setResult(fake)
        return true, result, fake
    elseif (name == 'bluetooth_name' and key == 'bluetooth_name') or
            (name == 'device_name' and key == 'device_name') or
            (name == 'boot_count' and key == 'boot_count') or
            (name == 'oaid' and key == 'pps_oaid') then
        local fake
        param:setResult(fake)
        return true, result, fake
    elseif name == 'input_method' and (key == 'default_input_method' or key == 'enabled_input_methods') then
        -- Components of keyboards, like com.example/.Keyboard;subtype:com.other/.Keyboard
        local kept = {}
        for entry in string.gmatch(result, '[^:]+') do
            local packageName = string.match(entry, '^[^/]+')
            if param:isPackageVisible(packageName) then
                kept[#kept + 1] = entry
            end
        end
        local fake = table.concat(kept, ':')
        if fake == result then
            return false
        end
        if fake == '' then
            fake = nil
        end
        param:setResult(fake)
        return true, result, fake
    else
        return false
    end
end
