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

-- Unity Ads collects for personalized ads with the consent the app gives it. The app keeps its ads, without the
-- consent: before the SDK starts, no consent is given, like an app asking and the user declining, and consent the app
-- gives later is replaced by none. Version 4 has setters for it, which generic_false_argument and generic_true_argument
-- answer; former versions read the keys of MetaData, the consents (gdpr, privacy, and pipl of China) and nonbehavioral.
local CONSENT = { consent = true, ['gdpr.consent'] = true, ['privacy.consent'] = true, ['pipl.consent'] = true }
local NONBEHAVIORAL = { nonbehavioral = true, ['user.nonbehavioral'] = true }

-- Before UnityAds.initialize, once per process: the setters of version 4, else the MetaData of former versions,
-- which the hooks of the setters and of MetaData.set see as consent of the app too
function declined(param)
    local scope = param:getApplicationContext()
    if param:getValue('unityads.declined', scope) then
        return false
    end
    param:putValue('unityads.declined', true, scope)
    local ads = param:findClass('com.unity3d.ads.UnityAds')
    local setters = pcall(function()
        ads:setUserConsent(false)
        ads:setUserOptOut(true)
        ads:setNonBehavioral(true)
    end)
    if not setters then
        -- Each value is committed before the next one is set, like the documentation of Unity says
        local class = param:findClass('com.unity3d.ads.metadata.MetaData')
        for key, value in pairs({ ['gdpr.consent'] = false, ['privacy.consent'] = false,
                                  ['user.nonbehavioral'] = true }) do
            local metadata = luajava.new(class, scope)
            metadata:set(key, value)
            metadata:commit()
        end
    end
    return true
end

function before(hook, param)
    if hook:getMethodName() == 'initialize' then
        return declined(param)
    end

    -- MetaData.set(key, value)
    local key = param:getArgument(0)
    local value
    if CONSENT[key] then
        value = false
    elseif NONBEHAVIORAL[key] then
        value = true
    else
        return false
    end
    if param:getArgument(1) == value then
        return false
    end
    param:setArgument(1, value)
    return true, key
end
