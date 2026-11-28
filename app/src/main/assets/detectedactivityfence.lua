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

-- Play services evaluates the activity fences of Awareness on the real activity, the state of a fence it sends to the app
-- tells whether and when the user moved, also for fences the app combines with others. The fake activity of the snapshots
-- is unknown, which never changes, so a fence of the fake activity is true of during(UNKNOWN) alone, and the others are
-- false: each primitive fence is replaced by itself AND its negation, which is false at any evaluation, or by itself OR
-- its negation, which is true, for the unknown activity of during, whose fence tells the fake activity. Combinations of
-- these fences with others keep their meaning, like a fence of an activity and a time. The callback intents and the
-- answers of queryFences, which give the state of a registered fence, then agree with the snapshots.
-- shortcut: the state of a combination of fences which are all false may stay unknown instead of false, like a device
-- which never reports such an activity; write the state too when apps read it.
-- shortcut: a fence the app makes with parceled fences, or with the fences of a version which R8 renamed, is not seen;
-- compare the parcel of the registration when apps do that.
local UNKNOWN = 4 -- DetectedActivityFence.UNKNOWN

function after(hook, param)
    local fence = param:getResult()
    local method = hook:getMethodName()
    if fence == nil or param:getException() ~= nil or
            (method ~= 'during' and method ~= 'starting' and method ~= 'stopping') then
        return false
    end

    -- The fake activity changes never, so a fence about a change is false: only a fence of the current, fake activity is
    -- true, and of the activities the app asked for only the unknown one is, by which the fake of during is chosen
    local unknown = false
    local types = param:getArgument(0)
    if method == 'during' and types ~= nil then
        for index = 1, types['length'] do
            if types[index] == UNKNOWN then
                unknown = true
                break
            end
        end
    end

    local ok, fake = pcall(function()
        -- The fences of a parcel and of the class whose names R8 can rename are not seen; no fence, like one of a device
        -- without activity recognition, rather than the real one
        local awareness = luajava.bindClass('com.google.android.gms.awareness.fence.AwarenessFence')
        local inverse = awareness['not'](fence)
        local combine = (unknown and awareness['or'] or awareness['and'])
        return combine({ fence, inverse })
    end)
    if not ok then
        return false
    end
    param:setResult(fake)
    return true, method
end
