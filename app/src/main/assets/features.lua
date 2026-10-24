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

-- Device features
-- The features of a device (android.hardware.* and android.software.*, some with a version, and the ones
-- of its maker, like com.google.android.feature.* or com.samsung.feature.*) tell its model. Apps get none:
-- hasSystemFeature answers false and getSystemAvailableFeatures lists only the OpenGL ES version, which has
-- no name, except the features of setting features.keep (comma separated; a name ending in * matches the
-- features starting with it, like android.hardware.camera*), for apps which need a feature to offer something,
-- like the camera. WebView does not start on a device without android.software.webview: the framework itself
-- (android.webkit) keeps that answer, an app asking gets false like for the other features.
-- The shared libraries of the system carry vendor names too, like com.google.android.dialer.support;
-- the libraries of the Android platform and the ones of Google on every device stay. Libraries of apps
-- (static and SDK libraries) stay too, apps use them, in the list of names as well as in the list
-- of SharedLibraryInfo. Apps can still load a library they know the name of.

local PLATFORM = { 'android.', 'androidx.', 'com.android.', 'javax.', 'org.apache.' }
local GOOGLE = { ['com.google.android.maps'] = true, ['com.google.android.media.effects'] = true }
-- SharedLibraryInfo: of the system (in its configuration or declared by a system app), static and SDK libraries
-- are of apps
local TYPE_BUILTIN = 0
local TYPE_DYNAMIC = 1

-- Whether a feature is one of setting features.keep; a name ending in * matches the features starting with it
function kept(name, param)
    local keep = param:getSetting('features.keep')
    if keep == nil then
        return false
    end
    for feature in string.gmatch(keep, '[^,%s]+') do
        if string.sub(feature, -1) == '*' then
            local prefix = string.sub(feature, 1, -2)
            if string.sub(name, 1, string.len(prefix)) == prefix then
                return true
            end
        elseif feature == name then
            return true
        end
    end
    return false
end

-- The classes on the stack, which tell the calls of the framework from the ones of the app
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- Whether a class of a package is on the stack, like android.webkit. when WebView checks its feature
function calledby(prefix)
    local frames = callers()
    for index = 1, frames['length'] do
        if string.sub(frames[index]:getClassName(), 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

-- Whether a feature is hidden from the app: all but the kept ones; the OpenGL ES version has no name
function hidden(name, param)
    if name == nil or kept(name, param) then
        return false
    end
    return not (name == 'android.software.webview' and calledby('android.webkit.'))
end

-- Whether a shared library of the system is not part of the Android platform
function vendor(name)
    if name == nil or GOOGLE[name] then
        return false
    end
    for _, prefix in ipairs(PLATFORM) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return false
        end
    end
    return true
end

-- Whether a shared library is a vendor library of the system, see vendor
function vendorlibrary(info)
    local kind = info:getType()
    return ((kind == TYPE_BUILTIN or kind == TYPE_DYNAMIC) and vendor(info:getName()))
end

-- The names of the libraries of apps, which getSystemSharedLibraryNames lists too, but without the type;
-- none before Android 8, which has neither these libraries nor getSharedLibraries
function applibraries(param)
    local names = {}
    -- The lookup is hooked itself (see after), which would restrict it and report it as a call of the app
    local list = param:unhooked(function()
        local pm = param:getApplicationContext():getPackageManager()
        local ok, result = pcall(pm.getSharedLibraries, pm, 0)
        return (ok and result or nil)
    end)
    if list ~= nil then
        local items = list:toArray()
        for index = 1, items['length'] do
            local kind = items[index]:getType()
            if kind ~= TYPE_BUILTIN and kind ~= TYPE_DYNAMIC then
                names[items[index]:getName()] = true
            end
        end
    end
    return names
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    if method == 'getSystemSharedLibraryNames' or method == 'getSharedLibraries' then
        -- A string array of names, or a list of SharedLibraryInfo
        local names = (method == 'getSystemSharedLibraryNames')
        local items = (names and result or result:toArray())
        local kept = luajava.newInstance('java.util.ArrayList')
        local apps
        for index = 1, items['length'] do
            local item = items[index]
            if names then
                if vendor(item) and apps == nil then
                    apps = applibraries(param)
                end
                if not vendor(item) or apps[item] then
                    kept:add(item)
                end
            elseif not vendorlibrary(item) then
                kept:add(item)
            end
        end
        if kept:size() == items['length'] then
            return false
        end
        if names then
            local stringClass = luajava.bindClass('java.lang.String')
            local arrayClass = luajava.bindClass('java.lang.reflect.Array')
            param:setResult(kept:toArray(arrayClass:newInstance(stringClass, 0)))
        else
            param:setResult(kept)
        end
        return true, tostring(items['length']), tostring(kept:size())
    end

    if method == 'hasSystemFeature' then
        local name = param:getArgument(0)
        if result and hidden(name, param) then
            param:setResult(false)
            return true, name, 'false'
        end
        return false
    end

    -- getSystemAvailableFeatures, the OpenGL ES version has no name
    local kept = luajava.newInstance('java.util.ArrayList')
    for index = 1, result['length'] do
        if not hidden(result[index].name, param) then
            kept:add(result[index])
        end
    end
    if kept:size() == result['length'] then
        return false
    end
    local featureClass = luajava.bindClass('android.content.pm.FeatureInfo')
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    param:setResult(kept:toArray(arrayClass:newInstance(featureClass, 0)))
    return true, tostring(result['length']), tostring(kept:size())
end
