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

-- The version of the DRM plugin, like 19.0.1@AV1A.240428.001 of Widevine, ends with the build of the firmware,
-- like Build.ID, which is faked: it ends with the fake one (unknown, see generic_unknown_value), so it agrees with
-- Build.ID; the version of the plugin itself stays, apps check it.
-- The build information of OEMCrypto of Widevine (oemCryptoBuildInformation) is JSON from OEMCrypto 18, of which
-- soc_vendor and soc_model name the maker and the model, like samsung and SM-S938U1 on devices of Qualcomm, which
-- Build.MANUFACTURER, Build.MODEL and Build.SOC_MODEL give as unknown: both become unknown in place, so the spacing
-- and the other fields stay, also the versions of the trusted app, like the version of the plugin.
-- shortcut: the free text of older OEMCrypto, like OEMCrypto Level3 Code 22594 May 28 2021 16:59:07 of Level 3,
-- stays, no free text of Level 1 was seen; fake it too when one is seen to name the device.
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local property = param:getArgument(0)
    local fake
    if property == 'version' then
        local version = string.match(result, '^([^@]*)@')
        if version == nil then
            return false
        end
        fake = version .. '@unknown'
    elseif property == 'oemCryptoBuildInformation' then
        -- An empty value stays empty, like in generic_unknown_value
        fake = string.gsub(result, '("soc_vendor"%s*:%s*")[^"]+"', '%1unknown"')
        fake = string.gsub(fake, '("soc_model"%s*:%s*")[^"]+"', '%1unknown"')
    else
        -- The id of the system of Widevine (systemId) stays: DRM apps verify the device by it, Netflix fails without it
        return false
    end
    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
