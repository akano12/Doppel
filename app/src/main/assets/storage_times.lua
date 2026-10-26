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

-- The folders of the shared storage are made when the device is set up, and their times stay, to the nanosecond,
-- until it is reset, so all apps read the same time, which links them to each other, like the times of folders
-- changed later, like of Download. Their times become those of the files of reproducible builds
-- (2009-01-01 00:00 UTC), like the install times of other apps (see packageinfo_createfromparcel).
-- The times are read with Os.stat and Os.lstat, File.lastModified and java.nio.file.Files (also the time
-- the folder was made); not covered are the times of files in the folders, Os.fstat and native code.
local FAKE = 1230768000

-- The folders the system makes, which apps can read the times of
local FOLDERS = { 'Alarms', 'Android', 'Android/data', 'Android/media', 'Android/obb', 'Audiobooks', 'DCIM',
                  'Documents', 'Download', 'Movies', 'Music', 'Notifications', 'Pictures', 'Podcasts', 'Recordings',
                  'Ringtones' }

-- The paths of the folders, with the paths of the shared storage of the user, see XCore.getFilter
function filter(hook)
    local root = luajava.bindClass('android.os.Environment'):getExternalStorageDirectory():getPath()
    local paths = { '/storage/emulated', '/storage/emulated/' }
    for _, top in ipairs({ root, '/sdcard', '/storage/self/primary' }) do
        table.insert(paths, top)
        table.insert(paths, top .. '/')
        for _, folder in ipairs(FOLDERS) do
            table.insert(paths, top .. '/' .. folder)
            table.insert(paths, top .. '/' .. folder .. '/')
        end
    end
    return paths
end

-- Whether an object of the system has the field, like the time a file was made, which recent versions have
function hasfield(object, name)
    return (pcall(function()
        return object:getClass():getDeclaredField(name)
    end))
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end

    -- java.nio reads the times into the attributes of its argument; the boot image has the methods calling this
    -- one (UnixFileAttributes.get) compiled inline, so their hooks would not apply
    if hook:getClassName() == 'sun.nio.fs.UnixNativeDispatcher' then
        local attributes = param:getArgument(1)
        if attributes == nil or (attributes.st_atime_sec == FAKE and attributes.st_mtime_sec == FAKE and
                attributes.st_ctime_sec == FAKE) then
            return false
        end
        local old = attributes.st_mtime_sec
        for _, name in ipairs({ 'atime', 'mtime', 'ctime' }) do
            attributes['st_' .. name .. '_sec'] = FAKE
            attributes['st_' .. name .. '_nsec'] = 0
        end
        if hasfield(attributes, 'st_birthtime_sec') then
            attributes.st_birthtime_sec = FAKE
        end
        return true, tostring(old), tostring(FAKE)
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    if method == 'getLastModifiedTime' then
        -- File.lastModified, 0 when there is no such file
        if result == 0 or result == FAKE * 1000 then
            return false
        end
        param:setResult(FAKE * 1000)
        return true, tostring(result), tostring(FAKE * 1000)
    end

    -- Os.stat and Os.lstat, with times in nanoseconds from Android 8.1
    if result.st_atime == FAKE and result.st_mtime == FAKE and result.st_ctime == FAKE then
        return false
    end
    local fake
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 27 then
        local time = luajava.newInstance('android.system.StructTimespec', FAKE, 0)
        fake = luajava.newInstance('android.system.StructStat',
                result.st_dev, result.st_ino, result.st_mode, result.st_nlink, result.st_uid, result.st_gid,
                result.st_rdev, result.st_size, time, time, time, result.st_blksize, result.st_blocks)
    else
        fake = luajava.newInstance('android.system.StructStat',
                result.st_dev, result.st_ino, result.st_mode, result.st_nlink, result.st_uid, result.st_gid,
                result.st_rdev, result.st_size, FAKE, FAKE, FAKE, result.st_blksize, result.st_blocks)
    end
    param:setResult(fake)
    return true, tostring(result.st_mtime), tostring(FAKE)
end
