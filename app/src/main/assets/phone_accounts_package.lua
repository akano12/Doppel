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

-- The phone accounts of another package, which Android 8.1 to 11 give without a permission: those of com.android.phone
-- are of the SIMs, whose ids are their serial numbers (ICC ID). None, like getCallCapablePhoneAccounts gives; the
-- accounts of the app itself stay. The proxy makes the list of the reply itself (createTypedArrayList), so a new
-- ArrayList is like the empty list of a package without accounts. The exception of a patched Android, which refuses
-- another package, and its ParceledListSlice stay.
function after(hook, param)
    local result = param:getResult()
    if not param:isInstance(result, 'java.util.List') or result:isEmpty() or
            param:getArgument(0) == param:getPackageName() then
        return false
    end
    param:setResult(luajava.newInstance('java.util.ArrayList'))
    return true
end
