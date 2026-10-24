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

-- Apps talk to each other through services, screens and broadcasts they name, like an app binding the service
-- of another app for the account signed in. The components of the apps which Get.Applications hides (see
-- package_probe and provider_hidden) cannot be reached, like for an app which is not installed: binding a service
-- fails, starting one finds none, starting a screen throws ActivityNotFoundException and a broadcast to the package
-- is not sent. Intents which name no package or component are resolved by Android, so they stay.

-- The package an intent names, by its component or its package, nil when it names none
function packageof(intent)
    if intent == nil then
        return nil
    end
    local component = intent:getComponent()
    if component ~= nil then
        return component:getPackageName()
    end
    return intent:getPackage()
end

-- Whether an intent names a package which is hidden
function hidden(param, intent)
    local packageName = packageof(intent)
    return packageName ~= nil and not param:isPackageVisible(packageName), packageName
end

-- The error of Android for a screen which is not found
function notfound(intent)
    local component = intent:getComponent()
    local message
    if component ~= nil then
        message = 'Unable to find explicit activity class ' .. component:toShortString() ..
                '; have you declared this activity in your AndroidManifest.xml, or does your intent not match its declared <intent-filter>?'
    else
        message = 'No Activity found to handle ' .. intent:toString()
    end
    return luajava.newInstance('android.content.ActivityNotFoundException', message)
end

function before(hook, param)
    local method = hook:getMethodName()

    -- Instrumentation.execStartActivities(AsUser): the first screen of a hidden app which cannot be found
    if method == 'execStartActivities' or method == 'execStartActivitiesAsUser' then
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

    -- The intent is the first argument of the methods of Context, the fifth of Instrumentation.execStartActivity
    local intent = param:getArgument(method == 'execStartActivity' and 4 or 0)
    local isHidden, packageName = hidden(param, intent)
    if not isHidden then
        return false
    end

    if method == 'bindService' then
        param:setResult(false)
    elseif method == 'startService' or method == 'startForegroundService' then
        param:setResult(nil)
    elseif method == 'execStartActivity' then
        param:setResult(notfound(intent))
    else
        -- sendBroadcast: nothing is sent, like without receivers
        param:setResult(nil)
    end
    return true, packageName
end
