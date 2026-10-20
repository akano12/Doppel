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

function isdeviceproperty(key)
    if key == 'ro.soc.model' or key == 'ro.soc.manufacturer' or
            key == 'ro.boot.hardware.sku' or key == 'ro.boot.vbmeta.digest' or key == 'ro.hardware' then
        return true
    end
    -- Like Build.VERSION.SECURITY_PATCH, also the patch level of the vendor partition
    if string.sub(key, -string.len('.security_patch')) == '.security_patch' then
        return true
    end
    if string.sub(key, 1, string.len('ro.product.')) ~= 'ro.product.' then
        return false
    end
    for _, suffix in ipairs({ '.model', '.brand', '.manufacturer', '.device', '.name', '.board' }) do
        if string.sub(key, -string.len(suffix)) == suffix then
            return true
        end
    end
    return false
end

function after(hook, param)
    -- The hooked methods are called for all properties, so check the name first
    local key = param:getArgument(0)
    if key == nil or not (string.sub(key, 1, 3) == 'ro.' or string.sub(key, 1, 4) == 'ril.' or
            string.sub(key, 1, 4) == 'gsm.' or key == 'sys.serialnumber') then
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '/(.*)$')

    if name == 'serial' and (key == 'ro.serialno' or key == 'ro.boot.serialno' or
            key == 'ril.serialnumber' or key == 'ro.boot.ap_serial' or key == 'sys.serialnumber' or
            key == 'ro.ril.oem.sno' or key == 'ro.ril.oem.psno' or key == 'gsm.serial') then
        local fake = param:getSetting('value.serial')
        if fake == nil then
            fake = 'unknown'
        end
        param:setResult(fake)
        return true, result, fake
    elseif name == 'build' and string.sub(key, 1, string.len('ro.build.')) == 'ro.build.' then
        local fake
        param:setResult(fake)
        return true, result, fake
    elseif name == 'operator' and (key == 'gsm.operator.alpha' or key == 'gsm.sim.operator.alpha') then
        local fake = 'unknown'
        param:setResult(fake)
        return true, result, fake
    elseif name == 'operator' and (key == 'gsm.operator.numeric' or key == 'gsm.sim.operator.numeric') then
        local fake = '00101' -- test network
        param:setResult(fake)
        return true, result, fake
    elseif name == 'operator' and (key == 'gsm.operator.iso-country' or key == 'gsm.sim.operator.iso-country') then
        local fake = 'xx'
        param:setResult(fake)
        return true, result, fake
    elseif name == 'vendor' and string.sub(key, 1, string.len('ro.vendor.')) == 'ro.vendor.' then
        local fake
        param:setResult(fake)
        return true, result, fake
    elseif name == 'device' and result ~= '' and isdeviceproperty(key) then
        -- Like the fields of android.os.Build
        local fake = 'unknown'
        param:setResult(fake)
        return true, result, fake
    else
        return false
    end
end
