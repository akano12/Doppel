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

-- BiometricManager.getStrings and getLastAuthenticationTime answer like on a device without biometric hardware:
-- with the screen lock (device credential) they answer for the screen lock only, else there are no texts
-- (BiometricManager.Strings) and there was no authentication. See biometrics.

local DEVICE_CREDENTIAL = 32768
local BIOMETRIC_NO_AUTHENTICATION = -1
-- The authenticators the methods accept, others are an error which stays
local VALID = { getStrings = 33023, getLastAuthenticationTime = 32783 }

function before(hook, param)
    local valid = VALID[hook:getMethodName()]
    if valid == nil then
        return false
    end
    local authenticators = param:getArgument(0)
    if authenticators == DEVICE_CREDENTIAL or bit32.band(authenticators, DEVICE_CREDENTIAL) == 0 or
            bit32.band(authenticators, bit32.bnot(valid)) ~= 0 then
        return false
    end
    param:setArgument(0, DEVICE_CREDENTIAL)
    return true, tostring(authenticators), tostring(DEVICE_CREDENTIAL)
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    local result = param:getResult()

    if method == 'getStrings' then
        -- The texts of biometrics only are none, see below
        if result ~= nil and bit32.band(param:getArgument(0), DEVICE_CREDENTIAL) == 0 then
            param:putValue('biometric', true, result)
        end
        return false

    elseif method == 'getLastAuthenticationTime' then
        if bit32.band(param:getArgument(0), DEVICE_CREDENTIAL) ~= 0 or result == BIOMETRIC_NO_AUTHENTICATION then
            return false
        end
        param:setResult(BIOMETRIC_NO_AUTHENTICATION)
        return true, tostring(result), tostring(BIOMETRIC_NO_AUTHENTICATION)

    else
        -- BiometricManager.Strings.getButtonLabel, getPromptMessage, getSettingName
        if result == nil or not param:getValue('biometric', param:getThis()) then
            return false
        end
        param:setResult(nil)
        return true, tostring(result), nil
    end
end
