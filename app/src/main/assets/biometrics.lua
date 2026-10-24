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

-- Copyright 2026 akano12

-- Whether a device has a fingerprint sensor, face or iris unlock, and whether fingerprints are enrolled,
-- tells about the device and its user. Apps get the answers of a device without biometric hardware,
-- so they offer their own login or the screen lock instead. Checks which include the screen lock
-- (device credential) stay, so apps can still use it (see biometrics_credential).

local FEATURES = {
    ['android.hardware.fingerprint'] = true,
    ['android.hardware.biometrics.face'] = true,
    ['android.hardware.biometrics.iris'] = true,
}
local BIOMETRIC_ERROR_NO_HARDWARE = 12
local DEVICE_CREDENTIAL = 32768
-- The managers of the services, which are there with the hardware only
local SERVICES = { fingerprint = true, face = true, iris = true }

function after(hook, param)
    local method = hook:getMethodName()

    if method == 'getSystemService' then
        -- Context.getSystemService gives apps targeting Android 8 or later no manager without the service,
        -- and never the iris manager; it is called for all services, so check the name first
        local name = param:getArgument(0)
        if not SERVICES[name] or param:getResult() == nil then
            return false
        end
        if name ~= 'iris' and param:getApplicationContext():getApplicationInfo().targetSdkVersion < 26 then
            return false
        end
        param:setResult(nil)
        return true, name, nil
    end

    local result = param:getResult()

    if method == 'getSystemAvailableFeatures' then
        -- The same features as hasSystemFeature finds
        if result == nil then
            return false
        end
        local kept = luajava.newInstance('java.util.ArrayList')
        for index = 1, result['length'] do
            if not FEATURES[result[index].name] then
                kept:add(result[index])
            end
        end
        if kept:size() == result['length'] then
            return false
        end
        local featureClass = luajava.bindClass('android.content.pm.FeatureInfo')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        param:setResult(kept:toArray(arrayClass:newInstance(featureClass, 0)))
        return true, tostring(result['length']), tostring(kept:size())

    elseif method == 'hasSystemFeature' then
        local name = param:getArgument(0)
        if not result or not FEATURES[name] then
            return false
        end
        param:setResult(false)
        return true, name, 'false'

    elseif method == 'canAuthenticate' then
        -- BiometricManager.canAuthenticate(int authenticators), API 30
        if string.find(hook:getName(), '/authenticators', 1, true) and
                bit32.band(param:getArgument(0), DEVICE_CREDENTIAL) ~= 0 then
            return false
        end
        if result == BIOMETRIC_ERROR_NO_HARDWARE then
            return false
        end
        param:setResult(BIOMETRIC_ERROR_NO_HARDWARE)
        return true, tostring(result), tostring(BIOMETRIC_ERROR_NO_HARDWARE)

    elseif method == 'getBiometricSensorStrengths' then
        -- BiometricManager, API 37: no sensors
        if result == nil or result:isEmpty() then
            return false
        end
        param:setResult(luajava.newInstance('java.util.HashMap'))
        return true, result:toString(), '{}'

    else
        -- FingerprintManager.isHardwareDetected, hasEnrolledFingerprints
        if not result then
            return false
        end
        param:setResult(false)
        return true, 'true', 'false'
    end
end
