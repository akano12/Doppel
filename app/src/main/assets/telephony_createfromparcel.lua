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

-- A SubscriptionInfo or a PhoneAccount the app gets from Android keeps the real ICCID, phone number and address in its
-- fields, which its parcel (writeToParcel), its text (toString, which prints them on debuggable builds) and reflection
-- give, while the getters give the fakes (generic_empty_text, value_phone_number_nonnull, phone_account_address). So it
-- is made again from its own parcel with the values the getters give, like Android makes it, and all of them agree.
-- Android 8.1 to 17 write these fields first, in the same order (the source of the PhoneAccount of Android 17 is not
-- published yet), and the rest of the parcel is copied as it is; an object of which the fields read back other values,
-- like of a ROM which writes other fields first, or which does not read back whole, stays as it is.
-- On Android 9 and 10 the card id or card string, which apps with the phone state permission get, is the ICCID of a SIM
-- card too, and is made none like the ICCID (see card).
-- shortcut: the EID of an eSIM in the card id or card string, the card string of Android 11 and later (which only apps
-- with identifier access get) and the group UUID of a SubscriptionInfo stay real in its parcel and fields, upgrade when
-- apps are seen reading them so.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local write = account
    if hook:getClassName() == 'android.telephony.SubscriptionInfo' then
        write = subscription
    end
    local made, old, new = remade(param, result, luajava.bindClass(hook:getClassName()).CREATOR, write)
    if made == nil then
        return false
    end
    param:setResult(made)
    return true, old, new
end

-- The object made again by the creator from the parcel of the object, of which write copies the first fields into
-- the new parcel with the fake values; nil when they stay or the new parcel does not read back whole
function remade(param, object, creator, write)
    local parcel = luajava.bindClass('android.os.Parcel')
    local real = parcel:obtain()
    local fake = parcel:obtain()
    local ok, made, old, new = pcall(function()
        param:unhooked(function()
            object:writeToParcel(real, 0)
        end)
        real:setDataPosition(0)
        local old, new = write(param, object, real, fake)
        if old == nil then
            return nil
        end
        local rest = real:dataPosition()
        fake:appendFrom(real, rest, real:dataSize() - rest)
        fake:setDataPosition(0)
        local made = param:unhooked(function()
            return creator:createFromParcel(fake)
        end)
        if fake:dataPosition() ~= fake:dataSize() then
            return nil
        end
        return made, old, new
    end)
    real:recycle()
    fake:recycle()
    -- The app gets the object of Android, the error goes to the log of the hook
    if not ok then
        error(made, 0)
    end
    return made, old, new
end

-- The ICCID and the phone number of a SubscriptionInfo, which Android writes after its id, and after its slot,
-- names, name source and icon tint; the old and the new values, nil when they stay
function subscription(param, info, real, fake)
    local text = luajava.bindClass('android.text.TextUtils').CHAR_SEQUENCE_CREATOR
    real:readInt() -- the id
    local start = real:dataPosition()
    local iccid = real:readString()
    local from = real:dataPosition()
    real:readInt() -- the slot
    text:createFromParcel(real) -- the display name
    text:createFromParcel(real) -- the carrier name
    real:readInt() -- the name source
    real:readInt() -- the icon tint
    local to = real:dataPosition()
    local number = real:readString()
    local read = param:unhooked(function()
        return iccid == info:getIccId() and number == info:getNumber()
    end)
    if not read then
        return nil
    end

    -- Like the getters: no ICCID, and the fake number or else none, where Android gives one
    local fakeiccid = iccid
    if iccid ~= nil and iccid ~= '' then
        fakeiccid = ''
    end
    local fakenumber = number
    if number ~= nil and number ~= '' then
        fakenumber = param:getSetting('value.phone_number') or ''
    end
    if fakeiccid == iccid and fakenumber == number then
        return nil
    end
    fake:appendFrom(real, 0, start)
    fake:writeString(fakeiccid)
    fake:appendFrom(real, from, to - from)
    fake:writeString(fakenumber)
    if fakeiccid ~= iccid then
        card(real, fake, iccid)
    end
    return tostring(iccid) .. ' ' .. tostring(number), tostring(fakeiccid) .. ' ' .. tostring(fakenumber)
