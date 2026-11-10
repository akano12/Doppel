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
-- too runs, since a device without biometrics shows the screen lock then, and so does a call without its arguments,
-- for which Android throws itself.
-- shortcut: such a prompt still offers the real biometrics next to the screen lock; narrow its authenticators to
-- DEVICE_CREDENTIAL (the hidden PromptInfo) when that matters.
local DEVICE_CREDENTIAL = 32768 -- BiometricManager.Authenticators
local HW_NOT_PRESENT = 12 -- BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT

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
    -- Android allows only without a negative button
    local prompt = param:getThis()
    local negative = prompt:getNegativeButtonText()
    if bit32.band(prompt:getAllowedAuthenticators(), DEVICE_CREDENTIAL) ~= 0 or negative == nil or tostring(negative) == '' then
        return false
    end
    -- The text of Android for the error, in the language of the device
    local message = 'Biometric hardware unavailable'
    pcall(function()
        local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
        local id = resources:getIdentifier('biometric_error_hw_unavailable', 'string', 'android')
        if id ~= 0 then
            message = resources:getString(id)
        end
    end)
    param:setResult(nil)
    executor:execute(luajava.createProxy('java.lang.Runnable', {
        run = function()
            callback:onAuthenticationError(HW_NOT_PRESENT, message)
        end
    }))
    return true
end
