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

-- The camera availability and torch callbacks are not registered, like on a device without cameras, which sends
-- them nothing. Where Android throws before it registers, the call runs and Android throws: for a null executor and,
-- without a Handler, on a thread without a Looper (IllegalArgumentException), and from Android 15 for a null
-- callback, which Android then dereferences (NullPointerException).
function before(hook, param)
    local types = hook:getParameterTypes()
    for index = 1, types['length'] do
        if param:getArgument(index - 1) == nil then
            local name = types[index]
            if name == 'java.util.concurrent.Executor' then
                return false
            elseif name == 'android.os.Handler' then
                if luajava.bindClass('android.os.Looper'):myLooper() == nil then
                    return false
                end
            elseif luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 35 then
                return false
            end
        end
    end
    param:setResult(nil)
    return true
end
