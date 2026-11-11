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

local STATIC = 8 -- java.lang.reflect.Modifier.STATIC

-- The result of extractResult and of the creator, which makes the results of the extras of intents, also those the app
-- reads itself, and of the snapshots of Awareness; R8 can merge the creator with those of other classes
function after(hook, param)
    local result = param:getResult()
    if not param:isInstance(result, 'com.google.android.gms.location.ActivityRecognitionResult') then
        return false
    end

    -- The unknown activity at 100%, in a mutable list like Play services gives, at the time of the real result and with
    -- its detector id, which equals, hashCode and the parcel compare too, by the constructor the creator uses;
    -- luajava.bindClass uses the thread context class loader, which cannot see the Play services classes of the app on
    -- all threads
    local ok, fake = pcall(function()
        local classActivity = param:findClass('com.google.android.gms.location.DetectedActivity')
        local activities = luajava.newInstance('java.util.ArrayList')
        activities:add(luajava.new(classActivity, 4, 100))
        local classResult = param:findClass('com.google.android.gms.location.ActivityRecognitionResult')
        local fake = luajava.new(classResult, activities, result:getTime(), result:getElapsedRealtimeMillis(), 0, nil)
        -- The detector id has no getter and a name which changes with each version and R8, and in 10.0.1 and older it is
        -- not the only int field: every int field is copied, so the version code of those versions, which the creator
        -- reads from the parcel, comes along whatever the order of the fields. A field which cannot be copied keeps the
        -- value of a new result; once accessible, ART lets the final version code be set too.
        -- shortcut: the extras, whose content Play services does not document, are left out, since with the creator
        -- faked too they would be the only part of the real result left besides its times and int fields; a real result
        -- with extras then differs in equals, hashCode and the parcel, copy them like the ints when it is known what
        -- they hold
        pcall(function()
            local classClass = luajava.bindClass('java.lang.Class')
            local fields = classClass.getDeclaredFields(classResult)
            for index = 1, fields['length'] do
                local field = fields[index]
                if classClass.getName(field:getType()) == 'int' and bit32.band(field:getModifiers(), STATIC) == 0 then
                    pcall(function()
                        field:setAccessible(true)
                        field:setInt(fake, field:getInt(result))
                    end)
                end
            end
        end)
        return fake
    end)
    -- Without the members, which R8 can remove or rename in an app which does not use them: no result, like an intent
    -- without one, rather than the real one.
    -- shortcut: the creator keeps the real result then, since readers like the snapshot of Awareness fail on none,
    -- which is so in every app R8 shrank: no rule keeps the name of DetectedActivity, so there the extras the app
    -- reads itself and the snapshots of Awareness stay real; replace the list of the result in place, found by its
    -- type, when minified apps need it
    if not ok then
        if hook:getMethodName() ~= 'extractResult' then
            return false
        end
        fake = nil
    end
    param:setResult(fake)
    return true, result, fake
end
