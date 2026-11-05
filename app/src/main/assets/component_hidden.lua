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

-- Apps talk to each other through services, screens and broadcasts they name, like an app binding the service
-- of another app for the account signed in. The components of the apps which Get.Applications hides (see
-- package_probe and provider_hidden) cannot be reached, like for an app which is not installed: binding a service
-- fails, starting one finds none, stopping one stops none, starting a screen throws ActivityNotFoundException,
-- a broadcast to the package is not sent and an ordered one reaches no receiver. Intents which name no package or
-- component are resolved by Android, so they stay.

-- The package an intent names, by its component or its package, nil when it names none; Android resolves an
-- intent which names none by its selector, if it has one
function packageof(intent)
    if intent == nil then
        return nil
    end
    local component = intent:getComponent()
    if component ~= nil then
        return component:getPackageName()
    end
    local packageName = intent:getPackage()
    if packageName == nil and intent:getSelector() ~= nil then
        return packageof(intent:getSelector())
    end
    return packageName
end

-- Whether an intent names a package which is hidden; a name ending in :hidden is the copy of identifier_services for
-- the same call, which hooks bindService too (see before), not a package of an app
function hidden(param, intent)
    local packageName = packageof(intent)
    return packageName ~= nil and string.sub(packageName, -7) ~= ':hidden' and
            not param:isPackageVisible(packageName), packageName
end

-- The error of Android for a screen which is not found (Instrumentation.checkStartActivityResult),
-- of which the hint about the intent filter came with Android 13
function notfound(intent)
    local component = intent:getComponent()
    local message
    if component ~= nil then
        message = 'Unable to find explicit activity class ' .. component:toShortString() ..
                '; have you declared this activity in your AndroidManifest.xml'
        if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 33 then
            message = message .. ', or does your intent not match its declared <intent-filter>?'
        else
            message = message .. '?'
        end
    else
        message = 'No Activity found to handle ' .. intent:toString()
    end
    return luajava.newInstance('android.content.ActivityNotFoundException', message)
end

function before(hook, param)
    local method = hook:getMethodName()

    -- Instrumentation.execStartActivitiesAsUser, which execStartActivities calls:
    -- the first screen of a hidden app which cannot be found
    if method == 'execStartActivitiesAsUser' then
        local intents = param:getArgument(4)
        if intents == nil then
            return false
        end
        for index = 1, intents['length'] do
            local isHidden, packageName = hidden(param, intents[index])
            if isHidden then
                param:setResult(notfound(intents[index]))
                return true, packageName
            end
        end
        return false
    end

    -- The intent is the first argument of the methods of Context, the second of ActivityManager.AppTask.startActivity,
    -- the fifth of Instrumentation.execStartActivity
    local index = 0
    if method == 'execStartActivity' then
        index = 4
    elseif method == 'startActivity' then
        index = 1
    end
    local intent = param:getArgument(index)
    local isHidden, packageName = hidden(param, intent)
    if not isHidden then
        return false
    end

    -- stopService and stopServiceAsUser, of which Android would stop a running service of the app or refuse one which
    -- is not exported, but finds none of a missing app
    if string.find(method, '^stop') then
        param:setResult(false)
    elseif string.find(method, '^start%a*Service') then
        -- startService, startForegroundService and their AsUser variants
        param:setResult(nil)
    elseif method == 'execStartActivity' or method == 'startActivity' or method == 'startActivityIfNeeded' then
        param:setResult(notfound(intent))
    elseif string.find(method, '^sendSticky') and intent:getComponent() ~= nil then
        -- Android throws itself for a sticky broadcast to a component, also of an app which is not installed
        return false
    elseif string.find(method, '^bind') or string.find(method, 'OrderedBroadcast') then
        -- bindService, bindIsolatedService, bindServiceAsUser, sendOrderedBroadcast, sendStickyOrderedBroadcast and
        -- their AsUser variants;
        -- Sent to a package which cannot exist, a name with a colon, so Android itself finds nothing, like for an app
        -- which is not installed: a result receiver gets its answer without receivers, and a binding fails with the
        -- connection registered, else unbindService, which apps always call, throws; the intent of the app stays
        local copy = intent:clone()
        copy:setSelector(nil) -- Android refuses a package next to a selector
        copy:setComponent(nil)
        copy:setPackage(packageName .. ':hidden')
        -- Without a component Android throws for the instance of an isolated service, also of a missing app
        if string.find(method, '^bindIsolated') and intent:getComponent() ~= nil then
            copy:setComponent(luajava.newInstance('android.content.ComponentName', packageName .. ':hidden',
                    intent:getComponent():getClassName()))
        end
        param:setArgument(0, copy)
    else
        -- sendBroadcast, sendStickyBroadcast and their variants: nothing is sent, like without receivers
        param:setResult(nil)
    end
    return true, packageName
end
