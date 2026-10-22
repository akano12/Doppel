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

-- Properties behind the fields of android.os.Build with their fake values, the same as getprop
-- prints (see shell_commands); a * matches the partition, like ro.vendor.build.id
local DEVICE = {
    { 'unknown', { 'ro.product.model', 'ro.product.*.model', 'ro.product.brand', 'ro.product.*.brand',
                   'ro.product.manufacturer', 'ro.product.*.manufacturer', 'ro.product.device',
                   'ro.product.*.device', 'ro.product.name', 'ro.product.*.name', 'ro.product.board',
                   'ro.product.*.board', 'ro.soc.model', 'ro.soc.manufacturer', 'ro.hardware',
                   'ro.boot.hardware.sku', 'ro.boot.product.hardware.sku', 'ro.boot.vbmeta.digest',
                   'ro.bootloader', 'ro.boot.bootloader', 'gsm.version.baseband' } },
    { 'unknown', { 'ro.build.fingerprint', 'ro.*.build.fingerprint', 'ro.build.id', 'ro.*.build.id',
                   'ro.build.display.id', 'ro.build.version.incremental', 'ro.*.build.version.incremental',
                   'ro.build.version.base_os', 'ro.build.host', 'ro.build.user', 'ro.build.tags',
                   'ro.*.build.tags', 'ro.build.type', 'ro.*.build.type', 'ro.build.description',
                   'ro.build.flavor', 'ro.build.product', 'ro.build.date', 'ro.*.build.date',
                   'ro.build.version.security_patch', 'ro.*.build.version.security_patch',
                   'ro.*.build.security_patch' } },
    { '0', { 'ro.build.date.utc', 'ro.*.build.date.utc' } },
}

-- The fake values by property name and the Lua patterns of the names with a *,
-- made once because properties are read often
local NAMES = {}
local PATTERNS = {}
for _, rule in ipairs(DEVICE) do
    for _, name in ipairs(rule[2]) do
        if string.find(name, '*', 1, true) then
            local pattern = '^' .. string.gsub(name, '[%.%*]', { ['.'] = '%.', ['*'] = '.+' }) .. '$'
            PATTERNS[#PATTERNS + 1] = { pattern, rule[1] }
        else
            NAMES[name] = rule[1]
        end
    end
end

-- The fake value of a device property, nil when it is not faked
function fakedevice(key)
    if NAMES[key] ~= nil then
        return NAMES[key]
    end
    for _, entry in ipairs(PATTERNS) do
        if string.match(key, entry[1]) then
            return entry[2]
        end
    end
    return nil
end

-- The value of a property which is not set: the default of get(key, def), else empty
function unset(hook, param)
    if string.find(hook:getName(), '.default/', 1, true) then
        return param:getArgument(1)
    end
    return ''
end

function after(hook, param)
    -- The hooked methods are called for all properties, so check the name first
    local key = param:getArgument(0)
    if key == nil or not (string.sub(key, 1, 3) == 'ro.' or string.sub(key, 1, 4) == 'ril.' or
            string.sub(key, 1, 4) == 'gsm.' or key == 'sys.serialnumber' or key == 'persist.sys.timezone') then
        return false
    end

    -- Properties which are not set stay empty, a fake value would tell they are faked
    local result = param:getResult()
    if result == nil or result == '' then
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
        local fake = unset(hook, param)
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
        local fake = unset(hook, param)
        param:setResult(fake)
        return true, result, fake
    elseif name == 'device' then
        -- Like the fields of android.os.Build
        local fake = fakedevice(key)
        if fake == nil or fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    elseif name == 'timezone' and key == 'persist.sys.timezone' then
        -- Like TimeZone.getDefault
        local fake = param:getSetting('value.timezone')
        if fake == nil or fake == '' then
            fake = 'UTC'
        end
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    else
        return false
    end
end
