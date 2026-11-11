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

-- The user agent and the profile of MMS, which messaging apps send to the carrier, can name the model of the device,
-- like SAMSUNG-SM-G991B and .../uaprof/SM-G991B.xml. Values other than the generic ones of Android become those,
-- which carriers accept from many devices, so MMS keep working; the generic ones and empty ones stay.
local GENERIC = {
    getMmsUserAgent = 'Android-Mms/2.0',
    getMmsUAProfUrl = 'http://www.google.com/oha/rdf/ua-profile-kila.xml',
}

-- The keys of the carrier configuration of MMS with the same values (SmsManager.getCarrierConfigValues and
-- CarrierConfigManager.getConfig and getConfigForSubId, KEY_MMS_USER_AGENT_STRING and KEY_MMS_UA_PROF_URL_STRING)
local KEYS = { userAgent = 'getMmsUserAgent', uaProfUrl = 'getMmsUAProfUrl' }

-- The name of the carrier and the version of its configuration in CarrierConfigManager name the real carrier, while
-- the SIM has the operator of the test network 00101, for which Android has neither: they become its empty defaults.
-- Only keys the bundle has are replaced, so the MMS values of SmsManager and the subsets of Android 14 asked for other
-- keys gain none. The override of the name becomes its default false as well, because carrier files that turn it on
-- always set a name, so an override next to the empty name would be a pair only Doppel gives.
-- shortcut: the other values of the carrier stay, carrier_config_applied_bool included, which as a set can still tell
-- the carrier; upgrade to the defaults of CarrierConfigManager.getDefaultConfig with only the keys that MMS and calls
-- of the app need, once those are known.
local DEFAULTS = { carrier_name_string = '', carrier_config_version_string = '' }

function after(hook, param)
    local result = param:getResult()
    local method = hook:getMethodName()
    if method == 'getCarrierConfigValues' or string.find(method, '^getConfig') then
        -- A copy for the app, of which the values are replaced like those of the getters
        if result == nil then
            return false
        end
        local changed = false
        for key, getter in pairs(KEYS) do
            local value = result:getString(key)
            if value ~= nil and value ~= '' and value ~= GENERIC[getter] then
                result:putString(key, GENERIC[getter])
                changed = true
            end
        end
        for key, default in pairs(DEFAULTS) do
            local value = result:getString(key)
            if value ~= nil and value ~= default then
                result:putString(key, default)
                changed = true
            end
        end
        if result:getBoolean('carrier_name_override_bool') then
            result:putBoolean('carrier_name_override_bool', false)
            changed = true
        end
        return changed
    end

    local fake = GENERIC[hook:getMethodName()]
    if result == nil or result == '' or fake == nil or result == fake then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
