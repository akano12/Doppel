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

-- The id of a file system: ext4 makes it of the UUID it got when it was formatted, so all apps read the same number
-- until the device is reset; 0 is what file systems without one give.

function after(hook, param)
    local result = param:getResult()
    if result == nil or result.f_fsid == 0 then
        return false
    end
    param:setResult(luajava.newInstance('android.system.StructStatVfs',
            result.f_bsize, result.f_frsize, result.f_blocks, result.f_bfree, result.f_bavail,
            result.f_files, result.f_ffree, result.f_favail, 0, result.f_flag, result.f_namemax))
    return true, tostring(result.f_fsid), '0'
end
