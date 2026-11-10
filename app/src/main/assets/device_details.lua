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

-- Small details of the device which together set it apart, with the values of a plain device:
-- - the heat (PowerManager): no thermal headroom known (NaN, like devices without the HAL), no throttling (0), also
--   for listeners;
-- - the audio output: 256 frames per buffer, which tells the audio chip; the sample rate stays, which is 48000
--   or 44100 Hz on nearly all devices and tells little, while the app plays at it;
-- - the vibrator: no amplitude control, effects and primitives unknown or not supported, no resonance (NaN),
--   which apps answer with a plain vibration;
-- - the certificates the user installed (AndroidCAStore, user: aliases), like of a VPN, proxy or ad blocker, are not
--   listed; which certificates connections trust stays;
-- - the details of the vibrator of Android 12 and later (Vibrator.getInfo), which the methods above use: those of a
--   vibrator without capabilities;
-- - the screen lock: set (KeyguardManager.isDeviceSecure and isKeyguardSecure), like on most devices, and so a way to
--   authenticate (BiometricManager.canAuthenticate with DEVICE_CREDENTIAL), else the answers contradict each other on
--   a device without a lock; an app which then asks for the lock gets the error of Android, like for a removed lock.
local PROPERTIES = { ['android.media.property.OUTPUT_FRAMES_PER_BUFFER'] = '256' }
local DEVICE_CREDENTIAL = 32768 -- BiometricManager.Authenticators
local BIOMETRIC_SUCCESS = 0

