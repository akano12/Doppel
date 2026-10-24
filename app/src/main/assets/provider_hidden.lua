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

-- Copyright 2026 akano12

-- Apps talk to each other through content providers, like Instagram asking WhatsApp for the account signed in.
-- The providers of the apps which Get.Applications hides (see package_probe) are not found, like Android answers
-- when the app of a provider is not installed: no cursor, no type, an unknown url or authority for changes and
-- calls, no file, no client. System providers and the providers of the app itself stay, like the apps.
-- An app which was given a uri of such a provider by its sender, like a photo shared with it, keeps it:
-- the grant of the sender (FLAG_GRANT_READ_URI_PERMISSION) tells the app is meant to read it.

local FLAG_GRANT_READ_URI_PERMISSION = 1
local FLAG_GRANT_WRITE_URI_PERMISSION = 2
local PERMISSION_GRANTED = 0

-- The authority of a content uri or of an authority, without the user in front, like 0@com.example.provider
-- (see contentresolver_query); nil for uris of other schemes, which the resolver serves itself
function authorityof(target)
    local authority = target
    if type(target) ~= 'string' then
        if target:getScheme() ~= 'content' then
            return nil
        end
        authority = target:getAuthority()
    end
    if authority == nil then
        return nil
    end
    return (string.gsub(authority, '^%d+@', ''))
end

-- The package of the provider of an authority, nil when Android finds none, then it answers itself;
-- found packages are kept per process, the lookup is hooked itself (see package_resolve)
function packageof(param, authority)
    local context = param:getApplicationContext()
    local key = 'provider.' .. authority
    local packageName = param:getValue(key, context)
    if packageName == nil then
        packageName = param:unhooked(function()
            local info = context:getPackageManager():resolveContentProvider(authority, 0)
            if info == nil then
                return nil
            end
            return info.packageName
        end)
        if packageName ~= nil then
            param:putValue(key, packageName, context)
        end
    end
    return packageName
end

-- Whether the sender of a uri granted the app access to it; an authority alone has no grant
function granted(param, target)
    if type(target) == 'string' then
        return false
    end
    local context = param:getApplicationContext()
    local process = luajava.bindClass('android.os.Process')
    local pid, uid = process:myPid(), process:myUid()
    return context:checkUriPermission(target, pid, uid, FLAG_GRANT_READ_URI_PERMISSION) == PERMISSION_GRANTED or
            context:checkUriPermission(target, pid, uid, FLAG_GRANT_WRITE_URI_PERMISSION) == PERMISSION_GRANTED
end

function before(hook, param)
    local target = param:getArgument(0)
    if target == nil then
        return false
    end
    local authority = authorityof(target)
    if authority == nil then
        return false
    end
    local packageName = packageof(param, authority)
    if packageName == nil or param:isPackageVisible(packageName) or granted(param, target) then
        return false
    end

    -- The answers of ContentResolver without a provider
    local method = hook:getMethodName()
    if method == 'insert' or method == 'bulkInsert' or method == 'delete' or method == 'update' then
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', 'Unknown URL ' .. target:toString()))
    elseif method == 'call' then
        local what = (type(target) == 'string' and 'authority ' or 'URI ') .. tostring(target)
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', 'Unknown ' .. what))
    elseif string.sub(method, 1, 4) == 'open' then
        param:setResult(luajava.newInstance('java.io.FileNotFoundException', 'No content provider: ' .. target:toString()))
    elseif method == 'refresh' then
        param:setResult(false)
    else
        -- query, getType, getStreamTypes, canonicalize, uncanonicalize and the content provider clients
        param:setResult(nil)
    end
    return true, packageName
end
