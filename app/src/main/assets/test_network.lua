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
-- network too.
-- Without a SIM or service the values are empty, which stay, a fake value would tell they are faked.

local FAKES = {
    getMcc = 1,
    getMnc = 1,
    getMccString = '001',
    getMncString = '01',
    getNetworkOperator = '00101',
    getSimOperator = '00101',
    getOperatorNumeric = '00101', -- ServiceState
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
function nocode(method, param)
    if method == 'getMcc' then
        return true
    elseif method == 'getMnc' then
        local this = param:getThis()
        return (param:unhooked(function()
            return this:getMcc()
        end) == 0)
    end
    return false
end

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    if type(result) == 'userdata' then
        result = result:toString() -- CharSequence
    end
    -- An unknown carrier (-1) stays, like an empty value
    if result == nil or result == '' or result == -1 or (result == 0 and nocode(method, param)) then
        return false
    end

    local fake = FAKES[method]
    -- The MNC of an emergency number of the carrier is text, like getMncString
    if method == 'getMnc' and hook:getClassName() == 'android.telephony.emergency.EmergencyNumber' then
        fake = FAKES.getMncString
    end
    if fake == nil or fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
