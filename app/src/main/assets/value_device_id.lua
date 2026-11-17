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

-- The IMEI and the MEID are replaced by the values of Fake values (by default none, like a device without a modem)
-- wherever Android gives them, whatever the phone type, like GsmCdmaPhone of Android, which gives both when the modem
-- reports both, also on devices without voice, like data-only tablets, whose phone type is none: getImei and
-- getPrimaryImei the IMEI, getMeid the MEID, and getDeviceId the kind of id it gives, the IMEI of 15 digits, the MEID of
-- 14, and none for anything else, like the ESN of an old CDMA phone. An empty or all-zero id is what Android gives when
-- the modem has no such id, an IMEI on every version, an all-zero MEID up to Android 11 and an empty one up to 10: it
-- stays when a fake of its kind is set, and without one it is gone like any other id, like on a device without a modem.
-- Each SIM slot has an id of its own, like on every phone with two slots, see slotid.

-- The slots of which the overloads know the fakes
-- shortcut: three slots, upgrade for a device whose default SIM is in a fourth one
local SLOTS = 3

function after(hook, param)
    -- Apps without permission keep the security exception
    local this = param:getThis()
    if this == nil or param:getException() ~= nil then
        return false
    end

    -- Android gives nothing to apps targeting Android 9 or earlier without the privilege, from Android 10
    local result = param:getResult()
    if result == nil then
        return false
    end
    local imei = param:getSetting('value.imei')
    local meid = param:getSetting('value.meid')
    -- Also the fake of the overload this one calls, like getImei() and getMeid() call the hooked overload with the slot
    -- of their subscription, which reported the fake of that slot already, also when it is of another kind than the
    -- one of this method
    for slot = 0, SLOTS - 1 do
        if result == slotid(imei, slot) or result == slotid(meid, slot) then
            return false
        end
    end

    local method = hook:getMethodName()
    local fake
    if method == 'getImei' or method == 'getPrimaryImei' then
        fake = imei
    elseif method == 'getMeid' then
        fake = meid
    elseif string.len(result) == 15 or result == '' then
        -- An empty one too, like getDeviceId of a GSM phone, which is its IMEI
        -- shortcut: the empty ESN getDeviceId of a CDMA phone may give counts as an IMEI, so it stays next to a fake IMEI
        -- while getEsn gives null, upgrade if a CDMA phone with an empty ESN matters
        fake = imei
    elseif string.len(result) == 14 then
        fake = meid
    end
    -- The none of a modem without the id, which carries nothing of the device next to a fake
    if fake ~= nil and string.match(result, '^0*$') then
        return false
    end
    -- getDeviceId() is of the first slot, the phone 0 of Android
    -- shortcut: getPrimaryImei is the fake of the first slot, upgrade if a device whose primary IMEI is of
    -- another slot matters
    if hook:getParameterTypes()['length'] == 1 then
        fake = slotid(fake, phoneof(this, param:getArgument(0)))
    end
    if result == fake then
        return false
    end
    -- Android never gives null for the primary IMEI of a device with a phone service: it throws when no phone has one
    if fake == nil and method == 'getPrimaryImei' then
        fake = luajava.newInstance('java.lang.UnsupportedOperationException', 'Operation not supported')
    end

    param:setResult(fake)
    return true, result, fake
end

-- The phone of a slot index: one which is no phone is the first one, like getDeviceId(slot) of Android takes it, and
-- like the default slot (Integer.MAX_VALUE), which getImei() and getMeid() pass without a SIM; for other indexes which
-- are no phone getImei(slot) and getMeid(slot) give null, which stays
function phoneof(this, slot)
    if slot < 0 or slot >= this:getPhoneCount() then
        return 0
    end
    return slot
end

-- The id of a slot: the one of Fake values for the first, for the others the next serial numbers of the same model,
-- the digits 9 to 14 of an IMEI and of a MEID, decimal or hexadecimal like the MEID, with the check digit of an IMEI
-- made again, so the type allocation code and the manufacturer code are the same for all slots; an id of another
-- form is the same for all slots
function slotid(id, slot)
    if id == nil or slot == 0 then
        return id
    end
    local decimal = (string.match(id, '^%d+$') ~= nil)
    local imei = (decimal and string.len(id) == 15)
    if not imei and not (string.len(id) == 14 and string.match(id, '^%x+$') ~= nil) then
        return id
    end
    local base = (decimal and 10 or 16)
    local serial = (tonumber(string.sub(id, 9, 14), base) + slot) % (decimal and 1000000 or 16777216)
    local next = string.sub(id, 1, 8) .. string.format(decimal and '%06d' or '%06X', serial)
    if imei then
        next = next .. luhn(next)
    end
    return next
end

-- The Luhn check digit of the digits, like FakeIds.luhn
function luhn(digits)
    local sum = 0
    for i = 0, string.len(digits) - 1 do
        local d = tonumber(string.sub(digits, -1 - i, -1 - i))
        if i % 2 == 0 then
            d = d * 2
            if d > 9 then
                d = d - 9
            end
        end
        sum = sum + d
    end
    return string.format('%d', (10 - sum % 10) % 10)
end
