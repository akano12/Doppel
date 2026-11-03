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

-- The kernel release and version identify the exact firmware build, like 6.6.50-android15-8-g8adecb593e9b,
-- also in /proc/sys/kernel/osrelease and version and /proc/version, which apps can read on some versions:
-- they become the release and version of Os.uname (see uname).
-- Also by other paths, like /proc/self/root/proc/..., see XParam.opens. Not covered is native code.
function filter(hook)
    return { '*kernel/osrelease*', '*kernel/version*', '*proc/version*' }
end

function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil then
        return false
    end
    local osrelease = param:opens('/proc/sys/kernel/osrelease')
    local proc = param:opens('/proc/version')
    if not osrelease and not proc and not param:opens('/proc/sys/kernel/version') then
        return false
    end
    local text = param:getFileText()
    if string.len(text) == 0 then
        return false
    end

    -- The release without the build, like uname
    local short = string.match(text, '^[%d%.]+') or string.match(text, '^Linux version ([%d%.]+)') or 'unknown'
    local fake
    if osrelease then
        fake = short .. '\n'
    elseif proc then
        fake = 'Linux version ' .. short .. ' #1 SMP PREEMPT\n'
    else
        fake = '#1 SMP PREEMPT\n'
    end
    if fake == text then
        return false
    end
    param:setFileText(fake)
    return true
end
