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

function after(hook, param)
    local result = param:getResult()
    if type(result) == 'userdata' then
        result = result:toString() -- CharSequence
    end
    if result == nil or result == '' then
        return false
    end

    local fake = FAKES[hook:getMethodName()]
    if fake == nil or fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
