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

-- Apps talk to each other through content providers, like Instagram asking WhatsApp for the account signed in.
-- The providers of the apps which Get.Applications hides (see package_probe) are not found, like Android answers
-- when the app of a provider is not installed: no cursor, no type, an unknown url or authority for changes and
-- calls, no file, no client, no change observer or notification. System providers and the providers of the app
-- itself stay, like the apps.
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
    return (string.gsub(authority, '^.*@', ''))
end

-- The package of the provider of an authority, found with the flags of PackageManager.resolveContentProvider like
-- Android looks for it, nil when Android finds none, then it answers itself; found packages are kept per process,
-- the lookup is hooked itself (see package_resolve)
function packageof(param, authority, flags)
    local context = param:getApplicationContext()
    local key = 'provider.' .. flags .. '.' .. authority
    local packageName = param:getValue(key, context)
    if packageName == nil then
        packageName = param:unhooked(function()
            local info = context:getPackageManager():resolveContentProvider(authority, flags)
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

-- Whether the sender of a uri granted the app access to it. An authority alone has no grant, but DocumentsContract
-- goes by the authority of a document the user picked: from Android 10 with call and the uri in the extras, on
-- Android 8.1 and 9 with a client of the authority, without the uri.
-- shortcut: a client needs a persisted grant, Android lists no others, upgrade when apps lose a document on Android 9
function granted(param, method, target, authority)
    local context = param:getApplicationContext()
    if type(target) == 'string' then
        if method == 'acquireUnstableContentProviderClient' then
            local permissions = context:getContentResolver():getPersistedUriPermissions()
            for index = 0, permissions:size() - 1 do
                if authorityof(permissions:get(index):getUri()) == authority then
                    return true
                end
            end
            return false
        end
        local extras = (method == 'call' and param:getArgument(3) or nil)
        target = (extras ~= nil and extras:getParcelable('uri') or nil) -- DocumentsContract.EXTRA_URI
        if not param:isInstance(target, 'android.net.Uri') or authorityof(target) ~= authority then
            return false
        end
    end
    local process = luajava.bindClass('android.os.Process')
    local pid, uid = process:myPid(), process:myUid()
    return context:checkUriPermission(target, pid, uid, FLAG_GRANT_READ_URI_PERMISSION) == PERMISSION_GRANTED or
            context:checkUriPermission(target, pid, uid, FLAG_GRANT_WRITE_URI_PERMISSION) == PERMISSION_GRANTED
end

-- The user of the authority of a uri like Android reads it (ContentProvider.getUserIdFromAuthority): the number in
-- front of the last @, like 10@com.example.provider, the user of the app without one, USER_NULL when it is no number
function userof(authority, own)
    local prefix = (authority ~= nil and string.match(authority, '^(.*)@') or nil)
    if prefix == nil then
        return own
    end
    local ok, user = pcall(function()
        return luajava.bindClass('java.lang.Integer'):parseInt(prefix)
    end)
    return (ok and user or -10000)
end

-- Whether USER_CURRENT (-2) is the user of the app, which ContentService takes for the current user and refuses
-- otherwise before it looks for a provider. From Android 12 UserManager tells it for the app's own user without a
-- permission.
-- shortcut: before Android 12 the current user is not known without a permission, so only user 0 counts as current,
-- which Android refuses while another user is in the foreground, and a secondary user in the foreground finds the
-- hidden provider, upgrade when apps of other users observe hidden providers on Android 11 and before
function foreground(param, own)
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 31 then
        return own == 0
    end
    local context = param:getApplicationContext()
    return param:unhooked(function()
        return context:getSystemService('user'):isUserForeground()
    end)
end

-- Change observers and notifications: for apps targeting Android 8 and later ContentService checks the provider of
-- each uri, disabled ones too, and throws without one, but goes on for a provider the app may use, like an exported
-- one. Android lets older targets go on without a provider too.
-- shortcut: for older targets Android ignores observers and notifications of a non-exported or permission-protected
-- hidden provider because its app is there, without the app they go on, upgrade when apps targeting below Android 8
-- observe such providers
-- shortcut: Android names the first uri of several it refuses, which can be one of a visible provider the app may not
-- use before one of a hidden app, upgrade when apps notify such uris together
-- shortcut: only the granted uris of a hidden provider stay, Android lets its other uris through too, upgrade when
-- apps observe more of a provider which shared a uri with them
function unobserved(hook, param, method)
    if param:getApplicationContext():getApplicationInfo().targetSdkVersion < 26 then
        return false
    end
    local target = param:getArgument(0)
    if target == nil or (method == 'registerContentObserver' and param:getArgument(2) == nil) then
        return false -- Android throws for the missing uri or observer first
    end
    -- The hidden overloads for a user get it as last argument and give ContentService the uri as it is, the others
    -- take the user from in front of the authority, whatever the scheme. ContentResolver notifies a collection of
    -- uris by user, in ascending order of the users and in the order of the collection for each, with one call of
    -- ContentService for each user, which notifies the observers of its uris before the next call.
    -- shortcut: the users which pass and sort before the user of a hidden uri are not notified, Android notifies them
    -- before it throws, upgrade when apps notify uris of USER_CURRENT (-2@) or other users together with others
    local own = math.floor(luajava.bindClass('android.os.Process'):myUid() / 100000) -- UserHandle.getUserId
    local user = (hook:getParameterTypes()['length'] == 4 and param:getArgument(3) or nil)
    local array = (param:isInstance(target, 'java.util.Collection') and target:toArray() or { target, length = 1 })
    local uris = {}
    for index = 1, array['length'] do
        local uri = array[index]
        uris[index] = { uri = uri, user = user or userof(uri ~= nil and uri:getAuthority() or nil, own), index = index }
    end
    table.sort(uris, function(a, b)
        return a.user < b.user or (a.user == b.user and a.index < b.index)
    end)
    for _, entry in ipairs(uris) do
        -- Android answers itself for a missing uri or one without a provider, so before the uris after it, and first
        -- for a user which is not the app's: another user needs permissions to it, an invalid one throws; no provider
        -- has an authority with a user, which the hidden overloads pass on. ContentService checks the authority of a
        -- uri of any scheme; an opaque uri has none.
        -- shortcut: USER_ALL (-1) is refused like to apps without INTERACT_ACROSS_USERS_FULL, upgrade when system apps
        -- observe hidden providers
        local uri = entry.uri
        local authority = (uri ~= nil and uri:getAuthority() ~= nil) and authorityof(uri:getAuthority()) or nil
        if authority == nil then
            return false
        end
        if (user ~= nil and string.find(uri:getAuthority(), '@', 1, true)) or
                (entry.user ~= own and (entry.user ~= -2 or not foreground(param, own))) then
            return false
        end
        -- MATCH_DISABLED_COMPONENTS, MATCH_DIRECT_BOOT_UNAWARE and MATCH_DIRECT_BOOT_AWARE, like ContentService
        local packageName = packageof(param, authority, 0x200 + 0x40000 + 0x80000)
        if packageName == nil then
            return false
        end
        if not param:isPackageVisible(packageName) and not granted(param, method, uri, authority) then
            local message = 'Failed to find provider ' .. authority .. ' for user ' .. own ..
                    '; expected to find a valid ContentProvider for this authority'
            param:setResult(luajava.newInstance('java.lang.SecurityException', message))
            return true, packageName
        end
    end
    return false
end

function before(hook, param)
    local method = hook:getMethodName()
    if method == 'registerContentObserver' or method == 'notifyChange' then
        return unobserved(hook, param, method)
    end
    local target = param:getArgument(0)
    if target == nil then
        return false
    end
    local authority = authorityof(target)
    if authority == nil then
        return false
    end
    local packageName = packageof(param, authority, 0)
    if packageName == nil or param:isPackageVisible(packageName) or granted(param, method, target, authority) then
        return false
    end

    -- The answers of ContentResolver without a provider, of which update says URI and the others URL
    if method == 'insert' or method == 'bulkInsert' or method == 'delete' or method == 'update' then
        local what = (method == 'update' and 'Unknown URI ' or 'Unknown URL ')
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', what .. target:toString()))
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
