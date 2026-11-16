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

-- The account types of the device name the apps offering them (packageName), which Android filters by the package
-- visibility rules from Android 11 only, and not for apps which see all packages: the types of hidden apps are left
-- out (see XParam.isPackageVisible), those of the system and of the app itself stay. An account names the app of its
-- authenticator by its type, and Android keeps no account whose authenticator is gone, so only the accounts of the
-- types the app sees stay, also of none when the lookup of the types fails.
-- shortcut: the calls which name a type themselves, like addAccount, getAuthTokenByFeatures and editProperties, still
-- reach the authenticator of a hidden app, and the chooser of accounts and the broadcast of a removed account still
-- show its accounts; answer them like for a type without an authenticator
-- shortcut: an authenticator still finds hidden apps by name for its own accounts, which Android answers for an app
-- it does not find: getPackagesAndVisibilityForAccount lists them, getAccountVisibility does not answer
-- VISIBILITY_NOT_VISIBLE and setAccountVisibility does not fail; answer them like for an app which is not installed
-- shortcut: the hidden getAuthenticatorTypesAsUser, which getAuthenticatorTypes calls from Android 11 only, and the
-- binder proxy IAccountManager$Stub$Proxy, which apps reach by reflection, still list the types and accounts of
-- hidden apps; hook them with this script like the getAccounts calls for a user and IContentService.getSyncAdapterTypes
-- shortcut: a change of an account of a hidden app which the app sees still runs its account listeners, by the
-- broadcast ACTION_VISIBLE_ACCOUNTS_CHANGED, with a list which did not change; drop the broadcast when no account of
-- the types the app sees changed

-- The packages of the authenticators by the types of accounts, read without the hook of the types, which would report
-- a call of the app; nil when the lookup fails, like when system server is gone
-- shortcut: read once per process, so the accounts of an authenticator installed while the app runs stay hidden until
-- it starts again; read again for a type not seen before to show them
function authenticators(param)
    local context = param:getApplicationContext()
    local packages = param:getValue('account.authenticators', context)
    if packages == nil then
        local ok, auths = pcall(function()
            return param:unhooked(function()
                return luajava.bindClass('android.accounts.AccountManager'):get(context):getAuthenticatorTypes()
            end)
        end)
        if not ok then
            return nil
        end
        packages = {}
        for index = 1, auths['length'] do
            packages[auths[index].type] = auths[index].packageName
        end
        param:putValue('account.authenticators', packages, context)
    end
    return packages
end

-- Whether the app sees the authenticator of a type of account in getAuthenticatorTypes
function typevisible(param, type)
    local packages = authenticators(param)
    local packageName = (packages ~= nil and packages[type] or nil)
    return packageName ~= nil and param:isPackageVisible(packageName)
end

-- The array without the items the function does not keep, in a new array of the class, also when all are left out
function filtered(param, result, className, keep)
    local kept = {}
    for index = 1, result['length'] do
        if keep(result[index]) then
            kept[#kept + 1] = result[index]
        end
    end
    if #kept == result['length'] then
        return false
    end
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local fake = arrayClass:newInstance(param:findClass(className), #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(#kept)
end

-- The argument with the type of the calls which name one, which Android answers for a type without an authenticator
-- through the future and the callback of the call (getAccountsByTypeAndFeatures) or by a SecurityException whose
-- message has no type (getAccountsAndVisibilityForPackage)
function typeargument(method)
    if method == 'getAccountsByTypeAndFeatures' then
        return 0
    elseif method == 'getAccountsAndVisibilityForPackage' then
        return 1
    end
    return nil
end

-- A type of a hidden app is asked as the empty type, which no authenticator can have (AccountAuthenticatorCache), so
-- Android answers like for a type without one: no accounts, or not the authenticator of it. No type stays, for the
-- exception of Android.
function before(hook, param)
    local index = typeargument(hook:getMethodName())
    if index == nil then
        return false
    end
    local type = param:getArgument(index)
    if type == nil or typevisible(param, type) then
        return false
    end
    param:setArgument(index, '')
    return true, type, ''
end

-- The calls which name a package, which Android answers for an app it does not find with no accounts, before it looks
-- at the type (getAccountsByTypeForPackage), or after the check of the type with an empty map, which the binder reads
-- as a HashMap (getAccountsAndVisibilityForPackage), so a hidden app is answered like one which is not installed
function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    if result == nil then
        return false
    end
    if method == 'getAccountsAndVisibilityForPackage' then
        if result:isEmpty() or param:isPackageVisible(param:getArgument(0)) then
            return false
        end
        param:setResult(luajava.newInstance('java.util.HashMap'))
        return true
    end
    if typeargument(method) ~= nil then
        return false
    end
    if method == 'getAuthenticatorTypes' then
        return filtered(param, result, 'android.accounts.AuthenticatorDescription', function(auth)
            return param:isPackageVisible(auth.packageName)
        end)
    end
    local hidden = (method == 'getAccountsByTypeForPackage' and not param:isPackageVisible(param:getArgument(1)))
    return filtered(param, result, 'android.accounts.Account', function(account)
        return not hidden and typevisible(param, account.type)
    end)
end
