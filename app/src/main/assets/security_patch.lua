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

-- The security patch level (Build.VERSION.SECURITY_PATCH) is given per quarter, like 2025-04-01 for 2025-05-05:
-- a date of fewer values, which apps can still parse and compare, where 'unknown' would make them fail.
-- The property is the same (see systemproperties_get). A patch level which is not set stays empty.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local year, month = string.match(result, '^(%d%d%d%d)%-(%d%d)%-%d%d$')
    if year == nil then
        return false
    end
    local fake = string.format('%s-%02d-01', year, math.floor((tonumber(month) - 1) / 3) * 3 + 1)
    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
