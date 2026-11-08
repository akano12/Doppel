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

-- Contacts and calendars have the name of their account as a column, like the address of a Google account: it is
-- the fake one of Read.Account (see account_createfromparcel), by a proxy of the cursor of the provider like
-- photo_location. The accounts of the app itself keep their names, also for its sync adapter.
local COLUMNS = { 'account_name', 'ownerAccount' }

-- The types of accounts of the app itself, like account_createfromparcel
function owntypes(param)
    local context = param:getApplicationContext()
    local own = param:getValue('account.types', context)
    if own == nil then
        own = {}
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

function after(hook, param)
    local uri = param:getArgument(0)
    local result = param:getResult()
    local authority = (uri ~= nil and uri:getAuthority() or nil)
    if authority == nil or result == nil then
        return false
    end
    authority = string.gsub(authority, '^.*@', '')
    if authority ~= 'com.android.contacts' and authority ~= 'com.android.calendar' then
        return false
    end
    local hidden = {}
    local any = false
    for _, name in ipairs(COLUMNS) do
        local index = result:getColumnIndex(name)
        if index >= 0 then
            hidden[index] = true
            any = true
        end
    end
    if not any then
        return false
    end
    local fake = param:getSetting('value.email') or 'private@example.com'
    local own = owntypes(param)
    local typeIndex = result:getColumnIndex('account_type')
    local methods = {}
    -- Whether the value of a column is faked: a name of an account which is not of the app
    local function faked(index)
        return hidden[index] and result:getString(index) ~= nil and
                not (typeIndex >= 0 and own[result:getString(typeIndex)])
    end
    -- The functions of the proxy get the arguments of the method only; the others call the method of the cursor.
    -- The text of a column can be read as its bytes and into a CharArrayBuffer too.
    methods.getString = function(index)
        if faked(index) then
            return fake
        end
        return result:getString(index)
    end
    -- The proxy returns a text as its bytes for byte[]; a java.lang.String would become a text of Lua again, which a
    -- StringBuilder does not
    methods.getBlob = function(index)
        if faked(index) then
            return fake
        end
        return result:getBlob(index)
    end
    methods.copyStringToBuffer = function(index, buffer)
        if not faked(index) then
            result:copyStringToBuffer(index, buffer)
            return
        end
        local text = luajava.newInstance('java.lang.StringBuilder', fake)
        local chars = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                luajava.bindClass('java.lang.Character').TYPE, text:length())
        text:getChars(0, text:length(), chars, 0)
        buffer.data = chars
        buffer.sizeCopied = text:length()
    end
    setmetatable(methods, { __index = function(_, method)
        -- The proxy itself for equals and hashCode, like any object
        if method == 'equals' or method == 'hashCode' then
            return nil
        end
        return function(...)
            return result[method](result, ...)
        end
    end })
    param:setResult(luajava.createProxy('android.database.Cursor', methods))
    return true
end
