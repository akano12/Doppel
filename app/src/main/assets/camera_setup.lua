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

-- The camera list of the app is empty, so every camera id is one the device does not have. Android checks the id of
-- a camera setup against its own camera list, which is real, and answers an id missing from it with this error, the
-- same text from Android 15 to 17 and without a cause, before it reads the characteristics; getCameraDeviceSetup
-- checks with isCameraDeviceSetupSupported. A null id goes to Android, which refuses it before the list.
-- isConcurrentSessionConfigurationSupported checks the ids of its map against the real combinations of concurrent
-- cameras first, of which a device without cameras has none, so it answers false without asking the camera service,
-- the same from Android 11 to 17; a null or empty map goes to Android, which refuses it before the combinations.
function before(hook, param)
    local argument = param:getArgument(0)
    if argument == nil then
        return false
    end
    if hook:getMethodName() == 'isConcurrentSessionConfigurationSupported' then
        if argument:size() == 0 then
            return false
        end
        param:setResult(false)
        return true
    end
    param:setResult(luajava.newInstance('java.lang.IllegalArgumentException',
            "Camera ID '" .. tostring(argument) .. "' not available on device."))
    return true
end
