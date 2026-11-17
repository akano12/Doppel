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

-- The screen, and the sound other apps play, are captured by a MediaProjection, of which the token comes in the result
-- of the consent activity of createScreenCaptureIntent. The app gets the result of a user declining instead,
-- RESULT_CANCELED without data, which SystemUI gives on every version, so it never gets a projection to start.
-- dispatchActivityResult(who, requestCode, resultCode, data, ...) delivers the results of an activity and its fragments.
function before(hook, param)
    local data = param:getArgument(3)
    if param:getArgument(2) ~= -1 or data == nil then
        return false
    end
    -- Reading an extra unparcels the extras, which fails for a class the app cannot load, like when the app reads them
    local ok, projection = pcall(function()
        return data:hasExtra('android.media.projection.extra.EXTRA_MEDIA_PROJECTION')
    end)
    if not ok or not projection then
        return false
    end
    param:setArgument(2, 0)
    param:setArgument(3, nil)
    return true
end
