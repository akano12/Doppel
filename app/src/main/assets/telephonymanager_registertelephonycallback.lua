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

-- Callbacks with cell info or cell location are registered, so their other events arrive, like the call state,
-- like listen leaves the other events (see telephonymanager_listen); their methods of the cell info and the cell
-- location do not run, so the app gets neither. The parameter is not named hook, the function to hook a method is.
function before(h, param)
    local index = 1
    if string.sub(h:getName(), -3) == '.33' then
        index = 2
    end

    local callback = param:getArgument(index)
    if callback == nil then
        return false
    end

    local restricted = false
    if param:isInstance(callback, 'android.telephony.TelephonyCallback$CellInfoListener') then
        hook(callback, 'onCellInfoChanged', skip)
        restricted = true
    end
    if param:isInstance(callback, 'android.telephony.TelephonyCallback$CellLocationListener') then
        hook(callback, 'onCellLocationChanged', skip)
        restricted = true
    end
    return restricted
end

-- A method of the callback which does not run
function skip(when, param)
    if when == 'before' then
        param:setResult(nil)
    end
end
