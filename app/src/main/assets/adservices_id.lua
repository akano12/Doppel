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

-- The ids of AdServices are made in the app of what the service sends (AdIdManager, AppSetIdManager): of the zero id,
-- which AdServices gives itself without a provider or after a reset, with the default it gives with it, so the
-- getters, toString, hashCode and equals of the id agree: an AdId with ad tracking limited, an AppSetId of the scope
-- of the app (SCOPE_APP). AdId and AppSetId check their arguments themselves.
local ZERO = '00000000-0000-0000-0000-000000000000'

function before(hook, param)
    local id = param:getArgument(0)
    if id == nil then
        return false
    end
    local default = (hook:getClassName() == 'android.adservices.adid.AdId' and true or 1)
    if id == ZERO and param:getArgument(1) == default then
        return false
    end
    param:setArgument(0, ZERO)
    param:setArgument(1, default)
    return true, id, ZERO
end
