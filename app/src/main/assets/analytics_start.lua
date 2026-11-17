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

-- SDKs which collect once they start, unless the app tells them that the user declined, are told so right before they
-- start, like an app asking and the user declining; consent the app gives later is replaced by none by the hooks of
-- their setters (generic_false_argument, generic_true_argument). A method missing in a version fails the call and the
-- SDK stays as it is.

-- Once per process, since the SDKs keep it for the process
function once(param, name)
    local scope = param:getApplicationContext()
    if param:getValue(name, scope) then
        return false
    end
    param:putValue(name, true, scope)
    return true
end

-- LevelPlay of Unity (ironSource): no consent for personalized ads and do not sell (CCPA); from version 9 by LevelPlay,
-- before by IronSource, which LevelPlay.init of version 8 can start too
function levelplay(param)
    if not once(param, 'levelplay.declined') then
        return false
    end
    for _, name in ipairs({ 'com.unity3d.mediation.LevelPlay', 'com.ironsource.mediationsdk.IronSource' }) do
        if pcall(function()
            local sdk = param:findClass(name)
            sdk:setConsent(false)
            sdk:setMetaData('do_not_sell', 'true')
        end) then
            return true
        end
    end
    return false
end

-- OneSignal holds back its requests other than GET while consent is required and not given, which it says to require
-- before it starts: version 5, else version 4 by its former names; its GET requests are held back by the hooks of its
-- HTTP clients (see unsent)
function onesignal(param)
    if not once(param, 'onesignal.declined') then
        return false
    end
    local sdk = param:findClass('com.onesignal.OneSignal')
    if not pcall(function()
        sdk:setConsentRequired(true)
        sdk:setConsentGiven(false)
    end) then
        sdk:setRequiresUserPrivacyConsent(true)
        sdk:provideUserConsent(false)
    end
    return true
end

-- Bugsnag sends no errors, sessions or native crash reports when its release stage is not one of the enabled ones,
-- which is how its documentation turns it off: none is enabled, in each configuration a client starts with, from
-- version 5. shortcut: reports it stored before the restriction are still sent when it starts, hook its stores when
-- that matters
function bugsnag(param)
    local config = param:getArgument(1)
    if config == nil then
        return false
    end
    config:setEnabledReleaseStages(luajava.newInstance('java.util.HashSet'))
    return true
end

-- The do not sell flag the app gives LevelPlay, also after it starts, is true, also by the form with a list of values,
-- which the declared type tells, since a null list must stay a list; its keys and values are case insensitive
function donotsell(hook, param)
    local key = param:getArgument(0)
    if type(key) ~= 'string' or key:lower() ~= 'do_not_sell' then
        return false
    end
    local value = param:getArgument(1)
    if hook:getParameterTypes()[2] == 'java.util.List' then
        if value ~= nil and value:size() == 1 and tostring(value:get(0)):lower() == 'true' then
            return false
        end
        local list = luajava.newInstance('java.util.ArrayList')
        list:add('true')
        param:setArgument(1, list)
        return true
    end
    if type(value) == 'string' and value:lower() == 'true' then
        return false
    end
    param:setArgument(1, 'true')
    return true
end

-- OneSignal holds back its requests other than GET without consent: its GET requests, which carry its install id, get
-- the answer of those
function unsent(param)
    local response = param:findClass('com.onesignal.core.internal.http.HttpResponse')
    -- By reflection, since LuaJ coerces an Integer parameter like int and so cannot pass nil to luajava.new
    local class = luajava.bindClass('java.lang.Class')
    local array = luajava.bindClass('java.lang.reflect.Array')
    local integer = luajava.bindClass('java.lang.Integer')
    local types = array:newInstance(class, 5)
    types[1] = integer.TYPE
    types[2] = luajava.bindClass('java.lang.String')
    types[3] = luajava.bindClass('java.lang.Throwable')
    types[4] = integer
    types[5] = integer
    local args = array:newInstance(luajava.bindClass('java.lang.Object'), 5)
    args[1] = 0
    param:setResult(class.getConstructor(response, types):newInstance(args))
    return true
end

-- The upload of its own logs and crash reports, which a configuration it fetched before can turn on without consent,
-- fails like without a network
function unlogged(param)
    local host = tostring(param:getArgument(0):getUrl()):match('//([^/:?#]+)') or ''
    local message = 'Unable to resolve host "' .. host .. '": No address associated with hostname'
    param:setResult(luajava.new(param:findClass('com.onesignal.logger.LogHttpResponse'), false, -1, message))
    return true
end

local START = {
    ['com.unity3d.mediation.LevelPlay'] = levelplay,
    ['com.ironsource.mediationsdk.IronSource'] = levelplay,
    ['com.onesignal.OneSignal'] = onesignal,
    ['com.bugsnag.android.Client'] = bugsnag,
    ['com.onesignal.core.internal.http.impl.HttpClient'] = unsent,
    ['com.onesignal.debug.internal.logging.logger.android.OneSignalLogHttpSender'] = unlogged,
}

function before(hook, param)
    if hook:getMethodName() == 'setMetaData' then
        return donotsell(hook, param)
    end
    local start = START[hook:getClassName()]
    if start == nil then
        return false
    end
    local ok, restricted = pcall(start, param)
    return ok and restricted
end
