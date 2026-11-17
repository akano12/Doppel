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

-- From Android 11 BiometricPrompt asks the service, which shows the real dialog of the sensors (before, the prompt
-- checks the hooked features first): like a device without biometrics, the app gets the error that there is no
-- hardware (BIOMETRIC_ERROR_HW_NOT_PRESENT) on its executor, without a dialog. A prompt which allows the screen lock
-- too gets the error of a device without biometrics and without a screen lock, when it has none: on Android 11 that
-- there are no biometrics (BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricService.checkAndGetAuthenticators), from Android 12
-- that there is no screen lock (BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL, PreAuthInfo); with a screen lock it runs, since
-- a device without biometrics shows the screen lock then, and so does a prompt for the screen lock alone, which
-- Android answers without the biometrics, and a call without its arguments, for which Android throws itself. The text
-- is Android's for no sensor: none on Android 11, which has texts only for a face or a fingerprint.
-- shortcut: a prompt which allows the screen lock on a device with one still offers the real biometrics next to it;
-- narrow its authenticators to DEVICE_CREDENTIAL (the hidden PromptInfo) when that matters.
local DEVICE_CREDENTIAL = 32768 -- BiometricManager.Authenticators
local HW_NOT_PRESENT = 12 -- BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
local NO_BIOMETRICS = 11 -- BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
local NO_DEVICE_CREDENTIAL = 14 -- BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL

-- The text of Android for an error, in the language of the device; none on Android 11 (BiometricPrompt.onError)
local function text(name, english)
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 31 then
        return ''
    end
    local message = english
    pcall(function()
        local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
        local id = resources:getIdentifier(name, 'string', 'android')
        if id ~= 0 then
            message = resources:getString(id)
        end
    end)
    return message
end

-- Whether the device has a screen lock, as the service sees it, without the hooks of the app (Use.Tracking hides it);
-- also when that cannot be read, so Android answers then
local function secure(param)
    local ok, result = pcall(param.unhooked, param, function()
        return param:getApplicationContext():getSystemService('keyguard'):isDeviceSecure()
    end)
    return not ok or result ~= false
end

function before(hook, param)
    -- authenticate(cancel, executor, callback) and authenticate(crypto, cancel, executor, callback)
    local count = hook:getParameterTypes()['length']
    local cancel = param:getArgument(count - 3)
    local executor = param:getArgument(count - 2)
    local callback = param:getArgument(count - 1)
    if cancel == nil or executor == nil or callback == nil then
        return false
    end
    -- The screen lock is allowed by the authenticators or by the deprecated setDeviceCredentialAllowed, which
    -- Android allows only without a negative button, then with weak biometrics (Utils.combineAuthenticatorBundles)
    local prompt = param:getThis()
    local authenticators = prompt:getAllowedAuthenticators()
    local negative = prompt:getNegativeButtonText()
    local code, message
    if bit32.band(authenticators, DEVICE_CREDENTIAL) ~= 0 or negative == nil or tostring(negative) == '' then
        if authenticators == DEVICE_CREDENTIAL or secure(param) then
            return false
        end
        local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
        code = (sdk < 31 and NO_BIOMETRICS or NO_DEVICE_CREDENTIAL)
        message = text('biometric_error_device_not_secured', 'No pin, pattern, or password set')
    else
        code = HW_NOT_PRESENT
        message = text('biometric_error_hw_unavailable', 'Biometric hardware unavailable')
    end
    param:setResult(nil)
    executor:execute(luajava.createProxy('java.lang.Runnable', {
        run = function()
            callback:onAuthenticationError(code, message)
        end
    }))
    return true
end
