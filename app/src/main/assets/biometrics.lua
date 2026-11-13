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
-- The services and their managers, which are there with the hardware only
local SERVICES = { fingerprint = true, face = true, iris = true }
local LOOKUPS = { getSystemService = true, getService = true, checkService = true }
-- BiometricService, which Android 10 starts with the hardware only too and later versions always; its manager stays,
-- since it has no binder without the hardware anyway
local BIOMETRIC = 'biometric'
-- The AIDL HALs of fingerprint and face, from Android 12, which servicemanager lists by the name of their interface.
-- shortcut: the AOSP names only, a vendor's own biometric service stays listed; add its name when one is seen.
local HALS = 'android.hardware.biometrics.'

-- Context.getSystemService, the lookups of ServiceManager and hasSystemFeature are called very often,
-- so the script runs for these names only
function filter(hook)
    local method = hook:getMethodName()
    local names = (LOOKUPS[method] and SERVICES) or (method == 'hasSystemFeature' and FEATURES)
    if not names then
        return nil
    end
    local list = {}
    for name in pairs(names) do
        list[#list + 1] = name
    end
    if method == 'getService' or method == 'checkService' then
        list[#list + 1] = BIOMETRIC
    end
    return list
end

-- Whether ServiceManager has no binder of the service on a device without the hardware. Taking the binder of
-- BiometricService on Android 10 is safe only because hasSystemFeature is hooked in this group too: BiometricPrompt
-- and BiometricManager there use it only when the device has a biometric feature.
local function absent(name)
    return SERVICES[name] or (name == BIOMETRIC and luajava.bindClass('android.os.Build$VERSION').SDK_INT == 29)
end

-- Takes the service from a manager which still has it, for when Android's own call of ServiceManager.getService, which
-- builds the managers of apps targeting Android 7.1 or lower, did not reach the hook, being compiled inline; the same
-- manager stays, like the one Android keeps for the app. The fields are listed like LuaJ lists them, which Android does
-- not report as a use of a hidden API, unlike getDeclaredField (see features.lua); a field the rules on hidden APIs
-- refuse is left out, so nothing changes then.
-- shortcut: where these rules apply, they give mService of FingerprintManager to apps targeting Android 8.1 or lower
-- only (max-target-o), and the one of FaceManager to no app, so a FaceManager built by an inlined call can keep its
-- service; hook the constructor of FaceManager when a probe shows the hook of getService does not apply there.
local function unserved(manager)
    local ok, taken = pcall(function()
        -- Lua sees a Class as its static members, so the methods of Class and Object get the object as the first argument
        local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(luajava.bindClass('java.lang.Object').getClass(manager))
        for index = 1, fields['length'] do
            local field = fields[index]
            if field:getName() == 'mService' then
                field:setAccessible(true)
                if field:get(manager) == nil then
                    return false
                end
                field:set(manager, nil)
                return true
            end
        end
        return false
    end)
    return ok and taken
end

function after(hook, param)
    -- What throws, like canAuthenticate without the permission USE_BIOMETRIC, throws on a device without the hardware too
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()

    if method == 'getService' or method == 'checkService' then
        -- ServiceManager has no binder of a service the system server did not start, which it starts with the hardware only,
        -- and neither has the IServiceManager behind it (ServiceManagerProxy), which apps reach by the greylisted
        -- getIServiceManager. Android builds the managers with getService too, so apps targeting Android 7.1 or lower get
        -- a manager without the service, which never calls back, like on a device without the hardware.
        -- shortcut: a transaction the app writes itself to the binder of the service manager (mRemote of the proxy,
        -- greylisted) still gets the binders and names, like any raw IBinder.transact (see the README).
        if param:getResult() == nil or not absent(param:getArgument(0)) then
            return false
        end
        param:setResult(nil)
        return true, param:getArgument(0), nil
    end

    if method == 'getSystemService' then
        -- Context.getSystemService gives apps targeting Android 8 or later no manager without the service,
        -- and never the iris manager; older apps get one without the service (see getService and unserved)
        local name = param:getArgument(0)
        if not SERVICES[name] or param:getResult() == nil then
            return false
        end
        if name ~= 'iris' and param:getApplicationContext():getApplicationInfo().targetSdkVersion < 26 then
            if not unserved(param:getResult()) then
                return false
            end
            return true, name, 'no service'
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

    elseif method == 'listServices' then
        -- ServiceManager, hidden: the names of the services, without the ones getService does not find and the HALs
        if result == nil then
            return false
        end
        local kept = luajava.newInstance('java.util.ArrayList')
        for index = 1, result['length'] do
            local name = result[index]
            if not absent(name) and string.find(name, HALS, 1, true) ~= 1 then
                kept:add(name)
            end
        end
        if kept:size() == result['length'] then
            return false
        end
        local stringClass = luajava.bindClass('java.lang.String')
        param:setResult(kept:toArray(luajava.bindClass('java.lang.reflect.Array'):newInstance(stringClass, 0)))
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

    elseif method == 'getAuthenticatorIds' then
        -- BiometricManager, hidden, which keystore asks for the keys which need a biometric for each use: none enrolled,
        -- so their generation fails like on a device without the hardware
        if result == nil or result['length'] == 0 then
            return false
        end
        param:setResult(luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.Long').TYPE, 0))
        return true, tostring(result['length']), '0'

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
