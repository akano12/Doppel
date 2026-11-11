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

-- The text of the service state, of its network registrations and of subscriptions names the operator,
-- which the getters fake (see test_network): the same fake values replace the real ones in the text, in
-- Use.Tracking like the getters; the network codes of subscriptions have a variant of their own (plmn), and their
-- card, number and group one in Read.Telephony (telephony), so an app restricted only there cannot read them in
-- the text.
-- Values run up to the next label, because operator names can contain commas and spaces;
-- empty values stay, like the getters keep them.

-- Label, fake value and the labels which can follow, of the versions which have the label
local SERVICE_STATE = {
    -- Android 11 and later
    { ', mOperatorAlphaLong=', 'unknown', { ', mOperatorAlphaShort=' } },
    { ', mOperatorAlphaShort=', 'unknown', { ', isManualNetworkSelection=' } },
    -- Android 8.1 to 10 print the names of the voice and the data network
    { ', mVoiceOperatorAlphaLong=', 'unknown', { ', mVoiceOperatorAlphaShort=' } },
    { ', mVoiceOperatorAlphaShort=', 'unknown', { ', mDataOperatorAlphaLong=' } },
    { ', mDataOperatorAlphaLong=', 'unknown', { ', mDataOperatorAlphaShort=' } },
    { ', mDataOperatorAlphaShort=', 'unknown', { ', isManualNetworkSelection=' } },
    -- Android 10 and later
    { ', mOperatorAlphaLongRaw=', 'unknown', { ', mOperatorAlphaShortRaw=' } },
    { ', mOperatorAlphaShortRaw=', 'unknown', { ', mIsDataRoamingFromRegistration=', ', mIsIwlanPreferred=' } },
}
-- Android 11 and later, Android 10 prints no registered network
local REGISTRATION = {
    { ' rRplmn=', '00101', { ' isUsingCarrierAggregation=', '}' } },
}
-- The carrier, its names and the country, like the getters in Use.Tracking. The ICCID stays, although its first digits
-- name the country and the operator, because getIccId, the parcel and the fields keep it in this group: an empty one
-- next to a real getIccId cannot happen on Android. Read.Telephony hides it in all of them (see telephony).
local SUBSCRIPTION = {
    { ' carrierId=', '1911', { ' displayName=' } },
    { ' displayName=', 'unknown', { ' carrierName=' } },
    { ' carrierName=', 'unknown', { ' nameSource=', ' isOpportunistic=' } },
    -- Android 11 to 13, and 14 and later
    { ' countryIso=', '', { ' isEmbedded=', ' profileClass=' } },
    -- Android 10, right after the MNC
    { 'mCountryIso=', '', { ' isEmbedded ' } },
    -- The rules of the certificates of the carrier and its app, which name the carrier: none, like a SIM without
    -- them, of the test network; Android 11 and later, Android 8.1 to 10 print one list (accessRules)
    { ' nativeAccessRules=', 'null', { ' carrierConfigAccessRules=', ' cardString=' } },
    { ' carrierConfigAccessRules=', 'null', { ' countryIso=', ' areUiccApplicationsEnabled=' } },
    { ' accessRules ', 'null', { ' cardString=', ' cardId=' } },
    { ' groupOwner=', '', { ' isGroupDisabled=', ' carrierConfigAccessRules=' } },
    { ' mGroupOwner=', '', { '}' } },
}
-- The network codes, like getMcc and getMnc, and the home networks, which are made of them
-- and have no getter for apps: Android 11 to 13, and 14 and later
local SUBSCRIPTION_PLMN = {
    { ' mcc=', '001', { ' mnc=' } },
    { ' mnc=', '01', { ' countryIso=', ' ehplmns=' } },
    { ' ehplmns=', '[]', { ' hplmns=' } },
    { ' hplmns=', '[]', { ' subscriptionType=', ' cardString=' } },
}
-- Android 10 prints them without equals signs
local SUBSCRIPTION_PLMN_10 = {
    { ' mcc ', '001', { ' mnc ' } },
    { ' mnc ', '01', { 'mCountryIso=' } },
    { ' ehplmns = ', '[]', { ' hplmns = ' } },
    { ' hplmns = ', '[]', { ' subscriptionType=' } },
}
-- Android 9 and before print the codes as numbers, like getMcc and getMnc return them
local SUBSCRIPTION_PLMN_NUMBERS = {
    { ' mcc ', '1', { ' mnc ' } },
    { ' mnc ', '1', { ' isEmbedded ' } },
}

