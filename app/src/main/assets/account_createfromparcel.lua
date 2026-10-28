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
        local auths = luajava.bindClass('android.accounts.AccountManager'):get(context):getAuthenticatorTypes()
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

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    if not owntypes(param)[result.type] then
        local old = result.name
        local fake = param:getSetting('value.email')
        if fake == nil then
            fake = 'private@example.com'
        end
        result.name = fake
        return true, old, fake
    else
        return false
    end
end
