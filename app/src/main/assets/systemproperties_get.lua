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

-- The security patch level per quarter, like 2025-04-01 for 2025-05-05: a date of fewer values,
-- which apps can still parse and compare (see security_patch)
local QUARTER = {}

-- Properties behind the fields of android.os.Build with their fake values, the same as getprop
-- prints (see shell_commands); a * matches the partition, like ro.vendor.build.id
local DEVICE = {
    { 'unknown', { 'ro.product.model', 'ro.product.*.model', 'ro.product.brand', 'ro.product.*.brand',
                   'ro.product.manufacturer', 'ro.product.*.manufacturer', 'ro.product.device',
                   'ro.product.*.device', 'ro.product.name', 'ro.product.*.name', 'ro.product.board',
                   'ro.product.*.board', 'ro.soc.model', 'ro.soc.manufacturer', 'ro.hardware',
                   'ro.boot.hardware', 'ro.boot.hardware.platform', 'ro.board.platform',
                   'ro.boot.hardware.sku', 'ro.boot.product.hardware.sku', 'ro.boot.vbmeta.digest',
                   'ro.bootloader', 'ro.boot.bootloader', 'ro.build.expect.bootloader',
                   'gsm.version.baseband', 'ro.build.expect.baseband' } },
    { 'unknown', { 'ro.build.fingerprint', 'ro.*.build.fingerprint', 'ro.build.id', 'ro.*.build.id',
                   'ro.build.display.id', 'ro.build.version.incremental', 'ro.*.build.version.incremental',
                   'ro.build.version.base_os', 'ro.build.host', 'ro.build.user', 'ro.build.tags',
                   'ro.*.build.tags', 'ro.build.type', 'ro.*.build.type', 'ro.build.description',
                   'ro.build.flavor', 'ro.build.product', 'ro.build.date', 'ro.*.build.date' } },
    { QUARTER, { 'ro.build.version.security_patch', 'ro.*.build.version.security_patch',
                 'ro.*.build.security_patch' } },
    { '0', { 'ro.build.date.utc', 'ro.*.build.date.utc' } },
}

-- The properties of the serial, like Build.SERIAL, and of the operator, like TelephonyManager.getNetworkOperator
-- (see test_network), with their fake values, the same as getprop prints (see shell_commands)
local SERIAL = { 'ro.serialno', 'ro.boot.serialno', 'ril.serialnumber', 'ro.boot.ap_serial', 'sys.serialnumber',
                 'ro.ril.oem.sno', 'ro.ril.oem.psno', 'gsm.serial' }
local OPERATOR = {
    ['gsm.operator.alpha'] = 'unknown', ['gsm.sim.operator.alpha'] = 'unknown',
    ['gsm.operator.numeric'] = '00101', ['gsm.sim.operator.numeric'] = '00101',
    ['gsm.operator.iso-country'] = 'xx', ['gsm.sim.operator.iso-country'] = 'xx',
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
function fakedevice(key, result)
    local fake = NAMES[key]
    if fake == nil then
        for _, entry in ipairs(PATTERNS) do
            if string.match(key, entry[1]) then
                fake = entry[2]
                break
            end
        end
    end
    if fake == QUARTER then
        fake = quarter(result)
    end
    return fake
end

-- The first day of the quarter of a date like 2025-05-05, nil when it is no date
function quarter(date)
    local year, month = string.match(date, '^(%d%d%d%d)%-(%d%d)%-%d%d$')
    if year == nil then
        return nil
    end
    return string.format('%s-%02d-01', year, math.floor((tonumber(month) - 1) / 3) * 3 + 1)
end

-- The number of a property value, like SystemProperties.getInt and getLong parse it (android::base::ParseInt):
-- decimal, or hexadecimal with 0x, and nothing else; nil when it is no number or out of range
function number(value, method)
    local text = string.match(value, '^%s*(.*)$')
    local result
    if string.match(text, '^0[xX]%x+$') then
        result = tonumber(string.sub(text, 3), 16)
    elseif string.match(text, '^%-?%d+$') then
        result = tonumber(text)
    end
    local limit = (method == 'getInt' and 2 ^ 31 or 2 ^ 63)
    if result == nil or result < -limit or result >= limit then
        return nil
    end
    return result
end

-- SystemProperties.getInt and getLong read properties natively, not with get: the number of the fake value,
-- else the default, like for 'unknown'. Properties which are not set or not a number give the default, which stays.
function typed(hook, param, key, result, method)
    local default = param:getArgument(1)
    if result == default then
        return false
    end
    local fake
    if string.match(hook:getName(), '/(.*)$') == 'device' then
        fake = fakedevice(key, '')
    end
    if fake == nil then
        return false
    end
    fake = number(fake, method)
    if fake == nil then
        fake = default
    end
    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end

-- The properties of a variant, so its script runs for these only (see XCore.getFilter); the properties of the device
-- have patterns, its script checks the names itself
function filter(hook)
    local name = string.match(hook:getName(), '/(.*)$')
    if name == 'serial' then
        return SERIAL
    elseif name == 'operator' then
        local keys = {}
        for key, _ in pairs(OPERATOR) do
            table.insert(keys, key)
        end
        return keys
    elseif name == 'timezone' then
        return { 'persist.sys.timezone' }
    end
    return nil
end

function after(hook, param)
    -- The hooked methods are called for all properties, so check the name first
    local key = param:getArgument(0)
    if key == nil or not (string.sub(key, 1, 3) == 'ro.' or string.sub(key, 1, 4) == 'ril.' or
            string.sub(key, 1, 4) == 'gsm.' or key == 'sys.serialnumber' or key == 'persist.sys.timezone') then
        return false
    end

    local result = param:getResult()
    local method = hook:getMethodName()
    if method == 'getInt' or method == 'getLong' then
        return typed(hook, param, key, result, method)
    end

    -- Properties which are not set stay empty, a fake value would tell they are faked
    if result == nil or result == '' then
        return false
    end

    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '/(.*)$')

    local serial = false
    for _, property in ipairs(SERIAL) do
        serial = serial or property == key
    end
    if name == 'serial' and serial then
        local fake = param:getSetting('value.serial')
        if fake == nil then
            fake = 'unknown'
        end
        param:setResult(fake)
        return true, result, fake
    elseif name == 'operator' and OPERATOR[key] ~= nil then
        local fake = OPERATOR[key]
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    elseif name == 'device' then
        -- Like the fields of android.os.Build
        local fake = fakedevice(key, result)
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
