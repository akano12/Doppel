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

-- The network and the SIM operator are those of the test network, MCC 001 and MNC 01,
-- in the formats of the methods; the country is empty, which Android gives for MCC 001 (MccTable has no country for
-- it), the names are unknown and the carrier is the test network of the carrier database of Android
-- (carrier_list.textpb, 1911 for 00101). Emergency numbers of the carrier have the country and MNC of the test
-- network too, and so do the cells, of which the operator of the network is the faked one; the service states and
-- subscriptions the app gets have the fakes in their fields too (see created).
-- Without a SIM or service the values are empty, which stay, a fake value would tell they are faked.

local FAKES = {
    getMcc = 1,
    getMnc = 1,
    getMccString = '001',
    getMncString = '01',
    getNetworkOperator = '00101',
    getSimOperator = '00101',
    getOperatorNumeric = '00101', -- ServiceState
    getMobileNetworkOperator = '00101', -- CellIdentity
    getRegisteredPlmn = '00101', -- NetworkRegistrationInfo
    getCountryIso = '',
    getNetworkCountryIso = '',
    getSimCountryIso = '',
    getNetworkOperatorName = 'unknown',
    getSimOperatorName = 'unknown',
    getOperatorAlphaLong = 'unknown', -- ServiceState
    getOperatorAlphaShort = 'unknown',
    getCarrierName = 'unknown', -- SubscriptionInfo
    getDisplayName = 'unknown',
    getCarrierId = 1911,
    getSimCarrierId = 1911, -- TelephonyManager
    getSimSpecificCarrierId = 1911,
    getCarrierIdFromSimMccMnc = 1911,
    getSimCarrierIdName = 'Test Network, Used by GSM test equipment',
}

-- Whether a code of 0 is empty: getMcc and getMnc of a subscription give 0 without its codes. No MCC is 0,
-- but an MNC can be (00), which is empty when the MCC is; the MCC is read without its hook, which would fake it
function nocode(method, param, this)
    if method == 'getMcc' then
        return true
    elseif method == 'getMnc' then
        this = this or param:getThis()
        return (param:unhooked(function()
            return this:getMcc()
        end) == 0)
    end
    return false
end

-- The fake of the value of a getter, nil when the value stays: an empty value, an unknown carrier (-1) and an unknown
-- code of a cell (Integer.MAX_VALUE) stay, and so does the fake itself
function faked(method, value)
    local fake = FAKES[method]
    if value == nil or value == '' or value == -1 or value == 2147483647 or value == fake then
        return nil
    end
    return fake
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == 'CREATOR:createFromParcel' then
        return created(hook, param)
    end
    local result = param:getResult()
    if type(result) == 'userdata' then
        result = result:toString() -- CharSequence
    end
    if result == 0 and nocode(method, param) then
        return false
    end
    -- The MNC of an emergency number of the carrier is text, like getMncString
    if method == 'getMnc' and hook:getClassName() == 'android.telephony.emergency.EmergencyNumber' then
        method = 'getMncString'
    end
    local fake = faked(method, result)
    if fake == nil then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end

