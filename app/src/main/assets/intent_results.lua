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

-- Play services sends its results to the PendingIntents of the app as extras of the intent, under names R8 cannot
-- rename, unlike the classes and methods of play-services-location which read them, which apps that shrank the
-- library do not have under their names. A device without activity recognition or location, or an app without the
-- permission, gets no such intents at all, so the intents with a result are marked, per variant, and do not reach the
-- receivers of the app (see receiver_skip). A service or activity Android gives the intent to cannot be left out like
-- that, so the extras answer like those of a device without the activity or location: the activity is the unknown
-- activity of activityrecognitionresult_extractresult and its transitions are none, in place of the real results, also
-- in apps without the hooks of extractResult, rather than no result, which code like the former sample of Google
-- (extractResult(intent).getProbableActivities()) fails on; the sleep events are left out, for which the hasEvents of
-- the sleep events are false and extractEvents empty; of a geofence (geofence) the transition and the location which
-- triggered it are left out and its fences are an empty list, for which GeofencingEvent.fromIntent gives an event
-- without an error, transition (-1) or fences, rather than the null of an intent without fences, which code like the
-- sample of Google (fromIntent(intent).hasError()) fails on. The error of geofences (gms_error_code), like when the
-- user turned location off, stays. The names are those of play-services-location 17.0.0 to 21.3.0.
-- This is done when the intent is read from its parcel and once more when Android sets the class loader of the
-- component it gives the intent to (Intent.setExtrasClassLoader), for a result which starts the app and so is read
-- before the hooks are there, like location updates (see location).
-- shortcut: a service or activity still gets the intent at the time of the real result, and in apps which shrank
-- Geofence.Builder and GeofencingRequest the fence Play services watches is the real one; leave out the start of the
-- component or the requests when apps time them.
-- shortcut: before Android 13 a look up reads all extras, so the parcelables of the app in extras with a result are
-- read by the hook before the app reads them, and written and read once more; read only the names from the parcel
-- when apps check that.
-- shortcut: before Android 13 the extras of an intent with binders or file descriptors stay as they are, since their
-- parcel cannot be marshalled to look for the names without reading the values of the app; look in the parcel
-- without marshalling when Play services or apps send results with such extras.
local ACTIVITY = 'com.google.android.location.internal.EXTRA_ACTIVITY_RESULT'
local ACTIVITIES = 'com.google.android.location.internal.EXTRA_ACTIVITY_RESULT_LIST'
local TRANSITIONS = 'com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT'
local KEYS = {
    activity = { ACTIVITY, ACTIVITIES, TRANSITIONS,
                 'com.google.android.location.internal.EXTRA_SLEEP_SEGMENT_RESULT',
                 'com.google.android.location.internal.EXTRA_SLEEP_CLASSIFY_RESULT' },
    geofence = { 'com.google.android.location.intent.extra.transition',
                 'com.google.android.location.intent.extra.geofence_list',
                 'com.google.android.location.intent.extra.triggering_location' },
}
-- The classes of the library which read a result with an extractResult that is hooked, in apps where R8 left them
local READERS = {
    [ACTIVITY] = 'com.google.android.gms.location.ActivityRecognitionResult',
    [ACTIVITIES] = 'com.google.android.gms.location.ActivityRecognitionResult',
    [TRANSITIONS] = 'com.google.android.gms.location.ActivityTransitionResult',
}
-- The extra of a variant which gets an empty list instead
local EMPTY = { geofence = 'com.google.android.location.intent.extra.geofence_list' }
-- The parcel of a SafeParcelable (SafeParcelWriter of play-services-basement): an object starts with the header of
-- its id and its size, a field with its id and size in one int, or with 0xFFFF in the size and the size in the next
-- int; these are the ints of the header of an object and of field 1 as Java has them
local OBJECT = -45243 -- 0xFFFF4F45, id 20293
local LIST = -65535 -- 0xFFFF0001
-- The fields of ActivityRecognitionResult kept from the real result: its times (2, 3), detector id (4) and the version
-- code of libraries 10.0.1 and older (1000), like the hook of extractResult keeps them, not its extras (5)
local KEPT = { [2] = true, [3] = true, [4] = true, [1000] = true }

