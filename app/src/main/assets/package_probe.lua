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
-- An intent Android hands back, like to the callback of a sent PendingIntent, gets the names the app asked for too.

-- The arguments with a package, else it is the first argument
local arguments = {
    checkPermission = { 1 }, -- permission, package
    checkSignatures = { 0, 1 },
    canPackageQuery = { 0, 1 }, -- source, target or targets
    getProperty = { 1 }, -- property, package or component
    isPermissionRevokedByPolicy = { 1 }, -- permission, package
    queryStatsForPackage = { 1 }, -- volume, package, user
    checkPackage = { 1 }, -- uid, package
    checkOpNoThrow = { 2 }, -- operation, uid, package
    unsafeCheckOpNoThrow = { 2 },
    checkOp = { 2 },
    unsafeCheckOp = { 2 },
    unsafeCheckOpRaw = { 2 },
    unsafeCheckOpRawNoThrow = { 2 },
    checkOpRawNoThrow = { 2 },
    setInstallerPackageName = { 0, 1 },
    -- context, request code, intent or intents: what a PendingIntent starts is sent by the system, past the hooks of
    -- the app, so one for a hidden app starts nothing
    getActivity = { 2 },
    getActivities = { 2 },
    getService = { 2 },
    getForegroundService = { 2 },
    getBroadcast = { 2 }
}

-- The substitutes of the calls in progress on this thread, for after, which comes in reverse order
local calls = {}

-- The suffix of the names which are not installed, random, so an app cannot ask for a name which looks like a substitute
-- (see hide): of the app and new each boot (see FakeIds), so every process of the app uses the same names, like for a
-- PendingIntent which another process or a later start of the app looks up again; of the process without the service
function suffixof(param)
    local suffix = param:getSetting('package_probe.suffix')
    if suffix ~= nil then
        return suffix
    end
    local context = param:getApplicationContext()
    suffix = param:getValue('package_probe.suffix', context)
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

-- The name a substitute stands for, nil for other names
function unhide(name, suffix)
    if name == nil or string.len(name) <= string.len(suffix) or string.sub(name, -string.len(suffix)) ~= suffix then
        return nil
    end
    return string.sub(name, 1, -string.len(suffix) - 1)
end