-- The ICCID, the card string (the ICCID or EID of the card), the phone number and the group, like the getters in
-- Read.Telephony: empty, the number set else empty, and no group
function telephony(text, number)
    local printed = function(value) return pii(number, value) end
    local rules = {
        { ' iccId=', '', { ' simSlotIndex=' } },
        -- Android 10 and later
        { ' cardString=', '', { ' cardId=' } },
        -- Android 11 and later, and 10
        { ' number=', printed, { ' dataRoaming=' } },
        { ' mNumber=', printed, { ' dataRoaming=' } },
        -- Android 14 and later, 11 to 13, and 10
        { ' groupUuid=', 'null', { ' groupOwner=' } },
        { ' groupUUID=', 'null', { ' isGroupDisabled=' } },
        { ' mGroupUUID=', 'null', { ' mIsGroupDisabled=' } },
    }
    -- Android 9 ends with the ICCID or EID as the id of the card; the versions which print the card string print the
    -- index of the card there, which is no identifier
    if not string.find(text, ' cardString=', 1, true) then
        table.insert(rules, { ' cardId=', '', { '}' } })
    end
    return rules
end

-- The number as Rlog.pii prints it, after what it printed for the real one: the number itself on debuggable builds,
-- else a hash in brackets, which user builds hide, and nothing for no number
function pii(number, shown)
    if number == '' or string.sub(shown, 1, 1) ~= '[' then
        return number
    elseif shown == '[****]' then
        return shown
    end
    local digest = luajava.bindClass('java.security.MessageDigest'):getInstance('SHA-1'):digest(number)
    return '[' .. luajava.bindClass('java.util.Base64'):getUrlEncoder():withoutPadding():encodeToString(digest) .. ']'
end

-- Replaces the value after a label up to the nearest of the following labels; a fake can be made of the value
function replace(text, label, fake, nexts)
    local _, last = string.find(text, label, 1, true)
    if last == nil then
        return text, false
    end
    local following
    for _, nextLabel in ipairs(nexts) do
        local at = string.find(text, nextLabel, last + 1, true)
        if at ~= nil and (following == nil or at < following) then
            following = at
        end
    end
    if following == nil then
        return text, false
    end
    local value = string.sub(text, last + 1, following - 1)
    if type(fake) == 'function' then
        fake = fake(value)
    end
    -- An unknown carrier stays unknown, like getCarrierId keeps it
    if value == fake or value == '' or value == 'null' or (label == ' carrierId=' and value == '-1') then
        return text, false
    end
    return string.sub(text, 1, last) .. fake .. string.sub(text, following), true
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local class = hook:getClassName()
    local variant = string.match(hook:getName(), '/(.*)$')
    local rules = SUBSCRIPTION
    if class == 'android.telephony.ServiceState' then
        rules = SERVICE_STATE
    elseif class == 'android.telephony.NetworkRegistrationInfo' then
        rules = REGISTRATION
    elseif variant == 'telephony' then
        rules = telephony(result, param:getSetting('value.phone_number') or '')
    elseif variant == 'plmn' then
        if string.find(result, ' mcc=', 1, true) then
            rules = SUBSCRIPTION_PLMN
        elseif string.find(result, 'mCountryIso=', 1, true) then
            -- Of the versions which print the codes without equals signs only Android 10 prints the country
            rules = SUBSCRIPTION_PLMN_10
        else
            rules = SUBSCRIPTION_PLMN_NUMBERS
        end
    end

    local fake = result
    local restricted = false
    for _, rule in ipairs(rules) do
        local changed
        fake, changed = replace(fake, rule[1], rule[2], rule[3])
        restricted = restricted or changed
    end
    if not restricted then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
