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

-- Apps check whether specific apps are installed, like banking apps, messengers or apps for rooted devices.
-- The package is reported as not installed, like the package lists of the group Get.Applications are filtered.
-- Own packages and system packages stay visible, because the Android framework looks them up in the app process.
-- Other packages, also packages which are not installed, are looked up under a name which is not installed,
-- so the app gets the answer of Android itself, like an exception with the same message and stack trace,
-- in which the name is replaced by the name the app asked for.

-- The arguments with a package, else it is the first argument
local arguments = {
    checkPermission = { 1 }, -- permission, package
    checkSignatures = { 0, 1 },
    canPackageQuery = { 0, 1 }, -- source, target or targets
    getProperty = { 1 } -- property, package or component
}

-- The substitutes of the calls in progress on this thread, for after, which comes in reverse order
local calls = {}

-- The suffix of the names which are not installed, random for the process,
-- so an app cannot ask for a name which looks like a substitute (see hide)
function suffixof(param)
    local context = param:getApplicationContext()
    local suffix = param:getValue('package_probe.suffix', context)
    if suffix == nil then
        suffix = '.x' .. math.random(100000000, 999999999)
        param:putValue('package_probe.suffix', suffix, context)
    end
    return suffix
end

-- The name to look up instead of a package, or nil when the package stays visible;
-- substitutes of hooks further out, like of getPackageInfo calling getPackageInfoAsUser, stay as they are
function hide(param, packageName, suffix, substitutes)
    if packageName == nil or string.sub(packageName, -string.len(suffix)) == suffix or
            param:isPackageVisible(packageName) then
        return nil
    end
    local name = packageName .. suffix
    table.insert(substitutes, { name, packageName })
    return name
end

-- The argument with the name to look up instead, or nil when it stays
function substitute(param, value, suffix, substitutes)
    if type(value) == 'string' then
        return hide(param, value, suffix, substitutes)
    elseif value == nil then
        return nil
    elseif param:isInstance(value, 'android.content.ComponentName') then
        local name = hide(param, value:getPackageName(), suffix, substitutes)
        if name ~= nil then
            return luajava.newInstance('android.content.ComponentName', name, value:getClassName())
        end
    elseif param:isInstance(value, 'android.content.Intent') then
        -- LauncherApps.resolveActivity, a copy, because the intent is of the app
        local component = value:getComponent()
        if component ~= nil then
            local name = hide(param, component:getPackageName(), suffix, substitutes)
            if name ~= nil then
                local intent = luajava.newInstance('android.content.Intent', value)
                intent:setComponent(luajava.newInstance('android.content.ComponentName', name, component:getClassName()))
                return intent
            end
        end
    elseif param:isInstance(value, '[Ljava.lang.String;') then
        -- canPackageQuery, a copy, because the array is of the app
        local names = nil
        for index = 1, value['length'] do
            local name = hide(param, value[index], suffix, substitutes)
            if name ~= nil then
                if names == nil then
                    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                    names = arrayClass:newInstance(luajava.bindClass('java.lang.String'), value['length'])
                    for copy = 1, value['length'] do
                        names[copy] = value[copy]
                    end
                end
                names[index] = name
            end
        end
        return names
    elseif param:isInstance(value, 'android.content.pm.VersionedPackage') then
        -- The last check, because the class is of Android 8 and later
        local name = hide(param, value:getPackageName(), suffix, substitutes)
        if name ~= nil then
            -- The version is part of the message of the exception
            local code
            if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 28 then
                code = value:getLongVersionCode()
            else
                code = value:getVersionCode()
            end
            return luajava.newInstance('android.content.pm.VersionedPackage', name, code)
        end
    end
    return nil
end

-- All occurrences of a text, without patterns: the characters which are magic in patterns are escaped
function replace(text, old, new)
    return (string.gsub(text, (string.gsub(old, '%W', '%%%0')), (string.gsub(new, '%%', '%%%%'))))
end

function before(hook, param)
    local substitutes = {}
    local indexes = arguments[hook:getMethodName()]
    if indexes == nil then
        indexes = { 0 }
    end

    local suffix = suffixof(param)
    for _, index in ipairs(indexes) do
        local value = substitute(param, param:getArgument(index), suffix, substitutes)
        if value ~= nil then
            param:setArgument(index, value)
        end
    end

    -- Each call has an entry, so the entries of nested calls on this thread match
    if #substitutes == 0 then
        table.insert(calls, false)
        return false
    end
    table.insert(calls, substitutes)
    return true, substitutes[1][2]
end

-- Android answered for a package which is not installed, an exception has the name the app asked for
function after(hook, param)
    local substitutes = table.remove(calls)
    if not substitutes then
        return false
    end

    local ex = param:getException()
    if ex == nil or ex:getMessage() == nil then
        return false
    end

    local message = ex:getMessage()
    for _, names in ipairs(substitutes) do
        message = replace(message, names[1], names[2])
    end
    if message == ex:getMessage() then
        return false
    end

    -- The same class, stack trace and cause, like a remote stack trace
    local fake = luajava.new(ex:getClass(), message)
    fake:setStackTrace(ex:getStackTrace())
    if ex:getCause() ~= nil then
        fake:initCause(ex:getCause())
    end
    param:setResult(fake)
    return false
end
