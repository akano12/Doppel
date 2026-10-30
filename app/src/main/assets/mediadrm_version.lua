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
-- like Build.ID, which is faked; the version of the plugin itself stays, apps check it
function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getArgument(0) ~= 'version' then
        return false
    end
    local fake = string.match(result, '^([^@]*)@')
    if fake == nil then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