-- The audio properties and the user certificates, so the script runs for these only (see XCore.getFilter)
function filter(hook)
    local method = hook:getMethodName()
    if method == 'getProperty' then
        local keys = {}
        for key, _ in pairs(PROPERTIES) do
            keys[#keys + 1] = key
        end
        return keys
    elseif method == 'containsAlias' or method == 'getCertificate' then
        return { 'user:*' }
    end
    return nil
end

-- The listeners of the heat: Android answers a new listener at once with the real values, so it is not added and gets
-- the values of the getters once, on the executor of the call, else on the main thread. Android throws for removing a
-- listener which was not added, which would crash the app, so removing one of these does nothing as well. One object
-- can listen to both the status and the headroom, so it is remembered for each.
function before(hook, param)
    local method = hook:getMethodName()
    local blocked = 'thermal.blocked.' .. (string.find(method, 'Headroom', 1, true) and 'headroom' or 'status')
    if method == 'removeThermalStatusListener' or method == 'removeThermalHeadroomListener' then
        local listener = param:getArgument(0)
        if listener == nil or not param:getValue(blocked, listener) then
            return false
        end
        param:putValue(blocked, nil, listener)
        param:setResult(nil)
        return false
    elseif method ~= 'addThermalStatusListener' and method ~= 'addThermalHeadroomListener' then
        return false
    end

    -- The listener is the last argument of both variants; Android throws for a null one or a null executor
    local count = hook:getParameterTypes()['length']
    local listener = param:getArgument(count - 1)
    local executor = (count == 2 and param:getArgument(0) or nil)
    if listener == nil or (count == 2 and executor == nil) then
        return false
    end
    -- A listener added twice, which Android refuses with the message of its version
    if param:getValue(blocked, listener) then
        local message = 'Listener already registered: '
        if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 36 then
            message = (method == 'addThermalStatusListener' and 'Thermal status' or 'Thermal headroom') ..
                    ' listener already registered: '
        end
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', message .. listener:toString()))
        return true
    end
    param:setResult(nil)
    param:putValue(blocked, true, listener)
    -- Headroom like a device whose sensors have no samples yet, which Android does not call back for (NaN)
    if method == 'addThermalHeadroomListener' then
        return true
    end

    local runnable = luajava.createProxy('java.lang.Runnable', { run = function()
        listener:onThermalStatusChanged(0) -- THERMAL_STATUS_NONE
    end })
    if executor ~= nil then
        executor:execute(runnable)
    else
        local looper = luajava.bindClass('android.os.Looper'):getMainLooper()
        luajava.newInstance('android.os.Handler', looper):post(runnable)
    end
    return true
end

-- Whether an array of arguments is empty, like areAllEffectsSupported() without effects
function none(array)
    return array == nil or array['length'] == 0
end

-- A Java array of the length of another one, of a type, with one value; nil when the other one has only that value,
-- like the array the hooked getInfo made the method of Vibrator return
function filled(type, other, value)
    local length = other['length']
    local same = true
    for index = 1, length do
        same = same and other[index] == value
    end
    if same then
        return nil
    end
    local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(type, length)
    for index = 1, length do
        array[index] = value
    end
    return array
end

-- Whether a system alias leaves the folder of the system certificates for more than its parent, like
-- system:../../../data/misc/user/0/...: the folders next to it are read-only, but the user ones are not
function outside(alias)
    if string.sub(alias, 1, 7) ~= 'system:' then
        return false
    end
    local depth = 0
    for part in string.gmatch(string.sub(alias, 8), '[^/]+') do
        if part == '..' then
            depth = depth - 1
        elseif part ~= '.' then
            depth = depth + 1
        end
        if depth < -1 then
            return true
        end
    end
    return false
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    local result = param:getResult()
    local fake
    if method == 'getThermalHeadroom' or method == 'getResonantFrequency' or method == 'getQFactor' then
        if result ~= result then
            return false
        end
        fake = 0 / 0
    elseif method == 'getCurrentThermalStatus' or method == 'areAllEffectsSupported' then
        -- THERMAL_STATUS_NONE, VIBRATION_EFFECT_SUPPORT_UNKNOWN; for no effects (yes) and of a vibrator which supports
        -- none (no, like a device without one) the answer of Android stays
        if method == 'areAllEffectsSupported' and (none(param:getArgument(0)) or result == 2) then
            return false
        end
        fake = 0
    elseif method == 'getThermalHeadroomThresholds' then
        if result == nil or result:isEmpty() then
            return false
        end
        fake = luajava.newInstance('java.util.HashMap')
        param:setResult(fake)
        return true, tostring(result:size()), '0'
    elseif method == 'getProperty' then
        fake = PROPERTIES[param:getArgument(0)]
        if fake == nil or result == nil then
            return false
        end
    elseif method == 'hasAmplitudeControl' or method == 'areAllPrimitivesSupported' then
        -- Of no primitives Android answers true
        if method == 'areAllPrimitivesSupported' and none(param:getArgument(0)) then
            return false
        end
        fake = false
    elseif method == 'areEffectsSupported' or method == 'getPrimitiveDurations' then
        -- Of the effects asked for: unknown (0), of primitives: no duration (0)
        fake = (result ~= nil and filled(luajava.bindClass('java.lang.Integer').TYPE, result, 0) or nil)
        if fake == nil then
            return false
        end
        param:setResult(fake)
        return true
    elseif method == 'arePrimitivesSupported' then
        fake = (result ~= nil and filled(luajava.bindClass('java.lang.Boolean').TYPE, result, false) or nil)
        if fake == nil then
            return false
        end
        param:setResult(fake)
        return true
    elseif method == 'aliases' then
        -- The aliases of the certificates, without those of the user
        if result == nil then
            return false
        end
        local kept = luajava.newInstance('java.util.LinkedHashSet')
        local iterator = result:iterator()
        while iterator:hasNext() do
            local alias = iterator:next()
            if string.sub(alias, 1, 5) ~= 'user:' then
                kept:add(alias)
            end
        end
        if kept:size() == result:size() then
            return false
        end
        param:setResult(kept)
        return true, tostring(result:size()), tostring(kept:size())
    elseif method == 'userAliases' then
        if result == nil or result:isEmpty() then
            return false
        end
        param:setResult(luajava.newInstance('java.util.LinkedHashSet'))
        return true, tostring(result:size()), '0'
    elseif method == 'getInfo' then
        -- An empty VibratorInfo (hidden): of Android 13 and later its constant, else built; the one of a device
        -- without a vibrator (NoVibratorInfo) stays, which answers no for all effects
        if result == nil then
            return false
        end
        local known, class = pcall(function()
            return result:getClass():getName()
        end)
        if known and string.find(class, 'NoVibratorInfo', 1, true) then
            return false
        end
        -- With the id of the vibrator: EMPTY_VIBRATOR_INFO has id -1, which getVibrator does not give
        local ok, empty = pcall(function()
            return luajava.newInstance('android.os.VibratorInfo$Builder', result:getId()):build()
        end)
        if not ok or empty == nil then
            ok, empty = pcall(function()
                return luajava.bindClass('android.os.VibratorInfo').EMPTY_VIBRATOR_INFO
            end)
        end
        if not ok or empty == nil then
            return false
        end
        param:setResult(empty)
        return true
    elseif method == 'isDeviceSecure' or method == 'isKeyguardSecure' then
        fake = true
    elseif method == 'canAuthenticate' then
        -- Biometrics alone are for Read.Biometrics (see biometrics)
        if bit32.band(param:getArgument(0), DEVICE_CREDENTIAL) == 0 then
            return false
        end
        fake = BIOMETRIC_SUCCESS
    elseif method == 'containsAlias' or method == 'getCertificate' then
        -- The filter also passes all aliases with /../, which can lead to the user ones
        local alias = param:getArgument(0)
        if string.sub(alias, 1, 5) ~= 'user:' and not outside(alias) then
            return false
        end
        if method == 'containsAlias' then
            fake = false
        end
    else
        return false
    end
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
