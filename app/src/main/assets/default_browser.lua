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

-- The default browser (PackageManager.getDefaultBrowserPackageNameAsUser, and from Android 12 the role behind it,
-- RoleManager.getBrowserRoleHolder) is told to any app, whether it may see that browser or not. A hidden browser
-- is the one a device without it has, which is how default_app_filter answers for the SMS and phone apps;
-- the app itself can still tell whether it is the default. The browser the configuration of the system names stays
-- when the package visibility rules of Android 11 and later hide it from the app and the configuration names no
-- certificate: the browser role then only lets an app of the system be that default, so it tells no choice of the user.
-- With the digest of a certificate Android 13 and later let any app signed with it be the default, also one the user
-- installed, and the package need not be installed at all, so such a browser counts as hidden.
-- shortcut: another browser of the system which those rules hide from the app counts as hidden, since the app cannot
-- look up its flags; ask the service for them when that matters
-- shortcut: without a digest an app the user installed under the configured name and chose as the default stays, and
-- a configured package which is not installed is the default of a device without the hidden browser, since the app
-- cannot tell either from a hidden app of the system; ask the service for the flags of the package when that matters

-- PackageManager: MATCH_ALL, and the flags the browser role adds, MATCH_DIRECT_BOOT_AWARE and
-- MATCH_DIRECT_BOOT_UNAWARE from Android 10 and MATCH_DEFAULT_ONLY from Android 11 (BrowserRoleBehavior)
local MATCH_ALL = 0x20000
local MATCH_DIRECT_BOOT = 0x80000 + 0x40000
local MATCH_DEFAULT_ONLY = 0x10000

-- The version of Android
function sdkversion()
    return luajava.bindClass('android.os.Build$VERSION').SDK_INT
end

-- The browser the configuration of the system names, nil when it names none, and whether the configuration names the
-- digest of its certificate after a colon: config_defaultBrowser of the browser role from Android 10, default_browser
-- of the package manager before
function configuredbrowser()
    local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
    local id = resources:getIdentifier(sdkversion() >= 29 and 'config_defaultBrowser' or 'default_browser', 'string',
            'android')
    local value = (id ~= 0 and resources:getString(id) or '')
    return string.match(value, '^[^:]+'), string.find(value, ':', 1, true) ~= nil
end

-- Whether the package visibility rules of Android 11 and later hide a package from the app, which then cannot tell it
-- from one which is not installed; before Android 11 a package the app cannot look up is not installed
function unseen(param, name)
    if sdkversion() < 30 then
        return false
    end
    return not pcall(function()
        return param:unhooked(function()
            -- MATCH_UNINSTALLED_PACKAGES, like XParam looks packages up
            return param:getApplicationContext():getPackageManager():getPackageInfo(name, 0x2000)
        end)
    end)
end

-- Whether a browser is the configured one, named without a certificate, and hidden from the app by the package
-- visibility rules; the browser role then only lets an app of the system be that default (FLAG_SYSTEM, which Role
-- checks from Android 13 only when no certificate is named). One value, since param:unhooked returns only the first.
function unseenconfigured(param, name)
    local configured, signed = configuredbrowser()
    return name == configured and not signed and unseen(param, name)
end

-- The default browser of a device on which the user did not choose one, nil when there is none: the one the
-- configuration of the system names, else the only browser (of the system on Android 8.1 and 9), else from Android 12
-- the only browser of the system (applyFactoryDefaultBrowserLPw of the package manager, the default and fallback
-- holders of BrowserRoleBehavior). Browsers are the apps the app sees which handle all web links. It is the state of
-- a device which never had the hidden browser; one from which it was removed has none on Android 8.1 and 9 and only
-- the fallback on Android 10 and 11, which leaves the configured one out, a state Android gives as well.
-- shortcut: counts the browsers the app may see, which the package visibility rules of Android 11 and later can make
-- fewer than the system sees, all but the configured one; ask the service when that matters
function systembrowser(param)
    -- Its lookups are hooked themselves (see package_resolve), which would restrict them and report them
    return param:unhooked(function()
        local sdk = sdkversion()
        local pm = param:getApplicationContext():getPackageManager()
        local intent = luajava.newInstance('android.content.Intent', 'android.intent.action.VIEW',
                luajava.bindClass('android.net.Uri'):parse('http:'))
        intent:addCategory('android.intent.category.BROWSABLE')
        local flags = MATCH_ALL + (sdk >= 29 and MATCH_DIRECT_BOOT or 0) + (sdk >= 30 and MATCH_DEFAULT_ONLY or 0)
        local ok, list = pcall(pm.queryIntentActivities, pm, intent, flags)
        local browsers, all, system = {}, {}, {}
        local items = (ok and list ~= nil) and list:toArray() or { length = 0 }
        for index = 1, items['length'] do
            local info = items[index]
            local ai = info.activityInfo.applicationInfo
            if info.handleAllWebDataURI and browsers[ai.packageName] == nil and param:isVisible(ai) then
                -- FLAG_SYSTEM of ApplicationInfo
                browsers[ai.packageName] = (math.floor(ai.flags) % 2 == 1)
                all[#all + 1] = ai.packageName
                if browsers[ai.packageName] then
                    system[#system + 1] = ai.packageName
                end
            end
        end

        -- From Android 10 a browser of the system, also one the app cannot look up when the configuration names no
        -- certificate, before any package which is installed
        local name = configuredbrowser()
        if name ~= nil and ((sdk >= 29 and (browsers[name] or unseenconfigured(param, name))) or
                (sdk < 29 and param:isInstalledVisible(name))) then
            return name
        end
        if sdk < 29 then
            return (#system == 1 and system[1] or nil)
        end
        if #all == 1 then
            return all[1]
        end
        return (sdk >= 31 and #system == 1 and system[1] or nil)
    end)
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getException() ~= nil or param:isPackageVisible(result) or
            param:unhooked(function() return unseenconfigured(param, result) end) then
        return false
    end
    local fake = systembrowser(param)
    param:setResult(fake)
    return true, result, fake
end
