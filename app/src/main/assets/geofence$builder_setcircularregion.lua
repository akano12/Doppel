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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- The region of the location fences of Awareness and of the geofences of HMS at 0, 0 with a radius of 0.1 m, which the
-- device never enters; a value out of range stays, so the checks of the library fail with the value of the app, like
-- the radius below 0 of LocationFence. The geofences of Play services get theirs in the parcel of their request.
function before(hook, param)
    if param:getArgument(0) >= -90 and param:getArgument(0) <= 90 then
        param:setArgument(0, 0) -- latitude
    end
    if param:getArgument(1) >= -180 and param:getArgument(1) <= 180 then
        param:setArgument(1, 0) -- longitude
    end
    if param:getArgument(2) > 0 then
        param:setArgument(2, 0.1) -- radius
    end
    return true
end