end

-- The card id (Android 9) or the card string (Android 10) of a SubscriptionInfo, which Android writes after its number,
-- roaming, MCC, MNC, country, icon, whether it is embedded and its access rules, is the ICCID of a SIM card: none, like
-- the ICCID, and like Android 11 and later give apps without identifier access (clearCardString). Copies the fields
-- before it into the new parcel when it is the ICCID; an EID, other versions and a parcel which does not read so stay.
function card(real, fake, iccid)
    local after = real:dataPosition()
    local ok, at = pcall(function()
        local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
        if sdk ~= 28 and sdk ~= 29 then
            return nil
        end
        real:readInt() -- the data roaming
        if sdk == 28 then
            real:readInt() -- the MCC
            real:readInt() -- the MNC
        else
            real:readString()
            real:readString()
        end
        real:readString() -- the country
        if sdk == 28 then
            luajava.bindClass('android.graphics.Bitmap').CREATOR:createFromParcel(real)
        else
            real:readParcelable(nil) -- the icon with the name of its class, or none
        end
        real:readInt() -- whether it is embedded
        local rules = real:readInt()
        -- A parcel read past its end gives zeros, so a count which does not fit would loop for long
        if rules > real:dataAvail() then
            return nil
        end
        for _ = 1, rules do
            if real:readInt() ~= 0 then -- an UiccAccessRule: the hash of a certificate, a package and an access type
                real:createByteArray()
                real:readString()
                real:readLong()
            end
        end
        local at = real:dataPosition()
        if digits(real:readString()) ~= digits(iccid) then
            return nil
        end
        return at
    end)
    if ok and at ~= nil then
        fake:appendFrom(real, after, at - after)
        fake:writeString('')
    else
        real:setDataPosition(after)
    end
end

-- An ICCID without the F of padding and in capitals: the card may give it so, the records without (stripTrailingFs)
function digits(iccid)
    return iccid and (iccid:upper():gsub('F+$', ''))
end

-- The address and the subscription address of a PhoneAccount, which Android writes after its handle, each after
-- whether it has one; the old and the new values, nil when they stay
function account(param, phone, real, fake)
    local uris = luajava.bindClass('android.net.Uri').CREATOR
    if real:readInt() ~= 0 then
        luajava.bindClass('android.telecom.PhoneAccountHandle').CREATOR:createFromParcel(real)
    end
    local start = real:dataPosition()
    local addresses = {}
    for index = 1, 2 do
        if real:readInt() ~= 0 then
            addresses[index] = uris:createFromParcel(real)
        end
    end
    local read = param:unhooked(function()
        return same(addresses[1], phone:getAddress()) and same(addresses[2], phone:getSubscriptionAddress())
    end)
    if not read then
        return nil
    end

    local fakes = { fakeaddress(param, phone, addresses[1]), fakeaddress(param, phone, addresses[2]) }
    if fakes[1] == nil and fakes[2] == nil then
        return nil
    end
    fake:appendFrom(real, 0, start)
    local old, new = {}, {}
    for index = 1, 2 do
        local address = fakes[index] or addresses[index]
        if address == nil then
            fake:writeInt(0)
        else
            fake:writeInt(1)
            address:writeToParcel(fake, 0)
        end
        old[index] = tostring(addresses[index] and addresses[index]:toString())
        new[index] = tostring(address and address:toString())
    end
    return table.concat(old, ' '), table.concat(new, ' ')
end

-- Whether two addresses are the same, also none
function same(address, other)
    if address == nil then
        return other == nil
    end
    return address:equals(other)
end

-- The fake address of a phone account, nil when the address stays; the same function as in phone_account_address,
-- which a test compares
function fakeaddress(param, account, address)
    if address == nil or address:getScheme() ~= 'tel' then
        return nil
    end
    local handle = account:getAccountHandle()
    if handle ~= nil and handle:getComponentName():getPackageName() == param:getPackageName() then
        return nil
    end

    local number = param:getSetting('value.phone_number') or ''
    if address:getSchemeSpecificPart() == number then
        return nil
    end
    return luajava.bindClass('android.net.Uri'):fromParts('tel', number, nil)
end
