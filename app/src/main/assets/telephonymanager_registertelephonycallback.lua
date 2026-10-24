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

-- Prevent callbacks with cell info or cell location
function before(hook, param)
    local index = 1
    if string.sub(hook:getName(), -3) == '.33' then
        index = 2
    end

    local callback = param:getArgument(index)
    if callback == nil then
        return false
    end

    if param:isInstance(callback, 'android.telephony.TelephonyCallback$CellInfoListener') or
            param:isInstance(callback, 'android.telephony.TelephonyCallback$CellLocationListener') then
        param:setResult(nil)
        return true
    end

    return false
end