-- A service state or a subscription the app gets from Android keeps the real operator in its fields, which its parcel
-- (writeToParcel), equals and hashCode give, also of a copy, while the getters give the fakes. Android makes each one
-- the app gets by its creator (createFromParcel), of which the hook gives it the fakes of the getters, so they agree.
function created(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    if hook:getClassName() == 'android.telephony.ServiceState' then
        return servicestate(param, result, sdk)
    end
    -- The fields after the country differ more by version: where they do not read so, the first ones are faked still
    local class = 'android.telephony.SubscriptionInfo'
    local ok, made, old, new = pcall(remade, param, result, class, layout(sdk, true))
    if not ok or made == nil then
        made, old, new = remade(param, result, class, layout(sdk, false))
    end
    if made == nil then
        return false
    end
    param:setResult(made)
    return true, old, new
end

-- The names and the code of the operator of a service state, by its public setter. Android 8.1 to 10 have them for
-- the voice and for the data network, which the setter sets alike, like Android itself sets them (ServiceStateTracker),
-- and the getters give those of voice. Android 10 and later also have the names as the network gave them (the raw
-- names), which only hidden methods set, so the state is made again from its parcel with those faked like the names;
-- Android gives them also where it removes the names from the state of an app without location.
-- shortcut: the network registrations with their cells stay real in the parcel and the fields, and so do names of
-- data on Android 8.1 to 10 where voice has none, which Android does not set apart; upgrade when apps are seen reading
-- them so.
function servicestate(param, state, sdk)
    local methods = { 'getOperatorAlphaLong', 'getOperatorAlphaShort', 'getOperatorNumeric' }
    local real = param:unhooked(function()
        return { state:getOperatorAlphaLong(), state:getOperatorAlphaShort(), state:getOperatorNumeric() }
    end)
    local fakes, old, new = {}, {}, {}
    local changed = false
    for index, method in ipairs(methods) do
        fakes[index] = faked(method, real[index]) or real[index]
        changed = changed or fakes[index] ~= real[index]
        old[index] = tostring(real[index])
        new[index] = tostring(fakes[index])
    end
    if changed then
        state:setOperatorName(fakes[1], fakes[2], fakes[3])
    end
    -- A state of which the fields do not read so, like of a ROM which writes other fields, keeps the setter's names
    local ok, made, raw, fakeraw = false, nil, nil, nil
    if sdk >= 29 then
        ok, made, raw, fakeraw = pcall(remade, param, state, 'android.telephony.ServiceState', statelayout(sdk))
    end
    if ok and made ~= nil then
        param:setResult(made)
        old[4], new[4] = raw, fakeraw
    elseif not changed then
        return false
    end
    return true, table.concat(old, ' '), table.concat(new, ' ')
end

-- The fields of a service state which Android 10 to 17 write up to its raw names, in this order: the getter of each
-- field which has one, how it is written, and the fake of a field without a getter; Android 10 writes the names of
-- voice, which the getters give, and those of data, which the setter set alike
function statelayout(sdk)
    local fields = {
        { nil, 'int' }, -- the state of voice
        { nil, 'int' }, -- the state of data
        { 'getOperatorAlphaLong', 'string' },
        { 'getOperatorAlphaShort', 'string' },
        { 'getOperatorNumeric', 'string' },
    }
    if sdk == 29 then
        for _ = 1, 3 do
            fields[#fields + 1] = { nil, 'string' } -- the names of data
        end
    end
    for _ = 1, 10 do
        fields[#fields + 1] = { nil, 'int' } -- the selection, the CDMA fields, emergency only and the boost
    end
    local raw = function(name)
        return faked('getOperatorAlphaLong', name)
    end
    for _, field in ipairs({
        { nil, 'list' }, -- the network registrations
        { nil, 'int' }, -- the channel
        { nil, 'ints' }, -- the bandwidths of the cells
        { nil, 'int' }, -- the frequency range of 5G
        { nil, 'string', raw },
        { nil, 'string', raw },
    }) do
        fields[#fields + 1] = field
    end
    return fields
end

-- The fields of a subscription which Android 8.1 to 17 write first, in this order: the getter of each field which has
-- one, how it is written, and the fake of a field without a getter; the codes are numbers up to Android 9. With tail
-- also those up to the home networks, which Android 10 and later write: the carrier, and the home networks, which have
-- no getter for apps and are none like operator_tostring prints them (an empty array, of which the count is written)
-- shortcut: the access rules and the group owner, which name the carrier too and which toString gives as none, stay
-- real in the parcel and the fields, upgrade when apps are seen reading them so.
function layout(sdk, tail)
    local code = (sdk < 29 and 'int' or 'string')
    local fields = {
        { nil, 'int' }, -- the id
        { nil, 'string' }, -- the ICCID
        { nil, 'int' }, -- the slot
        { 'getDisplayName', 'text' },
        { 'getCarrierName', 'text' },
        { nil, 'int' }, -- the name source
        { nil, 'int' }, -- the icon tint
        { nil, 'string' }, -- the number
        { nil, 'int' }, -- the data roaming
        { sdk < 29 and 'getMcc' or 'getMccString', code },
        { sdk < 29 and 'getMnc' or 'getMncString', code },
        { 'getCountryIso', 'string' },
    }
    if not tail or sdk < 29 then
        return fields
    end
    local none = function(count)
        if count > 0 then
            return 0
        end
    end
    -- False for a field which the version does not write
    local after = {
        sdk < 33 and { nil, 'icon' }, -- Android 13 and later load the icon when it is used
        { nil, 'int' }, -- whether it is embedded
        { nil, 'rules' }, -- the access rules
        { nil, 'string' }, -- the card string
        { nil, 'int' }, -- the card id
        sdk >= 33 and { nil, 'int' }, -- the port
        { nil, 'int' }, -- whether it is opportunistic
        { nil, sdk < 34 and 'string' or 'utf8' }, -- the group UUID
        { nil, 'int' }, -- whether the group is disabled
        { 'getCarrierId', 'int' },
        { nil, 'int' }, -- the profile class
        { nil, 'int' }, -- the type
        { nil, 'strings', none }, -- the equivalent home networks
        { nil, 'strings', none }, -- the home networks
    }
    for _, field in ipairs(after) do
        if field then
            fields[#fields + 1] = field
        end
    end
    return fields
end

-- An object made again by its creator from its own parcel, of which the fields of the layout have the fakes of the
-- getters, like telephony_createfromparcel makes a subscription with its ICCID and number, and the old and the new
-- values; nil when they stay, when the fields read back other values than the getters give, like of a ROM which writes
-- other fields first, or when it does not read back whole
function remade(param, object, class, fields)
    local parcel = luajava.bindClass('android.os.Parcel')
    local real = parcel:obtain()
    local fake = parcel:obtain()
    local ok, made, old, new = pcall(function()
        param:unhooked(function()
            object:writeToParcel(real, 0)
        end)
        real:setDataPosition(0)
        local old, new = {}, {}
        for _, field in ipairs(fields) do
            field.start = real:dataPosition()
            field.value = param:unhooked(function()
                return read(real, field[2])
            end)
            if field.value == false then
                return nil
            end
            field.size = real:dataPosition() - field.start
            local method = field[1]
            if method ~= nil then
                local value = param:unhooked(function()
                    return object[method](object)
                end)
                if type(value) == 'userdata' then
                    value = value:toString()
                end
                if value ~= field.value then
                    return nil
                end
                if not (value == 0 and nocode(method, param, object)) then
                    field.fake = faked(method, value)
                end
            elseif field[3] ~= nil then
                field.fake = field[3](field.value)
            end
            if field.fake ~= nil then
                old[#old + 1] = tostring(field.value)
                new[#new + 1] = tostring(field.fake)
            end
        end
        local rest = real:dataPosition()
        if #new == 0 then
            return nil
        end

        local text = luajava.bindClass('android.text.TextUtils')
        for _, field in ipairs(fields) do
            if field.fake == nil then
                fake:appendFrom(real, field.start, field.size)
            elseif field[2] == 'string' then
                fake:writeString(field.fake)
            elseif field[2] == 'text' then
                text:writeToParcel(field.fake, fake, 0)
            else
                fake:writeInt(field.fake) -- a number, or the count of an empty array
            end
        end
        fake:appendFrom(real, rest, real:dataSize() - rest)
        fake:setDataPosition(0)
        local made = param:unhooked(function()
            return luajava.bindClass(class).CREATOR:createFromParcel(fake)
        end)
        if fake:dataPosition() ~= fake:dataSize() then
            return nil
        end
        return made, table.concat(old, ' '), table.concat(new, ' ')
    end)
    real:recycle()
    fake:recycle()
    -- The app gets the object of Android, the error goes to the log of the hook
    if not ok then
        error(made, 0)
    end
    return made, old, new
end

-- A field read from a parcel as it is written: its value, of an array or a list its count, nil for a field which has
-- no value to compare or fake, and false for a count which does not fit in the parcel, like of a ROM which writes other
-- fields, of which reading on would loop for long
function read(parcel, kind)
    if kind == 'int' then
        return parcel:readInt()
    elseif kind == 'string' then
        return parcel:readString()
    elseif kind == 'text' then
        local text = luajava.bindClass('android.text.TextUtils').CHAR_SEQUENCE_CREATOR:createFromParcel(parcel)
        if type(text) == 'userdata' then
            text = text:toString() -- CharSequence
        end
        return text
    elseif kind == 'icon' then
        parcel:readParcelable(nil) -- the bitmap with the name of its class, or none
        return nil
    elseif kind == 'ints' then
        parcel:createIntArray()
        return nil
    end
    local count = parcel:readInt()
    if count > parcel:dataAvail() then
        return false
    end
    if kind == 'utf8' then
        -- Of which the reader (readString8) is hidden: the bytes and a zero after its length, padded to 4 bytes
        if count >= 0 then
            parcel:setDataPosition(parcel:dataPosition() + math.floor((count + 4) / 4) * 4)
        end
        return nil
    end
    for _ = 1, count do
        if kind == 'strings' then
            parcel:readString()
        elseif kind == 'list' then
            parcel:readValue(nil)
        elseif parcel:readInt() ~= 0 then -- an UiccAccessRule: the hash of a certificate, a package and an access type
            parcel:createByteArray()
            parcel:readString()
            parcel:readLong()
        end
    end
    return count
end