-- The copy of an intent of the app, which the substitute made last keeps with the intent, see after
function copyof(substitutes, intent, copy)
    substitutes[#substitutes].intent = intent
    substitutes[#substitutes].copy = copy
    return copy
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
        -- LauncherApps.resolveActivity and PendingIntent, a copy, because the intent is of the app
        local component = value:getComponent()
        if component ~= nil then
            local name = hide(param, component:getPackageName(), suffix, substitutes)
            if name ~= nil then
                local intent = luajava.newInstance('android.content.Intent', value)
                intent:setComponent(luajava.newInstance('android.content.ComponentName', name, component:getClassName()))
                return copyof(substitutes, value, intent)
            end
        elseif value:getSelector() ~= nil then
            -- Android sends an intent with a selector by the selector, so its package does not count
            local selector = substitute(param, value:getSelector(), suffix, substitutes)
            if selector ~= nil then
                local intent = luajava.newInstance('android.content.Intent', value)
                intent:setSelector(selector)
                return copyof(substitutes, value, intent)
            end
        elseif value:getPackage() ~= nil then
            local name = hide(param, value:getPackage(), suffix, substitutes)
            if name ~= nil then
                return copyof(substitutes, value, luajava.newInstance('android.content.Intent', value):setPackage(name))
            end
        end
    elseif param:isInstance(value, '[Landroid.content.Intent;') then
        -- PendingIntent.getActivities, a copy, because the array is of the app
        local intents = nil
        for index = 1, value['length'] do
            local intent = substitute(param, value[index], suffix, substitutes)
            if intent ~= nil then
                if intents == nil then
                    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                    intents = arrayClass:newInstance(luajava.bindClass('android.content.Intent'), value['length'])
                    for copy = 1, value['length'] do
                        intents[copy] = value[copy]
                    end
                end
                intents[index] = intent
            end
        end
        return intents
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

-- An intent Android hands back gets the names the app asked for instead of their substitutes: Android keeps the
-- intent of a PendingIntent as it was made, with the substitute, and gives the callback of a send a copy of its own
function restore(intent, suffix)
    if intent == nil then
        return
    end
    local component = intent:getComponent()
    local name = (component ~= nil and unhide(component:getPackageName(), suffix) or nil)
    if name ~= nil then
        intent:setComponent(luajava.newInstance('android.content.ComponentName', name, component:getClassName()))
    end
    name = unhide(intent:getPackage(), suffix)
    if name ~= nil then
        intent:setPackage(name)
    end
    restore(intent:getSelector(), suffix)
end

-- All occurrences of a text, without patterns: the characters which are magic in patterns are escaped
function replace(text, old, new)
    return (string.gsub(text, (string.gsub(old, '%W', '%%%0')), (string.gsub(new, '%%', '%%%%'))))
end

function before(hook, param)
    -- The callback of a PendingIntent or IntentSender the app sent, with the intent Android sent
    if hook:getMethodName() == 'performReceive' then
        restore(param:getArgument(0), suffixof(param))
        table.insert(calls, false)
        return false
    end

    local substitutes = {}
    local indexes = arguments[hook:getMethodName()]
    if indexes == nil then
        indexes = { 0 }
    end

    -- A provider of the app checks the app calling it, ContentProvider.getCallingPackage, which is not a probe
    if hook:getMethodName() == 'checkPackage' and
            param:getArgument(0) == luajava.bindClass('android.os.Binder'):getCallingUid() then
        table.insert(calls, false)
        return false
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

-- The app which defines a permission, nil when none does; asked without the hooks of the app, once per process,
-- since Context.checkPermission runs for most permission checks of the app; the same function as in package_resolve
function permissionowner(param, permission)
    local context = param:getApplicationContext()
    local key = 'permission.owner.' .. permission
    local owner = param:getValue(key, context)
    if owner == nil then
        local ok, name = pcall(function()
            return param:unhooked(function()
                return context:getPackageManager():getPermissionInfo(permission, 0).packageName
            end)
        end)
        owner = (ok and name or '')
        param:putValue(key, owner, context)
    end
    return (owner ~= '' and owner or nil)
end

-- Android answered for a package which is not installed, an exception has the name the app asked for
function after(hook, param)
    local substitutes = table.remove(calls)
    -- A permission of a hidden app which a visible package holds: a permission no app defines, which is denied
    if not substitutes and hook:getMethodName() == 'checkPermission' and param:getException() == nil and
            param:getResult() == 0 then
        local permission = param:getArgument(0)
        local owner = (permission ~= nil and permissionowner(param, permission) or nil)
        if owner ~= nil and not param:isPackageVisible(owner) then
            param:setResult(-1) -- PackageManager.PERMISSION_DENIED
            return true, owner
        end
    end
    if not substitutes then
        return false
    end

    -- Android moves the stream of a share to the clip data of the intent of an activity, with the permission to read it,
    -- which a device without Doppel does on the intent of the app and Android did on the copy here; also when the call
    -- failed after the move, since a check which fails before it, like of the flags on Android 12 and later, leaves the
    -- copy as it was
    -- shortcut: the other changes stay on the copy, the paths of a media scan (Android 10 and later) and of the output
    -- of a capture of an app targeting Android 10 or earlier (Android 11 and later), upgrade when an app reads them back
    local method = hook:getMethodName()
    if method == 'getActivity' or method == 'getActivities' then
        for _, names in ipairs(substitutes) do
            if names.copy ~= nil then
                if names.intent:getClipData() == nil and names.copy:getClipData() ~= nil then
                    names.intent:setClipData(names.copy:getClipData())
                end
                names.intent:setFlags(names.copy:getFlags())
            end
        end
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

    -- The same class, stack trace and cause, like a remote stack trace; an exception without a constructor with
    -- a message, or with its cause set already, gets the message itself, which only Java can set (see XParam).
    -- LuaJ takes another constructor when there is none with only a message, like (code, message), with other values
    local ok, fake = pcall(function()
        local made = luajava.new(ex:getClass(), message)
        if made:getMessage() ~= message then
            error('No constructor with a message')
        end
        made:setStackTrace(ex:getStackTrace())
        if ex:getCause() ~= nil then
            made:initCause(ex:getCause())
        end
        return made
    end)
    if ok then
        param:setResult(fake)
    else
        pcall(function()
            param:setMessage(ex, message)
        end)
    end
    return false
end
