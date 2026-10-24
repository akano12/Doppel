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
-- Copyright 2026 akano12

-- The IMEI of GSM phones and the MEID of CDMA phones are replaced by the values set, nothing by default;
-- getDeviceId returns the one of the phone type, getImei and getMeid only their own

local PHONE_TYPE_GSM = 1
local PHONE_TYPE_CDMA = 2

function after(hook, param)
    -- Apps without permission keep the security exception
    local this = param:getThis()
    if this == nil or param:getException() ~= nil then
        return false
    end

    local result = param:getResult()
    local method = hook:getMethodName()
    local type = this:getPhoneType()
    local fake
    if type == PHONE_TYPE_GSM and method ~= 'getMeid' then
        fake = param:getSetting('value.imei')
    elseif type == PHONE_TYPE_CDMA and method ~= 'getImei' and method ~= 'getPrimaryImei' then
        fake = param:getSetting('value.meid')
    end
    if result == nil and fake == nil then
        return false
    end

    param:setResult(fake)
    return true, result, fake
end
