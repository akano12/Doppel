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

-- The camera list of the app is empty (see getNumberOfCameras), so every camera id is one with no camcorder profiles:
-- get throws the error of Android for a profile its media profiles do not have, the same text from Android 8.1 to 17.
-- The checks of the quality come first and throw their own errors, which stay.
function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil then
        return false
    end
    param:setResult(luajava.newInstance('java.lang.RuntimeException', 'Error retrieving camcorder profile params'))
    return true
end
