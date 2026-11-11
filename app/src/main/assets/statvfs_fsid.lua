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
-- until the device is reset. f2fs, erofs and vfat make it of the number of the block device, which stat gives too
-- (st_dev), and fuse, proc and sysfs give 0; these stay. An id of a UUID is replaced by one of the app and its fake
-- Android ID (see Fake values), the same for the same file system, so file systems stay apart and ids of the same one,
-- like through sdcardfs of Android 10 and before, stay alike.

function after(hook, param)
    local result = param:getResult()
    if result == nil or result.f_fsid == 0 then
        return false
    end
    local fsid = result.f_fsid
    -- The number of a block device has 32 bits, an id of a UUID 64, of which a 32-bit process reads the low half only
    -- (the unsigned long of bionic), so there only stat tells a number of a device.
    -- shortcut: an id of a UUID of which the high half is 0 (one in 2^32) stays, and in a 32-bit process the id of
    -- f2fs below sdcardfs is replaced and the stat is one more disk read for StrictMode; upgrade if seen on a device.
    local wide = luajava.bindClass('android.os.Process'):is64Bit()
    if wide then
        if fsid > 0 and fsid < 2 ^ 32 then
            return false
        end
    elseif fsid == device(hook, param) then
        return false
    end
    local fake = param:fakeLong(result, 'f_fsid', param:getSetting('value.android_id') or '', wide and 64 or 32)
    return true, tostring(fsid), fake
end

-- The device of the file of the path or descriptor, by the real stat, nil when it fails
function device(hook, param)
    local ok, stat = pcall(function()
        return param:unhooked(function()
            local system = luajava.bindClass('android.system.Os')
            if hook:getMethodName() == 'fstatvfs' then
                return system:fstat(param:getArgument(0))
            end
            return system:stat(param:getArgument(0))
        end)
    end)
    return (ok and stat ~= nil) and stat.st_dev or nil
end
