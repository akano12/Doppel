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

-- Whether a device has a fingerprint sensor, face or iris unlock, and whether fingerprints are enrolled,
-- tells about the device and its user. Apps get the answers of a device without biometric hardware,
-- so they offer their own login or the screen lock instead. Checks which include the screen lock
-- (device credential) stay, so apps can still use it.

local FEATURES = {
    ['android.hardware.fingerprint'] = true,
    ['android.hardware.biometrics.face'] = true,
    ['android.hardware.biometrics.iris'] = true,
}
local BIOMETRIC_ERROR_NO_HARDWARE = 12
local DEVICE_CREDENTIAL = 32768

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()

    if method == 'hasSystemFeature' then
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

    else
        -- FingerprintManager.isHardwareDetected, hasEnrolledFingerprints
        if not result then
            return false
        end
        param:setResult(false)
        return true, 'true', 'false'
    end
end
