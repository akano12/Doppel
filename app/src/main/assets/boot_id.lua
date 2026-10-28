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

-- The id the kernel gives the time since the device started is the same for all apps, so apps can link
-- their users by it until the device restarts. Each app reads its own id instead, which changes
-- when the device restarts, like the id of the kernel. Also by other paths, like /proc/self/root/proc/...,
-- and by java.nio (UnixNativeDispatcher.open), see XParam.opens.
function filter(hook)
    return { '*boot_id*' }
end

function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil or
            not param:opens('/proc/sys/kernel/random/boot_id') then
        return false
    end
    local id = param:getFileText()
    if string.len(id) == 0 then
        return false
    end
    param:setFileText(param:uuidOf(id .. param:getPackageName()) .. '\n')
    return true
end
