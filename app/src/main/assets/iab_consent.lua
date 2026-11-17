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

-- The consent strings of the IAB which a consent tool of the app stored in its preferences are not there, like in an
-- app without such a tool: TCF version 2 and 1, and GPP, which can carry the TCF string as one of its sections. Ad SDKs
-- like those of Amazon and Unity read them before the consent given by their own API and send them with their
-- requests; without them they send the consent Use.Analytics declines. Whether GDPR applies is there as yes, see
-- applies.
-- shortcut: the getters, contains and getAll of the preferences of Android, not the keys a change listener hears, the
-- preferences file read as a file or preferences of the app's own implementation, upgrade when an SDK reads the strings
-- another way.
local PREFIXES = { 'IABTCF_', 'IABConsent_', 'IABGPP_' }
local APPLIES = 'IABTCF_gdprApplies'
-- Yes in the type the consent tool stored, a number by the IAB, text or a boolean by some tools
local YES = { number = 1, string = '1', boolean = true }
-- The type of value each getter gives, the getters of the other types throw for it
local GETTERS = { getString = 'string', getBoolean = 'boolean' }

-- Only these keys run the script for the getters by key; getAll has no key and runs it for each call
function filter(hook)
    if hook:getMethodName() == 'getAll' then
        return nil
    end
    local keys = {}
    for _, prefix in ipairs(PREFIXES) do
        keys[#keys + 1] = prefix .. '*'
    end
    return keys
end

-- The filter also lets other keys through, like those with ../, which only the scripts tell apart
function hidden(key)
    if type(key) ~= 'string' or key == APPLIES then
        return false
    end
    for _, prefix in ipairs(PREFIXES) do
        if key:sub(1, #prefix) == prefix then
            return true
        end
    end
    return false
end

-- Whether GDPR applies, as stored and as the app gets it: yes whenever the consent tool stored keys of the IAB, which
-- tells nothing about where the user is, so SDKs which find no consent string have no consent, like Amazon, which then
-- reads no advertising id and clears what it stored; not there without such keys
function applies(all)
    local stored = all:get(APPLIES)
    if stored ~= nil then
        return stored, YES[type(stored)] or stored
    end
    local keys = all:keySet():iterator()
    while keys:hasNext() do
        if hidden(keys:next()) then
            return nil, 1
        end
    end
    return nil, nil
end

-- Not stored: the caller's default, also for a value of another type, which would throw. Whether GDPR applies is read
-- from all keys without the hooks, which would hide them.
-- shortcut: the getters of the other types give the default where only other keys of the IAB are stored, not the
-- ClassCastException of a number, getInt and getLong give yes whatever type is stored, also where they would throw,
-- and getAll has it as Integer 1 also where getString gives the text a tool stored, upgrade when an SDK reads it so.
function before(hook, param)
    local method = hook:getMethodName()
    if method == 'getAll' then
        return false
    end
    local key = param:getArgument(0)
    if key == APPLIES then
        local stored, fake = applies(param:unhooked(function()
            return param:getThis():getAll()
        end))
        if method == 'contains' then
            -- Stored, or not there for the app either
            if stored ~= nil or fake == nil then
                return false
            end
            param:setResult(true)
        elseif method == 'getInt' or method == 'getLong' then
            -- Yes as the number of the IAB whatever type the tool stored, since SDKs like Amazon's read it only so
            -- and take a getter which throws as not known, which would leave them without the consent gate
            if fake == nil then
                return false
            end
            param:setResult(1)
        else
            -- The same, or the getter of another type, which throws for the stored value or gives the default
            if fake == stored or GETTERS[method] ~= type(fake) then
                return false
            end
            param:setResult(fake)
        end
        return true
    end
    if not hidden(key) then
        return false
    end
    if method == 'contains' then
        param:setResult(false)
    else
        param:setResult(param:getArgument(1))
    end
    return true
end

-- getAll gives a copy of the stored values on all versions, so the keys removed from it stay stored
function after(hook, param)
    if hook:getMethodName() ~= 'getAll' then
        return false
    end
    local all = param:getResult()
    if all == nil then
        return false
    end
    local stored, fake = applies(all)
    -- Yes as the number of the IAB whatever type the tool stored, since Amazon reads it from getAll after a change and
    -- takes only an Integer 1 or true as yes
    -- shortcut: a stored Long 1 stays, which Amazon takes as not known, upgrade when a consent tool stores a Long.
    local yes = fake ~= nil and stored ~= 1
    local changed = yes
    local keys = all:keySet():iterator()
    while keys:hasNext() do
        if hidden(keys:next()) then
            keys:remove()
            changed = true
        end
    end
    if yes then
        all:put(APPLIES, 1)
    end
    return changed
end