function after(hook, param)
    -- Hook names are like Function/variant
    local variant = string.match(hook:getName(), '/(.*)$')
    -- Android gives an intent to a component with the class loader of the component; else that of the app
    if hook:getMethodName() == 'setExtrasClassLoader' then
        return intentafter(param, variant, param:getThis(), param:getArgument(0), true)
    end
    return intentafter(param, variant, param:getResult(), param:getApplicationContext():getClassLoader(), false)
end

-- The extras without the results, parcelled again with the class loader they had, so the app reads the other extras
-- like on a device without Doppel: parcelled until its first read (Bundle.toString). Extras the app cannot read
-- either, like of a class it does not have, stay as they are, since before Android 13 its own reads fail on them too.
function intentafter(param, variant, intent, loader, given)
    -- Extras just read from the parcel of the intent are parcelled; those of an intent given to a component may have
    -- been read or put by the app already, or done when they were read
    local extras = (intent ~= nil and intent:getExtras() or nil)
    if extras == nil or (given and (param:getValue(variant .. '.result', intent) or not parcelled(extras) or
            seen(param, variant, intent))) then
        return false
    end
    local kept = extras:getClassLoader()
    extras:setClassLoader(loader)
    if not named(extras, KEYS[variant]) then
        return false
    end
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    local ok, found, bundle = pcall(function()
        local found, changed = false, false
        for _, key in ipairs(KEYS[variant]) do
            if extras:containsKey(key) then
                found = true
                -- A result which cannot be faked stays only for a hook of extractResult, which answers with a fake
                if faked(extras, key) then
                    changed = true
                elseif not hooked(param, READERS[key]) then
                    extras:remove(key)
                    changed = true
                end
            end
        end
        if found and EMPTY[variant] ~= nil then
            extras:putSerializable(EMPTY[variant], luajava.newInstance('java.util.ArrayList'))
            changed = true
        end
        if not changed then
            return found, nil
        end
        extras:writeToParcel(parcel, 0)
        parcel:setDataPosition(0)
        return found, parcel:readBundle(kept)
    end)
    parcel:recycle()
    if not ok or not found then
        return false
    end
    -- For the receivers (see receiver_skip) and the pass when Android gives the intent, per variant, by the identity of
    -- the intent (see XParam.putValue)
    param:putValue(variant .. '.result', true, intent)
    if bundle == nil then
        return false
    end
    intent:replaceExtras(bundle)
    return true
end

-- Whether the fake of a result took the place of the real one: the unknown activity in the bytes of each result of the
-- activity, of which Play services sends the latest and can send a list, and the result without transitions; not when
-- the real value is not such bytes
function faked(extras, key)
    return READERS[key] ~= nil and pcall(function()
        if key == ACTIVITIES then
            local real, list = extras:getSerializable(key), luajava.newInstance('java.util.ArrayList')
            for index = 0, real:size() - 1 do
                list:add(unknown(real:get(index)))
            end
            extras:putSerializable(key, list)
        elseif key == ACTIVITY then
            extras:putByteArray(key, unknown(extras:getByteArray(key)))
        else
            extras:putByteArray(key, written({ OBJECT, 12, LIST, 4, 0 }))
        end
    end)
end

