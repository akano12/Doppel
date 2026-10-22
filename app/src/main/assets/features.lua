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

-- Device features
-- Hide features which are not part of the Android platform (vendor features like
-- com.google.android.feature.* identify the manufacturer) and features listed in setting
-- features.hide (comma separated); apps and the framework itself check single platform
-- features to decide what works (like WebView), so these remain. getSystemAvailableFeatures
-- lists the same features as hasSystemFeature finds, an empty list would tell it is filtered.
-- The shared libraries of the system carry vendor names too, like com.google.android.dialer.support;
-- the libraries of the Android platform and the ones of Google on every device stay. Libraries of apps
-- (static and SDK libraries) stay too, apps use them. Apps can still load a library they know the name of.

local PLATFORM = { 'android.', 'androidx.', 'com.android.', 'javax.', 'org.apache.' }
local GOOGLE = { ['com.google.android.maps'] = true, ['com.google.android.media.effects'] = true }
local TYPE_BUILTIN = 0 -- SharedLibraryInfo

function hidden(name, param)
    if name == nil then
        return false
    end
    if string.sub(name, 1, 8) ~= 'android.' then
        return true
    end
    local extra = param:getSetting('features.hide')
    if extra ~= nil then
        for feature in string.gmatch(extra, '[^,%s]+') do
            if feature == name then
                return true
            end
        end
    end
    return false
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
        for index = 1, items['length'] do
            local item = items[index]
            if names then
                if not vendor(item) then
                    kept:add(item)
                end
            elseif item:getType() ~= TYPE_BUILTIN or not vendor(item:getName()) then
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
