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

-- The switch is the last boolean argument, like optOut(optOut), setNonBehavioral(nonBehavioral) and
-- setDoNotSell(doNotSell, context) of AppLovin
function before(hook, param)
    local index = switch(hook)
    local optout = param:getArgument(index)
    if not optout then
        param:setArgument(index, true)
    end
    return not optout
end

-- The index of the last boolean argument (see generic_false_argument)
function switch(hook)
    local types = hook:getParameterTypes()
    local index = types['length']
    while index > 1 and types[index] ~= 'boolean' and types[index] ~= 'java.lang.Boolean' do
        index = index - 1
    end
    return index - 1
end