-- The bytes of a parcel of ActivityRecognitionResult with one DetectedActivity, the unknown activity (4) at 100%, and
-- the kept fields of the real one, like the result the hook of extractResult makes; Parcel writes its ints in the
-- order of the processor, which is little-endian on every ABI of Android
function unknown(bytes)
    local real = luajava.bindClass('java.nio.ByteBuffer'):wrap(bytes)
    real:order(luajava.bindClass('java.nio.ByteOrder').LITTLE_ENDIAN)
    local id, size, position = field(real, 0)
    local stop = position + size
    assert(id == 20293 and stop <= bytes['length'], 'not a result')
    local ranges, length = {}, 0
    while position < stop do
        local start = position
        id, size, position = field(real, position)
        -- A size of bytes the app or another one made can be negative, which would read the same field again
        assert(size >= 0, 'not a result')
        position = position + size
        if KEPT[id] then
            ranges[#ranges + 1] = { start, position - start }
            length = length + position - start
        end
    end
    assert(position == stop, 'not a result')
    -- The list of field 1 is its count and each item after its size, like Parcel.writeTypedObject
    return written({ OBJECT, 40 + length, LIST, 32, 1, 24, OBJECT, 16, 0x40001, 4, 0x40002, 100 }, bytes, ranges)
end

-- The id of the field of a parcel at a position, its size and where its value starts, like SafeParcelReader.readSize
function field(buffer, position)
    local header = buffer:getInt(position)
    local id = header % 65536
    local high = (header - id) / 65536
    if high == -1 then
        return id, buffer:getInt(position + 4), position + 8
    end
    return id, high % 65536, position + 4
end

-- The ints as a parcel, followed by ranges of the bytes of another one
function written(ints, bytes, ranges)
    local length = 4 * #ints
    for _, range in ipairs(ranges or {}) do
        length = length + range[2]
    end
    local out = luajava.bindClass('java.nio.ByteBuffer'):allocate(length)
    out:order(luajava.bindClass('java.nio.ByteOrder').LITTLE_ENDIAN)
    for _, value in ipairs(ints) do
        out:putInt(value)
    end
    for _, range in ipairs(ranges or {}) do
        out:put(bytes, range[1], range[2])
    end
    return out:array()
end

-- Whether the app has a reader of a result under its name, with its extractResult, which is how the hook of that finds
-- it; else R8 renamed or inlined it, like ActivityRecognitionResult.extractResult, whose class keeps its name
function hooked(param, name)
    if name == nil then
        return false
    end
    local ok, found = pcall(function()
        -- Lua sees a Class as its static members, so the methods of Class get the class as the object
        local methods = luajava.bindClass('java.lang.Class').getDeclaredMethods(param:findClass(name))
        for index = 1, methods['length'] do
            if methods[index]:getName() == 'extractResult' then
                return true
            end
        end
        return false
    end)
    return ok and found
end

-- Whether the extras are still in their parcel (BaseBundle.mParcelledData): else the app put or read them, and a look
-- would run its code (writeToParcel) and parcel again what it has read, which gives new objects at its next read. The
-- fields are listed like LuaJ lists them, which Android does not report as a use of a hidden API, unlike
-- getDeclaredField (see settingssecure_getstring); without the field, like if a version hides it, an intent given to
-- a component is left as it is.
function parcelled(extras)
    local ok, parcel = pcall(function()
        -- Lua sees a Class as its static members, so the methods of Class get the class as the object
        local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(luajava.bindClass('android.os.BaseBundle'))
        for index = 1, fields['length'] do
            local field = fields[index]
            if field:getName() == 'mParcelledData' then
                field:setAccessible(true)
                return field:get(extras)
            end
        end
        return nil
    end)
    return ok and parcel ~= nil
end

-- Whether the intent was given already, for when apps set the class loader again, and marks it, per variant, by the
-- identity of the intent (see XParam.putValue); not when it is read from its parcel, so the pass when Android gives it
-- can leave out what that read could not
function seen(param, variant, intent)
    local name = variant .. '.intent'
    if param:getValue(name, intent) ~= nil then
        return true
    end
    param:putValue(name, true, intent)
    return false
end

-- Whether the extras have one of the names, looked for in their parcel, which has them as UTF-16, without reading the
-- values, because a read before Android 13 reads all of them, which runs code of the app. A parcel with binders or
-- file descriptors cannot be marshalled: from Android 13 the names are looked up, since that reads no values.
function named(extras, keys)
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    extras:writeToParcel(parcel, 0)
    local ok, bytes = pcall(function()
        return parcel:marshall()
    end)
    parcel:recycle()
    if not ok and luajava.bindClass('android.os.Build$VERSION').SDK_INT < 33 then
        return false
    end
    local all = (ok and luajava.newInstance('java.lang.String', bytes,
            luajava.bindClass('java.nio.charset.StandardCharsets').UTF_16LE) or nil)
    for _, key in ipairs(keys) do
        if (ok and string.find(all, key, 1, true) ~= nil) or (not ok and extras:containsKey(key)) then
            return true
        end
    end
    return false
end
