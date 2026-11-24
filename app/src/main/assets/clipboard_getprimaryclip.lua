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
-- shortcut: a description, which hasText, hasPrimaryClip and getPrimaryClip before its call read too, has no items, so
-- another clip of the same milliseconds with the same label and types, which setText and a copy of a text view both
-- make, keeps its real description, with its text classification, hasText and hasPrimaryClip, and getPrimaryClip calls
-- Android for it, which grants the app its content uris, before its items answer no clip; another process of the app,
-- or the app after a restart, gets no clip for its own one; keep the times longer when that matters
function own(param, description, clip)
    local set = param:getValue('clipboard.own', param:getApplicationContext())
    if set == nil then
        return false
    end
    local timestamp = description:getTimestamp()
    return timestamp >= set.from and timestamp <= set.to and described(description) == set.description and
        (clip == nil or items(clip) == set.items)
end

-- Android answers no clip when there is none or the app has no access to it (in the background, Android 10 and
-- later): getPrimaryClip and getPrimaryClipDescription null, hasPrimaryClip and hasText false; so does Doppel for a
-- clip which is not the app's own. The description, which Android gives without a grant and without the notice of a
-- paste, read without the hooks, tells whether there is a clip and whether it is the app's own.
function described_own(param)
    local manager = param:getThis()
    local description = param:unhooked(function() return manager:getPrimaryClipDescription() end)
    return description, description ~= nil and own(param, description)
end

function before(hook, param)
    local method = hook:getMethodName()
    if method == 'setPrimaryClip' then
        -- Android throws for no clip, before it sets anything
        local clip = param:getArgument(0)
        if clip ~= nil then
            local set = { from = now(), description = described(clip:getDescription()), items = items(clip) }
            param:putValue('clipboard.from', set, param:getApplicationContext())
        end
        return false
    end

    -- Android grants the app read access to the content uris of the clip it gets, and shows and logs the notice of a
    -- paste (Android 12 and later), so a clip which is not the app's own is not asked for; no clip or the own one is,
    -- and its items are compared after
    if method == 'getPrimaryClip' then
        local description, mine = described_own(param)
        if description == nil or mine then
            return false
        end
        param:setResult(nil)
        return true
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

    -- Android answers these itself, not from getPrimaryClip: true only for a clip, which the description tells
    if method == 'hasText' or method == 'hasPrimaryClip' then
        if result ~= true then
            return false
        end
        local _, mine = described_own(param)
        if mine then
            return false
        end
        param:setResult(false)
        return true
    end

    -- The label of a description can name what was copied, like a password; the log keeps what it gets
    if method == 'getPrimaryClipDescription' then
        if result == nil or own(param, result) then
            return false
        end
        param:setResult(nil)
        return true
    end

    -- getPrimaryClip of no clip or of the own one by its description: another app can set its clip after the
    -- description was read
    if result == nil or own(param, result:getDescription(), result) then
        return false
    end
    param:setResult(nil)
    return true
end
