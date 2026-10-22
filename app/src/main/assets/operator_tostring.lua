-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)

-- The text of the service state, of its network registrations and of subscriptions names the operator,
-- which the getters fake (see test_network): the same fake values replace the real ones in the text.
-- Values run up to the next label, because operator names can contain commas and spaces;
-- empty values stay, like the getters keep them.

-- Label, fake value and the labels which can follow, of the versions which have the label
local SERVICE_STATE = {
    { ', mOperatorAlphaLong=', 'unknown', { ', mOperatorAlphaShort=' } },
    { ', mOperatorAlphaShort=', 'unknown', { ', isManualNetworkSelection=' } },
    { ', mOperatorAlphaLongRaw=', 'unknown', { ', mOperatorAlphaShortRaw=' } },
    { ', mOperatorAlphaShortRaw=', 'unknown', { ', mIsDataRoamingFromRegistration=', ', mIsIwlanPreferred=', '}' } },
}
local REGISTRATION = {
    { ' rRplmn=', '00101', { ' isUsingCarrierAggregation=', ' isNonTerrestrialNetwork=', '}' } },
}
local SUBSCRIPTION = {
    { ' carrierId=', '-1', { ' displayName=' } },
    { ' displayName=', 'unknown', { ' carrierName=' } },
    { ' carrierName=', 'unknown', { ' isOpportunistic=', ' nameSource=', ' displayNameSource=' } },
    { ' mcc=', '001', { ' mnc=' } },
    { ' mnc=', '01', { ' ehplmns=', ' cardString=', ' mCountryIso=', ' countryIso=' } },
    { ' ehplmns=', '[]', { ' hplmns=' } },
    { ' hplmns=', '[]', { ' cardString=', ' mCardString=' } },
    { ' countryIso=', 'xx', { ' profileClass=', ' isGroupDisabled=' } },
    { ' mCountryIso=', 'xx', { ' isEmbedded=', ' nativeAccessRules=' } },
}

-- Replaces the value after a label up to the nearest of the following labels
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
    if value == fake or value == '' or value == 'null' then
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
    local rules = SUBSCRIPTION
    if class == 'android.telephony.ServiceState' then
        rules = SERVICE_STATE
    elseif class == 'android.telephony.NetworkRegistrationInfo' then
        rules = REGISTRATION
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
