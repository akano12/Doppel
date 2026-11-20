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

-- Calls which answer with an app, like the autofill service or the store which installed the app: nothing when that
-- app is hidden, which is what Android gives for an app the caller may not see (ComputerEngine.getInstallerPackageName).

-- mEnabled of AutofillManager, whether autofill is on in the app, false when it is not listed. The fields are listed
-- like LuaJ lists them, which Android does not report as a use of a hidden API (see features.lua).
local ENABLED = {}
local function enabledfield()
    if ENABLED.field == nil then
        ENABLED.field = false
        local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(
                luajava.bindClass('android.view.autofill.AutofillManager'))
        for index = 1, fields['length'] do
            if fields[index]:getName() == 'mEnabled' then
                fields[index]:setAccessible(true)
                ENABLED.field = fields[index]
            end
        end
    end
    return ENABLED.field
end

-- Whether the autofill service is hidden, with its package: read without the hooks, which would else hide it, and
-- hidden too when it cannot be read, since it could be one the app may not see.
-- Kept for a few seconds, since Android asks for each text field whether autofill is on; read again when the service
-- tells the app a new state, which it does when autofill was turned on or off (AutofillManagerServiceImpl.updateLocked)
-- but not when the user chose another service while autofill stays on, so a switch is seen once the kept one is old.
function autofillhidden(param, fresh)
    local context = param:getApplicationContext()
    local cached = param:getValue('autofill', context)
    if fresh or cached == nil or os.time() - cached.at >= 10 then
        local manager = param:getThis()
        local ok, service = pcall(function()
            return param:unhooked(function()
                local component = manager:getAutofillServiceComponentName()
                return (component ~= nil and component:getPackageName() or nil)
            end)
        end)
        cached = { at = os.time(), service = (ok and service or nil), failed = not ok }
        param:putValue('autofill', cached, context)
    end
    return (cached.failed or (cached.service ~= nil and not param:isPackageVisible(cached.service))), cached.service
end

-- The compatibility mode of autofill, which Android turns on in an activity for the browsers its autofill service names
-- (AutofillManagerServiceImpl.getCompatibilityPackagesLocked): skipped while that service is hidden, like with none
-- set, so no bridge of autofill becomes the accessibility policy, which would make AccessibilityManager.isEnabled true
-- and list Android's AutofillCompatAccessibilityService with no accessibility service on.
-- shortcut: the autofill options of the app still have the mode on, so Context.isAutofillCompatibilityEnabled and its
-- line in Activity.dump say true, and a bridge made before the service was hidden stays; hook that getter too (on
-- ContextImpl for Android 9) if a device shows it.
function before(hook, param)
    if hook:getMethodName() ~= 'enableCompatibilityMode' then
        return false
    end
    local hidden, service = autofillhidden(param, false)
    if not hidden then
        return false
    end
    param:setResult(nil)
    return true, service
end

function after(hook, param)
    local method = hook:getMethodName()

    -- Autofill, which Android turns on in the app only while an autofill service is set (isEnabledLocked of
    -- AutofillManagerServiceImpl): off while that service is hidden, like on a device whose service is set to none,
    -- so it agrees with getAutofillServiceComponentName and the autofill_service setting, which hide it; also for a
    -- view with a credential request of Android 15 and later, which a device without a service does not fill either
    -- (AutofillManagerServiceImpl.addClientLocked). It is turned off where AutofillManager learns it from the service,
    -- when it adds itself as a client and when the service tells it a new state, so isEnabled, the dump of
    -- AutofillManager (in Activity.dump) and the sessions agree: no session starts, so the service neither asks the
    -- app's views for their structure nor fills them. An AutofillManager turned off here is turned on again once a
    -- service is set and visible, since the service tells the app no new state when the user switches from one service
    -- to another (AutofillManagerServiceImpl.updateLocked).
    -- shortcut: where the hidden API rules refuse these private members to Doppel, only isEnabled is off (see below):
    -- Activity.dump still says enabled up to Android 12 and the hidden service still fills the app's views; with
    -- the PCC classification flag of Android 14 and later (off by default), which starts a session whether autofill
    -- is on or not, the hidden service still gets a session; an app which the augmented autofill service allows is
    -- not on for augmented autofill only, as it is with none set; and for up to 10 s, while the service read before is
    -- kept and the app does not read it itself, autofill stays on after the user switched from a visible service to a
    -- hidden one, so the hidden service gets sessions, or stays off after a switch from a hidden service to a visible
    -- one; hook the public notify calls of AutofillManager too, or watch the autofill_service setting, if a device
    -- shows any of these.
    if method == 'ensureServiceClientAddedIfNeededLocked' or method == 'tryAddServiceClientIfNeededLocked' or
            method == 'setState' then
        local manager = param:getThis()
        if method == 'setState' then
            param:putValue('autofilloff', nil, manager)
        end
        local field = (param:getException() == nil and enabledfield())
        if not field then
            return false
        end
        if field:get(manager) ~= true then
            if param:getValue('autofilloff', manager) then
                local hidden, service = autofillhidden(param, false)
                if not hidden and service ~= nil then
                    field:set(manager, true)
                    param:putValue('autofilloff', nil, manager)
                end
            end
            return false
        end
        local hidden, service = autofillhidden(param, method == 'setState')
        if not hidden then
            return false
        end
        field:set(manager, false)
        param:putValue('autofilloff', true, manager)
        return true, service
    end

    local result = param:getResult()
    -- The service the app reads is the one the autofill hooks keep, so isEnabled and adding the client agree at once
    if method == 'getAutofillServiceComponentName' and param:getException() == nil then
        param:putValue('autofill', { at = os.time(), service = (result ~= nil and result:getPackageName() or nil) },
                param:getApplicationContext())
    end
    if result == nil or param:getException() ~= nil then
        return false
    end

    -- The certificate of the store which started the install, which Android gives only with the name of that store
    -- (ComputerEngine.getInstallSourceInfo), so none when that name is hidden: it would name the store
    if method == 'getInitiatingPackageSigningInfo' then
        local info = param:getThis()
        local initiating = param:unhooked(function()
            return info:getInitiatingPackageName()
        end)
        if initiating == nil or param:isPackageVisible(initiating) then
            return false
        end
        param:setResult(nil)
        return true, initiating
    end

    -- Whether autofill is on, off while its service is hidden (see above), also where AutofillManager could not be
    -- turned off itself
    if method == 'isEnabled' then
        if result ~= true then
            return false
        end
        local hidden, service = autofillhidden(param, false)
        if not hidden then
            return false
        end
        param:setResult(false)
        return true, service
    end

    local packageName
    if hook:getClassName() == 'android.telecom.TelecomManager' then
        -- A PhoneAccountHandle, or the PhoneAccount of getPhoneAccount, named by the ConnectionService of its app
        local handle = (method == 'getPhoneAccount' and result:getAccountHandle() or result)
        packageName = handle:getComponentName():getPackageName()
    else
        packageName = (type(result) == 'string' and result or result:getPackageName())
    end
    if param:isPackageVisible(packageName) then
        return false
    end
    param:setResult(nil)
    return true, packageName
end
