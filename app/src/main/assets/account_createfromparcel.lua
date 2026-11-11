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

-- The types of accounts of the app itself, which keep their names; read once, since a list of accounts is read
-- an account at a time, and the authenticators of the app do not change while it runs
function owntypes(param)
    local context = param:getApplicationContext()
    local own = param:getValue('account.types', context)
    if own == nil then
        own = {}
        -- Without the hook of the types (see account_authenticators), which would report a call of the app
        local auths = param:unhooked(function()
            return luajava.bindClass('android.accounts.AccountManager'):get(context):getAuthenticatorTypes()
        end)
        local packageName = param:getPackageName()
        for index = 1, auths['length'] do
            if auths[index].packageName == packageName then
                own[auths[index].type] = true
            end
        end
        param:putValue('account.types', own, context)
    end
    return own
end

-- The fake name of an account of another app. Android never has two accounts of a type with the same name, so each
-- name gets its own fake: the first account of the type gets the fake email, since most users have one, and the next
-- ones a number in front of the @, like private2@example.com. The numbers follow the order of the accounts the app can
-- see (getAccountsByType), the same in each process for a type it can list, also when the process first sees a later
-- account alone, like in the result of the chooser; a name which is not among them, like of the account in the
-- broadcast of its removal, gets a number after them. The lookup grants the app nothing: it lists only accounts the
-- app can see, which onAccountAccessed leaves alone. A fake stays as it is, like the name of an Account the app made
-- itself from a fake name and parcelled, also into a later process.
-- shortcut: the numbers follow the accounts of when the process first needs them, so after an account before others
-- is removed or made visible a later process gives those others another number, and the names of a type the app
-- cannot list (getAccountsByType is empty, like for a calendar app which may not see the Google accounts whose
-- calendars it reads) are numbered in the order the process reads them, which can differ between processes; keep the
-- numbers in the service to keep them
-- shortcut: a name without its type, like in a cursor of contacts without the type of its rows, gets the fake of a
-- first account; read the type of the row to number it
function fakeof(param, name, type)
    local fake = param:getSetting('value.email')
    if fake == nil then
        fake = 'private@example.com'
    end
    local at = string.find(fake, '@', 1, true) or (#fake + 1)
    local head, tail = string.sub(fake, 1, at - 1), string.sub(fake, at)
    if #name >= #fake and string.sub(name, 1, #head) == head and string.sub(name, #name - #tail + 1) == tail and
            string.match(string.sub(name, #head + 1, #name - #tail), '^%d*$') then
        return name
    end
    if type == nil then
        return fake
    end
    -- The numbers of the names of each type, shared by the hooks of the process; new tables instead of a change,
    -- because other threads can read them at the same time.
    -- shortcut: two threads which see new names at the same moment can give them the same number, which needs a lock
    local context = param:getApplicationContext()
    local types = param:getValue('account.names', context) or {}
    local numbers = types[type] or {}
    if numbers[name] == nil then
        local count, copy = 0, {}
        for known, number in pairs(numbers) do
            copy[known] = number
            count = count + 1
        end
        -- Without the hook of Account, which would fake the names; a lookup which fails numbers in the order the
        -- process sees the names
        local ok, accounts = pcall(function()
            return param:unhooked(function()
                return luajava.bindClass('android.accounts.AccountManager'):get(context):getAccountsByType(type)
            end)
        end)
        for index = 1, (ok and accounts ~= nil and accounts['length'] or 0) do
            if copy[accounts[index].name] == nil then
                count = count + 1
                copy[accounts[index].name] = count
            end
        end
        if copy[name] == nil then
            copy[name] = count + 1
        end
        numbers = copy
        local all = {}
        for known, names in pairs(types) do
            all[known] = names
        end
        all[type] = numbers
        param:putValue('account.names', all, context)
    end
    if numbers[name] == 1 then
        return fake
    end
    return head .. numbers[name] .. tail
end

-- Intents have the name of an account as text in the result of the chooser of accounts (newChooseAccountIntent),
-- without an action, a component or data, and in the broadcast of a removed account (ACTION_ACCOUNT_REMOVED); other
-- intents go by without reading their extras
function intentafter(param)
    local intent = param:getResult()
    if intent == nil then
        return false
    end
    local action = intent:getAction()
    if action == nil then
        if intent:getComponent() ~= nil or intent:getData() ~= nil then
            return false
        end
    elseif action ~= 'android.accounts.action.ACCOUNT_REMOVED' then
        return false
    end
    -- A copy, since reading the extras of the intent itself before the app sets their class loader loses the parcels
    -- of classes of the app. The copy reads with the class loader of the app, like the app does: before Android 13 a
    -- read unparcels all extras, which logs an error for a class it cannot load, and from Android 13 a value read
    -- later keeps the class loader of the read, also in the extras the intent gets back
    local extras = intent:getExtras()
    if extras == nil then
        return false
    end
    extras:setClassLoader(param:getApplicationContext():getClassLoader())
    local ok, name, type = pcall(function()
        return extras:getString('authAccount'), extras:getString('accountType')
    end)
    if not ok or name == nil or type == nil or owntypes(param)[type] then
        return false
    end
    local fake = fakeof(param, name, type)
    if fake == name then
        return false
    end
    extras:putString('authAccount', fake)
    intent:replaceExtras(extras)
    return true, name, fake
end

function after(hook, param)
    if hook:getClassName() == 'android.content.Intent' then
        return intentafter(param)
    end

    local result = param:getResult()
    if result == nil or owntypes(param)[result.type] then
        return false
    end
    local old = result.name
    local fake = fakeof(param, old, result.type)
    if fake == old then
        return false
    end
    result.name = fake
    return true, old, fake
end
