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
-- in the formats of the methods; the country is the unknown country code xx,
-- the names are unknown and the carrier id is TelephonyManager.UNKNOWN_CARRIER_ID.
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
    getCountryIso = 'xx',
    getNetworkCountryIso = 'xx',
    getSimCountryIso = 'xx',
    getNetworkOperatorName = 'unknown',
    getSimOperatorName = 'unknown',
    getOperatorAlphaLong = 'unknown', -- ServiceState
    getOperatorAlphaShort = 'unknown',
    getCarrierName = 'unknown', -- SubscriptionInfo
    getDisplayName = 'unknown',
    getCarrierId = -1,
    getSimCarrierId = -1, -- TelephonyManager
    getSimSpecificCarrierId = -1,
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
    if result == nil or result == '' or (result == 0 and nocode(method, param)) then
        return false
    end

    local fake = FAKES[method]
    if fake == nil or fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
