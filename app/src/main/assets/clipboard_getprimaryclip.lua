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

-- Real clips have the time they were copied, made clips none; the setter is hidden, which can be refused
function stamp(fake, real)
    pcall(function() fake:setTimestamp(real:getTimestamp()) end)
end

function now()
    return luajava.bindClass('java.lang.System'):currentTimeMillis()
end

-- What an app sees of a clip: the label and the types of its description, and the text and the address of each item
function described(description)
    local seen = { tostring(description:getLabel()) }
    for i = 0, description:getMimeTypeCount() - 1 do
        seen[#seen + 1] = description:getMimeType(i)
    end
    return table.concat(seen, '\n')
end

function items(clip)
    local seen = {}
    for i = 0, clip:getItemCount() - 1 do
        local item = clip:getItemAt(i)
        seen[#seen + 1] = tostring(item:getText()) .. '\n' .. tostring(item:getUri())
    end
    return table.concat(seen, '\n')
end

-- Android gives a clip the time at which it is set, during the call of setPrimaryClip, so a clip of a time within
-- the last call of the app, which looks like the clip the app set, is the app's own, which Android gives back to the
-- app as it was set; another app can set a clip after Android stamped the one of the app, within the call.
-- shortcut: a description, which hasText reads too, has no items, so another clip of the same milliseconds with the
-- same label and types, which setText and a copy of a text view both make, keeps its real description, with its text
-- classification, and hasText; another process of the app, or the app after a restart, gets its own clip faked;
-- keep the times longer when that matters
function own(param, description, clip)
    local set = param:getValue('clipboard.own', param:getApplicationContext())
    if set == nil then
        return false
    end
    local timestamp = description:getTimestamp()
    return timestamp >= set.from and timestamp <= set.to and described(description) == set.description and
        (clip == nil or items(clip) == set.items)
end

function before(hook, param)
    if hook:getMethodName() == 'setPrimaryClip' then
        -- Android throws for no clip, before it sets anything
        local clip = param:getArgument(0)
        if clip ~= nil then
            local set = { from = now(), description = described(clip:getDescription()), items = items(clip) }
            param:putValue('clipboard.from', set, param:getApplicationContext())
        end
    end
    return false
end

function after(hook, param)
    local result = param:getResult()
    local method = hook:getMethodName()
    if method == 'setPrimaryClip' then
        -- An error, like for a clip without items, sets nothing, so the clip before stays the real one
        local context = param:getApplicationContext()
        local set = param:getValue('clipboard.from', context)
        if param:getException() == nil and set ~= nil then
            set.to = now()
            param:putValue('clipboard.own', set, context)
        end
        return false
    end

    -- hasText asks Android whether the first item of the real clip has text, which the fake clip always has; the
    -- description tells whether there is a clip, without the notice of a paste which reading the clip shows
    if method == 'hasText' then
        if result ~= false then
            return false
        end
        local manager = param:getThis()
        local description = param:unhooked(function() return manager:getPrimaryClipDescription() end)
        if description == nil or own(param, description) then
            return false
        end
        param:setResult(true)
        return true
    end

    -- The description of the clip, like from getPrimaryClipDescription: the one of the clip apps get instead,
    -- so the label and the types of the real clip stay hidden
    if method == 'getPrimaryClipDescription' then
        if result == nil or own(param, result) then
            return false
        end
        local fake = luajava.bindClass('android.content.ClipData'):newPlainText('text', 'Private'):getDescription()
        stamp(fake, result)
        param:setResult(fake)
        -- Not the label, which can name what was copied, like a password; the log keeps what it gets
        return true
    end

    if result == nil or result:getItemCount() == 0 or own(param, result:getDescription(), result) then
        return false
    end

    -- A plain label, which does not tell apps that a privacy module is installed
    local fake = result:newPlainText('text', 'Private')
    pcall(function() stamp(fake:getDescription(), result:getDescription()) end)
    param:setResult(fake)
    return true
end
